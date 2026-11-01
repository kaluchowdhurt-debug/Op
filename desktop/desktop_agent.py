# desktop/desktop_agent.py
"""Visible, local desktop automation adapter with a small command allowlist."""

from __future__ import annotations

import platform
import shlex
import subprocess
import sys
import webbrowser

HELP = """Commands:
  help                         Show commands
  open_url https://example.com Open a URL in the default browser
  type TEXT                    Type into the currently focused window
  open_app notepad             Windows only; opens allowlisted Notepad
  exit                         Quit"""


def type_text(text: str) -> None:
    """Type text into the currently focused window after a visible delay."""
    try:
        import pyautogui
    except ImportError as exc:
        raise RuntimeError("Install optional dependencies: pip install -r requirements.txt") from exc

    print("Typing in 3 seconds. Focus the intended window now; press Ctrl+C to cancel.")
    import time
    time.sleep(3)
    pyautogui.write(text, interval=0.02)


def open_app(name: str) -> None:
    """Launch only an explicitly allowlisted desktop app."""
    if platform.system() != "Windows":
        print("open_app is currently implemented for Windows only.")
        return

    allowed = {"notepad": ["notepad.exe"], "calculator": ["calc.exe"]}
    command = allowed.get(name.lower())
    if not command:
        print("Allowed apps: notepad, calculator")
        return

    subprocess.Popen(command, shell=False)


def run_command(line: str) -> bool:
    """Validate and run a local desktop command."""
    try:
        parts = shlex.split(line)
    except ValueError as exc:
        print(f"Could not parse command: {exc}")
        return True

    if not parts:
        return True

    command, *args = parts
    if command == "help":
        print(HELP)
    elif command == "open_url":
        if len(args) != 1 or not args[0].startswith(("https://", "http://")):
            print("Usage: open_url https://example.com")
        else:
            webbrowser.open(args[0], new=2)
    elif command == "type":
        if not args:
            print("Usage: type text to enter")
        else:
            try:
                type_text(" ".join(args))
            except RuntimeError as exc:
                print(exc)
            except KeyboardInterrupt:
                print("\nTyping cancelled.")
    elif command == "open_app":
        if len(args) != 1:
            print("Usage: open_app notepad")
        else:
            open_app(args[0])
    elif command == "exit":
        return False
    else:
        print("Unknown command. Type 'help'.")
    return True


def main() -> None:
    """Run a visible local command loop."""
    print("Desktop assistant is active in this terminal. Type 'help'.")
    while True:
        try:
            line = input("desktop> ")
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not run_command(line):
            break


if __name__ == "__main__":
    main()
