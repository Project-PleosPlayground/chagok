package ai.safetypoc.app.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** confirm_popup 크기 (디자인 시스템 7절, 영역사양_팝업 2) */
object ConfirmPopupSizes {
    /** 팝업 폭 */
    val width = 800.dp
    /** 안쪽 여백 p (네 변) */
    val padding = 48.dp
    /** 제목(2-1) 줄 높이 */
    val titleLineHeight = 56.sp
    /** 본문(2-2) 줄 높이 */
    val bodyLineHeight = 40.sp
    /** 제목 아래에서 본문까지 */
    val titleToBody = 16.dp
    /** 본문(없으면 제목) 아래에서 버튼까지 */
    val textToButtons = 48.dp
    /** 왼쪽 버튼과 오른쪽 버튼 사이 */
    val buttonGap = 32.dp
}

/** mission_info 크기 (디자인 시스템 6절, 영역사양_팝업 3) */
object MissionInfoSizes {
    /** 말풍선 최소 높이(한 줄일 때 높이) */
    val minHeight = 56.dp
    /** 테두리 두께 */
    val border = 2.dp
    /** 글 좌우 여백. ≈ 그림에서 잰 값(영역사양_팝업 C 그림. mission_info.svg에는 글이 없다) */
    val horizontalPadding = 32.dp
    /** 글 위아래 여백. 두 줄일 때 글이 테두리에 닿지 않게 둔다. [임시] */
    val verticalPadding = 8.dp
    /** info_icon 오른쪽 끝에서 말풍선까지. 자리를 잡는 화면이 쓴다 */
    val iconGap = 16.dp
}

/** 알림(L3) 크기 (영역사양_팝업 4) */
object NoticeSizes {
    /** 높이 */
    val height = 72.dp
    /** 최대 폭 */
    val maxWidth = 800.dp
    /** 글 좌우 여백. [임시] */
    val horizontalPadding = 32.dp
}
