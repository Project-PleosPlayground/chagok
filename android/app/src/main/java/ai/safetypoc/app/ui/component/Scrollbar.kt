package ai.safetypoc.app.ui.component

import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.ScrollbarSizes
import ai.safetypoc.app.ui.theme.TabButtonSizes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview

/**
 * scrollbar: 스크롤하는 목록 오른쪽의 세로 막대. 목록과 같은 높이로 놓는다.
 * 막대 길이는 보이는 비율, 자리는 [scrollState]의 스크롤 위치를 따른다. 스크롤할 내용이 없으면 그리지 않는다.
 */
@Composable
fun Scrollbar(scrollState: ScrollState, modifier: Modifier = Modifier) {
    ScrollbarTrack(modifier) {
        val max = scrollState.maxValue
        val viewport = scrollState.viewportSize
        if (max <= 0 || viewport <= 0) null
        else ThumbSpan(length = viewport.toFloat() / (viewport + max), position = scrollState.value.toFloat() / max)
    }
}

/**
 * scrollbar의 LazyColumn용. 줄 높이가 다르면 보이는 줄의 평균 높이로 전체 길이를 어림한다. 줄 사이 간격과 contentPadding 을 더한다.
 * 스크롤할 내용이 없으면 그리지 않는다.
 */
@Composable
fun Scrollbar(listState: LazyListState, modifier: Modifier = Modifier) {
    ScrollbarTrack(modifier) {
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        val viewport = info.viewportSize.height.toFloat()
        if (visible.isEmpty() || viewport <= 0f) return@ScrollbarTrack null
        // 줄 하나가 차지하는 길이 = 평균 줄 높이 + 줄 사이 간격(spacedBy). 목록 앞뒤 contentPadding 도 전체 길이에 넣는다
        val spacing = info.mainAxisItemSpacing.toFloat()
        val itemStride = visible.sumOf { it.size }.toFloat() / visible.size + spacing
        val content = info.beforeContentPadding + itemStride * info.totalItemsCount - spacing + info.afterContentPadding
        if (content <= viewport) return@ScrollbarTrack null
        val scrolled = listState.firstVisibleItemIndex * itemStride + listState.firstVisibleItemScrollOffset
        ThumbSpan(length = viewport / content, position = (scrolled / (content - viewport)).coerceIn(0f, 1f))
    }
}

/** 막대가 트랙에서 차지하는 비율 [length](0~1)와 자리 [position](0 = 맨 위, 1 = 맨 아래) */
private class ThumbSpan(val length: Float, val position: Float)

/** 세로 트랙에 막대를 그린다. [span]은 그릴 때마다 읽으므로 스크롤하면 다시 그린다. null이면 그리지 않는다 */
@Composable
private fun ScrollbarTrack(modifier: Modifier, span: () -> ThumbSpan?) {
    Box(
        modifier
            .width(ScrollbarSizes.width)
            .fillMaxHeight()
            .drawBehind {
                val s = span() ?: return@drawBehind
                val track = size.height
                val thumbLength = track * s.length
                val thumbSize = Size(size.width, thumbLength)
                val outline = AppShapes.pill.createOutline(thumbSize, layoutDirection, this)
                translate(top = (track - thumbLength) * s.position) {
                    drawOutline(outline, AppPalette.lineStrong)
                }
            },
    )
}

/** 미리보기용 스크롤 목록: 목록 높이는 tab_button 3개 높이 */
@Composable
private fun ScrollbarPreviewSample(scrollState: ScrollState) {
    val listHeight = TabButtonSizes.height * PREVIEW_VISIBLE_ROWS
    AppTheme {
        Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) {
            Row(
                modifier = Modifier.height(listHeight),
                horizontalArrangement = Arrangement.spacedBy(AppDimens.touchGap),
            ) {
                Column(Modifier.width(TabButtonSizes.width).verticalScroll(scrollState)) {
                    repeat(PREVIEW_ITEM_COUNT) { index ->
                        Text(text = "항목 ${index + 1}", style = AppType.smallRegular, modifier = Modifier.padding(AppDimens.grid))
                        Divider()
                    }
                }
                Scrollbar(scrollState)
            }
        }
    }
}

private const val PREVIEW_VISIBLE_ROWS = 3
private const val PREVIEW_ITEM_COUNT = 16

@Preview
@Composable
internal fun ScrollbarTopPreview() = ScrollbarPreviewSample(rememberScrollState())

@Preview
@Composable
internal fun ScrollbarLazyListPreview() {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = PREVIEW_VISIBLE_ROWS)
    AppTheme {
        Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) {
            Row(
                modifier = Modifier.height(TabButtonSizes.height * PREVIEW_VISIBLE_ROWS),
                horizontalArrangement = Arrangement.spacedBy(AppDimens.touchGap),
            ) {
                LazyColumn(Modifier.width(TabButtonSizes.width), state = listState) {
                    items(PREVIEW_ITEM_COUNT) { index ->
                        Text(text = "항목 ${index + 1}", style = AppType.smallRegular, modifier = Modifier.padding(AppDimens.grid))
                        Divider()
                    }
                }
                Scrollbar(listState)
            }
        }
    }
}

@Preview
@Composable
internal fun ScrollbarScrolledPreview() {
    val initial = with(LocalDensity.current) { (TabButtonSizes.height * PREVIEW_VISIBLE_ROWS).roundToPx() }
    ScrollbarPreviewSample(rememberScrollState(initial))
}
