package ai.safetypoc.app.ui.component

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.CoinBarSizes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import java.util.Locale

/** 포인트 숫자 표기: 천 단위 쉼표 (디자인 시스템 8절). 기기 언어와 무관하게 같은 모양 */
internal fun formatPoints(points: Int): String = String.format(Locale.ROOT, "%,d", points)

/**
 * coin_bar: 보유 포인트. 숫자 칸 + coin_icon 을 감싸는 캡슐.
 * - 숫자 칸은 왼쪽 여백부터 coin_icon 앞 간격까지. 최소 폭은 [coinBarTextMinWidth], 숫자는 칸 안에 왼쪽 정렬, 한 줄.
 * - 숫자가 최소 폭보다 길면 숫자 칸이 숫자 폭만큼 커지고 캡슐도 같이 커진다. 잘리지 않는다.
 * - 놓는 쪽이 오른쪽 끝을 고정(TopEnd 등)하므로 커지는 방향은 왼쪽이다. coin_icon 과 오른쪽 여백은 움직이지 않는다.
 */
@Composable
fun CoinBar(points: Int, modifier: Modifier = Modifier) {
    val textMinWidth = coinBarTextMinWidth()
    Row(
        modifier = modifier
            .height(CoinBarSizes.height)
            .clip(AppShapes.pill)
            .background(AppPalette.primaryBlue)
            .padding(start = CoinBarSizes.startPadding, end = CoinBarSizes.endPadding),
        horizontalArrangement = Arrangement.spacedBy(CoinBarSizes.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatPoints(points),
            modifier = Modifier.widthIn(min = textMinWidth),
            style = AppType.smallBold,
            color = AppPalette.white,
            textAlign = TextAlign.Start,
            maxLines = 1,
            softWrap = false,
        )
        Image(
            painter = painterResource(R.drawable.coin_icon),
            contentDescription = null,
            modifier = Modifier.size(CoinBarSizes.iconSize),
        )
    }
}

/**
 * 숫자 칸의 최소 폭: 기본 캡슐 폭에서 여백·간격·coin_icon 을 뺀 폭.
 * 기본 캡슐 폭 = 숫자가 0 일 때의 내용 폭 × [CoinBarSizes.widthScale]. 숫자 폭은 글꼴에 따라 달라서 화면에서 잰다.
 */
@Composable
private fun coinBarTextMinWidth(): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) {
        val zero = with(density) { measurer.measure(formatPoints(0), AppType.smallBold).size.width.toDp() }
        val fixed = CoinBarSizes.startPadding + CoinBarSizes.gap + CoinBarSizes.iconSize + CoinBarSizes.endPadding
        (fixed + zero) * CoinBarSizes.widthScale - fixed
    }
}

/** 미리보기 틀: 화면처럼 오른쪽 끝을 고정한다 */
@Composable
private fun CoinBarPreviewFrame(content: @Composable () -> Unit) {
    AppTheme {
        Box(
            Modifier.background(AppPalette.white).padding(AppDimens.screenMargin),
            contentAlignment = Alignment.CenterEnd,
        ) { content() }
    }
}

@Preview
@Composable
internal fun CoinBarSmallPreview() = CoinBarPreviewFrame { CoinBar(points = 120) }

@Preview
@Composable
internal fun CoinBarLargePreview() = CoinBarPreviewFrame { CoinBar(points = 33456) }
