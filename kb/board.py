"""Board model: reads/writes .kanban/ filesystem state with atomic operations."""

import fcntl
import json
import os
import subprocess
import tempfile
import time
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Optional

import yaml

KANBAN_DIR = ".kanban"
BOARD_FILE = "board.yaml"
CARDS_DIR = "cards"
WORKTREES_DIR = "worktrees"


# ── Helpers ────────────────────────────────────────────────────


def find_root(start: str = ".") -> Path:
    """Walk up from `start` to find the nearest .kanban/ directory."""
    p = Path(start).resolve()
    while True:
        if (p / KANBAN_DIR).is_dir():
            return p / KANBAN_DIR
        if p.parent == p:
            raise FileNotFoundError(
                "No .kanban/ directory found. Run `kb init` first."
            )
        p = p.parent


def _atomic_write(path: Path, content: str):
    """Write content to path atomically via temp file + rename."""
    fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
    try:
        os.write(fd, content.encode())
        os.fsync(fd)
        os.close(fd)
        os.rename(tmp, path)
    except BaseException:
        os.close(fd) if not os.get_inheritable(fd) else None
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def _flock_append(path: Path, line: str):
    """Append a line to a file with flock-based locking."""
    with open(path, "a") as f:
        fcntl.flock(f.fileno(), fcntl.LOCK_EX)
        try:
            f.write(line + "\n")
            f.flush()
            os.fsync(f.fileno())
        finally:
            fcntl.flock(f.fileno(), fcntl.LOCK_UN)


def _slugify(title: str) -> str:
    slug = title.lower().strip()
    slug = "".join(c if c.isalnum() or c == " " else "" for c in slug)
    slug = "-".join(slug.split())
    return slug[:40]


def _git(*args, cwd=None) -> subprocess.CompletedProcess:
    """Run a git command and return the result."""
    return subprocess.run(
        ["git"] + list(args),
        capture_output=True,
        text=True,
        cwd=cwd,
    )


# ── Data classes ───────────────────────────────────────────────


@dataclass
class HistoryEntry:
    ts: float
    role: str  # "system", "human", "agent"
    action: str
    content: str = ""
    agent_id: str = ""
    gate: str = ""

    def to_dict(self) -> dict:
        d = asdict(self)
        # strip empty optional fields
        return {k: v for k, v in d.items() if v or k in ("ts", "role", "action", "content")}

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False)


@dataclass
class Card:
    id: str
    title: str
    lane: str
    priority: int = 0
    blocked: bool = False
    blocked_reason: str = ""
    assigned_agent: str = ""
    branch: str = ""
    worktree: str = ""
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    tags: list = field(default_factory=list)

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass
class GateResult:
    gate: str
    passed: bool
    output: str
    timestamp: float = field(default_factory=time.time)

    def to_dict(self) -> dict:
        return asdict(self)


# ── Board ──────────────────────────────────────────────────────


class Board:
    def __init__(self, root: Optional[Path] = None):
        if root is None:
            root = find_root()
        self.root = Path(root)
        self.project_root = self.root.parent
        self.config_path = self.root / BOARD_FILE
        self.cards_dir = self.root / CARDS_DIR
        self.worktrees_dir = self.root / WORKTREES_DIR
        self.config = self._load_config()

    def _load_config(self) -> dict:
        with open(self.config_path) as f:
            return yaml.safe_load(f)

    def reload(self):
        self.config = self._load_config()

    # ── Lane helpers ───────────────────────────────────────────

    @property
    def lanes(self) -> list:
        return self.config.get("lanes", [])

    def lane_by_name(self, name: str) -> Optional[dict]:
        for lane in self.lanes:
            if lane["name"] == name:
                return lane
        return None

    def lane_names(self) -> list:
        return [l["name"] for l in self.lanes]

    def first_lane(self) -> str:
        return self.lanes[0]["name"]

    @property
    def base_branch(self) -> str:
        return self.config.get("base_branch", "main")

    @property
    def merge_strategy(self) -> str:
        return self.config.get("merge_strategy", "squash")

    # ── Card directory helpers ─────────────────────────────────

    def _find_card_dir(self, card_id: str) -> Path:
        """Find card dir by id prefix."""
        if self.cards_dir.exists():
            for d in sorted(self.cards_dir.iterdir()):
                if d.is_dir() and d.name.startswith(f"{card_id}-"):
                    return d
        raise FileNotFoundError(f"Card '{card_id}' not found.")

    def _create_card_dir(self, card_id: str, slug: str) -> Path:
        d = self.cards_dir / f"{card_id}-{slug}"
        d.mkdir(parents=True, exist_ok=True)
        return d

    def _next_id(self) -> str:
        existing = []
        if self.cards_dir.exists():
            for d in self.cards_dir.iterdir():
                if d.is_dir():
                    try:
                        existing.append(int(d.name.split("-")[0]))
                    except ValueError:
                        pass
        n = max(existing, default=0) + 1
        return f"{n:03d}"

    # ── Card CRUD ──────────────────────────────────────────────

    def load_card(self, card_id: str) -> Card:
        d = self._find_card_dir(card_id)
        with open(d / "meta.yaml") as f:
            data = yaml.safe_load(f)
        return Card(**data)

    def save_card(self, card: Card, card_dir: Optional[Path] = None):
        if card_dir is None:
            card_dir = self._find_card_dir(card.id)
        card.updated_at = time.time()
        content = yaml.dump(card.to_dict(), default_flow_style=False, sort_keys=False)
        _atomic_write(card_dir / "meta.yaml", content)

    def load_history(self, card_id: str) -> list:
        d = self._find_card_dir(card_id)
        hist_path = d / "history.jsonl"
        if not hist_path.exists():
            return []
        entries = []
        with open(hist_path) as f:
            for line in f:
                line = line.strip()
                if line:
                    entries.append(json.loads(line))
        return entries

    def append_history(self, card_id: str, entry: HistoryEntry):
        d = self._find_card_dir(card_id)
        hist_path = d / "history.jsonl"
        _flock_append(hist_path, entry.to_json())

    def load_description(self, card_id: str) -> str:
        d = self._find_card_dir(card_id)
        desc_path = d / "description.md"
        if desc_path.exists():
            return desc_path.read_text()
        return ""

    def save_description(self, card_id: str, content: str):
        d = self._find_card_dir(card_id)
        _atomic_write(d / "description.md", content)

    # ── All cards ──────────────────────────────────────────────

    def all_cards(self) -> list:
        cards = []
        if not self.cards_dir.exists():
            return cards
        for d in sorted(self.cards_dir.iterdir()):
            if d.is_dir() and (d / "meta.yaml").exists():
                cid = d.name.split("-")[0]
                try:
                    cards.append(self.load_card(cid))
                except Exception:
                    pass
        return cards

    def cards_in_lane(self, lane_name: str) -> list:
        return sorted(
            [c for c in self.all_cards() if c.lane == lane_name],
            key=lambda c: (c.priority, c.created_at),
        )

    # ── Git operations ─────────────────────────────────────────

    def _create_worktree(self, card: Card) -> tuple:
        """Create a git branch and worktree for a card. Returns (branch, worktree_path)."""
        slug = _slugify(card.title)
        branch = f"kb/{card.id}-{slug}"
        wt_path = self.worktrees_dir / card.id

        # create branch from base
        result = _git("branch", branch, self.base_branch, cwd=self.project_root)
        if result.returncode != 0:
            # branch might already exist (re-pull after reject)
            if "already exists" not in result.stderr:
                raise RuntimeError(f"Failed to create branch: {result.stderr}")

        # create worktree
        if not wt_path.exists():
            self.worktrees_dir.mkdir(parents=True, exist_ok=True)
            result = _git(
                "worktree", "add", str(wt_path), branch,
                cwd=self.project_root,
            )
            if result.returncode != 0:
                raise RuntimeError(f"Failed to create worktree: {result.stderr}")

        return branch, str(wt_path)

    def _remove_worktree(self, card: Card, delete_branch: bool = False):
        """Remove worktree and optionally the branch."""
        wt_path = Path(card.worktree) if card.worktree else self.worktrees_dir / card.id

        if wt_path.exists():
            _git("worktree", "remove", str(wt_path), "--force", cwd=self.project_root)

        if delete_branch and card.branch:
            _git("branch", "-D", card.branch, cwd=self.project_root)

    def _merge_card(self, card: Card):
        """Merge a card's branch into the base branch."""
        strategy = self.merge_strategy
        branch = card.branch

        if strategy == "squash":
            # switch to base, squash merge, switch back
            result = _git("checkout", self.base_branch, cwd=self.project_root)
            if result.returncode != 0:
                raise RuntimeError(f"Failed to checkout {self.base_branch}: {result.stderr}")

            result = _git("merge", "--squash", branch, cwd=self.project_root)
            if result.returncode != 0:
                # switch back before raising
                _git("checkout", "-", cwd=self.project_root)
                raise RuntimeError(f"Merge conflict: {result.stderr}")

            result = _git(
                "commit", "-m", f"kb: {card.title} (#{card.id})",
                cwd=self.project_root,
            )
            if result.returncode != 0:
                raise RuntimeError(f"Failed to commit merge: {result.stderr}")

        elif strategy == "merge":
            result = _git("checkout", self.base_branch, cwd=self.project_root)
            if result.returncode != 0:
                raise RuntimeError(f"Failed to checkout {self.base_branch}: {result.stderr}")

            result = _git(
                "merge", branch, "-m", f"kb: {card.title} (#{card.id})",
                cwd=self.project_root,
            )
            if result.returncode != 0:
                _git("merge", "--abort", cwd=self.project_root)
                raise RuntimeError(f"Merge conflict: {result.stderr}")

        elif strategy == "rebase":
            result = _git("rebase", self.base_branch, cwd=Path(card.worktree))
            if result.returncode != 0:
                _git("rebase", "--abort", cwd=Path(card.worktree))
                raise RuntimeError(f"Rebase conflict: {result.stderr}")

            result = _git("checkout", self.base_branch, cwd=self.project_root)
            if result.returncode != 0:
                raise RuntimeError(f"Failed to checkout {self.base_branch}: {result.stderr}")

            result = _git("merge", "--ff-only", branch, cwd=self.project_root)
            if result.returncode != 0:
                raise RuntimeError(f"Fast-forward failed: {result.stderr}")

        else:
            raise ValueError(f"Unknown merge strategy: {strategy}")

    def get_diff(self, card_id: str) -> str:
        """Get the diff of a card's branch vs base."""
        card = self.load_card(card_id)
        if not card.branch:
            return "(no branch)"
        result = _git(
            "diff", f"{self.base_branch}...{card.branch}",
            cwd=self.project_root,
        )
        return result.stdout if result.returncode == 0 else result.stderr

    def get_diff_stat(self, card_id: str) -> str:
        """Get diff --stat for a card's branch."""
        card = self.load_card(card_id)
        if not card.branch:
            return "(no branch)"
        result = _git(
            "diff", "--stat", f"{self.base_branch}...{card.branch}",
            cwd=self.project_root,
        )
        return result.stdout if result.returncode == 0 else result.stderr

    # ── Board operations ───────────────────────────────────────

    def create_card(
        self,
        title: str,
        lane: Optional[str] = None,
        tags: list = None,
        priority: int = 0,
        description: str = "",
    ) -> Card:
        card_id = self._next_id()
        slug = _slugify(title)
        if lane is None:
            lane = self.first_lane()
        if lane not in self.lane_names():
            raise ValueError(f"Lane '{lane}' not found. Available: {self.lane_names()}")

        card = Card(
            id=card_id,
            title=title,
            lane=lane,
            priority=priority,
            tags=tags or [],
        )
        card_dir = self._create_card_dir(card_id, slug)
        self.save_card(card, card_dir)

        if description:
            _atomic_write(card_dir / "description.md", description)

        self.append_history(
            card_id,
            HistoryEntry(
                ts=time.time(),
                role="system",
                action="created",
                content=f"Card created in lane '{lane}'",
            ),
        )
        return card

    def pull(self, agent: str = "", lane: Optional[str] = None) -> Optional[Card]:
        """Pull the next available card. Creates worktree + branch."""
        if lane is None:
            for ln in self.lanes:
                name = ln["name"]
                candidates = [
                    c
                    for c in self.cards_in_lane(name)
                    if not c.blocked and not c.assigned_agent
                ]
                if candidates:
                    lane = name
                    break
            if lane is None:
                return None

        lane_config = self.lane_by_name(lane)
        cards = self.cards_in_lane(lane)

        # check parallelism
        max_par = lane_config.get("max_parallelism")
        assigned = [c for c in cards if c.assigned_agent]
        if max_par and len(assigned) >= max_par:
            return None

        # find next unblocked, unassigned
        available = [c for c in cards if not c.blocked and not c.assigned_agent]
        if not available:
            return None

        card = available[0]
        card.assigned_agent = agent or f"agent-{uuid.uuid4().hex[:6]}"

        # create worktree + branch
        branch, wt_path = self._create_worktree(card)
        card.branch = branch
        card.worktree = wt_path

        self.save_card(card)
        self.append_history(
            card.id,
            HistoryEntry(
                ts=time.time(),
                role="system",
                action="pulled",
                content=f"Assigned to {card.assigned_agent}. Branch: {branch}. Worktree: {wt_path}",
                agent_id=card.assigned_agent,
            ),
        )
        return card

    def move(self, card_id: str, target_lane: str, agent: str = "") -> tuple:
        """Move card to target_lane. Returns (success, message, gate_results)."""
        if target_lane not in self.lane_names():
            return False, f"Lane '{target_lane}' not found.", []

        card = self.load_card(card_id)
        source_lane = card.lane

        if source_lane == target_lane:
            return False, f"Card is already in '{target_lane}'.", []

        if card.blocked:
            return False, f"Card {card_id} is blocked: {card.blocked_reason}", []

        target_config = self.lane_by_name(target_lane)

        # check WIP limit on target
        max_wip = target_config.get("max_wip")
        if max_wip:
            current = len(self.cards_in_lane(target_lane))
            if current >= max_wip:
                return False, f"Lane '{target_lane}' is at WIP limit ({max_wip}).", []

        # run quality gates
        gate_key = f"gate_from_{source_lane}"
        gates = target_config.get(gate_key, [])
        gate_results = []

        for gate_cmd in gates:
            result = self._run_gate(gate_cmd, card)
            gate_results.append(result)
            if not result.passed:
                self.append_history(
                    card_id,
                    HistoryEntry(
                        ts=time.time(),
                        role="system",
                        action="gate_fail",
                        content=f"Gate failed: {result.output}",
                        agent_id=agent,
                        gate=gate_cmd,
                    ),
                )
                return False, f"Gate failed: {gate_cmd}\n{result.output}", gate_results

        # handle on_enter: merge
        on_enter = target_config.get("on_enter")
        if on_enter == "merge" and card.branch:
            try:
                self._merge_card(card)
                self.append_history(
                    card_id,
                    HistoryEntry(
                        ts=time.time(),
                        role="system",
                        action="merged",
                        content=f"Branch {card.branch} merged into {self.base_branch} ({self.merge_strategy})",
                    ),
                )
                # cleanup worktree after merge
                self._remove_worktree(card, delete_branch=True)
                card.worktree = ""
                card.branch = ""
            except RuntimeError as e:
                self.append_history(
                    card_id,
                    HistoryEntry(
                        ts=time.time(),
                        role="system",
                        action="merge_fail",
                        content=str(e),
                    ),
                )
                return False, f"Merge failed: {e}", gate_results

        # all gates passed, move the card
        card.lane = target_lane
        card.assigned_agent = ""
        self.save_card(card)

        self.append_history(
            card_id,
            HistoryEntry(
                ts=time.time(),
                role="system",
                action="moved",
                content=f"Moved from '{source_lane}' to '{target_lane}'",
                agent_id=agent,
            ),
        )

        for gr in gate_results:
            self.append_history(
                card_id,
                HistoryEntry(
                    ts=time.time(),
                    role="system",
                    action="gate_pass",
                    content=f"Gate passed",
                    gate=gr.gate,
                ),
            )

        return True, f"Moved to '{target_lane}'.", gate_results

    def reject(self, card_id: str, reason: str = "", agent: str = "") -> Card:
        """Move card back to previous lane."""
        card = self.load_card(card_id)
        lane_names = self.lane_names()
        idx = lane_names.index(card.lane)
        prev_lane = lane_names[max(0, idx - 1)]

        card.lane = prev_lane
        card.assigned_agent = ""
        self.save_card(card)

        self.append_history(
            card_id,
            HistoryEntry(
                ts=time.time(),
                role="system",
                action="rejected",
                content=f"Rejected to '{prev_lane}': {reason}",
                agent_id=agent,
            ),
        )
        return card

    def block(self, card_id: str, reason: str = "") -> Card:
        card = self.load_card(card_id)
        card.blocked = True
        card.blocked_reason = reason
        self.save_card(card)
        self.append_history(
            card_id,
            HistoryEntry(
                ts=time.time(), role="system", action="blocked", content=reason
            ),
        )
        return card

    def unblock(self, card_id: str) -> Card:
        card = self.load_card(card_id)
        card.blocked = False
        card.blocked_reason = ""
        self.save_card(card)
        self.append_history(
            card_id,
            HistoryEntry(ts=time.time(), role="system", action="unblocked", content=""),
        )
        return card

    def add_note(self, card_id: str, message: str, agent: str = "human") -> None:
        role = "human" if agent == "human" else "agent"
        self.append_history(
            card_id,
            HistoryEntry(
                ts=time.time(),
                role=role,
                action="note",
                content=message,
                agent_id=agent if role == "agent" else "",
            ),
        )

    def cleanup(self, card_id: str, delete_branch: bool = False) -> None:
        card = self.load_card(card_id)
        self._remove_worktree(card, delete_branch=delete_branch)
        card.worktree = ""
        if delete_branch:
            card.branch = ""
        self.save_card(card)
        self.append_history(
            card_id,
            HistoryEntry(
                ts=time.time(),
                role="system",
                action="cleanup",
                content=f"Worktree removed. Branch deleted: {delete_branch}",
            ),
        )

    # ── Gate execution ─────────────────────────────────────────

    def _run_gate(self, gate_cmd: str, card: Card) -> GateResult:
        """Run a gate command with card context in env vars."""
        env = os.environ.copy()
        env["KB_CARD_ID"] = card.id
        env["KB_CARD_TITLE"] = card.title
        env["KB_CARD_LANE"] = card.lane
        env["KB_CARD_DIR"] = str(self._find_card_dir(card.id))
        env["KB_WORKTREE"] = card.worktree or ""
        env["KB_BRANCH"] = card.branch or ""
        env["KB_BASE_BRANCH"] = self.base_branch

        try:
            result = subprocess.run(
                gate_cmd,
                shell=True,
                capture_output=True,
                text=True,
                timeout=120,
                env=env,
                cwd=card.worktree or self.project_root,
            )
            passed = result.returncode == 0
            output = (result.stdout + result.stderr).strip()
            return GateResult(gate=gate_cmd, passed=passed, output=output)
        except subprocess.TimeoutExpired:
            return GateResult(gate=gate_cmd, passed=False, output="Gate timed out (120s)")
        except Exception as e:
            return GateResult(gate=gate_cmd, passed=False, output=str(e))

    # ── Context generation ─────────────────────────────────────

    def get_context(self, card_id: str) -> str:
        """Generate the full context for a sub-agent system prompt."""
        card = self.load_card(card_id)
        desc = self.load_description(card_id)
        history = self.load_history(card_id)
        diff_stat = self.get_diff_stat(card_id)

        lines = [
            f"# Task: {card.title}",
            f"Card ID: {card.id}",
            f"Lane: {card.lane}",
            f"Branch: {card.branch}",
            f"Worktree: {card.worktree}",
            "",
        ]

        if desc:
            lines += ["## Description", "", desc, ""]

        # what gates does this card need to pass?
        lane_names = self.lane_names()
        idx = lane_names.index(card.lane)
        if idx + 1 < len(lane_names):
            next_lane = lane_names[idx + 1]
            next_config = self.lane_by_name(next_lane)
            gate_key = f"gate_from_{card.lane}"
            gates = next_config.get(gate_key, [])
            if gates:
                lines += [
                    f"## Gates to pass (moving to '{next_lane}')",
                    "",
                ]
                for g in gates:
                    lines.append(f"- `{g}`")
                lines.append("")

        if diff_stat and diff_stat.strip() and "(no branch)" not in diff_stat:
            lines += ["## Current changes", "", "```", diff_stat.strip(), "```", ""]

        if history:
            lines += ["## History", ""]
            for entry in history[-30:]:  # last 30 entries
                from datetime import datetime
                ts = datetime.fromtimestamp(entry["ts"]).strftime("%H:%M:%S")
                role = entry.get("role", "?")
                action = entry.get("action", "?")
                content = entry.get("content", "")
                lines.append(f"[{ts}] **{role}/{action}**: {content}")
            lines.append("")

        lines += [
            "## Instructions",
            "",
            "You are working on this card. Your working directory is the git worktree for this card.",
            "Use these commands to interact with the board:",
            "",
            "- `kb note {card_id} \"<message>\"` — log your thinking or progress",
            "- `kb move {card_id} <lane>` — attempt to move the card forward (runs gates)",
            f"- `kb log {card_id}` — check for new human notes or instructions",
            "- `kb diff {card_id}` — see your changes vs the base branch",
            "",
            "Before each major step, check `kb log` for new human instructions.",
            "When you believe the task is complete and tests pass, move the card to the next lane.",
        ]

        return "\n".join(lines)


# ── Init ───────────────────────────────────────────────────────


def init_board(path: str = ".", template: Optional[dict] = None) -> Board:
    """Initialize a new .kanban/ directory in a git repo."""
    project_root = Path(path).resolve()

    # verify git repo
    result = _git("rev-parse", "--git-dir", cwd=project_root)
    if result.returncode != 0:
        raise RuntimeError(
            "Not a git repository. Initialize git first: `git init`"
        )

    # detect current branch
    result = _git("rev-parse", "--abbrev-ref", "HEAD", cwd=project_root)
    base_branch = result.stdout.strip() or "main"

    root = project_root / KANBAN_DIR
    if root.exists():
        raise FileExistsError(f"{root} already exists.")

    root.mkdir(parents=True)
    (root / CARDS_DIR).mkdir()
    (root / WORKTREES_DIR).mkdir()

    if template is None:
        template = {
            "project": project_root.name,
            "base_branch": base_branch,
            "merge_strategy": "squash",
            "agent_command": 'claude --system-prompt "$(kb context {card_id})" --cwd {worktree}',
            "lanes": [
                {"name": "backlog"},
                {
                    "name": "in-progress",
                    "max_wip": 5,
                    "max_parallelism": 2,
                },
                {
                    "name": "review",
                    "max_wip": 3,
                    "gate_from_in-progress": [
                        "echo 'Replace with: cd $KB_WORKTREE && npm test'; exit 0"
                    ],
                },
                {
                    "name": "done",
                    "gate_from_review": [
                        "echo 'Replace with: cd $KB_WORKTREE && npm run lint'; exit 0"
                    ],
                    "on_enter": "merge",
                },
            ],
        }

    config_path = root / BOARD_FILE
    with open(config_path, "w") as f:
        yaml.dump(template, f, default_flow_style=False, sort_keys=False)

    return Board(root)
