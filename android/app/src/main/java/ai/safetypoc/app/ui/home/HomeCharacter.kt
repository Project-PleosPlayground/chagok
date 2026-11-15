package ai.safetypoc.app.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import ai.safetypoc.app.ui.stage.rememberCharacterMotion
import ai.safetypoc.app.ui.stage.CharacterMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import engine.FeedbackCue
import engine.CueType
import ai.safetypoc.app.ui.theme.appShadow
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppShadows
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.animation.core.AnimationVector1D
import ai.safetypoc.app.R
import ai.safetypoc.app.ui.art.CharacterStill
import ai.safetypoc.app.ui.component.pressable
import ai.safetypoc.app.ui.stage.StageLayout
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppType
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 캐릭터를 눌렀는데 변신할 것이 없을 때 흔드는 각도(도). 발끝을 축으로 좌우로 한 번 [임시] */
private val WOBBLE_DEGREES = listOf(-4f, 4f, -3f, 3f, 0f)
private const val WOBBLE_STEP_MS = 70

/**
 * 홈 캐릭터. 누르면 [onTap]. [onTap] 이 false 를 돌려주면(볼 변신이 없으면, 예: 1단계) 살짝 흔들어 "아직"이라고 알린다.
 * [onTap] 이 null 이면 누를 수 없다(주행 화면).
 */
@Composable
fun HomeCharacter(
    assetKey: String, stage: Int, onTap: (() -> Boolean)?, driving: Boolean = false,
    reaction: FeedbackCue? = null, motion: CharacterMotion? = null,
    /** 주행 중: 눈(헤드라이트·바이저) 빛이 은은하게 켜져 있다가 가끔 깜빡인다 */
    eyeGlow: Boolean = false,
) {
    val m = motion ?: rememberCharacterMotion()
    val tilt = m.tilt
    // 주행 중 사건마다 1초 안쪽의 작은 몸짓 (11/14). 같은 cue 는 한 번만. 머리 소품도 [motion] 으로 같이 움직인다
    LaunchedEffect(reaction) { reaction?.let { react(it.type, m) } }
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val tap = onTap?.let { tapped ->
        Modifier.pressable(interaction) {
            if (!tapped()) scope.launch { for (d in WOBBLE_DEGREES) tilt.animateTo(d, tween(WOBBLE_STEP_MS)) }
        }
    } ?: Modifier
    Box(
        Modifier.fillMaxSize().then(tap).graphicsLayer {
            rotationZ = tilt.value
            translationX = m.x.value * size.width
            translationY = m.y.value * size.height
            transformOrigin = TransformOrigin(0.5f, 1f)
        },
    ) {
        CharacterStill(assetKey, stage, modifier = Modifier.fillMaxSize(), driving = driving) {}
        if (eyeGlow) EyeGlow(stage)
    }
}

/**
 * 눈 빛: 단계별 눈 자리(그림 크기 비율)에 주황빛 타원을 겹친다. 켜져 있다가 [BLINK_PERIOD_MS] 마다 짧게 두 번 깜빡인다.
 * 화면을 끌지 않을 만큼 작고 은은하게 (11/14)
 */
@Composable
private fun EyeGlow(stage: Int) {
    val eyes = EYES[stage] ?: return
    val a by rememberInfiniteTransition(label = "eye").animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(keyframes {
            durationMillis = BLINK_PERIOD_MS
            1f at 0; 1f at BLINK_PERIOD_MS - 520; 0.1f at BLINK_PERIOD_MS - 440; 1f at BLINK_PERIOD_MS - 330
            0.1f at BLINK_PERIOD_MS - 250; 1f at BLINK_PERIOD_MS - 140
        }),
        label = "blink",
    )
    Canvas(Modifier.fillMaxSize()) {
        for (e in eyes) {
            val c = Offset(size.width * e.cx, size.height * e.cy)
            val r = Size(size.width * e.w, size.height * e.h)
            drawOval(
                Brush.radialGradient(listOf(EYE_CORE.copy(alpha = 0.85f * a), EYE_GLOW.copy(alpha = 0.45f * a), Color.Transparent), c, r.width / 2),
                topLeft = Offset(c.x - r.width / 2, c.y - r.height * 1.6f), size = Size(r.width, r.height * 3.2f),
                blendMode = BlendMode.Screen,
            )
        }
    }
}

/** 눈 자리: 가운데(cx, cy)와 크기(w, h), 그림(560×560) 크기 비율. 1·2단계는 헤드라이트, 3~5단계는 바이저 */
private class Eye(val cx: Float, val cy: Float, val w: Float, val h: Float)
private val EYES = mapOf(
    1 to listOf(Eye(0.13f, 0.725f, 0.14f, 0.025f), Eye(0.84f, 0.725f, 0.12f, 0.025f)),
    2 to listOf(Eye(0.264f, 0.654f, 0.08f, 0.025f), Eye(0.46f, 0.656f, 0.07f, 0.025f)),
    3 to listOf(Eye(0.496f, 0.466f, 0.13f, 0.03f)),
    4 to listOf(Eye(0.49f, 0.407f, 0.13f, 0.028f)),
    5 to listOf(Eye(0.48f, 0.41f, 0.10f, 0.03f)),
)
private val EYE_CORE = Color(0xFFFFF1D6)
private val EYE_GLOW = Color(0xFFFFA43A)
private const val BLINK_PERIOD_MS = 3_600

/**
 * 변신을 처음 볼 수 있게 됐을 때 캐릭터 머리 위에 띄우는 안내. 작고 연한 회색 글자가 위아래로 천천히 떠다닌다.
 * 한 번 눌러 변신을 보면 사라진다. 주행 화면에는 띄우지 않는다
 */
@Composable
fun BoxScope.TransformHint(stage: Int) {
    val bob by rememberInfiniteTransition(label = "hint")
        .animateFloat(-1f, 1f, infiniteRepeatable(tween(HINT_FLOAT_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    BoxWithConstraints(Modifier.matchParentSize()) {
        Text(
            stringResource(R.string.transform_hint),
            Modifier.align(Alignment.TopCenter).offset(y = maxHeight * hintFrameY(stage))
                .graphicsLayer { translationY = bob * HINT_FLOAT.toPx() },
            style = AppType.smallRegular, color = AppPalette.textTertiary,
        )
    }
}

/** 안내 글자 세로 자리: 캐릭터 프레임 높이에 대한 비율. 머리에 쓰는 소품 자리(단계별 머리)보다 [HINT_ABOVE_HEAD] 위 */
private fun hintFrameY(stage: Int): Float =
    (StageLayout.headWearPoint(stage).y - HINT_ABOVE_HEAD - StageLayout.CHARACTER_TOP) / StageLayout.CHARACTER_SIZE
/** 모자 그림 중심에서 안내 글자 위 끝까지(스테이지 px) [임시] */
private const val HINT_ABOVE_HEAD = 230f
/** 위아래로 떠다니는 폭(한쪽)과 한 번 오르거나 내리는 시간 */
private val HINT_FLOAT = 6.dp
private const val HINT_FLOAT_MS = 1600

/**
 * 사건별 몸짓. 각도(도)와 이동(캐릭터 크기 비율). 모두 1초 안에 제자리로 돌아온다.
 * 급제동: 앞으로 쏠렸다가 휘청. 급가속: 뒤로 젖혀졌다 돌아옴. 깜빡이 없음: 움찔 좌우로. 깜빡이 칭찬: 폴짝.
 */
private suspend fun react(type: CueType, m: CharacterMotion) = coroutineScope {
    val tilt = m.tilt; val x = m.x; val y = m.y
    suspend fun steps(a: Animatable<Float, AnimationVector1D>, values: List<Float>, ms: Int) { for (v in values) a.animateTo(v, tween(ms)) }
    when (type) {
        CueType.HARSH_BRAKE -> {
            launch { steps(y, listOf(0.04f, 0f), 140) }
            steps(tilt, listOf(9f, -6f, 4f, -2f, 0f), 110)
        }
        CueType.HARSH_ACCEL -> {
            launch { steps(y, listOf(-0.02f, 0f), 200) }
            steps(tilt, listOf(-8f, -8f, 0f), 160)
        }
        CueType.LANE_NO_SIGNAL -> steps(x, listOf(-0.03f, 0.03f, -0.02f, 0.02f, 0f), 60)
        CueType.LANE_SIGNALED_FIRST -> steps(y, listOf(-0.06f, 0f, -0.03f, 0f), 120)
        // 배고파서 축 처졌다가 비틀 (11/15)
        CueType.LOW_BATTERY -> {
            steps(y, listOf(0.03f, 0.03f), 260)
            steps(tilt, listOf(-4f, 4f, -3f, 0f), 180)
            steps(y, listOf(0f), 400)
        }
        else -> Unit
    }
}

/**
 * 주행 중 사건 때 캐릭터 머리 위 말풍선(효과음과 같은 한 마디)을 [REACTION_SHOW_MS] 동안 보인다. 움직이지 않는다.
 * 깜빡이를 잘 켰을 때는 👍 가 몇 개 위로 뿅뿅 떠오르며 사라진다.
 */
@Composable
fun BoxScope.ReactionOverlay(reaction: FeedbackCue?, stage: Int) {
    val word = reaction?.type?.let { REACTION_WORDS[it] } ?: return
    var visible by remember(reaction) { mutableStateOf(true) }
    LaunchedEffect(reaction) { delay(REACTION_SHOW_MS); visible = false }
    if (!visible) return
    BoxWithConstraints(Modifier.matchParentSize()) {
        // 사건마다 테두리 색은 같고, 모양은 어울리는 것 중에서 매번 바뀐다 (11/14)
        val style = bubbleStyle(reaction.type, reaction.atMs / BUBBLE_SEED_MS)
        val edge = bubbleColor(reaction.type)
        Text(
            word,
            // 머리 오른쪽 위에 둔다. 꼬리(왼쪽 아래)가 머리를 가리킨다. 모자·얼굴을 가리지 않는다
            Modifier.align(Alignment.TopCenter).offset(x = maxWidth * BUBBLE_SIDE, y = maxHeight * hintFrameY(stage) - BUBBLE_LIFT)
                .appShadow(AppShadows.soft, style.shape)
                .background(AppPalette.white, style.shape)
                .border(BUBBLE_EDGE, edge, style.shape)
                .padding(start = style.padH, end = style.padH, top = style.padV, bottom = style.padV + style.tailPad),
            style = AppType.largeBold.copy(fontSize = BUBBLE_TEXT), color = AppPalette.ink,
        )
        if (reaction.type == CueType.LANE_SIGNALED_FIRST) {
            for ((i, dx) in THUMB_X.withIndex()) Thumb(Modifier.align(Alignment.Center).offset(x = maxWidth * dx), delayMs = i * THUMB_GAP_MS)
        }
    }
}

/** 👍 하나: 잠깐 기다렸다가 아래에서 위로 떠오르며 커졌다가 사라진다 */
@Composable
private fun Thumb(modifier: Modifier, delayMs: Long) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs); t.animateTo(1f, tween(THUMB_MS)) }
    Text(
        "👍",
        modifier.graphicsLayer {
            val v = t.value
            translationY = -v * size.height * 3f
            val s = if (v < 0.25f) v / 0.25f else 1f
            scaleX = s; scaleY = s
            alpha = if (v == 0f) 0f else 1f - (v - 0.6f).coerceAtLeast(0f) / 0.4f
        },
        style = AppType.largeBold,
    )
}

private val REACTION_WORDS = mapOf(
    CueType.HARSH_BRAKE to "오!",
    CueType.HARSH_ACCEL to "워어~",
    CueType.LANE_NO_SIGNAL to "이익!",
    CueType.LANE_SIGNALED_FIRST to "헤헤",
    CueType.LOW_BATTERY to "꼬르륵…",
)
private const val REACTION_SHOW_MS = 3_000L
/** 말풍선 테두리 두께, 모양 고르기용 시간 단위, 꼬리만큼 위로 올리는 거리 */
private val BUBBLE_EDGE = 4.dp
private const val BUBBLE_SEED_MS = 1_000L
private val BUBBLE_LIFT = 96.dp
/** 말풍선 글자 크기. 주행 중 한눈에 보이게 크게 (11/15) */
private val BUBBLE_TEXT = 60.sp
/** 말풍선을 머리에서 오른쪽으로 비키는 정도(캐릭터 폭 비율) */
private const val BUBBLE_SIDE = 0.26f
/** 👍 가로 자리(캐릭터 폭 비율), 사이 시간, 떠오르는 시간 */
private val THUMB_X = listOf(-0.28f, 0.3f, -0.1f, 0.18f)
private const val THUMB_GAP_MS = 180L
private const val THUMB_MS = 1_100
