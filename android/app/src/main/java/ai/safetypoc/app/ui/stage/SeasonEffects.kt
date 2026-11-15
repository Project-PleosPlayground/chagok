package ai.safetypoc.app.ui.stage

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.sin

/**
 * 크리스마스 트리 전구 반짝임 (11/15). 트리 그림 위 전구 자리에 따뜻한 불빛을 겹쳐 하나씩 번갈아 밝아졌다 어두워진다.
 * [modifier] 는 트리 그림과 같은 자리·크기다. [still] 이면(주행 중) 움직이지 않고 은은하게 켜 둔다
 */
@Composable
fun TreeTwinkle(modifier: Modifier, still: Boolean) {
    val t by if (still) remember0() else rememberInfiniteTransition(label = "twinkle")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(TWINKLE_MS, easing = LinearEasing)), label = "t")
    Canvas(modifier) {
        // 그림(546×720)이 칸 안에 비율대로 들어간 자리
        val k = minOf(size.width / TREE_W, size.height / TREE_H)
        val ox = (size.width - TREE_W * k) / 2; val oy = (size.height - TREE_H * k) / 2
        BULBS.forEachIndexed { i, (bx, by) ->
            val phase = (t + i * 0.37f) % 1f
            val a = if (still) 0.5f else (0.25f + 0.75f * ((sin(phase * 2 * PI) + 1) / 2).toFloat())
            val c = Offset(ox + bx * k, oy + by * k)
            val r = 20f * k
            drawCircle(Brush.radialGradient(listOf(GLOW.copy(alpha = a), Color.Transparent), c, r), r, c)
            drawCircle(CORE.copy(alpha = a), r * 0.28f, c)
        }
    }
}

/**
 * 눈 내리기 (11/15). 눈송이 [FLAKES] 개가 위에서 아래로 천천히 떨어지며 좌우로 살짝 흔들린다. 주행 중([still])에는 그리지 않는다
 */
@Composable
fun Snowfall(modifier: Modifier, still: Boolean) {
    if (still) return
    val t by rememberInfiniteTransition(label = "snow")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(SNOW_MS, easing = LinearEasing)), label = "t")
    Canvas(modifier) {
        for (i in 0 until FLAKES) {
            val seed = i * 0.6180339f
            val speed = 0.6f + (seed * 7 % 1f) * 0.8f
            val y = ((t * speed + seed) % 1f) * (size.height + 40f) - 20f
            val x = ((seed * 13 % 1f) * size.width) + sin((t * 4 + seed) * 2 * PI).toFloat() * 18f
            val r = 1.6f + (seed * 5 % 1f) * 2.4f
            drawCircle(SNOW.copy(alpha = 0.55f + (seed * 3 % 1f) * 0.4f), r, Offset(x, y))
        }
    }
}

@Composable
private fun remember0() = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

private const val TREE_W = 546f
private const val TREE_H = 720f
/** 트리 그림(546×720) 안 전구 자리 */
private val BULBS = listOf(
    248f to 160f, 318f to 234f, 180f to 292f, 212f to 312f, 316f to 346f, 226f to 393f, 410f to 401f,
    160f to 438f, 354f to 469f, 225f to 519f, 386f to 546f, 92f to 551f,
)
private val GLOW = Color(0xFFFFD27A)
private val CORE = Color(0xFFFFFBE8)
private val SNOW = Color(0xFFB9C7DA)
private const val TWINKLE_MS = 2_400
private const val SNOW_MS = 9_000
private const val FLAKES = 70
