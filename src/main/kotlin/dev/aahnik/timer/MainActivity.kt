package dev.aahnik.timer

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.RingtoneManager
import android.os.Bundle
import android.os.SystemClock
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MAX_MINUTES = 60
private const val TACHOMETER_START = -135f
private const val TACHOMETER_SPAN = 270f
private const val FLOOD_SCALE_SPAN = 0.76f

private enum class DragMode { Rotate, Vertical }

private enum class DialStyle(
    val dragMode: DragMode = DragMode.Rotate,
    val minutesPerTurn: Float = 60f,
    val floatsWithPhone: Boolean = true,
    val circular: Boolean = false,
) {
    Chronograph(circular = true),
    Bezel(circular = true),
    Tachometer(minutesPerTurn = MAX_MINUTES * 360f / TACHOMETER_SPAN, circular = true),
    DotMatrix(circular = true),
    Drum(DragMode.Vertical),
    Flood(DragMode.Vertical, floatsWithPhone = false),
    Flip(DragMode.Vertical),
    Hourglass(DragMode.Vertical, floatsWithPhone = false),
    Moon(circular = true),
}

private enum class Face(val background: Color, val ink: Color, val red: Color) {
    Cream(Color(0xFFF2EFE9), Color(0xFF141414), Color(0xFFD71921)),
    Black(Color(0xFF000000), Color(0xFFEDEDED), Color(0xFFE5322D)),
}

private data class Gravity(val x: Float, val y: Float, val z: Float)

private data class DialFrame(
    val remainingMinutes: Float,
    val totalMinutes: Float,
    val secondsAngle: Float,
    val elapsedFraction: Float,
    val running: Boolean,
    val sweepSeconds: Boolean,
    val background: Color,
    val ink: Color,
    val red: Color,
    val floodLevel: Float,
    val surfaceSlope: Float,
    val phoneRoll: Float,
    val floodZeroAtBottom: Float,
    val wavePhase: Float,
    val waveAmplitude: Float,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getPreferences(MODE_PRIVATE)
        setContent {
            var dialStyle by remember { mutableStateOf(DialStyle.entries.getOrElse(preferences.getInt("dial", 0)) { DialStyle.Chronograph }) }
            var face by remember { mutableStateOf(Face.entries.getOrElse(preferences.getInt("face", 0)) { Face.Cream }) }
            var sweepSeconds by remember { mutableStateOf(preferences.getBoolean("sweep", false)) }
            LaunchedEffect(dialStyle, face, sweepSeconds) {
                val transparent = android.graphics.Color.TRANSPARENT
                val bars = if (face == Face.Black) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(bars, bars)
                preferences.edit().putInt("dial", dialStyle.ordinal).putInt("face", face.ordinal).putBoolean("sweep", sweepSeconds).apply()
            }
            TimerScreen(
                dialStyle = dialStyle,
                face = face,
                sweepSeconds = sweepSeconds,
                onNextDial = { dialStyle = DialStyle.entries[(dialStyle.ordinal + 1) % DialStyle.entries.size] },
                onToggleFace = { face = if (face == Face.Cream) Face.Black else Face.Cream },
                onToggleSeconds = { sweepSeconds = !sweepSeconds },
            )
        }
    }
}

@Composable
private fun rememberDeviceOrientation(): State<Int> {
    val context = LocalContext.current
    val orientation = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val nearest = (degrees + 45) / 90 % 4 * 90
                val distance = abs(((degrees - nearest + 540) % 360) - 180)
                if (distance < 30 && nearest != orientation.intValue) orientation.intValue = nearest
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }
    return orientation
}

@Composable
private fun rememberGravity(): State<Gravity> {
    val context = LocalContext.current
    val gravity = remember { mutableStateOf(Gravity(0f, 9.8f, 0f)) }
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val previous = gravity.value
                val smoothing = 0.15f
                gravity.value = Gravity(
                    previous.x + (event.values[0] - previous.x) * smoothing,
                    previous.y + (event.values[1] - previous.y) * smoothing,
                    previous.z + (event.values[2] - previous.z) * smoothing,
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sensorManager.unregisterListener(listener) }
    }
    return gravity
}

@Composable
private fun TimerScreen(
    dialStyle: DialStyle,
    face: Face,
    sweepSeconds: Boolean,
    onNextDial: () -> Unit,
    onToggleFace: () -> Unit,
    onToggleSeconds: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val gravityState = rememberGravity()
    val gravity by gravityState
    val orientation by rememberDeviceOrientation()
    var phoneRoll by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(gravityState) {
        snapshotFlow { gravityState.value }.collect { reading ->
            if (hypot(reading.x, reading.y) < 3f) return@collect
            val rawRoll = Math.toDegrees(atan2(reading.x, reading.y).toDouble()).toFloat()
            phoneRoll += ((rawRoll - phoneRoll) % 360f + 540f) % 360f - 180f
        }
    }
    val floodZeroAtBottom by animateFloatAsState(if (orientation == 0 || orientation == 90) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 120f))
    var totalMillis by rememberSaveable { mutableLongStateOf(5 * 60_000L) }
    var remainingMillis by rememberSaveable { mutableLongStateOf(5 * 60_000L) }
    var running by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragMinutes by remember { mutableFloatStateOf(0f) }
    val finished = remainingMillis == 0L
    val elapsedMillis = totalMillis - remainingMillis
    val elapsedSeconds = (elapsedMillis / 1000).toInt()
    val elapsedFraction = if (totalMillis == 0L) 0f else elapsedMillis.toFloat() / totalMillis

    val background by animateColorAsState(face.background, tween(300))
    val ink by animateColorAsState(face.ink, tween(300))
    val red by animateColorAsState(face.red, tween(300))
    val remainingMinutes by animateFloatAsState(
        remainingMillis / 60_000f,
        if (dragging || running) snap() else spring(dampingRatio = if (dialStyle == DialStyle.Tachometer) 0.4f else 0.8f, stiffness = 250f),
    )
    val floodLevel by animateFloatAsState(elapsedFraction, if (running) snap() else tween(900))
    val waveAmplitude by animateFloatAsState(if (running) 1f else 0.3f, tween(600))
    val tiltX by animateFloatAsState(((gravity.z - 5f) * 2.4f).coerceIn(-15f, 15f), spring(dampingRatio = 0.7f, stiffness = 200f))
    val tiltY by animateFloatAsState((-gravity.x * 2.8f).coerceIn(-15f, 15f), spring(dampingRatio = 0.7f, stiffness = 200f))
    val dialIconRotation by animateFloatAsState(dialStyle.ordinal * 90f, spring(dampingRatio = 0.6f, stiffness = 300f))
    val ambient = rememberInfiniteTransition()
    val wavePhase by ambient.animateFloat(0f, 2 * PI.toFloat(), infiniteRepeatable(tween(2_400, easing = LinearEasing)))
    val secondsHand = remember { Animatable(0f) }
    val dialAppear = remember { Animatable(0f) }
    val dialPop = remember { Animatable(1f) }
    val finishBlink = remember { Animatable(0f) }
    val view = LocalView.current
    view.keepScreenOn = running
    val controlsAlpha by animateFloatAsState(if (running) 0f else 1f, tween(300))

    LaunchedEffect(running) {
        val window = (view.context as android.app.Activity).window
        val systemBars = WindowCompat.getInsetsController(window, view)
        systemBars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (running) systemBars.hide(WindowInsetsCompat.Type.systemBars()) else systemBars.show(WindowInsetsCompat.Type.systemBars())
    }

    fun popDial(from: Float) = scope.launch {
        dialPop.snapTo(from)
        dialPop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 400f))
    }

    LaunchedEffect(dialStyle) {
        dialAppear.snapTo(0f)
        dialAppear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 200f))
    }

    LaunchedEffect(elapsedSeconds, sweepSeconds) {
        when {
            elapsedMillis == 0L -> {
                secondsHand.snapTo(secondsHand.value % 360f)
                secondsHand.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 120f))
            }
            !sweepSeconds -> secondsHand.animateTo(elapsedSeconds * 6f, spring(dampingRatio = 0.35f, stiffness = 1500f))
            else -> secondsHand.snapTo(elapsedSeconds * 6f)
        }
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val endAt = SystemClock.elapsedRealtime() + remainingMillis
        while (remainingMillis > 0) {
            delay(16)
            remainingMillis = (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        }
        RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        running = false
    }

    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        repeat(2) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            finishBlink.animateTo(1f, tween(140))
            delay(220)
            finishBlink.animateTo(0f, tween(260))
            delay(120)
        }
    }

    fun turnAngle(point: Offset, area: IntSize) = atan2(point.x - area.width / 2f, area.height / 2f - point.y)

    fun applyDragMinutes(deltaMinutes: Float) {
        dragMinutes = (dragMinutes + deltaMinutes).coerceIn(1f, MAX_MINUTES.toFloat())
        val minutes = dragMinutes.roundToInt()
        if (minutes.toLong() * 60_000L != totalMillis) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            totalMillis = minutes * 60_000L
            remainingMillis = totalMillis
        }
    }

    fun dragBy(previous: Offset, current: Offset, area: IntSize, pixelsPerMinute: Float) {
        if (dialStyle == DialStyle.Flood) {
            val upIncreases = if (orientation == 0 || orientation == 90) 1f else -1f
            applyDragMinutes(upIncreases * (previous.y - current.y) / (area.height * FLOOD_SCALE_SPAN / MAX_MINUTES))
            return
        }
        when (dialStyle.dragMode) {
            DragMode.Vertical -> applyDragMinutes((previous.y - current.y) / pixelsPerMinute)
            DragMode.Rotate -> {
                val deadZone = minOf(area.width, area.height) * 0.13f
                val areaCenter = Offset(area.width / 2f, area.height / 2f)
                if ((current - areaCenter).getDistance() < deadZone || (previous - areaCenter).getDistance() < deadZone) return
                var deltaRadians = turnAngle(current, area) - turnAngle(previous, area)
                if (deltaRadians > PI) deltaRadians -= 2 * PI.toFloat()
                if (deltaRadians < -PI) deltaRadians += 2 * PI.toFloat()
                applyDragMinutes(deltaRadians / (2 * PI.toFloat()) * dialStyle.minutesPerTurn)
            }
        }
    }

    fun toggle() {
        if (finished) remainingMillis = totalMillis
        running = !running
        haptics.performHapticFeedback(if (running) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        popDial(0.96f)
    }

    fun reset() {
        running = false
        remainingMillis = totalMillis
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        popDial(0.93f)
    }

    val pouringOut = dialStyle == DialStyle.Flood && finished && gravity.y < -4f
    LaunchedEffect(pouringOut) { if (pouringOut) reset() }

    fun tapControl(action: () -> Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        action()
    }

    Box(Modifier.fillMaxSize().background(background)) {
        Canvas(Modifier.fillMaxSize()) {
            val gridShift = Offset(-tiltY, tiltX) * 2.5.dp.toPx()
            val spacing = 28.dp.toPx()
            val startX = ((gridShift.x % spacing) + spacing) % spacing - spacing
            val startY = ((gridShift.y % spacing) + spacing) % spacing - spacing
            var y = startY
            while (y < size.height + spacing) {
                var x = startX
                while (x < size.width + spacing) {
                    drawCircle(lerp(ink, red, finishBlink.value).copy(alpha = 0.07f), 1.2.dp.toPx(), Offset(x, y))
                    x += spacing
                }
                y += spacing
            }
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = dialAppear.value.coerceIn(0f, 1f)
                    if (dialStyle.floatsWithPhone) {
                        val scale = dialPop.value * (0.9f + 0.1f * dialAppear.value)
                        scaleX = scale
                        scaleY = scale
                        rotationX = tiltX
                        rotationY = tiltY
                        cameraDistance = 16 * density
                    }
                }
                .graphicsLayer { rotationZ = if (dialStyle.floatsWithPhone) phoneRoll else 0f }
                .pointerInput(running, dialStyle) {
                    val pixelsPerMinute = 24.dp.toPx()
                    if (!running) detectDragGestures(
                        onDragStart = { dragging = true; dragMinutes = totalMillis / 60_000f },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        dragBy(change.previousPosition, change.position, size, pixelsPerMinute)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { toggle() }, onLongPress = { reset() })
                }
        ) {
            val frame = DialFrame(
                remainingMinutes = remainingMinutes,
                totalMinutes = totalMillis / 60_000f,
                secondsAngle = if (sweepSeconds && running) elapsedMillis * 0.006f else secondsHand.value,
                elapsedFraction = elapsedFraction,
                running = running,
                sweepSeconds = sweepSeconds,
                background = background,
                ink = lerp(ink, red, finishBlink.value),
                red = red,
                floodLevel = floodLevel,
                surfaceSlope = if (orientation % 180 == 0 && abs(gravity.y) > 1f) (gravity.x / gravity.y).coerceIn(-1.2f, 1.2f) else 0f,
                phoneRoll = phoneRoll,
                floodZeroAtBottom = floodZeroAtBottom,
                wavePhase = wavePhase,
                waveAmplitude = waveAmplitude * 6.dp.toPx(),
            )
            if (dialStyle.circular) {
                drawCircle(background, dialRadius() + 8.dp.toPx())
                drawCircle(ink.copy(alpha = 0.18f), dialRadius() + 8.dp.toPx(), style = Stroke(1.dp.toPx()))
            }
            when (dialStyle) {
                DialStyle.Chronograph -> drawChronograph(frame)
                DialStyle.Bezel -> drawBezel(frame)
                DialStyle.Tachometer -> drawTachometer(frame)
                DialStyle.DotMatrix -> drawDotMatrix(frame)
                DialStyle.Drum -> drawDrum(frame, textMeasurer)
                DialStyle.Flood -> drawFlood(frame)
                DialStyle.Flip -> drawFlipClock(frame, textMeasurer)
                DialStyle.Hourglass -> {
                    val (flipX, flipY) = when (orientation) {
                        90 -> 1f to -1f
                        180 -> -1f to -1f
                        270 -> -1f to 1f
                        else -> 1f to 1f
                    }
                    withTransform({ scale(flipX, flipY, center) }) { drawHourglass(frame) }
                }
                DialStyle.Moon -> drawMoon(frame)
            }
            if (dialStyle.circular) {
                val glareCenter = center + Offset(-tiltY, -tiltX) / 30f * dialRadius() * 0.7f
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), glareCenter, dialRadius() * 0.9f),
                    dialRadius() + 8.dp.toPx(),
                )
            }
        }

        if (controlsAlpha > 0.01f) Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).graphicsLayer { alpha = controlsAlpha }) {
            IconControl("Next dial", { tapControl(onNextDial) }) {
                drawCircle(background, 14.dp.toPx())
                drawCircle(ink.copy(alpha = 0.5f), 10.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                drawLine(red, center, center + pointOnCircle(dialIconRotation, 7.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                drawCircle(ink, 2.dp.toPx())
            }
            IconControl(if (sweepSeconds) "Use ticking seconds" else "Use sweeping seconds", { tapControl(onToggleSeconds) }) {
                drawCircle(background, 14.dp.toPx())
                if (sweepSeconds) {
                    drawCircle(ink.copy(alpha = 0.5f), 10.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                } else {
                    repeat(12) { drawCircle(ink.copy(alpha = 0.6f), 1.2.dp.toPx(), center + pointOnCircle(it * 30f, 10.dp.toPx())) }
                }
                drawCircle(red, 2.5.dp.toPx(), center + pointOnCircle(45f, 10.dp.toPx()))
            }
            IconControl("Switch face colour", { tapControl(onToggleFace) }) {
                val iconRadius = 10.dp.toPx()
                val topLeft = center - Offset(iconRadius, iconRadius)
                drawCircle(background, 14.dp.toPx())
                drawArc(Face.Cream.background, 90f, 180f, true, topLeft, Size(iconRadius * 2, iconRadius * 2))
                drawArc(Face.Black.background, 270f, 180f, true, topLeft, Size(iconRadius * 2, iconRadius * 2))
                drawCircle(ink.copy(alpha = 0.5f), iconRadius, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

@Composable
private fun IconControl(description: String, onClick: () -> Unit, drawIcon: DrawScope.() -> Unit) {
    Canvas(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick),
        onDraw = drawIcon,
    )
}

private fun DrawScope.dialRadius() = size.minDimension * 0.44f - 16.dp.toPx()

private fun DrawScope.drawChronograph(frame: DialFrame) {
    val radius = dialRadius()
    val shadow = Color.Black.copy(alpha = 0.12f)
    drawCircle(frame.ink.copy(alpha = 0.15f), radius, style = Stroke(1.dp.toPx()))
    drawMinuteTicks(radius - 6.dp.toPx()) { minute -> if (minute % 5 == 0) frame.ink else frame.ink.copy(alpha = 0.35f) }
    drawArcFromTop(frame.red, radius - 30.dp.toPx(), 0f, frame.remainingMinutes * 6f, 3.dp.toPx())
    drawIndexTriangle(frame.red, radius - 24.dp.toPx())
    drawRect(frame.ink, Offset(center.x + radius + 2.dp.toPx(), center.y - 10.dp.toPx()), Size(6.dp.toPx(), 20.dp.toPx()))

    val subDialCenter = center + Offset(0f, radius * 0.42f)
    val subDialRadius = radius * 0.2f
    drawCircle(frame.ink.copy(alpha = 0.2f), subDialRadius, subDialCenter, style = Stroke(1.dp.toPx()))
    repeat(12) { hour ->
        val direction = pointOnCircle(hour * 30f, 1f)
        drawLine(frame.ink.copy(alpha = 0.5f), subDialCenter + direction * (subDialRadius - 5.dp.toPx()), subDialCenter + direction * subDialRadius, 1.dp.toPx())
    }
    drawLine(frame.ink, subDialCenter, subDialCenter + pointOnCircle(frame.elapsedFraction * 360f, subDialRadius * 0.8f), 2.dp.toPx(), StrokeCap.Round)
    drawCircle(frame.ink, 3.dp.toPx(), subDialCenter)

    drawHand(frame.ink, frame.remainingMinutes * 6f, radius * 0.72f, 18.dp.toPx(), 6.dp.toPx(), shadow)
    drawHand(frame.red, frame.secondsAngle, radius * 0.88f, 28.dp.toPx(), 2.dp.toPx(), shadow)
    drawCircle(frame.red, 5.dp.toPx(), center - pointOnCircle(frame.secondsAngle, 22.dp.toPx()))
    drawCircle(frame.ink, 8.dp.toPx())
    drawCircle(frame.red, 4.dp.toPx())
    drawCircle(frame.background, 1.5.dp.toPx())
}

private fun DrawScope.drawBezel(frame: DialFrame) {
    val radius = dialRadius()
    val bezelWidth = 32.dp.toPx()
    val bezelMiddle = radius - bezelWidth / 2
    drawCircle(frame.ink.copy(alpha = 0.07f), bezelMiddle, style = Stroke(bezelWidth))
    drawArcFromTop(frame.ink, bezelMiddle, 0f, frame.remainingMinutes * 6f, bezelWidth)
    drawMinuteTicks(radius - 4.dp.toPx(), majorLength = 12.dp.toPx(), minorLength = 5.dp.toPx()) { minute ->
        if (minute < frame.remainingMinutes) frame.background else frame.ink.copy(alpha = if (minute % 5 == 0) 0.6f else 0.3f)
    }
    drawIndexTriangle(frame.red, radius - bezelWidth - 6.dp.toPx())
    drawCircle(frame.ink.copy(alpha = 0.12f), radius - bezelWidth - 24.dp.toPx(), style = Stroke(1.dp.toPx()))

    val secondsTrackRadius = radius * 0.5f
    repeat(60) { drawCircle(frame.ink.copy(alpha = 0.15f), 1.2.dp.toPx(), center + pointOnCircle(it * 6f, secondsTrackRadius)) }
    drawCircle(frame.red, 5.dp.toPx(), center + pointOnCircle(frame.secondsAngle, secondsTrackRadius))
    drawHand(frame.ink, frame.elapsedFraction * 360f, radius * 0.3f, 0f, 3.dp.toPx(), Color.Black.copy(alpha = 0.1f), StrokeCap.Round)
    drawCircle(frame.ink, 5.dp.toPx())
}

private fun DrawScope.drawTachometer(frame: DialFrame) {
    val radius = dialRadius()
    val redZoneMinutes = 5
    for (minute in 0..MAX_MINUTES) {
        val direction = pointOnCircle(TACHOMETER_START + TACHOMETER_SPAN * minute / MAX_MINUTES, 1f)
        val isMajor = minute % 5 == 0
        val color = when {
            minute < redZoneMinutes -> frame.red
            isMajor -> frame.ink
            else -> frame.ink.copy(alpha = 0.35f)
        }
        val length = (if (isMajor) 16 else 7).dp.toPx()
        drawLine(color, center + direction * (radius - length), center + direction * radius, (if (isMajor) 2.5f else 1f).dp.toPx())
    }
    val trackRadius = radius - 26.dp.toPx()
    val minutesSweep = TACHOMETER_SPAN * frame.remainingMinutes / MAX_MINUTES
    drawArcFromTop(frame.ink.copy(alpha = 0.1f), trackRadius, TACHOMETER_START, TACHOMETER_SPAN, 1.dp.toPx())
    drawArcFromTop(frame.red, trackRadius, TACHOMETER_START, TACHOMETER_SPAN * redZoneMinutes / MAX_MINUTES, 3.dp.toPx())
    drawArcFromTop(frame.ink, trackRadius, TACHOMETER_START, minutesSweep, 3.dp.toPx())

    val secondsTrackRadius = radius * 0.42f
    repeat(60) { drawCircle(frame.ink.copy(alpha = 0.12f), 1.dp.toPx(), center + pointOnCircle(it * 6f, secondsTrackRadius)) }
    drawCircle(frame.red, 4.dp.toPx(), center + pointOnCircle(frame.secondsAngle, secondsTrackRadius))

    val barWidth = radius * 0.5f
    val barStart = center + Offset(-barWidth / 2, radius * 0.62f)
    drawLine(frame.ink.copy(alpha = 0.12f), barStart, barStart + Offset(barWidth, 0f), 4.dp.toPx(), StrokeCap.Round)
    drawLine(frame.ink, barStart, barStart + Offset(barWidth * frame.elapsedFraction, 0f), 4.dp.toPx(), StrokeCap.Round)

    val needleColor = if (frame.remainingMinutes < redZoneMinutes) frame.red else frame.ink
    drawHand(needleColor, TACHOMETER_START + minutesSweep, radius * 0.82f, 20.dp.toPx(), 4.dp.toPx(), Color.Black.copy(alpha = 0.12f), StrokeCap.Round)
    drawCircle(frame.ink, 10.dp.toPx())
    drawCircle(frame.background, 3.dp.toPx())
}

private fun DrawScope.drawDotMatrix(frame: DialFrame) {
    val radius = dialRadius()
    repeat(MAX_MINUTES) { minute ->
        val position = center + pointOnCircle(minute * 6f, radius - 6.dp.toPx())
        val dotRadius = (if (minute % 5 == 0) 5.5f else 3.5f).dp.toPx()
        val fill = (frame.remainingMinutes - minute).coerceIn(0f, 1f)
        drawCircle(frame.ink.copy(alpha = 0.12f), dotRadius, position)
        if (fill > 0f) drawCircle(frame.ink.copy(alpha = fill), dotRadius, position)
    }

    val secondsTrackRadius = radius - 30.dp.toPx()
    repeat(60) { drawCircle(frame.ink.copy(alpha = 0.12f), 1.5.dp.toPx(), center + pointOnCircle(it * 6f, secondsTrackRadius)) }
    drawCircle(frame.red, 5.dp.toPx(), center + pointOnCircle(frame.secondsAngle, secondsTrackRadius))

    val gridSize = 9
    val gridHalfWidth = radius * 0.42f
    val gridSpacing = gridHalfWidth * 2 / (gridSize - 1)
    val glyphCells = (0 until gridSize * gridSize).filter { cell ->
        val offset = Offset((cell % gridSize) * gridSpacing - gridHalfWidth, (cell / gridSize) * gridSpacing - gridHalfWidth)
        offset.getDistance() <= gridHalfWidth * 1.05f
    }
    val litCells = (frame.elapsedFraction * glyphCells.size).toInt()
    glyphCells.forEachIndexed { index, cell ->
        val position = center + Offset((cell % gridSize) * gridSpacing - gridHalfWidth, (cell / gridSize) * gridSpacing - gridHalfWidth)
        val color = when {
            index < litCells -> frame.ink
            index == litCells && frame.running -> frame.red
            else -> frame.ink.copy(alpha = 0.1f)
        }
        drawCircle(color, 3.dp.toPx(), position)
    }
}

private fun DrawScope.drawDrum(frame: DialFrame, textMeasurer: TextMeasurer) {
    val remainingSeconds = frame.remainingMinutes * 60f
    val minutesValue = rollingValue(remainingSeconds / 60f, 0.3f / 60f)
    val secondsValue = if (frame.sweepSeconds) remainingSeconds % 60f else rollingValue(remainingSeconds % 60f, 0.3f)
    val drumRadius = size.minDimension * 0.42f
    val columnGap = size.minDimension * 0.2f
    val digitStyle = TextStyle(fontSize = 104.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum")
    val windowHalfHeight = 72.dp.toPx()

    drawLine(frame.ink.copy(alpha = 0.15f), Offset(0f, center.y - windowHalfHeight), Offset(size.width, center.y - windowHalfHeight), 1.dp.toPx())
    drawLine(frame.ink.copy(alpha = 0.15f), Offset(0f, center.y + windowHalfHeight), Offset(size.width, center.y + windowHalfHeight), 1.dp.toPx())
    drawDrumColumn(textMeasurer, minutesValue, wraps = false, center.x - columnGap, drumRadius, digitStyle, frame.ink)
    drawDrumColumn(textMeasurer, secondsValue, wraps = true, center.x + columnGap, drumRadius, digitStyle, frame.ink)
    drawCircle(frame.red, 5.dp.toPx(), center - Offset(0f, 18.dp.toPx()))
    drawCircle(frame.red, 5.dp.toPx(), center + Offset(0f, 18.dp.toPx()))

    val pointerHeight = 10.dp.toPx()
    val pointerDepth = 12.dp.toPx()
    drawPath(Path().apply { moveTo(pointerDepth, center.y); lineTo(0f, center.y - pointerHeight); lineTo(0f, center.y + pointerHeight); close() }, frame.red)
    drawPath(Path().apply { moveTo(size.width - pointerDepth, center.y); lineTo(size.width, center.y - pointerHeight); lineTo(size.width, center.y + pointerHeight); close() }, frame.red)

    val barY = minOf(center.y + drumRadius + 32.dp.toPx(), size.height - 24.dp.toPx())
    val barStart = center.x - size.minDimension * 0.35f
    val barEnd = center.x + size.minDimension * 0.35f
    drawLine(frame.ink.copy(alpha = 0.12f), Offset(barStart, barY), Offset(barEnd, barY), 2.dp.toPx())
    drawLine(frame.red, Offset(barStart, barY), Offset(barStart + (barEnd - barStart) * frame.elapsedFraction, barY), 2.dp.toPx())
}

private fun DrawScope.drawDrumColumn(
    textMeasurer: TextMeasurer,
    value: Float,
    wraps: Boolean,
    columnX: Float,
    drumRadius: Float,
    style: TextStyle,
    color: Color,
) {
    val stepRadians = (PI / 7).toFloat()
    val nearest = value.roundToInt()
    for (number in nearest - 4..nearest + 4) {
        if (!wraps && number !in 0..MAX_MINUTES) continue
        val angle = (number - value) * stepRadians
        if (abs(angle) >= PI / 2) continue
        val label = if (wraps) Math.floorMod(number, 60) else number
        val layout = textMeasurer.measure("%02d".format(label), style)
        val y = center.y + drumRadius * sin(angle)
        val squash = cos(angle)
        withTransform({ scale(1f, squash, Offset(columnX, y)) }) {
            drawText(layout, color.copy(alpha = color.alpha * squash * squash), Offset(columnX - layout.size.width / 2f, y - layout.size.height / 2f))
        }
    }
}

private fun rollingValue(value: Float, rollPortion: Float): Float {
    val whole = floor(value)
    val fraction = value - whole
    return whole + ((fraction - (1f - rollPortion)) / rollPortion).coerceIn(0f, 1f)
}

private fun DrawScope.drawFlood(frame: DialFrame) {
    val scaleBottom = size.height * 0.9f
    val scaleTop = scaleBottom - size.height * FLOOD_SCALE_SPAN
    val scaleLeft = 24.dp.toPx()
    val waveLength = 48.dp.toPx()
    val reach = size.width + size.height

    fun drawScale(color: Color) {
        for (minute in 0..MAX_MINUTES) {
            val y = scaleBottom - (scaleBottom - scaleTop) * minute / MAX_MINUTES
            val isMajor = minute % 5 == 0
            drawLine(
                color.copy(alpha = if (isMajor) color.alpha else color.alpha * 0.45f),
                Offset(scaleLeft, y),
                Offset(scaleLeft + (if (isMajor) 20 else 8).dp.toPx(), y),
                (if (isMajor) 2f else 1f).dp.toPx(),
            )
        }
    }

    val rollRadians = Math.toRadians(-frame.phoneRoll.toDouble())
    val sinRoll = sin(rollRadians).toFloat()
    val cosRoll = cos(rollRadians).toFloat()
    fun filledFraction(depth: Float): Float {
        val columns = 48
        var covered = 0f
        for (column in 0 until columns) {
            val x = (column + 0.5f) / columns * size.width - size.width / 2
            val covers = if (abs(cosRoll) < 1e-3f) {
                if (x * sinRoll > -depth) size.height else 0f
            } else {
                val boundary = (-depth - x * sinRoll) / cosRoll
                if (cosRoll > 0) (size.height / 2 - boundary).coerceIn(0f, size.height) else (boundary + size.height / 2).coerceIn(0f, size.height)
            }
            covered += covers
        }
        return covered / (columns * size.height)
    }
    var low = -reach
    var high = reach
    repeat(24) {
        val middle = (low + high) / 2
        if (filledFraction(middle) < frame.floodLevel) low = middle else high = middle
    }
    val surfaceDepth = (low + high) / 2

    fun surfaceY(x: Float) = center.y - surfaceDepth + frame.waveAmplitude * sin(x / waveLength + frame.wavePhase)

    drawScale(frame.ink)
    if (frame.floodLevel > 0.001f) {
        val water = Path().apply {
            moveTo(center.x - reach, center.y + reach)
            var x = -reach
            val step = 6.dp.toPx()
            while (x <= reach) {
                lineTo(center.x + x, surfaceY(x))
                x += step
            }
            lineTo(center.x + reach, center.y + reach)
            close()
        }
        rotate(frame.phoneRoll, center) {
            drawPath(water, frame.ink)
            clipPath(water) { rotate(-frame.phoneRoll, center) { drawScale(frame.background) } }
            val buoyX = (((frame.secondsAngle / 360f) % 1f + 1f) % 1f - 0.5f) * size.minDimension * 0.9f
            drawCircle(frame.red, 6.dp.toPx(), Offset(center.x + buoyX, surfaceY(buoyX)))
        }
    }

    val minuteFraction = frame.remainingMinutes / MAX_MINUTES
    val markerFraction = frame.floodZeroAtBottom * minuteFraction + (1f - frame.floodZeroAtBottom) * (1f - minuteFraction)
    val markerY = scaleBottom - (scaleBottom - scaleTop) * markerFraction
    val markerX = scaleLeft + 28.dp.toPx()
    drawPath(Path().apply { moveTo(markerX, markerY); lineTo(markerX + 12.dp.toPx(), markerY - 8.dp.toPx()); lineTo(markerX + 12.dp.toPx(), markerY + 8.dp.toPx()); close() }, frame.red)
}

private fun DrawScope.drawMinuteTicks(
    outerRadius: Float,
    majorLength: Float = 14.dp.toPx(),
    minorLength: Float = 6.dp.toPx(),
    colorOf: (Int) -> Color,
) {
    repeat(MAX_MINUTES) { minute ->
        val direction = pointOnCircle(minute * 6f, 1f)
        val isMajor = minute % 5 == 0
        val length = if (isMajor) majorLength else minorLength
        drawLine(colorOf(minute), center + direction * (outerRadius - length), center + direction * outerRadius, (if (isMajor) 2f else 1f).dp.toPx())
    }
}

private fun DrawScope.drawIndexTriangle(color: Color, tipRadius: Float) {
    val tip = center - Offset(0f, tipRadius)
    val halfWidth = 7.dp.toPx()
    val height = 10.dp.toPx()
    val triangle = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - halfWidth, tip.y - height)
        lineTo(tip.x + halfWidth, tip.y - height)
        close()
    }
    drawPath(triangle, color)
}

private fun DrawScope.drawHand(
    color: Color,
    degreesFromTop: Float,
    length: Float,
    tail: Float,
    width: Float,
    shadow: Color,
    cap: StrokeCap = StrokeCap.Butt,
) {
    val direction = pointOnCircle(degreesFromTop, 1f)
    val start = center - direction * tail
    val end = center + direction * length
    val shadowOffset = Offset(2.dp.toPx(), 3.dp.toPx())
    drawLine(shadow, start + shadowOffset, end + shadowOffset, width, cap)
    drawLine(color, start, end, width, cap)
}

private fun DrawScope.drawArcFromTop(color: Color, radius: Float, startFromTop: Float, sweep: Float, width: Float) {
    rotate(-90f) {
        drawArc(color, startFromTop, sweep, false, center - Offset(radius, radius), Size(radius * 2, radius * 2), style = Stroke(width))
    }
}

private fun pointOnCircle(degreesFromTop: Float, radius: Float): Offset {
    val radians = Math.toRadians(degreesFromTop - 90.0)
    return Offset(cos(radians).toFloat(), sin(radians).toFloat()) * radius
}

private fun DrawScope.drawFlipClock(frame: DialFrame, textMeasurer: TextMeasurer) {
    val remainingSeconds = frame.remainingMinutes * 60f
    val cardWidth = size.minDimension * 0.38f
    val cardSize = Size(cardWidth, cardWidth * 1.25f)
    val cardOffset = size.minDimension * 0.03f + cardWidth / 2
    val digitStyle = TextStyle(fontSize = 104.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum")
    val flipPortion = if (frame.sweepSeconds) 0.95f else 0.35f
    drawFlipCard(textMeasurer, rollingValue(remainingSeconds / 60f, flipPortion / 60f), wraps = false, center - Offset(cardOffset, 0f), cardSize, digitStyle, frame)
    drawFlipCard(textMeasurer, rollingValue(remainingSeconds % 60f, flipPortion), wraps = true, center + Offset(cardOffset, 0f), cardSize, digitStyle, frame)
    drawCircle(frame.red, 5.dp.toPx(), center - Offset(0f, 20.dp.toPx()))
    drawCircle(frame.red, 5.dp.toPx(), center + Offset(0f, 20.dp.toPx()))

    val barY = center.y + cardSize.height / 2 + 40.dp.toPx()
    val barStart = center.x - cardOffset - cardWidth / 2
    val barEnd = center.x + cardOffset + cardWidth / 2
    drawLine(frame.ink.copy(alpha = 0.12f), Offset(barStart, barY), Offset(barEnd, barY), 2.dp.toPx())
    drawLine(frame.red, Offset(barStart, barY), Offset(barStart + (barEnd - barStart) * frame.elapsedFraction, barY), 2.dp.toPx())
}

private fun DrawScope.drawFlipCard(
    textMeasurer: TextMeasurer,
    rolledValue: Float,
    wraps: Boolean,
    cardCenter: Offset,
    cardSize: Size,
    style: TextStyle,
    frame: DialFrame,
) {
    val base = floor(rolledValue).toInt()
    val rollRemaining = rolledValue - base
    fun label(number: Int) = "%02d".format(if (wraps) Math.floorMod(number, 60) else number.coerceIn(0, MAX_MINUTES))
    val cardTopLeft = cardCenter - Offset(cardSize.width / 2, cardSize.height / 2)
    val hingeY = cardCenter.y
    val cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())

    fun drawHalf(text: String, isTop: Boolean, scaleY: Float, shade: Float) {
        val layout = textMeasurer.measure(text, style)
        withTransform({ scale(1f, scaleY, Offset(cardCenter.x, hingeY)) }) {
            clipRect(cardTopLeft.x, if (isTop) cardTopLeft.y else hingeY, cardTopLeft.x + cardSize.width, if (isTop) hingeY else cardTopLeft.y + cardSize.height) {
                drawRoundRect(frame.ink, cardTopLeft, cardSize, cornerRadius)
                drawText(layout, frame.background, Offset(cardCenter.x - layout.size.width / 2f, cardCenter.y - layout.size.height / 2f))
                if (shade > 0f) drawRect(Color.Black.copy(alpha = shade), cardTopLeft, cardSize)
            }
        }
    }

    if (rollRemaining <= 0f) {
        drawHalf(label(base), isTop = true, scaleY = 1f, shade = 0f)
        drawHalf(label(base), isTop = false, scaleY = 1f, shade = 0f)
    } else {
        val progress = 1f - rollRemaining
        val oldLabel = label(base + 1)
        val newLabel = label(base)
        drawHalf(newLabel, isTop = true, scaleY = 1f, shade = 0f)
        drawHalf(oldLabel, isTop = false, scaleY = 1f, shade = 0f)
        val flapShade = 0.3f * sin(progress * PI.toFloat())
        if (progress < 0.5f) {
            drawHalf(oldLabel, isTop = true, scaleY = cos(progress * PI.toFloat()), shade = flapShade)
        } else {
            drawHalf(newLabel, isTop = false, scaleY = -cos(progress * PI.toFloat()), shade = flapShade)
        }
    }
    drawLine(frame.background, Offset(cardTopLeft.x, hingeY), Offset(cardTopLeft.x + cardSize.width, hingeY), 2.dp.toPx())
}

private fun DrawScope.drawHourglass(frame: DialFrame) {
    val bulbHalfWidth = size.width * 0.3f
    val bulbHeight = size.height * 0.25f
    val neckHalfWidth = 4.dp.toPx()
    val sandLeft = frame.floodLevel.coerceIn(0f, 1f)
    val topBulb = Path().apply {
        moveTo(center.x - bulbHalfWidth, center.y - bulbHeight)
        lineTo(center.x + bulbHalfWidth, center.y - bulbHeight)
        lineTo(center.x + neckHalfWidth, center.y)
        lineTo(center.x - neckHalfWidth, center.y)
        close()
    }
    val bottomBulb = Path().apply {
        moveTo(center.x - neckHalfWidth, center.y)
        lineTo(center.x + neckHalfWidth, center.y)
        lineTo(center.x + bulbHalfWidth, center.y + bulbHeight)
        lineTo(center.x - bulbHalfWidth, center.y + bulbHeight)
        close()
    }

    fun sandBelow(surfaceAtCenter: Float, floorY: Float, moundHeight: Float): Path = Path().apply {
        val left = center.x - bulbHalfWidth
        val right = center.x + bulbHalfWidth
        moveTo(left, surfaceAtCenter + frame.surfaceSlope * (left - center.x))
        lineTo(center.x, surfaceAtCenter - moundHeight)
        lineTo(right, surfaceAtCenter + frame.surfaceSlope * (right - center.x))
        lineTo(right, floorY)
        lineTo(left, floorY)
        close()
    }

    val topSandHeight = bulbHeight * kotlin.math.sqrt(1f - sandLeft)
    if (topSandHeight > 0.5f) clipPath(topBulb) { drawPath(sandBelow(center.y - topSandHeight, center.y, 0f), frame.ink) }
    val bottomSandHeight = bulbHeight * (1f - kotlin.math.sqrt(1f - sandLeft))
    val bottomSurface = center.y + bulbHeight - bottomSandHeight
    if (bottomSandHeight > 0.5f) clipPath(bottomBulb) { drawPath(sandBelow(bottomSurface, center.y + bulbHeight, 10.dp.toPx() * sandLeft.coerceAtMost(0.5f) * 2), frame.ink) }

    if (frame.running && sandLeft < 1f) {
        val streamBottom = bottomSurface - 10.dp.toPx() * sandLeft.coerceAtMost(0.5f) * 2
        if (frame.sweepSeconds) {
            drawLine(frame.red, center, Offset(center.x, streamBottom), 1.5.dp.toPx())
            repeat(6) { grain ->
                val progress = ((frame.wavePhase / (2 * PI.toFloat())) * 4f + grain / 6f) % 1f
                drawCircle(frame.red, 2.5.dp.toPx(), Offset(center.x, center.y + (streamBottom - center.y) * progress))
            }
        } else {
            val sinceTick = 1f - (frame.remainingMinutes * 60f) % 1f
            val fall = (sinceTick / 0.7f).coerceIn(0f, 1f).let { it * it }
            if (sinceTick < 0.75f) {
                repeat(5) { grain ->
                    val trail = grain * 0.035f
                    val progress = (fall - trail).coerceIn(0f, 1f)
                    drawCircle(frame.red.copy(alpha = 1f - grain * 0.15f), (2.8f - grain * 0.3f).dp.toPx(), Offset(center.x, center.y + (streamBottom - center.y) * progress))
                }
            }
        }
    }

    drawPath(topBulb, frame.ink.copy(alpha = 0.5f), style = Stroke(1.5.dp.toPx()))
    drawPath(bottomBulb, frame.ink.copy(alpha = 0.5f), style = Stroke(1.5.dp.toPx()))
    val capWidth = bulbHalfWidth * 2 + 32.dp.toPx()
    val capHeight = 10.dp.toPx()
    drawRect(frame.ink, Offset(center.x - capWidth / 2, center.y - bulbHeight - capHeight), Size(capWidth, capHeight))
    drawRect(frame.ink, Offset(center.x - capWidth / 2, center.y + bulbHeight), Size(capWidth, capHeight))
    listOf(-1f, 1f).forEach { side ->
        val postX = center.x + side * (bulbHalfWidth + 10.dp.toPx())
        drawLine(frame.ink, Offset(postX, center.y - bulbHeight), Offset(postX, center.y + bulbHeight), 3.dp.toPx())
    }

    val markerX = center.x + bulbHalfWidth + 24.dp.toPx()
    val markerY = center.y + bulbHeight - 2 * bulbHeight * frame.remainingMinutes / MAX_MINUTES
    drawPath(Path().apply { moveTo(markerX, markerY); lineTo(markerX + 12.dp.toPx(), markerY - 8.dp.toPx()); lineTo(markerX + 12.dp.toPx(), markerY + 8.dp.toPx()); close() }, frame.red)
}

private fun DrawScope.drawMoon(frame: DialFrame) {
    val radius = dialRadius() * 0.62f
    val litFraction = if (frame.totalMinutes > 0f) (frame.remainingMinutes / frame.totalMinutes).coerceIn(0f, 1f) else 1f
    val terminator = 1f - 2f * litFraction
    val moonBounds = androidx.compose.ui.geometry.Rect(center, radius)

    drawCircle(frame.background, radius)
    listOf(Offset(-0.3f, -0.35f) to 0.18f, Offset(0.35f, -0.1f) to 0.12f, Offset(-0.1f, 0.4f) to 0.15f, Offset(0.25f, 0.45f) to 0.08f, Offset(0.5f, -0.5f) to 0.07f).forEach { (offset, size) ->
        drawCircle(frame.ink.copy(alpha = 0.08f), radius * size, center + offset * radius)
    }
    val shadow = Path().apply {
        arcTo(moonBounds, -90f, -180f, forceMoveTo = true)
        val terminatorBounds = androidx.compose.ui.geometry.Rect(center.x - abs(terminator) * radius, center.y - radius, center.x + abs(terminator) * radius, center.y + radius)
        arcTo(terminatorBounds, 90f, if (terminator > 0f) -180f else 180f, forceMoveTo = false)
        close()
    }
    drawPath(shadow, frame.ink)
    drawCircle(frame.ink, radius, style = Stroke(1.5.dp.toPx()))

    val orbitRadius = radius * 1.35f
    repeat(60) { drawCircle(frame.ink.copy(alpha = 0.15f), 1.2.dp.toPx(), center + pointOnCircle(it * 6f, orbitRadius)) }
    drawCircle(frame.red, 6.dp.toPx(), center + pointOnCircle(frame.secondsAngle, orbitRadius))
    val minuteRingRadius = radius * 1.55f
    repeat(MAX_MINUTES) { minute ->
        val fill = (frame.remainingMinutes - minute).coerceIn(0f, 1f)
        drawLine(
            frame.ink.copy(alpha = 0.1f + 0.9f * fill),
            center + pointOnCircle(minute * 6f, minuteRingRadius),
            center + pointOnCircle(minute * 6f, minuteRingRadius + (if (minute % 5 == 0) 12 else 6).dp.toPx()),
            (if (minute % 5 == 0) 2f else 1f).dp.toPx(),
        )
    }
}

