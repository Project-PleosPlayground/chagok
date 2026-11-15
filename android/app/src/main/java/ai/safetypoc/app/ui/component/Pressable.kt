package ai.safetypoc.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * 누르는 요소의 공통 클릭. 물결(ripple) 같은 움직이는 표시를 그리지 않는다(디자인 시스템 2절 "주행 중 움직임").
 * 누름 모양이 필요하면 [interactionSource]의 collectIsPressedAsState로 색을 바꾼다.
 */
fun Modifier.pressable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = interactionSource,
    indication = null,
    enabled = enabled,
    role = Role.Button,
    onClick = onClick,
)
