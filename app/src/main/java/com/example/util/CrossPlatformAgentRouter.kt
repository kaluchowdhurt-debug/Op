package com.example.util

/**
 * Kotlin port of `core/agent.py` and `desktop/desktop_agent.py` from `cross_platform_ai_assistant`.
 * Provides safe, allowlisted command parsing (`shlex.split` equivalent) and execution for:
 * - `help`
 * - `open_url https://example.com`
 * - `type TEXT`
 * - `open_app notepad|calculator|browser|clock|accessibility`
 * - `accessibility` (opens Android Accessibility Settings per `android/MainActivity.kt`)
 * - `back` / `home` / `recents` / `notifications` (via `AssistantAccessibilityService`)
 * - `exit`
 */
data class AgentCommandResult(
    val shouldContinue: Boolean,
    val outputMessage: String,
    val matchedExplicitCommand: Boolean = true
)

object CrossPlatformAgentRouter {

    const val HELP_TEXT = """Commands:
  help                         Show commands
  open_url https://example.com Open a URL in the default browser
  type TEXT                    Type into focused input or copy to clipboard
  open_app <name>              Open allowlisted app (calculator, notepad, browser, clock, accessibility)
  accessibility                Open Android Accessibility Settings
  back                         Perform global Back action (requires Accessibility enabled)
  exit                         Quit command session / return to 3D Robot"""

    val ALLOWED_APPS = setOf("notepad", "calculator", "browser", "clock", "accessibility")

    private val KNOWN_COMMANDS = setOf(
        "help",
        "open_url",
        "type",
        "open_app",
        "accessibility",
        "back",
        "home",
        "recents",
        "notifications",
        "exit"
    )

    /**
     * POSIX-style shell tokenizer matching Python's `shlex.split(line)`.
     * Throws [IllegalArgumentException] if a quoted string is unterminated.
     */
    fun shlexSplit(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var escaping = false
        var tokenStarted = false

        for (ch in line) {
            when {
                escaping -> {
                    current.append(ch)
                    escaping = false
                    tokenStarted = true
                }
                ch == '\\' && !inSingleQuote -> {
                    escaping = true
                    tokenStarted = true
                }
                ch == '\'' && !inDoubleQuote -> {
                    inSingleQuote = !inSingleQuote
                    tokenStarted = true
                }
                ch == '"' && !inSingleQuote -> {
                    inDoubleQuote = !inDoubleQuote
                    tokenStarted = true
                }
                ch.isWhitespace() && !inSingleQuote && !inDoubleQuote -> {
                    if (tokenStarted) {
                        tokens.add(current.toString())
                        current.clear()
                        tokenStarted = false
                    }
                }
                else -> {
                    current.append(ch)
                    tokenStarted = true
                }
            }
        }

        if (escaping || inSingleQuote || inDoubleQuote) {
            throw IllegalArgumentException("No closing quotation")
        }

        if (tokenStarted) {
            tokens.add(current.toString())
        }

        return tokens
    }

    /**
     * Checks whether the input line starts with one of the explicit CLI commands
     * from `core/agent.py` or `desktop/desktop_agent.py`.
     */
    fun isExplicitCliCommand(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        val firstToken = trimmed.substringBefore(' ').lowercase()
        return firstToken in KNOWN_COMMANDS
    }

    /**
     * Runs a command using the exact rules of `core/agent.py` and `desktop/desktop_agent.py`.
     */
    fun runCommand(
        line: String,
        onOpenUrl: (String) -> Boolean = { true },
        onTypeText: (String) -> Boolean = { true },
        onOpenApp: (String) -> Boolean = { true },
        onOpenAccessibilitySettings: () -> Boolean = { true },
        onPerformGlobalAction: (String) -> Boolean = { false }
    ): AgentCommandResult {
        val parts = try {
            shlexSplit(line)
        } catch (exc: IllegalArgumentException) {
            return AgentCommandResult(
                shouldContinue = true,
                outputMessage = "Could not parse command: ${exc.message}"
            )
        }

        if (parts.isEmpty()) {
            return AgentCommandResult(
                shouldContinue = true,
                outputMessage = ""
            )
        }

        val command = parts[0].lowercase()
        val args = parts.drop(1)

        return when (command) {
            "help" -> {
                AgentCommandResult(
                    shouldContinue = true,
                    outputMessage = HELP_TEXT
                )
            }

            "open_url" -> {
                if (args.size != 1 || (!args[0].startsWith("https://") && !args[0].startsWith("http://"))) {
                    AgentCommandResult(
                        shouldContinue = true,
                        outputMessage = "Usage: open_url https://example.com"
                    )
                } else {
                    val opened = onOpenUrl(args[0])
                    AgentCommandResult(
                        shouldContinue = true,
                        outputMessage = if (opened) {
                            "Opened URL in the default browser: ${args[0]}"
                        } else {
                            "Could not open browser for URL: ${args[0]}"
                        }
                    )
                }
            }

            "type" -> {
                if (args.isEmpty()) {
                    AgentCommandResult(
                        shouldContinue = true,
                        outputMessage = "Usage: type text to enter"
                    )
                } else {
                    val textToType = args.joinToString(" ")
                    val typedDirectly = onTypeText(textToType)
                    AgentCommandResult(
                        shouldContinue = true,
                        outputMessage = if (typedDirectly) {
                            "Typed text into focused field (and copied to clipboard): \"$textToType\""
                        } else {
                            "Copied text to clipboard ready to paste: \"$textToType\" (Enable Device Assistant Accessibility to auto-type into external fields)."
                        }
                    )
                }
            }

            "open_app" -> {
                if (args.size != 1) {
                    AgentCommandResult(
                        shouldContinue = true,
                        outputMessage = "Usage: open_app notepad"
                    )
                } else {
                    val appName = args[0].lowercase()
                    if (appName !in ALLOWED_APPS) {
                        AgentCommandResult(
                            shouldContinue = true,
                            outputMessage = "Allowed apps: notepad, calculator, browser, clock, accessibility"
                        )
                    } else {
                        val launched = onOpenApp(appName)
                        AgentCommandResult(
                            shouldContinue = true,
                            outputMessage = if (launched) {
                                "Opened allowlisted app: $appName"
                            } else {
                                "Could not find a matching app on this device for: $appName"
                            }
                        )
                    }
                }
            }

            "accessibility" -> {
                val opened = onOpenAccessibilitySettings()
                AgentCommandResult(
                    shouldContinue = true,
                    outputMessage = if (opened) {
                        "Opening Android Accessibility Settings. Enable 'Device Assistant Accessibility' manually only if you want to test it."
                    } else {
                        "Could not open Android Accessibility Settings."
                    }
                )
            }

            "back", "home", "recents", "notifications" -> {
                val performed = onPerformGlobalAction(command)
                AgentCommandResult(
                    shouldContinue = true,
                    outputMessage = if (performed) {
                        "Performed global action: $command"
                    } else {
                        "Accessibility service is not enabled yet. Type 'accessibility' to open Accessibility Settings and enable Device Assistant Accessibility."
                    }
                )
            }

            "exit" -> {
                AgentCommandResult(
                    shouldContinue = false,
                    outputMessage = "Exiting command mode and returning to 3D Robot assistant."
                )
            }

            else -> {
                AgentCommandResult(
                    shouldContinue = true,
                    outputMessage = "Unknown command. Type 'help'.",
                    matchedExplicitCommand = false
                )
            }
        }
    }
}
