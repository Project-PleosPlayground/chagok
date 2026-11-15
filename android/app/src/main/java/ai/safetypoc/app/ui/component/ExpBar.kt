package ai.safetypoc.app.ui.component

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.ExpBarSizes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * exp_bar: 캡슐 하나에 기름통 배지, 단계, 연료계 막대, 경험치 숫자.
 * - 바탕 surface 캡슐(tab_button 과 같은 면), 높이는 coin_bar 와 같다
 * - 기름통은 흰 원 안에. 경험치를 "연료"로 채워 변신한다는 비유
 * - 막대: 흰 바탕에 [progress](0~1, 밖의 값은 자른다)만큼 primary_blue. ¼·½·¾ 눈금을 그어 연료계처럼 읽힌다
 * - 숫자: 이번 단계에서 모은 양 / 다음 단계까지 필요한 양. 최고 단계면([xpSpan] 0) "MAX" 와 꽉 찬 막대
 */
@Composable
fun ExpBar(stage: Int, xpInStage: Int, xpSpan: Int, progress: Float, modifier: Modifier = Modifier) {
    val max = xpSpan <= 0
    val filled = if (max) 1f else if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
    Row(
        modifier.height(ExpBarSizes.height).clip(AppShapes.pill).background(AppPalette.surface)
            .padding(start = ExpBarSizes.startPadding, end = ExpBarSizes.endPadding),
        horizontalArrangement = Arrangement.spacedBy(ExpBarSizes.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(ExpBarSizes.badge).background(AppPalette.white, CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.exp_icon), contentDescription = null, Modifier.size(ExpBarSizes.iconWidth, ExpBarSizes.iconHeight))
        }
        Text(stringResource(R.string.exp_level, stage), style = AppType.smallBold, color = AppPalette.ink, maxLines = 1)
        FuelGauge(filled)
        Text(
            if (max) stringResource(R.string.exp_max) else stringResource(R.string.exp_amount, xpInStage, xpSpan),
            style = AppType.smallRegular, color = AppPalette.textSecondary, maxLines = 1,
        )
    }
}

/** 연료계 막대: 흰 바탕, 파란 채움, 칸 경계 눈금(채운 곳과 빈 곳 모두에 같은 색) */
@Composable
private fun FuelGauge(filled: Float) {
    Box(
        Modifier.size(ExpBarSizes.barWidth, ExpBarSizes.barHeight).clip(AppShapes.pill).background(AppPalette.white)
            .drawWithContent {
                drawContent()
                val w = ExpBarSizes.tick.toPx()
                for (i in 1 until ExpBarSizes.tickSections) {
                    val x = size.width * i / ExpBarSizes.tickSections
                    drawLine(AppPalette.surface, Offset(x, 0f), Offset(x, size.height), strokeWidth = w)
                }
            },
    ) {
        if (filled > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(filled).clip(AppShapes.pill).background(AppPalette.primaryBlue))
    }
}

@Composable
private fun ExpBarPreviewFrame(content: @Composable () -> Unit) = AppTheme {
    Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) { content() }
}

@Preview
@Composable
internal fun ExpBarEmptyPreview() = ExpBarPreviewFrame { ExpBar(stage = 1, xpInStage = 0, xpSpan = 100, progress = 0f) }

@Preview
@Composable
internal fun ExpBarHalfPreview() = ExpBarPreviewFrame { ExpBar(stage = 3, xpInStage = 180, xpSpan = 300, progress = 0.6f) }

@Preview
@Composable
internal fun ExpBarFullPreview() = ExpBarPreviewFrame { ExpBar(stage = 5, xpInStage = 0, xpSpan = 0, progress = 1f) }
