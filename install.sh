#!/usr/bin/env bash
set -euo pipefail

# kb-agent installer
# Creates a symlink to the kb CLI on your PATH

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
KB_SCRIPT="$REPO_DIR/bin/kb"

echo "=== kb-agent installer ==="

# --- dependency checks ---
if ! command -v bb &>/dev/null; then
    echo "❌ Babashka (bb) not found. Install it first:"
    echo "    https://github.com/babashka/babashka#installation"
    exit 1
fi
echo "✓ Babashka $(bb --version)"

if ! command -v git &>/dev/null; then
    echo "❌ git not found. Install it first."
    exit 1
fi
echo "✓ git $(git --version)"

# --- find install dir ---
PREFERRED_DIRS=(
    "$HOME/.local/bin"
    "/usr/local/bin"
)

INSTALL_DIR=""
for dir in "${PREFERRED_DIRS[@]}"; do
    # Check if dir exists and is on PATH
    if [ -d "$dir" ] && echo "$PATH" | tr ':' '\n' | grep -qx "$dir"; then
        # Check if writable
        if [ -w "$dir" ]; then
            INSTALL_DIR="$dir"
            break
        fi
    fi
done

# If none found, offer to create ~/.local/bin
if [ -z "$INSTALL_DIR" ]; then
    if [ -d "$HOME/.local/bin" ]; then
        INSTALL_DIR="$HOME/.local/bin"
    else
        read -p "No writable bin directory on PATH found. Create $HOME/.local/bin and add to PATH? [Y/n] " ans
        if [[ ! "$ans" =~ ^[Nn] ]]; then
            mkdir -p "$HOME/.local/bin"
            INSTALL_DIR="$HOME/.local/bin"
            if ! echo "$PATH" | tr ':' '\n' | grep -qx "$HOME/.local/bin"; then
                echo "⚠️  $HOME/.local/bin is not on your PATH."
                echo "    Add this to your shell profile (~/.bashrc, ~/.zshrc, etc.):"
                echo "      export PATH=\"\$HOME/.local/bin:\$PATH\""
            fi
        else
            echo "Aborting."
            exit 1
        fi
    fi
fi

# --- create symlink ---
TARGET="$INSTALL_DIR/kb"

if [ -e "$TARGET" ] || [ -L "$TARGET" ]; then
    read -p "⚠️  $TARGET already exists. Overwrite? [y/N] " ans
    if [[ "$ans" =~ ^[Yy] ]]; then
        rm -f "$TARGET"
    else
        echo "Aborting."
        exit 1
    fi
fi

ln -s "$KB_SCRIPT" "$TARGET"
echo "✓ Linked $TARGET → $KB_SCRIPT"

# --- verify ---
if command -v kb &>/dev/null; then
    echo "✓ kb is on PATH: $(command -v kb)"
    echo ""
    echo "Usage: kb <command> [options]"
    echo "  kb init          # create board in a git repo"
    echo "  kb status        # view the board"
    echo "  kb serve         # start web UI"
else
    echo "⚠️  kb symlink created, but it may not be on your current PATH yet."
    echo "    Try:  export PATH=\"$INSTALL_DIR:\$PATH\""
fi
