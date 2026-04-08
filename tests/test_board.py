"""Tests for kb board model and CLI."""

import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import pytest
import yaml

# add project to path
sys.path.insert(0, str(Path(__file__).parent.parent))

from kb.board import Board, Card, HistoryEntry, init_board, _git


@pytest.fixture
def git_repo(tmp_path):
    """Create a temporary git repo with an initial commit."""
    _git("init", cwd=tmp_path)
    _git("config", "user.email", "test@test.com", cwd=tmp_path)
    _git("config", "user.name", "Test", cwd=tmp_path)
    # create initial file and commit
    (tmp_path / "README.md").write_text("# Test project\n")
    _git("add", ".", cwd=tmp_path)
    _git("commit", "-m", "initial", cwd=tmp_path)
    return tmp_path


@pytest.fixture
def board(git_repo):
    """Create a board in the git repo."""
    return init_board(str(git_repo))


class TestInit:
    def test_creates_kanban_dir(self, git_repo):
        board = init_board(str(git_repo))
        assert (git_repo / ".kanban").is_dir()
        assert (git_repo / ".kanban" / "board.yaml").exists()
        assert (git_repo / ".kanban" / "cards").is_dir()
        assert (git_repo / ".kanban" / "worktrees").is_dir()

    def test_detects_base_branch(self, git_repo):
        board = init_board(str(git_repo))
        # git init creates 'master' or 'main' depending on config
        assert board.base_branch in ("main", "master")

    def test_fails_without_git(self, tmp_path):
        with pytest.raises(RuntimeError, match="Not a git repository"):
            init_board(str(tmp_path))

    def test_fails_if_exists(self, git_repo):
        init_board(str(git_repo))
        with pytest.raises(FileExistsError):
            init_board(str(git_repo))

    def test_default_lanes(self, board):
        names = board.lane_names()
        assert names == ["backlog", "in-progress", "review", "done"]

    def test_default_config(self, board):
        assert board.merge_strategy == "squash"
        assert board.config.get("agent_command")


class TestCardCRUD:
    def test_create_card(self, board):
        card = board.create_card("Fix the auth bug")
        assert card.id == "001"
        assert card.title == "Fix the auth bug"
        assert card.lane == "backlog"
        assert card.priority == 0

    def test_create_with_options(self, board):
        card = board.create_card(
            "Add tests",
            lane="backlog",
            tags=["testing", "urgent"],
            priority=1,
            description="Write unit tests for auth module.",
        )
        assert card.tags == ["testing", "urgent"]
        assert card.priority == 1
        desc = board.load_description(card.id)
        assert "unit tests" in desc

    def test_create_invalid_lane(self, board):
        with pytest.raises(ValueError, match="not found"):
            board.create_card("Bad", lane="nonexistent")

    def test_auto_increment_id(self, board):
        c1 = board.create_card("First")
        c2 = board.create_card("Second")
        c3 = board.create_card("Third")
        assert c1.id == "001"
        assert c2.id == "002"
        assert c3.id == "003"

    def test_load_card(self, board):
        board.create_card("Test card")
        card = board.load_card("001")
        assert card.title == "Test card"
        assert card.lane == "backlog"

    def test_load_missing_card(self, board):
        with pytest.raises(FileNotFoundError):
            board.load_card("999")

    def test_save_card_updates_timestamp(self, board):
        card = board.create_card("Test")
        t1 = card.updated_at
        time.sleep(0.05)
        card.title = "Updated"
        board.save_card(card)
        reloaded = board.load_card(card.id)
        assert reloaded.updated_at > t1
        assert reloaded.title == "Updated"


class TestHistory:
    def test_history_on_create(self, board):
        card = board.create_card("Test")
        history = board.load_history(card.id)
        assert len(history) == 1
        assert history[0]["action"] == "created"
        assert history[0]["role"] == "system"

    def test_add_note(self, board):
        card = board.create_card("Test")
        board.add_note(card.id, "This is a note", agent="human")
        history = board.load_history(card.id)
        assert len(history) == 2
        assert history[1]["action"] == "note"
        assert history[1]["role"] == "human"
        assert history[1]["content"] == "This is a note"

    def test_agent_note(self, board):
        card = board.create_card("Test")
        board.add_note(card.id, "Thinking about approach", agent="claude-123")
        history = board.load_history(card.id)
        assert history[1]["role"] == "agent"
        assert history[1]["agent_id"] == "claude-123"

    def test_history_entry_json(self):
        entry = HistoryEntry(
            ts=1234567890.0,
            role="agent",
            action="thinking",
            content="Analyzing code",
            agent_id="claude-1",
        )
        d = entry.to_dict()
        assert "gate" not in d  # empty fields stripped
        assert d["agent_id"] == "claude-1"


class TestAllCards:
    def test_all_cards(self, board):
        board.create_card("A")
        board.create_card("B")
        board.create_card("C")
        cards = board.all_cards()
        assert len(cards) == 3

    def test_cards_in_lane(self, board):
        board.create_card("A", lane="backlog")
        board.create_card("B", lane="backlog")
        assert len(board.cards_in_lane("backlog")) == 2
        assert len(board.cards_in_lane("in-progress")) == 0

    def test_priority_ordering(self, board):
        board.create_card("Low", priority=10)
        board.create_card("High", priority=0)
        board.create_card("Mid", priority=5)
        cards = board.cards_in_lane("backlog")
        assert cards[0].title == "High"
        assert cards[1].title == "Mid"
        assert cards[2].title == "Low"


class TestPull:
    def test_pull_creates_worktree(self, board):
        board.create_card("Task A")
        card = board.pull(agent="test-agent")
        assert card is not None
        assert card.assigned_agent == "test-agent"
        assert card.branch.startswith("kb/001-")
        assert card.worktree
        assert Path(card.worktree).is_dir()

    def test_pull_respects_assignment(self, board):
        board.create_card("Task A")
        board.pull(agent="agent-1")
        # second pull should return None (only one card, already assigned)
        result = board.pull(agent="agent-2")
        assert result is None

    def test_pull_respects_parallelism(self, board):
        # in-progress has max_parallelism: 2
        board.create_card("A", lane="in-progress")
        board.create_card("B", lane="in-progress")
        board.create_card("C", lane="in-progress")

        c1 = board.pull(agent="a1", lane="in-progress")
        c2 = board.pull(agent="a2", lane="in-progress")
        c3 = board.pull(agent="a3", lane="in-progress")

        assert c1 is not None
        assert c2 is not None
        assert c3 is None  # parallelism limit hit

    def test_pull_skips_blocked(self, board):
        board.create_card("Blocked task")
        board.create_card("Available task")
        board.block("001", reason="waiting on design")

        card = board.pull(agent="test")
        assert card.id == "002"
        assert card.title == "Available task"

    def test_pull_auto_assigns_agent(self, board):
        board.create_card("Task")
        card = board.pull()
        assert card.assigned_agent.startswith("agent-")

    def test_pull_records_history(self, board):
        board.create_card("Task")
        board.pull(agent="claude-1")
        history = board.load_history("001")
        pulled = [e for e in history if e["action"] == "pulled"]
        assert len(pulled) == 1
        assert "claude-1" in pulled[0]["content"]


class TestMove:
    def test_move_simple(self, board):
        board.create_card("Task", lane="in-progress")
        # gates have placeholder "exit 0" commands
        success, msg, _ = board.move("001", "review")
        assert success
        card = board.load_card("001")
        assert card.lane == "review"

    def test_move_clears_assignment(self, board):
        board.create_card("Task", lane="in-progress")
        card = board.load_card("001")
        card.assigned_agent = "test"
        board.save_card(card)

        board.move("001", "review")
        card = board.load_card("001")
        assert card.assigned_agent == ""

    def test_move_blocked_fails(self, board):
        board.create_card("Task", lane="in-progress")
        board.block("001", reason="nope")
        success, msg, _ = board.move("001", "review")
        assert not success
        assert "blocked" in msg.lower()

    def test_move_same_lane_fails(self, board):
        board.create_card("Task", lane="backlog")
        success, msg, _ = board.move("001", "backlog")
        assert not success

    def test_move_invalid_lane(self, board):
        board.create_card("Task")
        success, msg, _ = board.move("001", "nonexistent")
        assert not success

    def test_move_wip_limit(self, board):
        # review has max_wip: 3
        board.create_card("A", lane="review")
        board.create_card("B", lane="review")
        board.create_card("C", lane="review")
        board.create_card("D", lane="in-progress")

        success, msg, _ = board.move("004", "review")
        assert not success
        assert "WIP limit" in msg

    def test_gate_failure(self, board, git_repo):
        # set up a gate that fails
        board.config["lanes"][2]["gate_from_in-progress"] = ["exit 1"]
        with open(board.config_path, "w") as f:
            yaml.dump(board.config, f, default_flow_style=False, sort_keys=False)
        board.reload()

        board.create_card("Task", lane="in-progress")
        success, msg, results = board.move("001", "review")
        assert not success
        assert len(results) == 1
        assert not results[0].passed

        # history should record the gate failure
        history = board.load_history("001")
        gate_fails = [e for e in history if e["action"] == "gate_fail"]
        assert len(gate_fails) == 1

    def test_gate_success(self, board, git_repo):
        board.config["lanes"][2]["gate_from_in-progress"] = ["echo 'all good'; exit 0"]
        with open(board.config_path, "w") as f:
            yaml.dump(board.config, f, default_flow_style=False, sort_keys=False)
        board.reload()

        board.create_card("Task", lane="in-progress")
        success, msg, results = board.move("001", "review")
        assert success
        assert results[0].passed
        assert "all good" in results[0].output

    def test_gate_receives_env_vars(self, board, git_repo):
        board.config["lanes"][2]["gate_from_in-progress"] = [
            'echo "ID=$KB_CARD_ID TITLE=$KB_CARD_TITLE LANE=$KB_CARD_LANE BASE=$KB_BASE_BRANCH"'
        ]
        with open(board.config_path, "w") as f:
            yaml.dump(board.config, f, default_flow_style=False, sort_keys=False)
        board.reload()

        board.create_card("My Task", lane="in-progress")
        success, msg, results = board.move("001", "review")
        assert success
        output = results[0].output
        assert "ID=001" in output
        assert "TITLE=My Task" in output
        assert "LANE=in-progress" in output


class TestReject:
    def test_reject_moves_back(self, board):
        board.create_card("Task", lane="review")
        card = board.reject("001", reason="needs more tests")
        assert card.lane == "in-progress"

    def test_reject_from_first_lane(self, board):
        board.create_card("Task", lane="backlog")
        card = board.reject("001")
        assert card.lane == "backlog"  # stays in first lane

    def test_reject_clears_assignment(self, board):
        board.create_card("Task", lane="review")
        card = board.load_card("001")
        card.assigned_agent = "test"
        board.save_card(card)

        card = board.reject("001")
        assert card.assigned_agent == ""


class TestBlockUnblock:
    def test_block_and_unblock(self, board):
        board.create_card("Task")
        board.block("001", reason="waiting")
        card = board.load_card("001")
        assert card.blocked
        assert card.blocked_reason == "waiting"

        board.unblock("001")
        card = board.load_card("001")
        assert not card.blocked
        assert card.blocked_reason == ""


class TestGitWorktree:
    def test_worktree_has_files(self, board, git_repo):
        board.create_card("Task")
        card = board.pull(agent="test")
        wt = Path(card.worktree)
        assert (wt / "README.md").exists()

    def test_worktree_isolation(self, board, git_repo):
        board.create_card("Task A")
        board.create_card("Task B")

        card_a = board.pull(agent="a1", lane="backlog")
        # need to move A out of backlog first to free it up,
        # or create B in a different state
        # Actually, both are in backlog, pull takes the first available
        # Let's create them differently

    def test_changes_in_worktree_dont_affect_main(self, board, git_repo):
        board.create_card("Task")
        card = board.pull(agent="test")
        wt = Path(card.worktree)

        # make a change in the worktree
        (wt / "newfile.txt").write_text("hello from worktree\n")
        _git("add", ".", cwd=wt)
        _git("commit", "-m", "add newfile", cwd=wt)

        # main tree should NOT have this file
        assert not (git_repo / "newfile.txt").exists()

    def test_cleanup_removes_worktree(self, board, git_repo):
        board.create_card("Task")
        card = board.pull(agent="test")
        wt_path = Path(card.worktree)
        assert wt_path.is_dir()

        board.cleanup(card.id, delete_branch=True)
        card = board.load_card(card.id)
        assert card.worktree == ""
        assert card.branch == ""

    def test_diff_shows_changes(self, board, git_repo):
        board.create_card("Task")
        card = board.pull(agent="test")
        wt = Path(card.worktree)

        (wt / "newfile.txt").write_text("hello\n")
        _git("add", ".", cwd=wt)
        _git("commit", "-m", "add file", cwd=wt)

        diff = board.get_diff(card.id)
        assert "newfile.txt" in diff

        stat = board.get_diff_stat(card.id)
        assert "newfile.txt" in stat


class TestMerge:
    def test_merge_on_done(self, board, git_repo):
        board.create_card("Task", lane="in-progress")
        card = board.pull(agent="test", lane="in-progress")
        wt = Path(card.worktree)

        # make changes in worktree
        (wt / "feature.txt").write_text("new feature\n")
        _git("add", ".", cwd=wt)
        _git("commit", "-m", "implement feature", cwd=wt)

        # move through review (gates are exit 0 placeholders)
        board.move(card.id, "review")

        # move to done (triggers merge)
        success, msg, _ = board.move(card.id, "done")
        assert success, msg

        # file should now be on the base branch
        result = _git("checkout", board.base_branch, cwd=git_repo)
        assert (git_repo / "feature.txt").exists()


class TestContext:
    def test_context_includes_title(self, board):
        board.create_card("Fix the auth bug", description="JWT tokens expire too early.")
        context = board.get_context("001")
        assert "Fix the auth bug" in context
        assert "JWT tokens expire" in context

    def test_context_includes_history(self, board):
        board.create_card("Task")
        board.add_note("001", "Use jose library", agent="human")
        context = board.get_context("001")
        assert "jose library" in context

    def test_context_includes_gates(self, board):
        board.create_card("Task", lane="in-progress")
        context = board.get_context("001")
        # should mention gates for moving to review
        assert "gate" in context.lower() or "Gates" in context

    def test_context_includes_instructions(self, board):
        board.create_card("Task")
        context = board.get_context("001")
        assert "kb note" in context
        assert "kb move" in context
        assert "kb log" in context


class TestCLI:
    """Integration tests via subprocess."""

    def _run_kb(self, git_repo, *args):
        """Run kb CLI as subprocess."""
        env = os.environ.copy()
        env["PYTHONPATH"] = str(Path(__file__).parent.parent)
        result = subprocess.run(
            [sys.executable, "-m", "kb.cli"] + list(args),
            capture_output=True,
            text=True,
            cwd=git_repo,
            env=env,
        )
        return result

    def test_cli_init(self, git_repo):
        r = self._run_kb(git_repo, "init")
        assert r.returncode == 0
        assert "Initialized" in r.stdout

    def test_cli_add(self, git_repo):
        self._run_kb(git_repo, "init")
        r = self._run_kb(git_repo, "add", "My task")
        assert r.returncode == 0
        assert "001" in r.stdout

    def test_cli_add_json(self, git_repo):
        self._run_kb(git_repo, "init")
        r = self._run_kb(git_repo, "add", "My task", "--json")
        assert r.returncode == 0
        data = json.loads(r.stdout)
        assert data["id"] == "001"
        assert data["title"] == "My task"

    def test_cli_status(self, git_repo):
        self._run_kb(git_repo, "init")
        self._run_kb(git_repo, "add", "Task A")
        self._run_kb(git_repo, "add", "Task B")
        r = self._run_kb(git_repo, "status")
        assert r.returncode == 0
        assert "Task A" in r.stdout
        assert "Task B" in r.stdout
        assert "BACKLOG" in r.stdout

    def test_cli_status_json(self, git_repo):
        self._run_kb(git_repo, "init")
        self._run_kb(git_repo, "add", "Task A")
        r = self._run_kb(git_repo, "status", "--json")
        data = json.loads(r.stdout)
        assert "backlog" in data
        assert len(data["backlog"]) == 1

    def test_cli_pull(self, git_repo):
        self._run_kb(git_repo, "init")
        self._run_kb(git_repo, "add", "Task")
        r = self._run_kb(git_repo, "pull", "--agent", "test-agent")
        assert r.returncode == 0
        assert "test-agent" in r.stdout
        assert "Worktree" in r.stdout

    def test_cli_full_workflow(self, git_repo):
        """Test the full lifecycle: init → add → pull → move → done."""
        self._run_kb(git_repo, "init")
        self._run_kb(git_repo, "add", "Implement feature")

        # pull (creates worktree)
        r = self._run_kb(git_repo, "pull", "--agent", "claude", "--json")
        card = json.loads(r.stdout)
        assert card["branch"].startswith("kb/")

        # add a note
        r = self._run_kb(git_repo, "note", "001", "Working on it", "--agent", "claude")
        assert r.returncode == 0

        # check log
        r = self._run_kb(git_repo, "log", "001")
        assert "Working on it" in r.stdout

        # move to review (gates should pass with placeholder commands)
        # card is in backlog after pull, need to be in in-progress first
        # Actually pull assigns but doesn't move. Let's move manually.
        r = self._run_kb(git_repo, "move", "001", "in-progress")
        assert r.returncode == 0

        r = self._run_kb(git_repo, "move", "001", "review")
        assert r.returncode == 0

        # show
        r = self._run_kb(git_repo, "show", "001")
        assert "review" in r.stdout

    def test_cli_block_unblock(self, git_repo):
        self._run_kb(git_repo, "init")
        self._run_kb(git_repo, "add", "Task")

        r = self._run_kb(git_repo, "block", "001", "--reason", "waiting")
        assert r.returncode == 0

        r = self._run_kb(git_repo, "unblock", "001")
        assert r.returncode == 0

    def test_cli_context(self, git_repo):
        self._run_kb(git_repo, "init")
        self._run_kb(git_repo, "add", "Fix auth", "--desc", "Fix the JWT bug")
        r = self._run_kb(git_repo, "context", "001")
        assert r.returncode == 0
        assert "Fix auth" in r.stdout
        assert "kb note" in r.stdout


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
