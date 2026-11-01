# tests/test_agent.py
"""Basic tests for safe command validation."""

from core.agent import run_command


def test_unknown_command_is_handled(capsys):
    assert run_command("not_a_real_command") is True
    assert "Unknown command" in capsys.readouterr().out


def test_exit_returns_false():
    assert run_command("exit") is False


def test_open_url_rejects_non_http_scheme(capsys):
    assert run_command("open_url file:///etc/passwd") is True
    assert "Usage" in capsys.readouterr().out
