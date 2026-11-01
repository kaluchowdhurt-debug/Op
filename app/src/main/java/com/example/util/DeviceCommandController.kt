package com.example.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import com.example.service.AssistantAccessibilityService
import java.util.Locale

sealed interface DeviceCommandAction {
    data object None : DeviceCommandAction
    data class ImmediateSpokenResult(
        val spokenMessage: String
    ) : DeviceCommandAction
    data class ExitSession(
        val spokenMessage: String
    ) : DeviceCommandAction
}

object DeviceCommandController {

    fun evaluateCommand(context: Context, rawPrompt: String): DeviceCommandAction {
        val trimmed = rawPrompt.trim()
        if (trimmed.isEmpty()) return DeviceCommandAction.None

        // 1. Check explicit commands from cross_platform_ai_assistant (core/agent.py & desktop/desktop_agent.py)
        if (CrossPlatformAgentRouter.isExplicitCliCommand(trimmed)) {
            val result = CrossPlatformAgentRouter.runCommand(
                line = trimmed,
                onOpenUrl = { url -> openUrlInBrowser(context, url) },
                onTypeText = { text -> typeOrCopyText(context, text) },
                onOpenApp = { appName -> openAllowlistedApp(context, appName) },
                onOpenAccessibilitySettings = { openAccessibilitySettings(context) },
                onPerformGlobalAction = { actionName -> performGlobalAction(actionName) }
            )
            return if (!result.shouldContinue) {
                DeviceCommandAction.ExitSession(spokenMessage = result.outputMessage)
            } else {
                DeviceCommandAction.ImmediateSpokenResult(spokenMessage = result.outputMessage)
            }
        }

        val lower = trimmed.lowercase(Locale.getDefault())

        // 2. Natural language URL opening (e.g. "open https://example.com" or "open google.com")
        val urlRegex = Regex("""(https?://[^\s]+)""", RegexOption.IGNORE_CASE)
        val matchedUrl = urlRegex.find(trimmed)?.value
        if (matchedUrl != null && (lower.startsWith("open ") || lower.contains("kholo") || lower.contains("open url"))) {
            val opened = openUrlInBrowser(context, matchedUrl)
            val msg = if (opened) {
                "Maine browser mein URL khol diya hai: $matchedUrl"
            } else {
                "URL open nahi ho paya: $matchedUrl"
            }
            return DeviceCommandAction.ImmediateSpokenResult(spokenMessage = msg)
        }

        // 3. Natural language Accessibility Settings request (from android/MainActivity.kt in zip)
        if (lower.contains("accessibility setting") ||
            lower.contains("open accessibility") ||
            lower.contains("accessibility kholo") ||
            lower.contains("accessibility status")
        ) {
            val enabled = AssistantAccessibilityService.isServiceEnabled(context)
            openAccessibilitySettings(context)
            val statusText = if (enabled) {
                "Device Assistant Accessibility abhi ENABLED hai. Maine Accessibility Settings khol di hai."
            } else {
                "Device Assistant Accessibility abhi OFF hai. Maine Android Accessibility Settings khol di hai—agar aap test karna chahte hain toh ise manually on kar sakte hain."
            }
            return DeviceCommandAction.ImmediateSpokenResult(spokenMessage = statusText)
        }

        // 4. Natural language allowlisted app launch (calculator / notepad / browser)
        if (lower == "open calculator" || lower == "calculator kholo") {
            val ok = openAllowlistedApp(context, "calculator")
            return DeviceCommandAction.ImmediateSpokenResult(
                spokenMessage = if (ok) "Calculator khol diya gaya hai." else "Is device par Calculator app nahi mila."
            )
        }
        if (lower == "open notepad" || lower == "notepad kholo" || lower == "open notes") {
            val ok = openAllowlistedApp(context, "notepad")
            return DeviceCommandAction.ImmediateSpokenResult(
                spokenMessage = if (ok) "Notepad / Notes app khol diya gaya hai." else "Notes app open karne ke liye koi app nahi mila."
            )
        }

        // 5. Battery Status command
        if (lower.contains("battery") || lower.contains("charge kitna")) {
            val batteryText = getBatteryStatusSummary(context)
            return DeviceCommandAction.ImmediateSpokenResult(spokenMessage = batteryText)
        }

        return DeviceCommandAction.None
    }

    fun openUrlInBrowser(context: Context, url: String): Boolean {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun typeOrCopyText(context: Context, text: String): Boolean {
        // Always copy to clipboard as reliable fallback on all Android devices
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("assistant_typed_text", text))
        } catch (_: Exception) {
        }

        // If AssistantAccessibilityService is active and an input node is focused, type directly
        val service = AssistantAccessibilityService.instance
        return service?.typeTextIntoFocusedNode(text) == true
    }

    fun openAllowlistedApp(context: Context, name: String): Boolean {
        return try {
            val intent: Intent? = when (name.lowercase(Locale.getDefault())) {
                "calculator" -> {
                    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
                }
                "notepad" -> {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "")
                    }
                }
                "browser" -> {
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                }
                "clock" -> {
                    Intent(AlarmClock.ACTION_SHOW_ALARMS)
                }
                "accessibility" -> {
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                }
                else -> null
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun openAccessibilitySettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun performGlobalAction(actionName: String): Boolean {
        val service = AssistantAccessibilityService.instance ?: return false
        return when (actionName.lowercase(Locale.getDefault())) {
            "back" -> service.performBackForExplicitWorkflow()
            "home" -> service.performHomeForExplicitWorkflow()
            "recents" -> service.performRecentsForExplicitWorkflow()
            "notifications" -> service.performNotificationsForExplicitWorkflow()
            else -> false
        }
    }

    fun getBatteryStatusSummary(context: Context): String {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val status = context.registerReceiver(null, filter)
            val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val chargingText = if (plugged != 0) "aur phone abhi charge ho raha hai" else "aur charger connected nahi hai"
            if (pct >= 0) {
                "Aapke phone ki battery abhi $pct percent hai, $chargingText."
            } else {
                "Battery level abhi read nahi ho paya."
            }
        } catch (_: Exception) {
            "Battery status abhi check nahi ho saka."
        }
    }
}
