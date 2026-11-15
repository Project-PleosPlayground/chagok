package ai.safetypoc.app.ui.mission

import androidx.compose.ui.unit.dp

/** 미션 화면 배치 값 (영역사양_미션화면, 디자인 시스템 6절 "미션 부품") */
object MissionScreenSizes {
    /** 카드 열 폭: mission_box 폭 */
    val cardColumnWidth = MissionBoxSizes.width
    /** 카드 사이 */
    val cardGap = 32.dp
    /** 섹션 사이 [임시] */
    val sectionGap = 72.dp
    /** 카드 열 ~ 스크롤 막대 [임시] */
    val scrollbarGap = 96.dp
    /** 1칸 창: 카드 열 ~ 스크롤 막대. 카드 폭을 넓히려고 줄인다 [임시] */
    val narrowScrollbarGap = 24.dp
    /** 1칸 창: 섹션 머리 ~ 첫 카드 [임시] */
    val narrowHeaderGap = 16.dp
    /** 섹션 머리 아이콘 상자 한 변 */
    val headerIcon = 48.dp
    /** mission_box·mission_blank 모서리: radius_l. 점선을 직접 그릴 때 쓴다 */
    val cardRadius = 24.dp
    /** 섹션 머리: 아이콘 상자 ~ 제목 [임시] */
    val headerIconGap = 16.dp
    /** 슬롯이 가득 찼을 때 제공 카드 불투명도 [제안] */
    const val DIM_ALPHA = 0.5f
}

/** mission_box 와 그 안 부품 크기 (디자인 시스템 6절) */
object MissionBoxSizes {
    val width = 832.dp
    /** 최소 높이. 내용이 넘치면 늘어난다 */
    val height = 168.dp
    /** 제목 최대 줄 수 [임시] */
    const val TITLE_MAX_LINES = 2
    /** 설명 최대 줄 수 [임시] */
    const val DESCRIPTION_MAX_LINES = 2
    /** 카드 안 좌우 여백 [임시] 캡처에서 잰 값 */
    val horizontalPadding = 32.dp
    /** 카드 안 위아래 여백 [임시] 캡처에서 잰 값 */
    val verticalPadding = 24.dp
    /** 줄 사이 [임시] */
    val lineGap = 8.dp
    /** 제공 카드 테두리, mission_blank 점선 두께 */
    val border = 2.dp
    /** mission_blank 점선: 선 길이 [임시] */
    val dash = 8.dp
    /** mission_blank 점선: 빈 길이 [임시] */
    val dashGap = 6.dp
    /** 라벨 ~ 값, 진행 묶음 ~ 남은 시간 묶음 [임시] */
    val partGap = 16.dp
}

/** status_chip (성공·실패) */
object StatusChipSizes {
    val width = 96.dp
    val height = 40.dp
    /** 칩 ~ 제목 [임시] */
    val titleGap = 16.dp
}

/** reward 칩 */
object RewardChipSizes {
    val height = 40.dp
    val icon = 24.dp
    val border = 1.dp
    /** 칩 안 좌우 여백 [임시] */
    val horizontalPadding = 12.dp
    /** 아이콘 ~ 숫자, 칩 사이 [임시] */
    val gap = 8.dp
}

/** mission_progress_bar (mission_box 안) */
object MissionProgressBarSizes {
    val width = 168.dp
    val height = 8.dp
}

/** complete_mission 버튼 */
object CompleteMissionSizes {
    /** 접힌 탭 칸 폭(128)에 맞췄다 (11/14) */
    val width = 128.dp
    val height = 48.dp
    val border = 1.dp
}
