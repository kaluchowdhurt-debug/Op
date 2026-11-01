package com.example.model

import androidx.compose.ui.graphics.Color

enum class RobotEmotion(
    val displayName: String,
    val statusSubtitle: String,
    val visorColor: Color,
    val headPitchBias: Float,
    val headRollBias: Float
) {
    NEUTRAL(
        displayName = "Online & Ready",
        statusSubtitle = "Standing by for your voice or text command",
        visorColor = Color(0xFF00E5FF),
        headPitchBias = 0f,
        headRollBias = 0f
    ),
    LISTENING(
        displayName = "Listening Intently",
        statusSubtitle = "Capturing live audio wave input…",
        visorColor = Color(0xFF00FFB2),
        headPitchBias = -8f,
        headRollBias = 5f
    ),
    THINKING(
        displayName = "Synthesizing",
        statusSubtitle = "Processing context & personal memory bank…",
        visorColor = Color(0xFF9D6BFF),
        headPitchBias = 6f,
        headRollBias = -12f
    ),
    SPEAKING(
        displayName = "Speaking",
        statusSubtitle = "Delivering vocal & 3D response…",
        visorColor = Color(0xFF00E5FF),
        headPitchBias = -3f,
        headRollBias = 0f
    ),
    HAPPY(
        displayName = "Delighted",
        statusSubtitle = "Glad to help you out!",
        visorColor = Color(0xFF10B981),
        headPitchBias = -5f,
        headRollBias = 6f
    ),
    CURIOUS(
        displayName = "Curious",
        statusSubtitle = "Exploring ideas & possibilities",
        visorColor = Color(0xFFF59E0B),
        headPitchBias = 4f,
        headRollBias = 14f
    ),
    EXCITED(
        displayName = "Supercharged",
        statusSubtitle = "High-energy mode engaged!",
        visorColor = Color(0xFFFFB300),
        headPitchBias = -7f,
        headRollBias = -8f
    ),
    FOCUSED(
        displayName = "Deep Focus",
        statusSubtitle = "Precision task & schedule execution",
        visorColor = Color(0xFF38BDF8),
        headPitchBias = -4f,
        headRollBias = 0f
    );

    companion object {
        fun fromString(value: String?): RobotEmotion {
            return entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: NEUTRAL
        }
    }
}

enum class RobotGesture(
    val label: String,
    val durationMs: Long
) {
    NONE("Idle Hover", 0L),
    WAVE("Friendly Wave", 2600L),
    NOD("Affirmative Nod", 2200L),
    THINK_TILT("Deep Tilt", 2800L),
    SCAN("3D Holo Scan", 2600L),
    CELEBRATE("Victory Spin & Bounce", 3000L),
    BOUNCE("Happy Hop", 2200L);

    companion object {
        fun fromString(value: String?): RobotGesture {
            return entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: NONE
        }
    }
}

enum class RobotSkinTheme(
    val displayName: String,
    val chassisPrimary: Color,
    val chassisSecondary: Color,
    val accentGlow: Color,
    val coreGlow: Color
) {
    CYBER_CYAN(
        displayName = "Titanium & Cyber Cyan",
        chassisPrimary = Color(0xFFE2E8F0),
        chassisSecondary = Color(0xFF94A3B8),
        accentGlow = Color(0xFF00E5FF),
        coreGlow = Color(0xFF7C4DFF)
    ),
    SOLAR_AMBER(
        displayName = "Gold & Solar Flare",
        chassisPrimary = Color(0xFFFEF3C7),
        chassisSecondary = Color(0xFFD97706),
        accentGlow = Color(0xFFF59E0B),
        coreGlow = Color(0xFFEF4444)
    ),
    NEON_EMERALD(
        displayName = "Stealth & Bio-Emerald",
        chassisPrimary = Color(0xFFCBD5E1),
        chassisSecondary = Color(0xFF475569),
        accentGlow = Color(0xFF10B981),
        coreGlow = Color(0xFF00E5FF)
    ),
    PLASMA_VIOLET(
        displayName = "Obsidian & Plasma",
        chassisPrimary = Color(0xFFDDD6FE),
        chassisSecondary = Color(0xFF6D28D9),
        accentGlow = Color(0xFFC084FC),
        coreGlow = Color(0xFF38BDF8)
    )
}

enum class RobotPersonality(
    val displayName: String,
    val description: String,
    val systemPromptDirective: String
) {
    FRIENDLY_COMPANION(
        displayName = "Warm & Expressive",
        description = "Encouraging, empathetic, and playful 3D companion",
        systemPromptDirective = "Speak warmly, concisely, and enthusiastically like a caring, expressive 3D robot friend."
    ),
    WITTY_TECH_GENIUS(
        displayName = "Witty Tech Genius",
        description = "Sharp, clever, and sci-fi inspired problem solver",
        systemPromptDirective = "Speak with clever wit, crisp intelligence, and subtle futuristic charm."
    ),
    CALM_COACH(
        displayName = "Mindful Productivity Coach",
        description = "Focused on clarity, calm prioritization, and habits",
        systemPromptDirective = "Speak calmly, structured, and mindfully, helping the user focus on one clear step at a time."
    )
}

data class Robot3DState(
    val emotion: RobotEmotion = RobotEmotion.NEUTRAL,
    val activeGesture: RobotGesture = RobotGesture.WAVE,
    val skinTheme: RobotSkinTheme = RobotSkinTheme.CYBER_CYAN,
    val personality: RobotPersonality = RobotPersonality.FRIENDLY_COMPANION,
    val cameraYaw: Float = 16f,
    val cameraPitch: Float = -8f,
    val cameraZoom: Float = 1.0f,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val audioAmplitude: Float = 0.15f,
    val voiceOutputEnabled: Boolean = true,
    val speechRate: Float = 1.05f,
    val speechPitch: Float = 1.1f,
    val pokeCount: Int = 0,
    val touchScreenEnabled: Boolean = true,
    val showTouchPointerVisual: Boolean = true,
    val touchHapticsEnabled: Boolean = true,
    val touchSensitivity: Float = 1.2f,
    val screenBrightness: Float = 0.85f
)
