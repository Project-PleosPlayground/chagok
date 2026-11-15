package ai.safetypoc.app.ui.stage

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * 캐릭터 몸짓 값: 기울기(도, 캐릭터 발밑 기준)와 이동(캐릭터 크기 비율).
 * 캐릭터 그림과 머리에 쓰는 소품이 같은 값을 읽어 함께 움직인다 (11/14 주행 반응).
 */
class CharacterMotion {
    val tilt = Animatable(0f)
    val x = Animatable(0f)
    val y = Animatable(0f)
}

@Composable
fun rememberCharacterMotion(): CharacterMotion = remember { CharacterMotion() }
