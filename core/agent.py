# core/agent.py
"""Small, explicit command router shared by the assistant starter."""

from __future__ import annotations

import shlex
import webbrowser

HELP = """Commands:
  help                         Show commands
  open_url https://example.com Open a URL in the default browser
  exit                         Quit"""


def run_command(line: str) -> bool:
    """Run one allowlisted command; return False when the user asks to exit."""
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
            print("Opened URL in the default browser.")
    elif command == "exit":
        return False
    else:
        print("Unknown command. Type 'help'.")
    return True


def main() -> None:
    """Run the local interactive command loop."""
    print("Cross-Platform AI Assistant starter. Type 'help'.")
    while True:
        try:
            line = input("assistant> ")
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not run_command(line):
            break


if __name__ == "__main__":
    main()
