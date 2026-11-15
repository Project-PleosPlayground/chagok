package ai.safetypoc.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 디자인 시스템 5절 모서리 */
object AppShapes {
    /** radius_s 8: exp_bar 막대, status_badge */
    val radiusS = RoundedCornerShape(8.dp)
    /** radius_m 16: car_name_card, summary_item_box */
    val radiusM = RoundedCornerShape(16.dp)
    /** radius_l 24: 버튼, mission_box, mission_card */
    val radiusL = RoundedCornerShape(24.dp)
    /** radius_xl 32: tab_button, confirm_popup, 흰 카드 */
    val radiusXl = RoundedCornerShape(32.dp)
    /** pill: 높이의 반 */
    val pill = RoundedCornerShape(percent = 50)
    /** 원 */
    val circle = CircleShape
}
