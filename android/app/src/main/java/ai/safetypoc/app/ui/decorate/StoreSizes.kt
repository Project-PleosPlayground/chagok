package ai.safetypoc.app.ui.decorate

import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.BackButtonSizes
import ai.safetypoc.app.ui.theme.ButtonDarkSizes
import ai.safetypoc.app.ui.theme.CoinBarSizes
import androidx.compose.ui.unit.dp

/** 상점 화면 배치 값 (영역사양_상점화면). 번호는 영역 사양의 요소 번호다 */
object StoreScreenSizes {
    /** 2 store_panel 폭 (디자인 시스템 6절) */
    val panelWidth = 800.dp
    /** 3·4 머리 줄 높이: back_button 누르는 영역. 제목은 이 줄의 세로 가운데 */
    val headerHeight = BackButtonSizes.touchArea
    /** 4 제목 아래에서 5 segmented_tab 위까지. ≈ 영역 사양 */
    val headerToTab = 48.dp
    /** 5 segmented_tab 아래에서 6 아이템 목록 위까지. ≈ 영역 사양 */
    val tabToList = 48.dp

    /** 창 위 끝에서 6 아이템 목록(또는 item_detail) 위 끝까지 */
    val listTop = AppDimens.screenMargin + headerHeight + headerToTab + SegmentedTabSizes.height + tabToList

    /** 1 스테이지 위쪽 빈 곳 끝: 창 위 끝에서 coin_bar 아래 a 까지 */
    val stageAreaTop = AppDimens.screenMargin + CoinBarSizes.height + AppDimens.screenMargin
    /** 1 스테이지 아래쪽 빈 곳 끝: 창 아래 끝에서 저장 버튼 위 a 까지 */
    val stageAreaBottom = AppDimens.screenMargin + ButtonDarkSizes.screenHeight + AppDimens.screenMargin
}

/** segmented_tab 크기 (디자인 시스템 6절 "아이템 상점") */
object SegmentedTabSizes {
    /** 칸 폭 */
    val cellWidth = 224.dp
    /** 칸 높이 */
    val cellHeight = 64.dp
    /** 바탕 높이. ≈ 영역 사양(5) */
    val height = 80.dp
    /** 바탕 안 여백(네 변): 칸이 바탕 세로 가운데에 오고, 양 끝 칸도 같은 간격을 둔다 */
    val inset = (height - cellHeight) / 2
    /** 고르지 않은 두 칸 사이 세로선 높이. 시안 캡처에서 잰 값 */
    val dividerHeight = 32.dp
}

/** item_box 크기 (디자인 시스템 6절 "아이템 상점") */
object ItemBoxSizes {
    /** 상자 폭 */
    val width = 272.dp
    /** 상자 높이 */
    val height = 304.dp
    /** 테두리 두께 */
    val border = 2.dp
    /** 목록의 칸 사이 가로. ≈ 영역 사양(6-1) */
    val columnGap = 88.dp
    /** 목록의 칸 사이 세로. ≈ 영역 사양(6-1) */
    val rowGap = 64.dp
    /** 아이템 그림 틀 한 변 [임시] 시안 캡처는 176. 그림 여백을 잘라 낸 뒤 칸에 비해 작아 보여 키웠다 (11/14 상점 검토) */
    val picture = 200.dp
    /** 상자 위 끝에서 그림 틀까지 [임시] 시안 캡처는 40. 위 여백이 많아 보여 줄였다 (11/14 상점 검토) */
    val pictureTop = 24.dp
    /** 그림 틀 아래에서 이름까지 */
    val pictureToName = 16.dp
    /** 상자 위·오른쪽 끝에서 lock_icon·check_icon 틀까지. 시안 캡처에서 잰 값 */
    val badgeInset = 8.dp
    /** lock_icon 틀 한 변 (아이콘 원본 크기) */
    val lockIcon = 56.dp
    /** check_icon 틀 한 변 (아이콘 원본 크기) */
    val checkIcon = 44.dp
}

/** item_detail 크기 (디자인 시스템 6절 "아이템 상점") */
object ItemDetailSizes {
    /** 장소 선물 알약 안 여백 (11/15) */
    val placePadH = 20.dp
    val placePadV = 10.dp
    /** 큰 상자 폭 */
    val boxWidth = 496.dp
    /** 큰 상자 높이 */
    val boxHeight = 464.dp
    /** 큰 상자 테두리 두께 */
    val border = 2.dp
    /** 큰 상자 안 아이템 그림 틀 한 변 */
    val picture = 320.dp
    /** 큰 상자 아래에서 이름까지 */
    val boxToName = 32.dp
    /** 이름 아래에서 가격까지 */
    val nameToPrice = 16.dp
    /** 가격 아래에서 버튼까지 */
    val priceToButtons = 32.dp
    /** 가격의 coin_icon 한 변 */
    val coinIcon = 40.dp
    /** coin_icon 과 가격 숫자 사이 */
    val coinGap = 8.dp
    /** 두 버튼 사이: 누르는 영역 사이 최소 거리 */
    val buttonGap = AppDimens.touchGap
}
