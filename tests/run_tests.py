"""Self-contained test runner for kb. No external test framework needed."""

import json
import os
import subprocess
import sys
import tempfile
import time
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from kb.board import Board, Card, HistoryEntry, init_board, _git

PASS = 0
FAIL = 0
ERRORS = []


def test(name):
    """Decorator to register and run a test."""
    def wrapper(fn):
        global PASS, FAIL
        try:
            fn()
            PASS += 1
            print(f"  ✓ {name}")
        except Exception as e:
            FAIL += 1
            ERRORS.append((name, e))
            print(f"  ✗ {name}: {e}")
        return fn
    return wrapper


def make_repo():
    """Create a temp git repo with initial commit."""
    d = tempfile.mkdtemp()
    _git("init", cwd=d)
    _git("config", "user.email", "t@t.com", cwd=d)
    _git("config", "user.name", "T", cwd=d)
    Path(d, "README.md").write_text("# test\n")
    _git("add", ".", cwd=d)
    _git("commit", "-m", "init", cwd=d)
    return Path(d)


def make_board(repo=None):
    """Create a board in a fresh repo."""
    if repo is None:
        repo = make_repo()
    return init_board(str(repo)), repo


def run_kb(repo, *args):
    """Run kb CLI."""
    env = os.environ.copy()
    env["PYTHONPATH"] = str(Path(__file__).parent.parent)
    return subprocess.run(
        [sys.executable, "-m", "kb.cli"] + list(args),
        capture_output=True, text=True, cwd=repo, env=env,
    )


# ── Init tests ─────────────────────────────────────────────────

print("\n── Init ──")

@test("creates .kanban directory")
def _():
    board, repo = make_board()
    assert (repo / ".kanban").is_dir()
    assert (repo / ".kanban" / "board.yaml").exists()
    assert (repo / ".kanban" / "cards").is_dir()
    assert (repo / ".kanban" / "worktrees").is_dir()

@test("detects base branch")
def _():
    board, repo = make_board()
    assert board.base_branch in ("main", "master")

@test("fails without git")
def _():
    d = tempfile.mkdtemp()
    try:
        init_board(d)
        assert False, "should have raised"
    except RuntimeError:
        pass

@test("fails if already exists")
def _():
    board, repo = make_board()
    try:
        init_board(str(repo))
        assert False, "should have raised"
    except FileExistsError:
        pass

@test("default lanes")
def _():
    board, _ = make_board()
    assert board.lane_names() == ["backlog", "in-progress", "review", "done"]


# ── Card CRUD ──────────────────────────────────────────────────

print("\n── Card CRUD ──")

@test("create card")
def _():
    board, _ = make_board()
    card = board.create_card("Fix auth bug")
    assert card.id == "001"
    assert card.title == "Fix auth bug"
    assert card.lane == "backlog"

@test("create with options")
def _():
    board, _ = make_board()
    card = board.create_card("Task", tags=["urgent"], priority=1, description="Do the thing.")
    assert card.tags == ["urgent"]
    assert card.priority == 1
    assert "Do the thing" in board.load_description(card.id)

@test("create invalid lane raises")
def _():
    board, _ = make_board()
    try:
        board.create_card("Bad", lane="nope")
        assert False
    except ValueError:
        pass

@test("auto increment IDs")
def _():
    board, _ = make_board()
    assert board.create_card("A").id == "001"
    assert board.create_card("B").id == "002"
    assert board.create_card("C").id == "003"

@test("load and save card")
def _():
    board, _ = make_board()
    board.create_card("Test")
    card = board.load_card("001")
    assert card.title == "Test"
    card.title = "Updated"
    board.save_card(card)
    assert board.load_card("001").title == "Updated"

@test("load missing card raises")
def _():
    board, _ = make_board()
    try:
        board.load_card("999")
        assert False
    except FileNotFoundError:
        pass


# ── History ────────────────────────────────────────────────────

print("\n── History ──")

@test("history on create")
def _():
    board, _ = make_board()
    board.create_card("Test")
    h = board.load_history("001")
    assert len(h) == 1
    assert h[0]["action"] == "created"

@test("add human note")
def _():
    board, _ = make_board()
    board.create_card("Test")
    board.add_note("001", "Use jose library", agent="human")
    h = board.load_history("001")
    assert h[1]["role"] == "human"
    assert h[1]["content"] == "Use jose library"

@test("add agent note")
def _():
    board, _ = make_board()
    board.create_card("Test")
    board.add_note("001", "Thinking...", agent="claude-1")
    h = board.load_history("001")
    assert h[1]["role"] == "agent"
    assert h[1]["agent_id"] == "claude-1"


# ── Pull ───────────────────────────────────────────────────────

print("\n── Pull ──")

@test("pull creates worktree")
def _():
    board, _ = make_board()
    board.create_card("Task")
    card = board.pull(agent="test")
    assert card is not None
    assert card.assigned_agent == "test"
    assert card.branch.startswith("kb/001-")
    assert Path(card.worktree).is_dir()

@test("pull skips assigned cards")
def _():
    board, _ = make_board()
    board.create_card("Task")
    board.pull(agent="a1")
    assert board.pull(agent="a2") is None

@test("pull respects parallelism")
def _():
    board, _ = make_board()
    board.create_card("A", lane="in-progress")
    board.create_card("B", lane="in-progress")
    board.create_card("C", lane="in-progress")
    assert board.pull(agent="a1", lane="in-progress") is not None
    assert board.pull(agent="a2", lane="in-progress") is not None
    assert board.pull(agent="a3", lane="in-progress") is None  # limit=2

@test("pull skips blocked cards")
def _():
    board, _ = make_board()
    board.create_card("Blocked")
    board.create_card("Available")
    board.block("001", reason="waiting")
    card = board.pull(agent="test")
    assert card.id == "002"

@test("pull records history")
def _():
    board, _ = make_board()
    board.create_card("Task")
    board.pull(agent="claude-1")
    h = board.load_history("001")
    pulled = [e for e in h if e["action"] == "pulled"]
    assert len(pulled) == 1
    assert "claude-1" in pulled[0]["content"]


# ── Move ───────────────────────────────────────────────────────

print("\n── Move ──")

@test("move simple")
def _():
    board, _ = make_board()
    board.create_card("Task", lane="in-progress")
    ok, msg, _ = board.move("001", "review")
    assert ok, msg
    assert board.load_card("001").lane == "review"

@test("move clears assignment")
def _():
    board, _ = make_board()
    board.create_card("Task", lane="in-progress")
    c = board.load_card("001")
    c.assigned_agent = "x"
    board.save_card(c)
    board.move("001", "review")
    assert board.load_card("001").assigned_agent == ""

@test("move blocked fails")
def _():
    board, _ = make_board()
    board.create_card("Task", lane="in-progress")
    board.block("001")
    ok, _, _ = board.move("001", "review")
    assert not ok

@test("move same lane fails")
def _():
    board, _ = make_board()
    board.create_card("Task", lane="backlog")
    ok, _, _ = board.move("001", "backlog")
    assert not ok

@test("move wip limit")
def _():
    board, _ = make_board()
    board.create_card("A", lane="review")
    board.create_card("B", lane="review")
    board.create_card("C", lane="review")
    board.create_card("D", lane="in-progress")
    ok, msg, _ = board.move("004", "review")
    assert not ok
    assert "WIP" in msg

@test("gate failure blocks move")
def _():
    board, repo = make_board()
    import yaml
    board.config["lanes"][2]["gate_from_in-progress"] = ["exit 1"]
    with open(board.config_path, "w") as f:
        yaml.dump(board.config, f, default_flow_style=False, sort_keys=False)
    board.reload()
    board.create_card("Task", lane="in-progress")
    ok, _, results = board.move("001", "review")
    assert not ok
    assert not results[0].passed
    h = board.load_history("001")
    assert any(e["action"] == "gate_fail" for e in h)

@test("gate success allows move")
def _():
    board, _ = make_board()
    import yaml
    board.config["lanes"][2]["gate_from_in-progress"] = ["echo ok; exit 0"]
    with open(board.config_path, "w") as f:
        yaml.dump(board.config, f, default_flow_style=False, sort_keys=False)
    board.reload()
    board.create_card("Task", lane="in-progress")
    ok, _, results = board.move("001", "review")
    assert ok
    assert results[0].passed

@test("gate receives env vars")
def _():
    board, _ = make_board()
    import yaml
    board.config["lanes"][2]["gate_from_in-progress"] = [
        'echo "ID=$KB_CARD_ID TITLE=$KB_CARD_TITLE"'
    ]
    with open(board.config_path, "w") as f:
        yaml.dump(board.config, f, default_flow_style=False, sort_keys=False)
    board.reload()
    board.create_card("My Task", lane="in-progress")
    ok, _, results = board.move("001", "review")
    assert ok
    assert "ID=001" in results[0].output
    assert "TITLE=My Task" in results[0].output


# ── Reject ─────────────────────────────────────────────────────

print("\n── Reject ──")

@test("reject moves back")
def _():
    board, _ = make_board()
    board.create_card("Task", lane="review")
    card = board.reject("001", reason="bad")
    assert card.lane == "in-progress"

@test("reject from first lane stays")
def _():
    board, _ = make_board()
    board.create_card("Task", lane="backlog")
    card = board.reject("001")
    assert card.lane == "backlog"


# ── Block / Unblock ────────────────────────────────────────────

print("\n── Block/Unblock ──")

@test("block and unblock")
def _():
    board, _ = make_board()
    board.create_card("Task")
    board.block("001", reason="waiting")
    c = board.load_card("001")
    assert c.blocked and c.blocked_reason == "waiting"
    board.unblock("001")
    c = board.load_card("001")
    assert not c.blocked and c.blocked_reason == ""


# ── Git worktree ───────────────────────────────────────────────

print("\n── Git worktree ──")

@test("worktree has project files")
def _():
    board, repo = make_board()
    board.create_card("Task")
    card = board.pull(agent="test")
    assert (Path(card.worktree) / "README.md").exists()

@test("worktree changes dont affect main")
def _():
    board, repo = make_board()
    board.create_card("Task")
    card = board.pull(agent="test")
    wt = Path(card.worktree)
    (wt / "newfile.txt").write_text("hello\n")
    _git("add", ".", cwd=wt)
    _git("commit", "-m", "add", cwd=wt)
    assert not (repo / "newfile.txt").exists()

@test("diff shows changes")
def _():
    board, repo = make_board()
    board.create_card("Task")
    card = board.pull(agent="test")
    wt = Path(card.worktree)
    (wt / "newfile.txt").write_text("hello\n")
    _git("add", ".", cwd=wt)
    _git("commit", "-m", "add", cwd=wt)
    assert "newfile.txt" in board.get_diff(card.id)
    assert "newfile.txt" in board.get_diff_stat(card.id)

@test("cleanup removes worktree")
def _():
    board, repo = make_board()
    board.create_card("Task")
    card = board.pull(agent="test")
    wt = Path(card.worktree)
    assert wt.is_dir()
    board.cleanup(card.id, delete_branch=True)
    card = board.load_card(card.id)
    assert card.worktree == ""
    assert card.branch == ""


# ── Merge ──────────────────────────────────────────────────────

print("\n── Merge ──")

@test("merge on done brings changes to base")
def _():
    board, repo = make_board()
    board.create_card("Task", lane="in-progress")
    card = board.pull(agent="test", lane="in-progress")
    wt = Path(card.worktree)
    (wt / "feature.txt").write_text("new feature\n")
    _git("add", ".", cwd=wt)
    _git("commit", "-m", "feat", cwd=wt)
    board.move(card.id, "review")
    ok, msg, _ = board.move(card.id, "done")
    assert ok, msg
    _git("checkout", board.base_branch, cwd=repo)
    assert (repo / "feature.txt").exists()


# ── Context ────────────────────────────────────────────────────

print("\n── Context ──")

@test("context includes title and description")
def _():
    board, _ = make_board()
    board.create_card("Fix auth", description="JWT tokens expire too early.")
    ctx = board.get_context("001")
    assert "Fix auth" in ctx
    assert "JWT tokens" in ctx

@test("context includes history")
def _():
    board, _ = make_board()
    board.create_card("Task")
    board.add_note("001", "Use jose library")
    ctx = board.get_context("001")
    assert "jose library" in ctx

@test("context includes instructions")
def _():
    board, _ = make_board()
    board.create_card("Task")
    ctx = board.get_context("001")
    assert "kb note" in ctx
    assert "kb move" in ctx


# ── CLI integration ────────────────────────────────────────────

print("\n── CLI ──")

@test("cli init")
def _():
    repo = make_repo()
    r = run_kb(repo, "init")
    assert r.returncode == 0
    assert "Initialized" in r.stdout

@test("cli add")
def _():
    repo = make_repo()
    run_kb(repo, "init")
    r = run_kb(repo, "add", "My task")
    assert r.returncode == 0
    assert "001" in r.stdout

@test("cli add --json")
def _():
    repo = make_repo()
    run_kb(repo, "init")
    r = run_kb(repo, "add", "My task", "--json")
    data = json.loads(r.stdout)
    assert data["id"] == "001"

@test("cli status")
def _():
    repo = make_repo()
    run_kb(repo, "init")
    run_kb(repo, "add", "Task A")
    run_kb(repo, "add", "Task B")
    r = run_kb(repo, "status")
    assert "Task A" in r.stdout
    assert "BACKLOG" in r.stdout

@test("cli status --json")
def _():
    repo = make_repo()
    run_kb(repo, "init")
    run_kb(repo, "add", "Task")
    r = run_kb(repo, "status", "--json")
    data = json.loads(r.stdout)
    assert len(data["backlog"]) == 1

@test("cli pull")
def _():
    repo = make_repo()
    run_kb(repo, "init")
    run_kb(repo, "add", "Task")
    r = run_kb(repo, "pull", "--agent", "test-agent")
    assert r.returncode == 0
    assert "test-agent" in r.stdout

@test("cli full workflow")
def _():
    repo = make_repo()
    run_kb(repo, "init")
    run_kb(repo, "add", "Feature")
    r = run_kb(repo, "pull", "--agent", "claude", "--json")
    assert r.returncode == 0
    card = json.loads(r.stdout)
    assert card["branch"].startswith("kb/")

    r = run_kb(repo, "note", "001", "Working on it", "--agent", "claude")
    assert r.returncode == 0

    r = run_kb(repo, "log", "001")
    assert "Working on it" in r.stdout

    r = run_kb(repo, "move", "001", "in-progress")
    assert r.returncode == 0

    r = run_kb(repo, "move", "001", "review")
    assert r.returncode == 0

    r = run_kb(repo, "show", "001")
    assert "review" in r.stdout

@test("cli block and unblock")
def _():
    repo = make_repo()
    run_kb(repo, "init")
    run_kb(repo, "add", "Task")
    assert run_kb(repo, "block", "001", "--reason", "waiting").returncode == 0
    assert run_kb(repo, "unblock", "001").returncode == 0

@test("cli context")
def _():
    repo = make_repo()
    run_kb(repo, "init")
    run_kb(repo, "add", "Fix auth", "--desc", "Fix the JWT bug")
    r = run_kb(repo, "context", "001")
    assert r.returncode == 0
    assert "Fix auth" in r.stdout
    assert "kb note" in r.stdout

@test("cli reject")
def _():
    repo = make_repo()
    run_kb(repo, "init")
    run_kb(repo, "add", "Task", "--lane", "review")  # skip to review
    # need to create in review... but add defaults to first lane
    # Let's just create and move
    run_kb(repo, "add", "Task")
    run_kb(repo, "move", "001", "in-progress")
    run_kb(repo, "move", "001", "review")
    r = run_kb(repo, "reject", "001", "--reason", "needs work")
    assert r.returncode == 0
    assert "in-progress" in r.stdout


# ── Summary ────────────────────────────────────────────────────

print(f"\n{'=' * 50}")
print(f"  {PASS} passed, {FAIL} failed")
if ERRORS:
    print(f"\n  Failures:")
    for name, err in ERRORS:
        print(f"    {name}:")
        print(f"      {err}")
print(f"{'=' * 50}")

sys.exit(1 if FAIL else 0)
