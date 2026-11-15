package ai.safetypoc.app.ui.component

import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShadows
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.DriveButtonSizes
import ai.safetypoc.app.ui.theme.appShadow
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview

/**
 * drive_start_button: 흰 원 안 파란 재생 삼각형 + 글자. 바탕 primary_blue, 위가 조금 밝은 세로 그라데이션.
 * [가안] 흰 원 배지는 drive_end_button 의 정지 표시와 짝을 이룬다 (11/14 홈 검토)
 */
@Composable
fun DriveStartButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = modifier
            .size(DriveButtonSizes.width, DriveButtonSizes.height)
            .graphicsLayer { val k = if (pressed) DriveButtonSizes.pressedScale else 1f; scaleX = k; scaleY = k }
            .appShadow(AppShadows.float, AppShapes.radiusXl)
            .background(DRIVE_START_BRUSH, AppShapes.radiusXl)
            .pressable(interactionSource, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(DriveButtonSizes.iconTextGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(DriveButtonSizes.stopBadge).background(AppPalette.white, CircleShape)
                .drawBehind {
                    // 재생 삼각형: 원 가운데에서 시각적 무게중심이 맞게 오른쪽으로 조금 민다
                    val h = size.height * PLAY_HEIGHT
                    val w = h * PLAY_WIDTH_RATIO
                    val left = (size.width - w) / 2 + w * PLAY_NUDGE
                    val top = (size.height - h) / 2
                    val path = Path().apply {
                        moveTo(left, top); lineTo(left + w, size.height / 2); lineTo(left, top + h); close()
                    }
                    drawPath(path, AppPalette.primaryBlue, style = Fill)
                    drawPath(path, AppPalette.primaryBlue, style = Stroke(width = h * PLAY_ROUND, join = StrokeJoin.Round))
                },
        )
        Text(text = text, style = AppType.mediumBold, color = AppPalette.white)
    }
}

/** 재생 삼각형: 원 지름에 대한 높이, 높이에 대한 폭, 오른쪽으로 미는 비율, 모서리를 둥글게 하는 선 두께 */
private const val PLAY_HEIGHT = 0.38f
private const val PLAY_WIDTH_RATIO = 0.9f
private const val PLAY_NUDGE = 0.12f
private const val PLAY_ROUND = 0.18f

/** drive_start_button 바탕: primary_blue 에서 위쪽을 흰색 12% 만큼 밝힌다 */
private val DRIVE_START_BRUSH = Brush.verticalGradient(
    listOf(lerp(AppPalette.primaryBlue, AppPalette.white, 0.12f), AppPalette.primaryBlue),
)

/**
 * drive_end_button: 정지 표시 + 글자. 바탕 red.
 * [가안] 시안의 회색 X 는 "닫기"로 읽혀 촌스러워 보여서, 흰 원 안에 둥근 정지 네모(■)로 바꿨다.
 * 크기·모서리·그림자는 drive_start_button 과 같고, 주행 화면 아래 가운데에 놓는다 (11/14)
 */
@Composable
fun DriveEndButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = modifier
            .size(DriveButtonSizes.width, DriveButtonSizes.height)
            .graphicsLayer { val k = if (pressed) DriveButtonSizes.pressedScale else 1f; scaleX = k; scaleY = k }
            .appShadow(AppShadows.float, AppShapes.radiusXl)
            .background(AppPalette.red, AppShapes.radiusXl)
            .pressable(interactionSource, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(DriveButtonSizes.iconTextGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(DriveButtonSizes.stopBadge).background(AppPalette.white, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(DriveButtonSizes.stopMark).background(AppPalette.red, RoundedCornerShape(DriveButtonSizes.stopMarkRadius)))
        }
        Text(text = text, style = AppType.mediumBold, color = AppPalette.white)
    }
}

@Preview
@Composable
internal fun DriveStartButtonDefaultPreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) {
            DriveStartButton(text = "주행 시작", onClick = {})
        }
    }
}

@Preview
@Composable
internal fun DriveEndButtonDefaultPreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) {
            DriveEndButton(text = "주행 종료", onClick = {})
        }
    }
}
