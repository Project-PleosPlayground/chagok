package ai.safetypoc.app.ui.stage

import androidx.compose.ui.graphics.TransformOrigin
import ai.safetypoc.app.R
import ai.safetypoc.app.ui.art.ItemArt
import ai.safetypoc.app.ui.theme.LocalDriving
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import core.item.ItemCategory
import core.item.ItemId
import core.item.StagePoint

/** 미리보기 아이템의 불투명도. 놓인 아이템과 구분한다 */
private const val PREVIEW_ALPHA = 0.5f
/** 펫이 위아래로 오르내리는 폭(스테이지 px, 가운데에서 한쪽)과 한 번 오르거나 내리는 시간 [임시] */
private const val PET_FLOAT_STAGE_PX = 16f
private const val PET_FLOAT_MS = 1400

/** 스테이지 위아래 화면 여백 a (스펙_스테이지 "화면별 스테이지 크기") */
private val SCREEN_MARGIN = 48.dp

/**
 * 스테이지 (기능 뼈대, 디자인 없음). 아이템 그림 대신 이름을 쓴 네모로 그린다. 디자인이 나오면 이 함수만 바꾼다.
 * [modifier] 로 받은 영역의 가운데에 놓고, 높이는 영역 높이에서 화면 여백을 위아래로 뺀 값이다(스펙_스테이지 "화면별 스테이지 크기").
 * [임시] 받은 영역 전체를 화면의 빈 곳으로 본다. 영역이 좁으면 너비에 맞춰 줄인다.
 * [onMove] 를 주면 아이템을 끌어 옮길 수 있다. 터치한 곳에 겹친 아이템 중 맨 위 하나만 옮긴다 (08 "스테이지 배치").
 * [characterLabel] 은 캐릭터 자리 네모에 쓰는 글자다. 없으면 "캐릭터".
 * [character] 를 주면 네모 대신 캐릭터 자리에 그것을 그린다. [characterOverlay] 는 캐릭터 자리 위에 아이템보다 위로 얹는다(말풍선 등).
 */
@Composable
fun StageCanvas(
    items: List<StageItemView>,
    modifier: Modifier = Modifier,
    onMove: ((ItemId, StagePoint) -> Unit)? = null,
    characterLabel: String? = null,
    character: (@Composable () -> Unit)? = null,
    characterOverlay: (@Composable BoxScope.() -> Unit)? = null,
    /** 캐릭터 몸짓. 머리에 쓰는 소품도 같이 움직인다 */
    motion: CharacterMotion? = null,
) {
    BoxWithConstraints(modifier.padding(vertical = SCREEN_MARGIN), contentAlignment = Alignment.Center) {
        val height = minOf(maxHeight, maxWidth * (StageLayout.HEIGHT / StageLayout.WIDTH))
        StageBody(items, height, onMove, characterLabel, character, characterOverlay, motion)
    }
}

@Composable
private fun StageBody(
    items: List<StageItemView>, height: Dp, onMove: ((ItemId, StagePoint) -> Unit)?, characterLabel: String?,
    character: (@Composable () -> Unit)?, characterOverlay: (@Composable BoxScope.() -> Unit)?,
    motion: CharacterMotion?,
) {
    Box(
        Modifier.size(height * (StageLayout.WIDTH / StageLayout.HEIGHT), height),
    ) {
        val density = LocalDensity.current
        val scale = with(density) { height.toPx() } / StageLayout.HEIGHT
        /** 스테이지 px → dp. 이름을 Density.toDp 와 다르게 둔다(같으면 안쪽 호출이 자기 자신을 불러 무한 재귀) */
        fun Float.stageDp(): Dp = with(density) { (this@stageDp * scale).toDp() }
        val current by rememberUpdatedState(items)
        val move by rememberUpdatedState(onMove)
        // 처음 닿은 곳으로 집을 아이템을 고른다(드래그로 인정되는 거리를 넘긴 뒤의 위치가 아니라)
        val drag = if (onMove == null) Modifier else Modifier.pointerInput(scale) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val hit = StageLayout.topAt(current, StagePoint(down.position.x / scale, down.position.y / scale)) ?: return@awaitEachGesture
                var at = hit.position
                drag(down.id) { change ->
                    val d = change.positionChange()
                    change.consume()
                    at = StageLayout.clamp(StagePoint(at.x + d.x / scale, at.y + d.y / scale))
                    move?.invoke(hit.id, at)
                }
            }
        }
        Box(Modifier.fillMaxSize().then(drag)) {
            for (item in items.filter { it.category == ItemCategory.BACKGROUND }) {
                // 배경 소품(트리 등)은 정해진 자리에, 그 밖의 배경은 스테이지 전체에 (11/15)
                val box = StageLayout.backgroundBox(item.imageKey)
                val place = if (box == null) Modifier.fillMaxSize()
                    else Modifier.offset(box.left.stageDp(), box.top.stageDp()).size(box.width.stageDp(), box.height.stageDp())
                ItemArt(item.imageKey, item.name, place.alpha(if (item.preview) PREVIEW_ALPHA else 1f))
                if (item.imageKey == StageLayout.CHRISTMAS_TREE) TreeTwinkle(place, still = LocalDriving.current)
            }
            val characterSlot = Modifier.offset(StageLayout.CHARACTER_LEFT.stageDp(), StageLayout.CHARACTER_TOP.stageDp())
                .size(StageLayout.CHARACTER_SIZE.stageDp())
            if (character != null) {
                Box(characterSlot, contentAlignment = Alignment.Center) { character() }
            } else {
                Box(
                    characterSlot.background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { Text(characterLabel ?: stringResource(R.string.stage_character)) }
            }
            val half = StageLayout.ITEM_BOX / 2
            // 펫은 공중에 떠 위아래로 천천히 오르내린다. 주행 중에는 움직이지 않는다(디자인 시스템 2절 "주행 중 움직임")
            val bob = if (LocalDriving.current) remember { mutableFloatStateOf(0f) } else rememberInfiniteTransition(label = "pet")
                .animateFloat(-1f, 1f, infiniteRepeatable(tween(PET_FLOAT_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
            for (item in items.filter { it.category != ItemCategory.BACKGROUND }) {
                // 주작만 떠다닌다. 땅 펫(현무·해태)은 바닥에 서 있다
                val floats = item.category == ItemCategory.PET && !StageLayout.isGroundPet(item.imageKey)
                // 머리에 쓰는 소품은 캐릭터 몸짓을 따라 캐릭터 발밑을 축으로 함께 기울고 움직인다
                val worn = motion != null && item.imageKey in StageLayout.HEAD_WEAR
                ItemArt(
                    item.imageKey, item.name,
                    Modifier.offset((item.position.x - half).stageDp(), (item.position.y - half).stageDp())
                        .size(StageLayout.ITEM_BOX.stageDp())
                        .graphicsLayer {
                            if (floats) translationY = bob.value * (PET_FLOAT_STAGE_PX * scale)
                            rotationZ = item.tilt
                        }
                        .then(if (!worn) Modifier else Modifier.graphicsLayer {
                            val m = motion!!
                            val pivotX = StageLayout.CHARACTER_LEFT + StageLayout.CHARACTER_SIZE / 2
                            val pivotY = StageLayout.CHARACTER_TOP + StageLayout.CHARACTER_SIZE
                            transformOrigin = TransformOrigin(
                                (pivotX - (item.position.x - half)) / StageLayout.ITEM_BOX,
                                (pivotY - (item.position.y - half)) / StageLayout.ITEM_BOX,
                            )
                            rotationZ = m.tilt.value
                            translationX = m.x.value * StageLayout.CHARACTER_SIZE * scale
                            translationY = m.y.value * StageLayout.CHARACTER_SIZE * scale
                        })
                        .alpha(if (item.preview) PREVIEW_ALPHA else 1f),
                )
            }
            // 눈 내리기 배경: 캐릭터와 아이템 위로 눈송이가 떨어진다
            if (items.any { it.imageKey == StageLayout.SNOW }) Snowfall(Modifier.fillMaxSize(), still = LocalDriving.current)
            characterOverlay?.let { Box(characterSlot, content = it) }
        }
    }
}
