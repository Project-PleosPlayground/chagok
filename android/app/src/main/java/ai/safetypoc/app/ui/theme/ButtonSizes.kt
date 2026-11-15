package ai.safetypoc.app.ui.theme

import androidx.compose.ui.unit.dp

/** button_dark 크기 (디자인 시스템 6절). button_light도 짝을 이루는 button_dark와 같은 크기를 쓴다 */
object ButtonDarkSizes {
    /** 화면 안 버튼 폭 */
    val screenWidth = 136.dp
    /** 화면 안 버튼 높이 */
    val screenHeight = 80.dp
    /** 팝업 안 버튼 폭 */
    val popupWidth = 336.dp
    /** 팝업 안 버튼 높이 */
    val popupHeight = 96.dp
    /** 빈 상태 안(mission_empty) 버튼 폭 */
    val emptyStateWidth = 280.dp
    /** 빈 상태 안(mission_empty) 버튼 높이 */
    val emptyStateHeight = 88.dp
}

/** button_outline 크기 (디자인 시스템 6절) */
object ButtonOutlineSizes {
    /** 폭 */
    val width = 136.dp
    /** 높이 */
    val height = 80.dp
    /** 테두리 두께 */
    val borderWidth = 1.dp
}

/** next_button 크기 (디자인 시스템 6절) */
object NextButtonSizes {
    /** 원 지름 */
    val diameter = 88.dp
    /** next_icon을 그리는 틀. 아이콘 그림(84)이 원 그림(84)과 같은 틀이라 원 지름과 같게 둔다 */
    val iconFrame = diameter
}

/** back_button 크기 (디자인 시스템 6절) */
object BackButtonSizes {
    /** 누르는 영역 한 변 */
    val touchArea = AppDimens.touchTarget
    /** back_icon을 그리는 틀 한 변. ≈ 그림에서 잰 값(back_icon 58 × 51) */
    val icon = 56.dp
}

/** drive_start_button, drive_end_button 크기 (디자인 시스템 6절) */
object DriveButtonSizes {
    /** 폭 */
    val width = 360.dp
    /** 높이 */
    val height = 96.dp
    /** play_icon, close_icon 한 변. ≈ 그림에서 잰 값(아이콘 그림 32 × 32) */
    val icon = 32.dp
    /** 아이콘과 글자 사이. [임시] 버튼 그림에 아이콘·글자가 없어 잴 수 없음 */
    val iconTextGap = 16.dp
    /** drive_end_button 정지 표시: 흰 원 지름, 안의 둥근 네모 한 변과 모서리 [가안] */
    val stopBadge = 44.dp
    val stopMark = 16.dp
    val stopMarkRadius = 4.dp
    /** 누르는 동안 줄이는 비율 [가안] */
    const val pressedScale = 0.97f
}

/** info_icon 크기 (디자인 시스템 6절) */
object InfoIconSizes {
    /** 보이는 아이콘 한 변 */
    val icon = 24.dp
    /** 누르는 영역 한 변 */
    val touchArea = AppDimens.touchTarget
}
