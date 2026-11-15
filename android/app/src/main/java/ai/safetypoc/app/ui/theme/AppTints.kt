package ai.safetypoc.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 비활성 버튼의 글자·테두리 색: [enabled] 면 [color], 아니면 text_disabled.
 * tab_button 주행 중 비활성과 같은 모양을 쓰는 버튼이 함께 쓴다
 */
fun inkOrDisabled(color: Color, enabled: Boolean): Color = if (enabled) color else AppTints.textDisabled

/** 디자인 시스템 4절 "투명도로 쓰는 것": 팔레트 색에 투명도를 준 색. 이름은 쓰는 곳 */
object AppTints {
    /** tab_button 누름: ink 15% */
    val tabButtonPressed = AppPalette.ink.copy(alpha = 0.15f)
    /** drive_end_button 닫기 아이콘: ink 54% */
    val driveEndIcon = AppPalette.ink.copy(alpha = 0.54f)
    /** confirm_popup 본문 글자: ink 84% */
    val popupBody = AppPalette.ink.copy(alpha = 0.84f)
    /** info_icon: ink 84% */
    val infoIcon = AppPalette.ink.copy(alpha = 0.84f)
    /** confirm_popup 뒤 막(scrim): ink 40% */
    val scrim = AppPalette.ink.copy(alpha = 0.40f)
    /** 주행 중 전면 프로텍터 막: ink 85% [임시] */
    val driveProtector = AppPalette.ink.copy(alpha = 0.85f)
    /** mission_info 테두리: ink 40% */
    val missionInfoBorder = AppPalette.ink.copy(alpha = 0.40f)
    /** summary_item_box 테두리: ink 10% */
    val summaryItemBorder = AppPalette.ink.copy(alpha = 0.10f)
    /** 주행 결과의 '좋음' 초록: 밝은 green 보다 조금 어둡고 차분하게 (11/14) */
    val resultGreen = Color(0xFF1A9E4B)
    /** 성공 카드 바탕: green_dark 20% */
    val successCard = AppPalette.greenDark.copy(alpha = 0.20f)
    /** 실패 카드 바탕: red 20% */
    val failCard = AppPalette.red.copy(alpha = 0.20f)
    /** ai_badge 바탕: primary_blue 10% */
    val aiBadge = AppPalette.primaryBlue.copy(alpha = 0.10f)
    /** button_outline 바탕: white 20% */
    val buttonOutline = AppPalette.white.copy(alpha = 0.20f)
    /** car_name_card 바탕: white 3% */
    val carNameCard = AppPalette.white.copy(alpha = 0.03f)
    /** 그림 없는 빈 슬롯 바탕: platform_gray 40% */
    val emptySlot = AppPalette.platformGray.copy(alpha = 0.40f)
    /** score_card 분석 글, tip_box 글: ink 84% */
    val scoreCardBody = AppPalette.ink.copy(alpha = 0.84f)
    /** safety_score_card 가운데 선: ink 10% */
    val scoreCardDivider = AppPalette.ink.copy(alpha = 0.10f)
    /** reward_chip_point 글자. 팔레트에 없는 색(시안 ≈ #B07800)이라 여기에만 둔다 [임시] */
    val rewardPointText = Color(0xFFB07800)
    /** text_disabled: 주행 중 tab_button 글자. [임시] text_tertiary (#85) */
    val textDisabled = AppPalette.textTertiary
}

/** 디자인 시스템 4절 그라데이션. 팔레트 밖 색이라 여기에만 둔다 */
object AppGradients {
    /** score_card 머리: 위에서 아래 */
    val scoreCardHead = Brush.verticalGradient(listOf(Color(0xFFD6E8FA), Color(0xFFEEF4FB)))
}
