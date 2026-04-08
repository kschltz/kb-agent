"""CLI entry point for kb."""

import argparse
import json
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Optional

from .board import Board, init_board


def _ts(t: float) -> str:
    return datetime.fromtimestamp(t).strftime("%Y-%m-%d %H:%M:%S")


def _out(data, as_json: bool):
    if as_json:
        print(json.dumps(data, indent=2, ensure_ascii=False))
    else:
        if isinstance(data, str):
            print(data)


# ── Commands ───────────────────────────────────────────────────


def cmd_init(args):
    try:
        board = init_board(args.path)
        print(f"Initialized kanban board at {board.root}")
        print(f"Base branch: {board.base_branch}")
        print("Edit .kanban/board.yaml to configure lanes and gates.")
    except (FileExistsError, RuntimeError) as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)


def cmd_add(args):
    board = Board()
    tags = [t.strip() for t in args.tags.split(",") if t.strip()] if args.tags else []

    description = ""
    if args.desc:
        desc_path = Path(args.desc)
        if desc_path.exists():
            description = desc_path.read_text()
        else:
            description = args.desc  # treat as inline description

    card = board.create_card(
        title=args.title,
        lane=args.lane,
        tags=tags,
        priority=args.priority,
        description=description,
    )

    if args.json:
        _out(card.to_dict(), True)
    else:
        print(f"Created card {card.id}: {card.title} [{card.lane}]")


def cmd_pull(args):
    board = Board()
    card = board.pull(agent=args.agent, lane=args.lane)

    if card is None:
        if args.json:
            _out(None, True)
        else:
            print("No cards available to pull.")
        sys.exit(1)

    if args.json:
        _out(card.to_dict(), True)
    else:
        print(f"Pulled card {card.id}: {card.title} [{card.lane}]")
        print(f"  Agent:    {card.assigned_agent}")
        print(f"  Branch:   {card.branch}")
        print(f"  Worktree: {card.worktree}")

    # optionally spawn sub-agent
    if args.spawn:
        _spawn_agent(board, card)


def cmd_move(args):
    board = Board()
    success, message, gate_results = board.move(
        args.card_id, args.lane, agent=args.agent
    )

    if args.json:
        _out(
            {
                "success": success,
                "message": message,
                "gate_results": [g.to_dict() for g in gate_results],
            },
            True,
        )
    else:
        if success:
            print(f"✓ {message}")
        else:
            print(f"✗ {message}", file=sys.stderr)
            sys.exit(1)


def cmd_reject(args):
    board = Board()
    card = board.reject(args.card_id, reason=args.reason, agent=args.agent)

    if args.json:
        _out(card.to_dict(), True)
    else:
        print(f"Rejected card {card.id} → {card.lane}")


def cmd_block(args):
    board = Board()
    card = board.block(args.card_id, reason=args.reason)

    if args.json:
        _out(card.to_dict(), True)
    else:
        print(f"Blocked card {card.id}: {args.reason}")


def cmd_unblock(args):
    board = Board()
    card = board.unblock(args.card_id)

    if args.json:
        _out(card.to_dict(), True)
    else:
        print(f"Unblocked card {card.id}")


def cmd_note(args):
    board = Board()
    board.add_note(args.card_id, args.message, agent=args.agent)

    if args.json:
        _out({"status": "ok"}, True)
    else:
        print(f"Note added to card {args.card_id}")


def cmd_log(args):
    board = Board()
    history = board.load_history(args.card_id)

    if args.since:
        history = [e for e in history if e.get("ts", 0) > args.since]

    if args.json:
        _out(history, True)
    else:
        if not history:
            print("No history.")
            return
        for entry in history:
            ts = _ts(entry.get("ts", 0))
            role = entry.get("role", "?")
            action = entry.get("action", "?")
            content = entry.get("content", "")
            agent_id = entry.get("agent_id", "")
            agent_str = f" [{agent_id}]" if agent_id else ""
            gate = entry.get("gate", "")
            gate_str = f" (gate: {gate})" if gate else ""
            print(f"  {ts}  {role}/{action}{agent_str}{gate_str}: {content}")


def cmd_diff(args):
    board = Board()
    if args.stat:
        diff = board.get_diff_stat(args.card_id)
    else:
        diff = board.get_diff(args.card_id)

    if args.json:
        _out({"diff": diff}, True)
    else:
        print(diff)


def cmd_show(args):
    board = Board()
    card = board.load_card(args.card_id)
    desc = board.load_description(args.card_id)
    history = board.load_history(args.card_id)
    diff_stat = board.get_diff_stat(args.card_id)

    if args.json:
        _out(
            {
                "card": card.to_dict(),
                "description": desc,
                "history": history,
                "diff_stat": diff_stat,
            },
            True,
        )
        return

    print(f"\n{'=' * 60}")
    print(f"  Card {card.id}: {card.title}")
    print(f"{'=' * 60}")
    print(f"  Lane:     {card.lane}")
    print(f"  Priority: {card.priority}")
    print(f"  Blocked:  {card.blocked}" + (f" ({card.blocked_reason})" if card.blocked_reason else ""))
    print(f"  Agent:    {card.assigned_agent or '(none)'}")
    print(f"  Branch:   {card.branch or '(none)'}")
    print(f"  Worktree: {card.worktree or '(none)'}")
    print(f"  Tags:     {', '.join(card.tags) if card.tags else '(none)'}")
    print(f"  Created:  {_ts(card.created_at)}")
    print(f"  Updated:  {_ts(card.updated_at)}")

    if desc:
        print(f"\n  Description:")
        for line in desc.strip().split("\n"):
            print(f"    {line}")

    if diff_stat and diff_stat.strip() and "(no branch)" not in diff_stat:
        print(f"\n  Changes:")
        for line in diff_stat.strip().split("\n"):
            print(f"    {line}")

    print(f"\n  History ({len(history)} entries):")
    for entry in history[-15:]:
        ts = _ts(entry.get("ts", 0))
        role = entry.get("role", "?")
        action = entry.get("action", "?")
        content = entry.get("content", "")
        print(f"    {ts}  {role}/{action}: {content}")
    if len(history) > 15:
        print(f"    ... ({len(history) - 15} earlier entries)")
    print()


def cmd_status(args):
    board = Board()
    cards = board.all_cards()

    if args.json:
        result = {}
        for lane in board.lane_names():
            lane_cards = [c.to_dict() for c in cards if c.lane == lane]
            result[lane] = lane_cards
        _out(result, True)
        return

    print(f"\n  {board.config.get('project', 'kb')} — {board.base_branch}")
    print()

    for lane_conf in board.lanes:
        name = lane_conf["name"]
        lane_cards = sorted(
            [c for c in cards if c.lane == name],
            key=lambda c: (c.priority, c.created_at),
        )
        max_wip = lane_conf.get("max_wip", "∞")
        max_par = lane_conf.get("max_parallelism", "∞")
        header = f"── {name.upper()} ({len(lane_cards)}/{max_wip}) [par: {max_par}] "
        print(header + "─" * max(0, 60 - len(header)))

        if not lane_cards:
            print("  (empty)")
        for c in lane_cards:
            flags = []
            if c.blocked:
                flags.append("BLOCKED")
            if c.assigned_agent:
                flags.append(f"→ {c.assigned_agent}")
            if c.branch:
                flags.append(c.branch)
            if c.tags:
                flags.append(" ".join(f"#{t}" for t in c.tags))
            flag_str = f"  ({', '.join(flags)})" if flags else ""
            print(f"  [{c.id}] {c.title}{flag_str}")
        print()


def cmd_context(args):
    board = Board()
    context = board.get_context(args.card_id)

    if args.json:
        _out({"context": context}, True)
    else:
        print(context)


def cmd_spawn(args):
    board = Board()
    card = board.load_card(args.card_id)
    _spawn_agent(board, card)


def _spawn_agent(board: Board, card):
    """Spawn a sub-agent for a card using the configured command."""
    cmd_template = board.config.get("agent_command", "")
    if not cmd_template:
        print("Error: no agent_command configured in board.yaml", file=sys.stderr)
        sys.exit(1)

    import subprocess

    cmd = cmd_template.replace("{card_id}", card.id)
    cmd = cmd.replace("{worktree}", card.worktree or ".")
    cmd = cmd.replace("{branch}", card.branch or "")

    board.append_history(
        card.id,
        __import__("kb.board", fromlist=["HistoryEntry"]).HistoryEntry(
            ts=time.time(),
            role="system",
            action="spawned",
            content=f"Sub-agent spawned: {cmd}",
        ),
    )

    print(f"Spawning agent for card {card.id}...")
    print(f"  Command: {cmd}")
    print(f"  Worktree: {card.worktree}")

    try:
        subprocess.run(cmd, shell=True, cwd=card.worktree or ".")
    except KeyboardInterrupt:
        print("\nAgent interrupted.")


def cmd_cleanup(args):
    board = Board()
    board.cleanup(args.card_id, delete_branch=args.delete_branch)

    if args.json:
        _out({"status": "ok"}, True)
    else:
        print(f"Cleaned up card {args.card_id}")


def cmd_serve(args):
    from .server import run_server

    run_server(host=args.host, port=args.port)


# ── Main ───────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(prog="kb", description="Kanban board for coding agents")
    sub = parser.add_subparsers(dest="command")

    # init
    p = sub.add_parser("init", help="Initialize a new kanban board")
    p.add_argument("--path", default=".", help="Project root path")

    # add
    p = sub.add_parser("add", help="Add a card to the board")
    p.add_argument("title", help="Card title")
    p.add_argument("--lane", default=None, help="Target lane (default: first lane)")
    p.add_argument("--tags", default="", help="Comma-separated tags")
    p.add_argument("--priority", type=int, default=0, help="Priority (lower = higher)")
    p.add_argument("--desc", default="", help="Description file path or inline text")
    p.add_argument("--json", action="store_true")
    p.add_argument("--agent", default="")

    # pull
    p = sub.add_parser("pull", help="Pull next available card")
    p.add_argument("--lane", default=None, help="Pull from specific lane")
    p.add_argument("--agent", default="", help="Agent identifier")
    p.add_argument("--spawn", action="store_true", help="Spawn sub-agent after pulling")
    p.add_argument("--json", action="store_true")

    # move
    p = sub.add_parser("move", help="Move a card to a lane (runs gates)")
    p.add_argument("card_id", help="Card ID")
    p.add_argument("lane", help="Target lane")
    p.add_argument("--agent", default="")
    p.add_argument("--json", action="store_true")

    # reject
    p = sub.add_parser("reject", help="Reject a card back to previous lane")
    p.add_argument("card_id")
    p.add_argument("--reason", default="")
    p.add_argument("--agent", default="")
    p.add_argument("--json", action="store_true")

    # block / unblock
    p = sub.add_parser("block", help="Block a card")
    p.add_argument("card_id")
    p.add_argument("--reason", default="")
    p.add_argument("--json", action="store_true")

    p = sub.add_parser("unblock", help="Unblock a card")
    p.add_argument("card_id")
    p.add_argument("--json", action="store_true")

    # note
    p = sub.add_parser("note", help="Add a note to a card")
    p.add_argument("card_id")
    p.add_argument("message")
    p.add_argument("--agent", default="human")
    p.add_argument("--json", action="store_true")

    # log
    p = sub.add_parser("log", help="Show card history")
    p.add_argument("card_id")
    p.add_argument("--since", type=float, default=None, help="Show entries after this timestamp")
    p.add_argument("--json", action="store_true")

    # diff
    p = sub.add_parser("diff", help="Show card diff vs base branch")
    p.add_argument("card_id")
    p.add_argument("--stat", action="store_true", help="Show stat summary only")
    p.add_argument("--json", action="store_true")

    # show
    p = sub.add_parser("show", help="Show card details")
    p.add_argument("card_id")
    p.add_argument("--json", action="store_true")

    # status
    p = sub.add_parser("status", help="Show board status")
    p.add_argument("--json", action="store_true")

    # context
    p = sub.add_parser("context", help="Output card context for agent system prompt")
    p.add_argument("card_id")
    p.add_argument("--json", action="store_true")

    # spawn
    p = sub.add_parser("spawn", help="Spawn a sub-agent for a card")
    p.add_argument("card_id")

    # cleanup
    p = sub.add_parser("cleanup", help="Remove worktree for a card")
    p.add_argument("card_id")
    p.add_argument("--delete-branch", action="store_true", help="Also delete the git branch")
    p.add_argument("--json", action="store_true")

    # serve
    p = sub.add_parser("serve", help="Start web UI server")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8741)

    args = parser.parse_args()
    if not args.command:
        parser.print_help()
        sys.exit(1)

    handlers = {
        "init": cmd_init,
        "add": cmd_add,
        "pull": cmd_pull,
        "move": cmd_move,
        "reject": cmd_reject,
        "block": cmd_block,
        "unblock": cmd_unblock,
        "note": cmd_note,
        "log": cmd_log,
        "diff": cmd_diff,
        "show": cmd_show,
        "status": cmd_status,
        "context": cmd_context,
        "spawn": cmd_spawn,
        "cleanup": cmd_cleanup,
        "serve": cmd_serve,
    }

    try:
        handlers[args.command](args)
    except FileNotFoundError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
