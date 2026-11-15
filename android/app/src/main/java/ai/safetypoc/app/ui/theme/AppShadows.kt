package ai.safetypoc.app.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 그림자 한 겹. 피그마 표기: 아래로 [offsetY], 흐림 [blur], 불투명도 [alpha](검정).
 * [alpha]는 0보다 크고 1보다 작다. 1이면 Paint.setShadowLayer가 투명한 Paint의 알파를 써서 그림자가 보이지 않는다
 */
data class ShadowLayer(val offsetY: Dp, val blur: Dp, val alpha: Float) {
    init {
        require(alpha > 0f && alpha < 1f) { "그림자 불투명도는 0과 1 사이여야 한다: $alpha" }
    }
}

/** 디자인 시스템 5절 그림자. 겹은 아래에서 위 순서로 그린다 */
data class AppShadow(val layers: List<ShadowLayer>)

/** 디자인 시스템 5절 그림자 */
object AppShadows {
    /** shadow_card: mission_box, mission_card, exp_bar */
    val card = AppShadow(listOf(ShadowLayer(4.dp, 4.dp, 0.25f), ShadowLayer(3.dp, 12.dp, 0.045f)))
    /** shadow_float: drive_start_button, drive_end_button */
    val float = AppShadow(listOf(ShadowLayer(8.dp, 16.dp, 0.25f)))
    /** shadow_soft: score_card, car_name_card, confirm_popup */
    val soft = AppShadow(listOf(ShadowLayer(4.dp, 16.dp, 0.08f)))
}

/** 피그마(CSS) 흐림 값은 가우스 시그마의 2배다 */
private const val BLUR_PER_SIGMA = 2f
/** Android 그림자 반지름 → 시그마 변환(Skia ConvertRadiusToSigma)의 계수와 상수 */
private const val SKIA_SIGMA_SCALE = 0.57735f
private const val SKIA_SIGMA_BIAS = 0.5f

/**
 * [shape] 모양의 그림자를 요소 뒤에 그린다. 요소 바탕은 이 Modifier 뒤에 그린다.
 * 그림자는 레이아웃 박스 밖으로 번지고 크기 계산에 들어가지 않는다(디자인 시스템 3절 "박스").
 */
fun Modifier.appShadow(shadow: AppShadow, shape: Shape): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawIntoCanvas { canvas ->
        for (layer in shadow.layers) {
            val sigma = layer.blur.toPx() / BLUR_PER_SIGMA
            val radius = ((sigma - SKIA_SIGMA_BIAS) / SKIA_SIGMA_SCALE).coerceAtLeast(Float.MIN_VALUE)
            val paint = Paint().apply {
                color = Color.Transparent
                asFrameworkPaint().setShadowLayer(
                    radius, 0f, layer.offsetY.toPx(), Color.Black.copy(alpha = layer.alpha).toArgb(),
                )
            }
            canvas.drawOutline(outline, paint)
        }
    }
}
