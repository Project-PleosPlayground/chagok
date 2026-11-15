package ai.safetypoc.app.ui.home

import ai.safetypoc.app.ui.theme.AppPalette
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import engine.CueType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 말풍선 모양 하나: 테두리 안쪽 글자 여백(가로, 세로)도 모양마다 다르다.
 * [tailPad] 는 꼬리 자리. 꼬리 달린 모양은 몸통이 위쪽에만 있어서, 아래 여백을 이만큼 더 줘야 글자가 몸통 한가운데 온다
 */
class BubbleStyle(val shape: Shape, val padH: Dp, val padV: Dp, val tailPad: Dp = 0.dp)

/** 꼬리 높이: 말풍선 높이에 대한 비율. 말풍선이 머리 오른쪽 위에 있어 꼬리는 왼쪽 아래(머리 쪽)를 가리킨다 */
private const val TAIL = 0.22f

/** 뾰족뾰족 터지는 모양(깜짝). 꼭짓점 [spikes] 개 */
private fun burst(spikes: Int, inner: Float) = GenericShape { size, _ ->
    val cx = size.width / 2; val cy = size.height / 2
    val rx = size.width / 2; val ry = size.height / 2
    for (i in 0 until spikes * 2) {
        val a = PI * i / spikes - PI / 2
        val k = if (i % 2 == 0) 1f else inner
        val x = cx + (rx * k * cos(a)).toFloat(); val y = cy + (ry * k * sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** 둥근 네모 + 아래 꼬리 */
private val roundTail = GenericShape { size, _ ->
    val body = size.height * (1 - TAIL)
    val box = Path().apply { addRoundRect(RoundRect(Rect(0f, 0f, size.width, body), CornerRadius(body / 2))) }
    val tail = Path().apply {
        moveTo(size.width * 0.20f, body - 2); lineTo(size.width * 0.08f, size.height); lineTo(size.width * 0.36f, body - 2); close()
    }
    addPath(Path().apply { op(box, tail, PathOperation.Union) })
}

/** 타원 + 아래 꼬리 */
private val ovalTail = GenericShape { size, _ ->
    val body = size.height * (1 - TAIL)
    val oval = Path().apply { addOval(Rect(0f, 0f, size.width, body)) }
    val tail = Path().apply {
        moveTo(size.width * 0.20f, body * 0.8f); lineTo(size.width * 0.10f, size.height); lineTo(size.width * 0.36f, body * 0.9f); close()
    }
    addPath(Path().apply { op(oval, tail, PathOperation.Union) })
}

/** 몽글몽글 구름 + 아래 작은 방울 두 개 */
private val cloud = GenericShape { size, _ ->
    val body = size.height * (1 - TAIL)
    var acc = Path().apply { addRoundRect(RoundRect(Rect(size.width * 0.12f, body * 0.22f, size.width * 0.88f, body * 0.86f), CornerRadius(body * 0.3f))) }
    val bumps = listOf(0.18f to 0.55f, 0.36f to 0.3f, 0.6f to 0.26f, 0.82f to 0.52f, 0.5f to 0.78f, 0.28f to 0.76f, 0.72f to 0.76f)
    for ((fx, fy) in bumps) {
        val r = body * 0.27f
        val c = Path().apply { addOval(Rect(Offset(size.width * fx, body * fy), r)) }
        acc = Path().apply { op(acc, c, PathOperation.Union) }
    }
    addPath(acc)
    addOval(Rect(Offset(size.width * 0.22f, body + size.height * TAIL * 0.35f), size.height * 0.05f))
    addOval(Rect(Offset(size.width * 0.13f, body + size.height * TAIL * 0.8f), size.height * 0.03f))
}

private val SPIKY = BubbleStyle(burst(12, 0.78f), 76.dp, 58.dp)
private val SPIKY_SOFT = BubbleStyle(burst(16, 0.86f), 68.dp, 52.dp)
private val ROUND = BubbleStyle(roundTail, 46.dp, 24.dp, tailPad = 36.dp)
private val OVAL = BubbleStyle(ovalTail, 56.dp, 26.dp, tailPad = 38.dp)
private val CLOUD = BubbleStyle(cloud, 60.dp, 38.dp, tailPad = 44.dp)

/** 사건마다 고를 수 있는 모양들. 놀람은 뾰족한 쪽, 칭찬은 몽글한 쪽 */
private val STYLES = mapOf(
    CueType.HARSH_BRAKE to listOf(SPIKY, SPIKY_SOFT, ROUND),
    CueType.HARSH_ACCEL to listOf(SPIKY_SOFT, SPIKY, OVAL),
    CueType.LANE_NO_SIGNAL to listOf(ROUND, OVAL, SPIKY_SOFT),
    CueType.LANE_SIGNALED_FIRST to listOf(CLOUD, OVAL, ROUND),
    CueType.LOW_BATTERY to listOf(CLOUD, ROUND),
)

/** 사건 색: 테두리. 무슨 일인지는 색으로 늘 같게 */
fun bubbleColor(type: CueType): Color = when (type) {
    CueType.HARSH_BRAKE -> AppPalette.red
    CueType.HARSH_ACCEL, CueType.LANE_NO_SIGNAL -> AppPalette.orange
    CueType.LOW_BATTERY -> AppPalette.textSecondary
    else -> AppPalette.primaryBlue
}

/** [seed] 로 모양을 고른다. 같은 cue 는 같은 모양, 다음 cue 는 다른 모양일 수 있다 */
fun bubbleStyle(type: CueType, seed: Long): BubbleStyle {
    val list = STYLES[type] ?: listOf(ROUND)
    return list[Math.floorMod(seed, list.size.toLong()).toInt()]
}
