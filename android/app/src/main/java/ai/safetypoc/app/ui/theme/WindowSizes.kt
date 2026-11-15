package ai.safetypoc.app.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/** 창 모양 구분 (스펙_스테이지 "기준 화면") */
object WindowSizes {
    /**
     * 이 폭보다 좁으면 1칸 창으로 본다. 1칸 창은 가장 넓은 기종이 840, 2칸 창은 가장 좁은 기종이 1256 이라
     * 그 사이 값이면 어느 기종이든 두 창이 갈린다 [임시]
     */
    val narrowBelow = 1000.dp
}

/**
 * 지금 창이 1칸 창(좁은 창)인지. [AppTheme]가 창 폭으로 정해 내려 준다. 내려 주지 않은 곳에서는 2칸 창으로 본다.
 * 좁은 창에서만 배치를 바꾸는 화면이 읽는다.
 */
val LocalNarrowWindow = compositionLocalOf { false }
