package ai.safetypoc.app.ui.theme

import androidx.compose.ui.unit.dp

/** tab_button 크기 (디자인 시스템 6절 "앱 공통") */
object TabButtonSizes {
    /** 버튼 폭 */
    val width = 240.dp
    /** 버튼 높이 */
    val height = 96.dp
}

/** tab_bar 크기 (디자인 시스템 6절 "앱 공통") */
object TabBarSizes {
    /** 버튼 사이 간격: 누르는 영역 사이 최소 거리 */
    val gap = AppDimens.touchGap
    /** 흰 판 안 버튼 사이 [가안]. 한 판으로 모으며 좁혔다 (11/14) */
    val innerGap = 8.dp
    /** 흰 판 안쪽 여백 [가안] */
    val inset = 12.dp
}

/** coin_bar 크기 (디자인 시스템 6절 "앱 공통") */
object CoinBarSizes {
    /** 막대 높이 */
    val height = 64.dp
    /** coin_icon 한 변. ≈ 그림에서 잰 값 */
    val iconSize = 48.dp
    /** 오른쪽 끝에서 coin_icon까지. ≈ 그림에서 잰 값 */
    val endPadding = 16.dp
    /** 왼쪽 끝에서 숫자까지. [임시] 그림에 숫자가 없어 재지 못함 */
    val startPadding = 24.dp
    /** 숫자와 coin_icon 사이. [임시] 그림에 숫자가 없어 재지 못함 */
    val gap = 8.dp
    /** 기본 캡슐 폭 = 숫자가 0 일 때의 내용(여백 + 숫자 + 간격 + coin_icon) 폭 × 이 값. 숫자가 이 폭에 들어가지 않으면 캡슐이 커진다 */
    const val widthScale = 1.5f
}

/** exp_bar 크기. [가안] 디자인 시스템 6절 "앱 공통"의 기름통+막대를 캡슐 하나로 다시 짰다 (11/14 홈 검토) */
object ExpBarSizes {
    /** 캡슐 높이: coin_bar 와 같아 위쪽 줄이 한 벌로 보인다 */
    val height = CoinBarSizes.height
    /** 캡슐 왼쪽 안쪽 여백: 기름통 원이 캡슐 안에 들어앉게 좁게 */
    val startPadding = 8.dp
    /** 캡슐 오른쪽 안쪽 여백 */
    val endPadding = 24.dp
    /** 요소 사이 */
    val gap = 16.dp
    /** 기름통을 담는 흰 원 지름 */
    val badge = 48.dp
    /** exp_icon 안 그림 크기 (원본 56 × 48 을 원에 맞게 줄임) */
    val iconWidth = 34.dp
    val iconHeight = 29.dp
    /** 막대 폭 */
    val barWidth = 280.dp
    /** 막대 높이 */
    val barHeight = 16.dp
    /** 연료계 눈금 두께. 막대를 ¼·½·¾ 로 나눈다 */
    val tick = 2.dp
    /** 연료계 눈금 수 + 1 (4칸) */
    const val tickSections = 4
}

/** divider 크기 (디자인 시스템 6절 "앱 공통") */
object DividerSizes {
    /** 선 두께 */
    val thickness = 1.dp
}

/** scrollbar 크기 (디자인 시스템 6절 "앱 공통") */
object ScrollbarSizes {
    /** 막대 폭 */
    val width = 8.dp
}
