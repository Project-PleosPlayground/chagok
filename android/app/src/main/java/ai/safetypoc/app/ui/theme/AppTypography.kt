package ai.safetypoc.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 디자인 시스템 5절 글자. 값은 디자인_시스템.md 5절 표와 같게 둔다(DesignSystemSyncTest). 글꼴 Roboto는 FontFamily.Default */
object AppType {
    /** large_bold */
    val largeBold = TextStyle(fontFamily = FontFamily.Default, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    /** large_regular */
    val largeRegular = TextStyle(fontFamily = FontFamily.Default, fontSize = 40.sp, fontWeight = FontWeight.Normal)
    /** medium_bold */
    val mediumBold = TextStyle(fontFamily = FontFamily.Default, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    /** medium_regular */
    val mediumRegular = TextStyle(fontFamily = FontFamily.Default, fontSize = 32.sp, fontWeight = FontWeight.Normal)
    /** small_bold */
    val smallBold = TextStyle(fontFamily = FontFamily.Default, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    /** small_regular */
    val smallRegular = TextStyle(fontFamily = FontFamily.Default, fontSize = 24.sp, fontWeight = FontWeight.Normal)
}
