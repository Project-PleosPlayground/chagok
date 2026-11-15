package ai.safetypoc.app.ui.theme

import androidx.compose.ui.unit.dp

/** 디자인 시스템 2절·3절 공통 길이 */
object AppDimens {
    /** 크기·간격의 단위. 크기와 간격은 이 값의 배수 */
    val grid = 8.dp
    /** 화면 여백 a: 창 가장자리에서 UI까지 */
    val screenMargin = 48.dp
    /** 누르는 영역의 최소 한 변 */
    val touchTarget = 64.dp
    /** 누르는 영역 사이, 화면 가장자리에서 누르는 영역까지의 최소 거리 */
    val touchGap = 24.dp
}

/** 시간 값 (밀리초) */
object AppTiming {
    /** mission_info가 열린 뒤 닫히기까지 (디자인 시스템 6절) */
    const val MISSION_INFO_MS = 1_000L
    /** 알림(L3)이 뜬 뒤 사라지기까지. [임시] 영역사양_팝업 4 (#85) */
    const val TOAST_MS = 2_000L
}
