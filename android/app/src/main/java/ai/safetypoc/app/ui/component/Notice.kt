package ai.safetypoc.app.ui.component

import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppTiming
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.NoticeSizes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay

/** 지금 떠 있는 알림. [id]는 띄운 순번이라, 같은 글을 다시 띄워도 새 값이 된다 */
data class NoticeData(val text: String, val id: Long)

/**
 * 알림(L3) 상태. 화면이 하나 두고 [show]로 띄운다. 알림은 한 번에 하나만 뜨고, 새로 띄우면 앞의 것을 바꾼다.
 * 같은 글을 떠 있는 동안 다시 띄우면 그 시점부터 [AppTiming.TOAST_MS]를 다시 잰다(화면에는 알림 하나가 더 오래 보인다).
 */
@Stable
class NoticeHostState {
    /** 떠 있는 알림. 없으면 null */
    var current: NoticeData? by mutableStateOf(null)
        private set

    private var shown = 0L

    /** [text]를 알림으로 띄운다 */
    fun show(text: String) {
        current = NoticeData(text, ++shown)
    }

    /** [data]가 아직 떠 있는 알림이면 내린다. 그 사이 새로 띄운 알림은 남긴다 */
    fun dismiss(data: NoticeData) {
        if (current == data) current = null
    }
}

/** 화면 하나에 둘 알림 상태 */
@Composable
fun rememberNoticeHostState(): NoticeHostState = remember { NoticeHostState() }

/**
 * [state]의 알림을 그리고 [AppTiming.TOAST_MS] 뒤 내린다. 떠 있는 알림이 없으면 아무것도 그리지 않는다.
 * 자리(창 아래 a, 가로 가운데)는 화면이 [modifier]로 잡는다.
 */
@Composable
fun NoticeHost(state: NoticeHostState, modifier: Modifier = Modifier) {
    val data = state.current ?: return
    LaunchedEffect(data.id) {
        delay(AppTiming.TOAST_MS)
        state.dismiss(data)
    }
    Notice(text = data.text, modifier = modifier)
}

/**
 * 알림(L3)의 모양: 화면 위에 잠깐 뜨는 한 줄. 막(scrim)이 없고 뒤 화면을 막지 않는다.
 * 폭은 글에 맞추고 최대 [NoticeSizes.maxWidth], 넘치면 말줄임. 띄우고 내리는 것은 [NoticeHost]가 한다.
 */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .widthIn(max = NoticeSizes.maxWidth)
            .height(NoticeSizes.height)
            .background(AppPalette.buttonDark, AppShapes.pill)
            .padding(horizontal = NoticeSizes.horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = AppPalette.white,
            style = AppType.smallRegular,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview
@Composable
internal fun NoticeDefaultPreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.surface).padding(AppDimens.screenMargin)) {
            Notice(text = "도전 슬롯이 가득 찼어요.")
        }
    }
}

@Preview
@Composable
internal fun NoticeHostShownPreview() {
    val state = remember { NoticeHostState().apply { show("도전 슬롯이 가득 찼어요.") } }
    AppTheme {
        Box(Modifier.background(AppPalette.surface).padding(AppDimens.screenMargin)) {
            NoticeHost(state)
        }
    }
}
