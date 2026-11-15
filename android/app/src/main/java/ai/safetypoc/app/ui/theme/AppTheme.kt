package ai.safetypoc.app.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * 지금 주행 중인지. 주행 중이면 애니메이션·움직이는 표시를 쓰지 않고 바로 바꿔 보인다(디자인 시스템 2절 "주행 중 움직임").
 * 정차 중에만 움직임을 쓰는 컴포넌트가 읽는다. [AppTheme]가 내려 주며, 내려 주지 않은 곳에서는 주행 중으로 본다.
 */
val LocalDriving = compositionLocalOf { true }

/**
 * 앱 테마. 글자 기본값은 ink, small_regular.
 * [driving]은 앱의 주행 상태를 그대로 넘긴다. 기본값은 움직임을 쓰지 않는 쪽(주행 중)이다.
 */
@Composable
fun AppTheme(driving: Boolean = true, content: @Composable () -> Unit) {
    // 앱 창의 폭. 분할 화면에서는 디스플레이 전체가 아니라 앱이 받은 창의 폭이다
    val narrow = LocalConfiguration.current.screenWidthDp.dp < WindowSizes.narrowBelow
    MaterialTheme {
        CompositionLocalProvider(
            LocalContentColor provides AppPalette.ink,
            LocalTextStyle provides AppType.smallRegular,
            LocalDriving provides driving,
            LocalNarrowWindow provides narrow,
            content = content,
        )
    }
}
