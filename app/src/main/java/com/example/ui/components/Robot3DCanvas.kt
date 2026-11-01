package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.example.model.Robot3DState
import com.example.model.RobotEmotion
import com.example.model.RobotGesture
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3): Vec3 = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )

    fun normalized(): Vec3 {
        val len = sqrt(x * x + y * y + z * z)
        return if (len > 1e-5f) Vec3(x / len, y / len, z / len) else Vec3(0f, 0f, 1f)
    }

    fun rotateX(deg: Float): Vec3 {
        val rad = deg * (PI.toFloat() / 180f)
        val c = cos(rad)
        val s = sin(rad)
        return Vec3(x, y * c - z * s, y * s + z * c)
    }

    fun rotateY(deg: Float): Vec3 {
        val rad = deg * (PI.toFloat() / 180f)
        val c = cos(rad)
        val s = sin(rad)
        return Vec3(x * c + z * s, y, -x * s + z * c)
    }

    fun rotateZ(deg: Float): Vec3 {
        val rad = deg * (PI.toFloat() / 180f)
        val c = cos(rad)
        val s = sin(rad)
        return Vec3(x * c - y * s, x * s + y * c, z)
    }
}

private data class Face3D(
    val vertices: List<Vec3>,
    val baseColor: Color,
    val emissive: Boolean = false,
    val borderColor: Color? = null
)

private data class ProjectedPolygon(
    val points: List<Offset>,
    val avgZ: Float,
    val shadedColor: Color,
    val borderColor: Color?
)

@Composable
fun Robot3DCanvas(
    state: Robot3DState,
    onCameraOrbit: (deltaYaw: Float, deltaPitch: Float) -> Unit,
    onPokeRobot: () -> Unit,
    onResetCamera: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // High-precision frame-synchronized nanosecond clock for ultra-smooth frame rendering
    var elapsedSec by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var startNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (startNanos == 0L) startNanos = frameNanos
                elapsedSec = (frameNanos - startNanos) / 1_000_000_000f
            }
        }
    }

    val smoothYaw by animateFloatAsState(
        targetValue = state.cameraYaw,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 400f),
        label = "smooth_yaw"
    )
    val smoothPitch by animateFloatAsState(
        targetValue = state.cameraPitch,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 400f),
        label = "smooth_pitch"
    )
    val smoothHeadPitch by animateFloatAsState(
        targetValue = state.emotion.headPitchBias,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 300f),
        label = "head_pitch"
    )
    val smoothHeadRoll by animateFloatAsState(
        targetValue = state.emotion.headRollBias,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 300f),
        label = "head_roll"
    )
    val smoothAmplitude by animateFloatAsState(
        targetValue = if (state.isSpeaking) 0.78f else if (state.isListening) state.audioAmplitude else 0.12f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "smooth_amp"
    )

    val reusablePath = remember { Path() }
    var activeTouchPoint by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = modifier
            .testTag("robot_3d_stage")
            .pointerInput(state.touchScreenEnabled, state.touchSensitivity) {
                if (!state.touchScreenEnabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset -> activeTouchPoint = offset },
                    onDragEnd = { activeTouchPoint = null },
                    onDragCancel = { activeTouchPoint = null },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        activeTouchPoint = change.position
                        val sens = state.touchSensitivity.coerceIn(0.4f, 3.0f)
                        onCameraOrbit(dragAmount.x * 0.45f * sens, dragAmount.y * 0.35f * sens)
                    }
                )
            }
            .pointerInput(state.touchScreenEnabled) {
                if (!state.touchScreenEnabled) return@pointerInput
                detectTapGestures(
                    onPress = { offset ->
                        activeTouchPoint = offset
                        tryAwaitRelease()
                        activeTouchPoint = null
                    },
                    onTap = { onPokeRobot() },
                    onDoubleTap = { onResetCamera() }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w * 0.5f, h * 0.47f)
            val scale = minOf(w, h) * 0.27f * state.cameraZoom

            val phase = elapsedSec * 2.4f
            val fastPhase = elapsedSec * 8.5f
            val blinkMod = elapsedSec % 3.8f
            val isBlinking = blinkMod in 3.62f..3.74f

            val activeAccent = if (state.emotion == RobotEmotion.NEUTRAL) {
                state.skinTheme.accentGlow
            } else {
                state.emotion.visorColor
            }

            // 1. Dynamic Radial Aura (pulses smoothly with voice during call)
            val auraScale = 0.56f + smoothAmplitude * 0.12f * sin(fastPhase)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        activeAccent.copy(alpha = 0.24f + smoothAmplitude * 0.12f),
                        state.skinTheme.coreGlow.copy(alpha = 0.09f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = minOf(w, h) * auraScale
                ),
                radius = minOf(w, h) * auraScale,
                center = center
            )

            // 2. Ultra-Smooth Continuous 3D Motion Physics
            val hoverY = sin(phase) * 0.11f + sin(phase * 0.5f) * 0.04f + when (state.activeGesture) {
                RobotGesture.BOUNCE, RobotGesture.CELEBRATE -> abs(sin(fastPhase * 0.8f)) * 0.24f
                else -> 0f
            }

            val gestureYaw = when (state.activeGesture) {
                RobotGesture.CELEBRATE -> sin(fastPhase * 0.6f) * 26f
                RobotGesture.SCAN -> sin(phase * 1.6f) * 22f
                else -> sin(phase * 0.6f) * 5.5f
            }

            val nodPitch = when (state.activeGesture) {
                RobotGesture.NOD -> sin(fastPhase * 1.2f) * 12f
                else -> if (state.isSpeaking) sin(fastPhase * 0.9f) * 3.5f else 0f
            }

            val headTiltRoll = smoothHeadRoll + when (state.activeGesture) {
                RobotGesture.THINK_TILT -> -14f
                else -> cos(phase * 0.8f) * 3.2f
            }

            val totalYaw = smoothYaw + gestureYaw
            val totalPitch = smoothPitch

            // 3. Draw 3D Pedestal & Live Call Voice Wave Rings
            draw3DPedestalRings(
                center = center,
                scale = scale,
                yaw = totalYaw,
                pitch = totalPitch,
                phase = phase,
                fastPhase = fastPhase,
                accentColor = activeAccent,
                coreColor = state.skinTheme.coreGlow,
                isSpeakingOrListening = state.isSpeaking || state.isListening,
                amplitude = smoothAmplitude,
                reusablePath = reusablePath
            )

            // 4. Construct 3D Robot Character Mesh
            val faces = ArrayList<Face3D>(64)
            val chassisMain = state.skinTheme.chassisPrimary
            val chassisDark = state.skinTheme.chassisSecondary
            val visorDark = Color(0xFF070B14)

            val torsoOffset = Vec3(0f, -0.58f + hoverY, 0f)
            val torsoBreathZ = sin(phase) * 1.5f
            faces.addAll(
                createBeveledBoxFaces(
                    center = torsoOffset,
                    halfW = 0.56f,
                    halfH = 0.44f,
                    halfD = 0.36f,
                    primaryColor = chassisMain,
                    sideColor = chassisDark,
                    rotX = 0f,
                    rotY = sin(phase * 0.5f) * 3f,
                    rotZ = torsoBreathZ
                )
            )

            // Glowing Chest Reactor Core
            val corePulse = 0.14f + 0.035f * sin(fastPhase) * (1f + smoothAmplitude)
            faces.add(
                Face3D(
                    vertices = listOf(
                        Vec3(-corePulse, torsoOffset.y + corePulse, 0.37f),
                        Vec3(corePulse, torsoOffset.y + corePulse, 0.37f),
                        Vec3(corePulse, torsoOffset.y - corePulse, 0.37f),
                        Vec3(-corePulse, torsoOffset.y - corePulse, 0.37f)
                    ),
                    baseColor = state.skinTheme.coreGlow,
                    emissive = true,
                    borderColor = activeAccent
                )
            )

            // Articulated Floating Hands (expressive gestures while talking or waving)
            val talkHandMotion = if (state.isSpeaking) sin(fastPhase * 0.7f) * 0.14f else 0f
            val leftArmWaveY = if (state.activeGesture == RobotGesture.CELEBRATE) {
                0.45f + sin(fastPhase) * 0.22f
            } else {
                -0.54f + hoverY * 1.25f + cos(phase) * 0.07f + talkHandMotion
            }
            val rightArmWaveY = when (state.activeGesture) {
                RobotGesture.WAVE, RobotGesture.CELEBRATE -> 0.48f + sin(fastPhase * 1.4f) * 0.24f
                else -> -0.54f + hoverY * 1.25f - cos(phase) * 0.07f - talkHandMotion
            }
            val rightArmRotZ = when (state.activeGesture) {
                RobotGesture.WAVE, RobotGesture.CELEBRATE -> -35f + sin(fastPhase * 1.4f) * 25f
                else -> 12f + (if (state.isSpeaking) sin(fastPhase) * 10f else 0f)
            }

            faces.addAll(
                createBeveledBoxFaces(
                    center = Vec3(-0.92f, leftArmWaveY, 0.08f),
                    halfW = 0.15f,
                    halfH = 0.26f,
                    halfD = 0.16f,
                    primaryColor = chassisDark,
                    sideColor = chassisMain,
                    rotX = 10f,
                    rotY = 0f,
                    rotZ = -12f
                )
            )

            faces.addAll(
                createBeveledBoxFaces(
                    center = Vec3(0.92f, rightArmWaveY, 0.12f),
                    halfW = 0.15f,
                    halfH = 0.26f,
                    halfD = 0.16f,
                    primaryColor = chassisDark,
                    sideColor = chassisMain,
                    rotX = -12f,
                    rotY = 0f,
                    rotZ = rightArmRotZ
                )
            )

            // Neck Joint
            faces.addAll(
                createBeveledBoxFaces(
                    center = Vec3(0f, -0.02f + hoverY, 0f),
                    halfW = 0.22f,
                    halfH = 0.12f,
                    halfD = 0.22f,
                    primaryColor = chassisDark,
                    sideColor = chassisDark.copy(alpha = 0.85f),
                    rotX = 0f,
                    rotY = 0f,
                    rotZ = 0f
                )
            )

            // Expressive 3D Head
            val headCenter = Vec3(0f, 0.56f + hoverY, 0f)
            val headPitch = smoothHeadPitch + nodPitch
            val headRoll = headTiltRoll
            val headYaw = when (state.emotion) {
                RobotEmotion.CURIOUS -> 10f
                RobotEmotion.THINKING -> -8f
                else -> sin(phase * 0.7f) * 4f
            }

            faces.addAll(
                createBeveledBoxFaces(
                    center = headCenter,
                    halfW = 0.78f,
                    halfH = 0.56f,
                    halfD = 0.54f,
                    primaryColor = chassisMain,
                    sideColor = chassisDark,
                    rotX = headPitch,
                    rotY = headYaw,
                    rotZ = headRoll
                )
            )

            // Ears
            faces.addAll(
                createHeadAttachedBox(
                    headCenter = headCenter,
                    localOffset = Vec3(-0.88f, 0f, 0f),
                    halfW = 0.10f,
                    halfH = 0.26f,
                    halfD = 0.24f,
                    color = activeAccent,
                    emissive = true,
                    headPitch = headPitch,
                    headYaw = headYaw,
                    headRoll = headRoll
                )
            )
            faces.addAll(
                createHeadAttachedBox(
                    headCenter = headCenter,
                    localOffset = Vec3(0.88f, 0f, 0f),
                    halfW = 0.10f,
                    halfH = 0.26f,
                    halfD = 0.24f,
                    color = activeAccent,
                    emissive = true,
                    headPitch = headPitch,
                    headYaw = headYaw,
                    headRoll = headRoll
                )
            )

            // Antenna
            faces.addAll(
                createHeadAttachedBox(
                    headCenter = headCenter,
                    localOffset = Vec3(0f, 0.68f, 0f),
                    halfW = 0.06f,
                    halfH = 0.14f,
                    halfD = 0.06f,
                    color = chassisDark,
                    emissive = false,
                    headPitch = headPitch,
                    headYaw = headYaw,
                    headRoll = headRoll
                )
            )
            faces.addAll(
                createHeadAttachedBox(
                    headCenter = headCenter,
                    localOffset = Vec3(0f, 0.86f, 0f),
                    halfW = 0.13f,
                    halfH = 0.13f,
                    halfD = 0.13f,
                    color = activeAccent,
                    emissive = true,
                    headPitch = headPitch,
                    headYaw = headYaw,
                    headRoll = headRoll
                )
            )

            // Front Visor
            val visorLocalVerts = listOf(
                Vec3(-0.64f, 0.38f, 0.55f),
                Vec3(0.64f, 0.38f, 0.55f),
                Vec3(0.64f, -0.38f, 0.55f),
                Vec3(-0.64f, -0.38f, 0.55f)
            ).map {
                headCenter + it.rotateZ(headRoll).rotateX(headPitch).rotateY(headYaw)
            }
            faces.add(
                Face3D(
                    vertices = visorLocalVerts,
                    baseColor = visorDark,
                    emissive = true,
                    borderColor = activeAccent.copy(alpha = 0.75f)
                )
            )

            // Eyes
            val eyeHalfH = when {
                isBlinking -> 0.02f
                state.emotion == RobotEmotion.HAPPY || state.emotion == RobotEmotion.EXCITED -> 0.11f
                state.emotion == RobotEmotion.FOCUSED -> 0.07f
                else -> 0.14f
            }
            val rightEyeScale = if (state.emotion == RobotEmotion.CURIOUS) 1.28f else 1.0f

            val leftEyeVerts = listOf(
                Vec3(-0.38f, 0.10f + eyeHalfH, 0.565f),
                Vec3(-0.16f, 0.10f + eyeHalfH, 0.565f),
                Vec3(-0.16f, 0.10f - eyeHalfH, 0.565f),
                Vec3(-0.38f, 0.10f - eyeHalfH, 0.565f)
            ).map {
                headCenter + it.rotateZ(headRoll).rotateX(headPitch).rotateY(headYaw)
            }
            faces.add(Face3D(vertices = leftEyeVerts, baseColor = activeAccent, emissive = true))

            val rHalfH = eyeHalfH * rightEyeScale
            val rightEyeVerts = listOf(
                Vec3(0.16f, 0.10f + rHalfH, 0.565f),
                Vec3(0.38f, 0.10f + rHalfH, 0.565f),
                Vec3(0.38f, 0.10f - rHalfH, 0.565f),
                Vec3(0.16f, 0.10f - rHalfH, 0.565f)
            ).map {
                headCenter + it.rotateZ(headRoll).rotateX(headPitch).rotateY(headYaw)
            }
            faces.add(Face3D(vertices = rightEyeVerts, baseColor = activeAccent, emissive = true))

            // Lip-Sync / Voice Equalizer on Visor
            if (state.isSpeaking || state.isListening) {
                for (i in 0 until 5) {
                    val barX = -0.32f + i * 0.16f
                    val waveH = (0.04f + 0.12f * abs(sin(fastPhase * 1.4f + i * 0.85f)) *
                        max(0.35f, smoothAmplitude)).coerceAtMost(0.17f)
                    val barVerts = listOf(
                        Vec3(barX - 0.045f, -0.18f + waveH, 0.568f),
                        Vec3(barX + 0.045f, -0.18f + waveH, 0.568f),
                        Vec3(barX + 0.045f, -0.18f - waveH, 0.568f),
                        Vec3(barX - 0.045f, -0.18f - waveH, 0.568f)
                    ).map {
                        headCenter + it.rotateZ(headRoll).rotateX(headPitch).rotateY(headYaw)
                    }
                    faces.add(Face3D(vertices = barVerts, baseColor = activeAccent, emissive = true))
                }
            } else {
                val mouthY = if (state.emotion == RobotEmotion.HAPPY) -0.16f else -0.20f
                val mouthVerts = listOf(
                    Vec3(-0.22f, mouthY + 0.03f, 0.568f),
                    Vec3(0.22f, mouthY + 0.03f, 0.568f),
                    Vec3(0.18f, mouthY - 0.03f, 0.568f),
                    Vec3(-0.18f, mouthY - 0.03f, 0.568f)
                ).map {
                    headCenter + it.rotateZ(headRoll).rotateX(headPitch).rotateY(headYaw)
                }
                faces.add(
                    Face3D(
                        vertices = mouthVerts,
                        baseColor = activeAccent.copy(alpha = 0.88f),
                        emissive = true
                    )
                )
            }

            // 5. Project & Z-Sort
            val lightDir = Vec3(0.45f, 0.72f, 0.85f).normalized()
            val cameraDist = 4.6f

            val projectedPolygons = faces.map { face ->
                val worldVerts = face.vertices.map { v ->
                    v.rotateY(totalYaw).rotateX(totalPitch)
                }

                val v0 = worldVerts[0]
                val v1 = worldVerts[1]
                val v2 = worldVerts[2]
                val normal = (v1 - v0).cross(v2 - v0).normalized()
                val avgZ = (worldVerts[0].z + worldVerts[1].z + worldVerts[2].z + worldVerts[3].z) * 0.25f

                val shadedColor = if (face.emissive) {
                    face.baseColor
                } else {
                    val diffuse = max(0.24f, abs(normal.dot(lightDir)))
                    val rim = (1f - abs(normal.z)).coerceIn(0f, 1f) * 0.20f
                    val factor = (diffuse + rim).coerceIn(0.28f, 1.12f)
                    Color(
                        red = (face.baseColor.red * factor).coerceIn(0f, 1f),
                        green = (face.baseColor.green * factor).coerceIn(0f, 1f),
                        blue = (face.baseColor.blue * factor).coerceIn(0f, 1f),
                        alpha = face.baseColor.alpha
                    )
                }

                val screenPts = worldVerts.map { p ->
                    val perspective = cameraDist / (cameraDist - p.z).coerceAtLeast(1.2f)
                    Offset(
                        x = center.x + p.x * scale * perspective,
                        y = center.y - p.y * scale * perspective
                    )
                }

                ProjectedPolygon(
                    points = screenPts,
                    avgZ = avgZ,
                    shadedColor = shadedColor,
                    borderColor = face.borderColor
                )
            }.sortedBy { it.avgZ }

            // 6. Render Sorted Polygons using single reusable Path
            projectedPolygons.forEach { poly ->
                reusablePath.reset()
                reusablePath.moveTo(poly.points[0].x, poly.points[0].y)
                for (i in 1 until poly.points.size) {
                    reusablePath.lineTo(poly.points[i].x, poly.points[i].y)
                }
                reusablePath.close()

                drawPath(path = reusablePath, color = poly.shadedColor)
                val strokeCol = poly.borderColor ?: Color.White.copy(alpha = 0.12f)
                drawPath(
                    path = reusablePath,
                    color = strokeCol,
                    style = Stroke(width = if (poly.borderColor != null) 3.5f else 1.2f)
                )
            }

            // 7. Orbiting Energy Particles
            drawOrbitingParticles(
                center = center,
                scale = scale,
                yaw = totalYaw,
                pitch = totalPitch,
                phase = phase,
                fastPhase = fastPhase,
                accentColor = activeAccent,
                isThinking = state.emotion == RobotEmotion.THINKING
            )

            // 8. Live Touch Screen Visual Pointer Ring (when Touch Pointer setting is ON)
            val touchPt = activeTouchPoint
            if (state.touchScreenEnabled && state.showTouchPointerVisual && touchPt != null) {
                val pulseR = 38f + 8f * sin(fastPhase * 1.4f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            activeAccent.copy(alpha = 0.45f),
                            activeAccent.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        center = touchPt,
                        radius = pulseR * 1.6f
                    ),
                    radius = pulseR * 1.6f,
                    center = touchPt
                )
                drawCircle(
                    color = activeAccent,
                    radius = pulseR,
                    center = touchPt,
                    style = Stroke(width = 3.5f)
                )
                drawCircle(
                    color = Color.White,
                    radius = 8f,
                    center = touchPt
                )
            }
        }
    }
}

private fun DrawScope.draw3DPedestalRings(
    center: Offset,
    scale: Float,
    yaw: Float,
    pitch: Float,
    phase: Float,
    fastPhase: Float,
    accentColor: Color,
    coreColor: Color,
    isSpeakingOrListening: Boolean,
    amplitude: Float,
    reusablePath: Path
) {
    val pedestalY = -1.28f
    val cameraDist = 4.6f
    val ringRadii = listOf(0.75f, 1.15f, 1.55f)

    ringRadii.forEachIndexed { idx, baseR ->
        val dynamicBoost = if (isSpeakingOrListening) {
            1f + 0.16f * sin(fastPhase + idx) * max(0.35f, amplitude)
        } else {
            1f + 0.04f * sin(phase + idx)
        }
        val radius = baseR * dynamicBoost
        val segments = 40
        reusablePath.reset()
        for (i in 0..segments) {
            val angle = (i.toFloat() / segments) * (2f * PI.toFloat()) + phase * (if (idx % 2 == 0) 0.4f else -0.3f)
            val pt3D = Vec3(
                x = cos(angle) * radius,
                y = pedestalY,
                z = sin(angle) * radius
            ).rotateY(yaw).rotateX(pitch)

            val perspective = cameraDist / (cameraDist - pt3D.z).coerceAtLeast(1.2f)
            val sx = center.x + pt3D.x * scale * perspective
            val sy = center.y - pt3D.y * scale * perspective
            if (i == 0) reusablePath.moveTo(sx, sy) else reusablePath.lineTo(sx, sy)
        }
        val ringColor = if (idx == 1) coreColor else accentColor
        drawPath(
            path = reusablePath,
            color = ringColor.copy(alpha = (0.50f - idx * 0.12f).coerceAtLeast(0.16f)),
            style = Stroke(width = (4f - idx).coerceAtLeast(1.5f), cap = StrokeCap.Round)
        )
    }
}

private fun DrawScope.drawOrbitingParticles(
    center: Offset,
    scale: Float,
    yaw: Float,
    pitch: Float,
    phase: Float,
    fastPhase: Float,
    accentColor: Color,
    isThinking: Boolean
) {
    val cameraDist = 4.6f
    val particleCount = if (isThinking) 10 else 6
    val speedPhase = if (isThinking) fastPhase * 0.7f else phase

    for (i in 0 until particleCount) {
        val angle = speedPhase + (i.toFloat() / particleCount) * (2f * PI.toFloat())
        val orbitRadius = if (isThinking) 1.32f else 1.48f
        val yOffset = sin(angle * 2f + i) * 0.65f
        val pt = Vec3(
            x = cos(angle) * orbitRadius,
            y = 0.1f + yOffset,
            z = sin(angle) * orbitRadius
        ).rotateY(yaw).rotateX(pitch)

        val perspective = cameraDist / (cameraDist - pt.z).coerceAtLeast(1.2f)
        val sx = center.x + pt.x * scale * perspective
        val sy = center.y - pt.y * scale * perspective
        val dotRadius = (if (isThinking) 6.5f else 4.5f) * perspective

        drawCircle(
            color = accentColor.copy(alpha = 0.75f),
            radius = dotRadius,
            center = Offset(sx, sy)
        )
    }
}

private fun createBeveledBoxFaces(
    center: Vec3,
    halfW: Float,
    halfH: Float,
    halfD: Float,
    primaryColor: Color,
    sideColor: Color,
    rotX: Float,
    rotY: Float,
    rotZ: Float
): List<Face3D> {
    val corners = listOf(
        Vec3(-halfW, halfH, halfD),
        Vec3(halfW, halfH, halfD),
        Vec3(halfW, -halfH, halfD),
        Vec3(-halfW, -halfH, halfD),
        Vec3(-halfW, halfH, -halfD),
        Vec3(halfW, halfH, -halfD),
        Vec3(halfW, -halfH, -halfD),
        Vec3(-halfW, -halfH, -halfD)
    ).map { local ->
        center + local.rotateZ(rotZ).rotateX(rotX).rotateY(rotY)
    }

    return listOf(
        Face3D(listOf(corners[0], corners[1], corners[2], corners[3]), primaryColor),
        Face3D(listOf(corners[5], corners[4], corners[7], corners[6]), sideColor),
        Face3D(listOf(corners[4], corners[0], corners[3], corners[7]), sideColor),
        Face3D(listOf(corners[1], corners[5], corners[6], corners[2]), sideColor),
        Face3D(listOf(corners[4], corners[5], corners[1], corners[0]), primaryColor),
        Face3D(listOf(corners[3], corners[2], corners[6], corners[7]), sideColor)
    )
}

private fun createHeadAttachedBox(
    headCenter: Vec3,
    localOffset: Vec3,
    halfW: Float,
    halfH: Float,
    halfD: Float,
    color: Color,
    emissive: Boolean,
    headPitch: Float,
    headYaw: Float,
    headRoll: Float
): List<Face3D> {
    val corners = listOf(
        Vec3(-halfW, halfH, halfD),
        Vec3(halfW, halfH, halfD),
        Vec3(halfW, -halfH, halfD),
        Vec3(-halfW, -halfH, halfD),
        Vec3(-halfW, halfH, -halfD),
        Vec3(halfW, halfH, -halfD),
        Vec3(halfW, -halfH, -halfD),
        Vec3(-halfW, -halfH, -halfD)
    ).map { v ->
        val inHeadSpace = localOffset + v
        headCenter + inHeadSpace.rotateZ(headRoll).rotateX(headPitch).rotateY(headYaw)
    }

    return listOf(
        Face3D(listOf(corners[0], corners[1], corners[2], corners[3]), color, emissive),
        Face3D(listOf(corners[5], corners[4], corners[7], corners[6]), color, emissive),
        Face3D(listOf(corners[4], corners[0], corners[3], corners[7]), color, emissive),
        Face3D(listOf(corners[1], corners[5], corners[6], corners[2]), color, emissive),
        Face3D(listOf(corners[4], corners[5], corners[1], corners[0]), color, emissive),
        Face3D(listOf(corners[3], corners[2], corners[6], corners[7]), color, emissive)
    )
}
