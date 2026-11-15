package ai.safetypoc.app.ui.profile

import ai.safetypoc.app.ui.theme.AppDimens
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 디자인 시스템 6절 "프로필" 컴포넌트 크기 */
object ProfileComponentSizes {
    /** profile_card 한 변 */
    val card = 320.dp
    /** profile_card 안 캐릭터 그림 폭 */
    val cardPictureWidth = 272.dp
    /** profile_card 수정 모드의 edit_icon 한 변. ≈ 그림에서 잰 값(edit_icon 73 × 75) */
    val editIcon = 72.dp
    /** add_button 한 변 */
    val addButton = 80.dp
    /** add_button 안 add_icon 한 변 */
    val addIcon = 48.dp
    /** profile_edit_button 폭 */
    val editButtonWidth = 256.dp
    /** profile_edit_button 높이 */
    val editButtonHeight = 64.dp
    /** profile_edit_button 테두리 두께 */
    val editButtonBorder = 1.dp
    /** text_field 폭 */
    val textFieldWidth = 608.dp
    /** text_field 높이 */
    val textFieldHeight = 96.dp
    /** text_field 테두리 두께. ≈ 캡처에서 잰 값 */
    val textFieldBorder = 2.dp
    /** text_field 안 왼쪽·오른쪽 여백. ≈ 캡처에서 잰 값 */
    val textFieldPadding = 32.dp
    /** text_field 지우기 아이콘(검은 원) 지름. ≈ 캡처에서 잰 값 */
    val clearIcon = 40.dp
    /** 지우기 아이콘 안 close_icon 한 변. [임시] 캡처가 작아 잴 수 없음 */
    val clearMark = 24.dp
    /** 지우기 아이콘을 누르는 영역 한 변 */
    val clearTouch = AppDimens.touchTarget
    /** car_name_card 폭 */
    val carNameCardWidth = 192.dp
    /** car_name_card 높이 */
    val carNameCardHeight = 64.dp
}

/** 캐릭터 그림 비율(폭 : 높이). 캐릭터 그림 원본(Grandeur_Lv5.svg 225 × 202)에서 잰 값 */
const val CHARACTER_ASPECT = 225f / 202f

/** profile_card 수정 모드에서 캐릭터 그림의 불투명도. [임시] 영역사양_프로필화면 "흐리게"의 값이 없다 */
const val EDIT_PICTURE_ALPHA = 0.4f

/** 프로필 화면 배치 (영역사양_프로필화면). 세로 위치는 창 위에서 잰 시안 근사값(≈)이다 */
object ProfileScreenSizes {
    /** 프로필 줄(3)의 카드 위 */
    val rowTop = 480.dp
    /** 안내(2) 아래에서 프로필 줄(3) 위까지 */
    val guideToRow = 104.dp
    /** 프로필 줄 칸 사이 */
    val rowGap = 96.dp
    /** 카드 아래에서 이름(3-3) 위까지 */
    val cardToName = 40.dp
    /** 창 아래에서 프로필 관리(4) 아래까지 */
    val editButtonBottom = 112.dp
    /** 빈 상태 안내 제목과 설명 사이. [임시] 시안 캡처(profileView_0)가 없다 */
    val emptyLineGap = 16.dp
    /** 1칸 창: 창 위에서 안내(2) 아래까지 [임시] */
    val narrowGuideBottom = 136.dp
    /** 1칸 창: 안내(2) 아래에서 첫 줄 카드 위까지 [임시] */
    val narrowGuideToGrid = 40.dp
    /** 1칸 창: 2열 칸 사이(가로) [임시] */
    val narrowColumnGap = 64.dp
    /** 1칸 창: 2열 줄 사이(세로) [임시] */
    val narrowRowGap = 32.dp
    /** 1칸 창: 한 줄에 놓는 칸 수 */
    const val NARROW_COLUMNS = 2
}

/** 프로필 추가 화면 배치 (영역사양_프로필추가화면). 세로 위치는 창 위에서 잰 시안 근사값(≈)이다 */
object AddProfileScreenSizes {
    /** 차 고르기(11/15 개편): 좌우로 넘기는 줄의 위·높이, 한 칸 폭, 가운데 차 폭 */
    val pickerTop = 150.dp
    val pickerHeight = 420.dp
    val pageWidth = 440.dp
    /** 1칸 창 한 칸 폭: 양옆 차가 가운데 차 쪽으로 모여 창 안에 보이게 줄였다. 가운데 차는 칸보다 넓게 그린다 [임시] */
    val narrowPageWidth = 320.dp
    val carWidth = 380.dp
    /** 양옆 차 크기·진하기 */
    const val SIDE_SCALE = 0.62f
    const val SIDE_ALPHA = 0.35f
    val shadowHeight = 36.dp
    /** 차 줄 아래 차 이름, 이름 아래 점 표시 */
    val pickerToName = 20.dp
    val nameToDots = 16.dp
    val dot = 8.dp
    val dotOnWidth = 24.dp
    val dotGap = 8.dp
    val zero = 0.dp
    /** 가운데 차 그림 위 */
    val centerCarTop = 196.dp
    /** 가운데 차 그림 폭. ≈ 캡처에서 잰 값(333) */
    val centerCarWidth = 320.dp
    /** 양옆 차 크기 배율. ≈ 0.7배 */
    const val SIDE_CAR_SCALE = 0.7f
    /** 양옆 차를 더 내려 발이 타원 위에 닿게 [가안]. 타원은 양옆으로 갈수록 위 끝이 낮다 */
    val sideCarDrop = 20.dp
    /** 창 가로 중앙에서 양옆 차 가운데까지. ≈ 캡처에서 잰 값 */
    val sideCarOffset = 300.dp
    /** platform 그림(빛 번짐 포함) 위. 타원 위 ≈ 448 에서 그림 틀의 위 여백(85 × 배율)을 뺀 값 */
    val platformTop = 336.dp
    /** platform 그림(빛 번짐 포함) 폭. 타원 폭이 캡처에서 잰 값(≈ 903)이 되게 그림 틀(1125)을 줄인 값 */
    val platformWidth = 1112.dp
    /** platform 그림(빛 번짐 포함) 높이. 그림 틀 비율(1125 × 348) */
    val platformHeight = 344.dp
    /** car_name_card 위 [가안]. 받침 타원 아래 끝(platformTop + 222/348 × 높이 ≈ 555) 바로 아래. next_button 과 프로필 이름 사이를 띄우려고 붙였다. 시안은 받침에 걸쳤다 */
    val carNameTop = 500.dp
    /** car_name_card 아래에서 next_button 위까지 */
    val carNameToNext = 40.dp
    /** text_field(4) 위. ≈ 캡처에서 잰 값 */
    val textFieldTop = 816.dp
    /** 프로필 이름(3) 아래에서 text_field 위까지 */
    val labelToField = 16.dp
    /** 프로필 이름 글자 줄 높이 */
    val labelHeight = 36.dp
    /** text_field 아래에서 도움말(4-2) 위까지 */
    val fieldToHelp = 24.dp
    /** 창 아래에서 버튼 두 개(5) 아래까지 */
    val buttonsBottom = 96.dp
    /** 저장과 취소 사이 */
    val buttonGap = 40.dp
    /** 가운데 차가 떠오르는 높이 [가안] */
    val floatLift = 14.dp
}

/** Preview 창 폭(dp). 기준 기종 2칸 창 (디자인 시스템 3절) */
internal const val PREVIEW_WINDOW_WIDTH = 1700
/** Preview 창 높이(dp) */
internal const val PREVIEW_WINDOW_HEIGHT = 1184

/**
 * car_picker 받침 [가안]. platform 그림 위에 차 발밑 그림자를 얹는다.
 * 비율은 platform 그림 틀(1125 × 348)에 대한 값이다. 타원은 x 105~1020, y 86~222 에 있다.
 */
object PlatformFinish {
    const val ELLIPSE_LEFT = 105f / 1125f
    const val ELLIPSE_RIGHT = 1020f / 1125f
    const val ELLIPSE_TOP = 86f / 348f
    const val ELLIPSE_BOTTOM = 222f / 348f
    /** 발밑 그림자: 타원 위 끝에서 아래로(타원 높이에 대한 비율), 폭(차 그림 폭에 대한 비율), 진하기 */
    const val SHADOW_Y = 0.30f
    const val SHADOW_WIDTH = 0.62f
    const val SHADOW_ALPHA = 0.30f
    const val SIDE_SHADOW_ALPHA = 0.16f
}

/** car_name_card 리퀴드 글래스 [가안]. 흐림 효과는 API 31 부터라 쓰지 않고, 반투명 흰 면·위쪽 빛·얇은 흰 테두리로 유리처럼 */
object GlassPill {
    val fillTop = Color.White.copy(alpha = 0.34f)
    val fillBottom = Color.White.copy(alpha = 0.12f)
    val border = Color.White.copy(alpha = 0.55f)
    val borderWidth = 1.dp
    val shadow = Color(0xFF061456).copy(alpha = 0.35f)
    val letterSpacing = 1.sp
}

/**
 * profile_card·add 칸 모양 [가안]. 회색 면 대신 흰 바탕에 옅은 파랑 테두리만(애플 설정 카드 느낌).
 * 누르는 동안 테두리가 primary_blue 로 진해진다.
 */
object ProfileCardStyle {
    val border = Color(0xFFD3E2FB)
    val borderWidth = 2.dp
    val borderPressedWidth = 3.dp
    /** add 칸 안 원 바탕(아주 옅은 파랑) */
    val addFill = Color(0xFFF2F7FF)
    val addCircle = 96.dp
}
