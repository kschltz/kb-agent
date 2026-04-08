"""Test web server boots and serves content."""

import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from kb.board import init_board, _git

PASS = 0
FAIL = 0
ERRORS = []


def test(name):
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
    return wrapper


def make_repo():
    d = tempfile.mkdtemp()
    _git("init", cwd=d)
    _git("config", "user.email", "t@t.com", cwd=d)
    _git("config", "user.name", "T", cwd=d)
    Path(d, "README.md").write_text("# test\n")
    _git("add", ".", cwd=d)
    _git("commit", "-m", "init", cwd=d)
    return Path(d)


print("\n── Web Server ──")


@test("server module imports")
def _():
    from kb.server import get_board_state, handle_ui_command, get_html


@test("get_html returns content")
def _():
    from kb.server import get_html
    html = get_html()
    assert "kb" in html
    assert "<div" in html
    assert "WebSocket" in html


@test("get_board_state works with real board")
def _():
    repo = make_repo()
    board = init_board(str(repo))
    board.create_card("Test task")
    os.chdir(repo)
    from kb.server import get_board_state
    state = get_board_state()
    assert "lanes" in state
    assert state["lanes"][0]["name"] == "backlog"
    assert len(state["lanes"][0]["cards"]) == 1


@test("handle_ui_command: add card")
def _():
    repo = make_repo()
    init_board(str(repo))
    os.chdir(repo)
    from kb.server import handle_ui_command
    result = handle_ui_command({"action": "add", "title": "New task"})
    assert result["success"]
    assert result["card"]["title"] == "New task"


@test("handle_ui_command: note")
def _():
    repo = make_repo()
    board = init_board(str(repo))
    board.create_card("Task")
    os.chdir(repo)
    from kb.server import handle_ui_command
    result = handle_ui_command({"action": "note", "card_id": "001", "message": "Hello"})
    assert result["success"]


@test("handle_ui_command: block/unblock")
def _():
    repo = make_repo()
    board = init_board(str(repo))
    board.create_card("Task")
    os.chdir(repo)
    from kb.server import handle_ui_command
    result = handle_ui_command({"action": "block", "card_id": "001", "reason": "test"})
    assert result["success"]
    result = handle_ui_command({"action": "unblock", "card_id": "001"})
    assert result["success"]


@test("handle_ui_command: move")
def _():
    repo = make_repo()
    board = init_board(str(repo))
    board.create_card("Task", lane="in-progress")
    os.chdir(repo)
    from kb.server import handle_ui_command
    result = handle_ui_command({"action": "move", "card_id": "001", "lane": "review"})
    assert result["success"], result.get("message", "")


@test("server boots and serves HTML via HTTP")
def _():
    repo = make_repo()
    init_board(str(repo))

    env = os.environ.copy()
    env["PYTHONPATH"] = str(Path(__file__).parent.parent)

    os.chdir(repo)
    from kb.server import Server, Handler
    srv = Server(('127.0.0.1', 18741), Handler)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()

    try:
        time.sleep(0.3)
        resp = urllib.request.urlopen("http://127.0.0.1:18741/", timeout=3)
        html = resp.read().decode()
        assert "kb" in html
        assert "WebSocket" in html

        resp = urllib.request.urlopen("http://127.0.0.1:18741/api/state", timeout=3)
        state = json.loads(resp.read().decode())
        assert "lanes" in state
    finally:
        srv.shutdown()


@test("ui.html contains all required UI elements")
def _():
    from kb.server import get_html
    html = get_html()
    # board structure
    assert 'id="board"' in html
    assert 'id="detail-panel"' in html
    assert 'id="overlay"' in html
    # drag and drop
    assert 'dragstart' in html
    assert 'dragover' in html
    assert 'drop' in html
    # note input
    assert 'note-input' in html
    # add card
    assert 'add-modal' in html
    # WebSocket
    assert 'new WebSocket' in html
    # diff viewer
    assert 'diff-block' in html


print(f"\n{'='*50}")
print(f"  {PASS} passed, {FAIL} failed")
if ERRORS:
    print(f"\n  Failures:")
    for name, err in ERRORS:
        print(f"    {name}: {err}")
print(f"{'='*50}")
sys.exit(1 if FAIL else 0)
