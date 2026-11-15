package ai.safetypoc.app.ui.profile

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.component.ButtonDark
import ai.safetypoc.app.ui.component.ButtonOutline
import ai.safetypoc.app.ui.component.ConfirmPopup
import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.LocalDriving
import ai.safetypoc.app.ui.theme.LocalNarrowWindow
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import core.character.CharacterId
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.TransformOrigin
import ai.safetypoc.app.ui.theme.AppShapes
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * 프로필 추가 화면 (영역사양_프로필추가화면). 메뉴 없이 창 전체를 쓴다.
 * 프로필 수정 화면도 이 화면이다([AddProfileState.editing]). 지금 이름·차로 시작하고 삭제 버튼과 삭제 확인 팝업이 더 있다 (#111).
 * 세로 순서 차 고르기(2) → 프로필 이름(3) → 이름 입력(4) → 버튼 두 개(5). 3을 뺀 모두 창 가로 중앙.
 * [driving]이면 이름 입력을 막고 열린 키보드를 닫는다. 입력한 글은 남긴다(디자인 시스템 2절 "키보드 입력은 정차 중에만").
 * 뒤로 가기는 취소와 같다. 삭제 팝업이 떠 있으면 팝업만 닫는다(ConfirmPopup 이 나중에 그려져 먼저 받는다).
 */
@Composable
fun AddProfileScreen(state: AddProfileState, actions: AddProfileActions, driving: Boolean, modifier: Modifier = Modifier) {
    BackHandler(onBack = actions::cancel)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(driving) {
        if (driving) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    Box(modifier.fillMaxSize().background(AppPalette.white)) {
        CarPicker(state, actions)
        NameInput(state, actions, enabled = !driving)
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = AddProfileScreenSizes.buttonsBottom),
            horizontalArrangement = Arrangement.spacedBy(AddProfileScreenSizes.buttonGap),
        ) {
            ButtonDark(stringResource(R.string.save_button), onClick = actions::save, enabled = state.canSave)
            ButtonOutline(stringResource(R.string.cancel_button), onClick = actions::cancel)
        }
        // 삭제는 오른쪽 위(여백 a). 프로필 화면 수정 모드의 취소와 같은 자리
        if (state.editing) {
            ButtonOutline(
                stringResource(R.string.delete_button),
                onClick = actions::delete,
                modifier = Modifier.align(Alignment.TopEnd).padding(AppDimens.screenMargin),
            )
        }
    }
    if (state.confirmingDelete) {
        ConfirmPopup(
            title = stringResource(R.string.profile_delete_title),
            body = stringResource(R.string.profile_delete_body),
            confirmText = stringResource(R.string.delete_button),
            dismissText = stringResource(R.string.cancel_button),
            onConfirm = actions::confirmDelete,
            onDismiss = actions::dismissDelete,
        )
    }
}

/**
 * car_picker(2) 개편 (11/15): 원판·화살표 없이 흰 바탕에 차를 옆으로 늘어놓고 손가락으로 좌우로 넘긴다.
 * 가운데 차는 크고 또렷하게 천천히 떠오르고, 양옆 차는 작고 옅게 걸쳐 보인다(누르면 그 차로). 아래에 차 이름과 점 표시
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.CarPicker(state: AddProfileState, actions: AddProfileActions) {
    if (state.cars.isEmpty()) return
    val s = AddProfileScreenSizes
    // 끝없이 돌게: 쪽 수를 크게 잡고 가운데에서 시작한다. 실제 차는 쪽 번호 % 차 수. 어느 차든 양옆에 차가 있다
    val n = state.cars.size
    val pager = rememberPagerState(initialPage = LOOP_PAGES / 2 / n * n + state.selected) { LOOP_PAGES }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { actions.selectCar(it % n) } }
    LaunchedEffect(state.selected) {
        val now = pager.settledPage % n
        if (now != state.selected) pager.animateScrollToPage(pager.settledPage + (state.selected - now))
    }
    val driving = LocalDriving.current
    val lift = if (driving) 0f else
        rememberInfiniteTransition(label = "float")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(FLOAT_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "lift").value
    val pageWidth = if (LocalNarrowWindow.current) s.narrowPageWidth else s.pageWidth
    BoxWithConstraints(atTop(s.pickerTop).fillMaxWidth().height(s.pickerHeight)) {
        HorizontalPager(
            pager, Modifier.fillMaxSize(),
            pageSize = PageSize.Fixed(pageWidth),
            contentPadding = PaddingValues(horizontal = ((maxWidth - pageWidth) / 2).coerceAtLeast(s.zero)),
            userScrollEnabled = !driving,
        ) { page ->
            // 가운데에서 떨어진 정도 0(가운데) ~ 1(옆자리). 두 칸 넘게 떨어진 차는 숨겨 화면에 세 대만 (11/15)
            val dist = abs(pager.currentPage - page + pager.currentPageOffsetFraction)
            val off = dist.coerceIn(0f, 1f)
            val farFade = (2f - dist).coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = farFade }.clickable(enabled = page != pager.currentPage && !driving, indication = null,
                    interactionSource = remember { MutableInteractionSource() }) { scope.launch { pager.animateScrollToPage(page) } },
                contentAlignment = Alignment.BottomCenter,
            ) {
                // 발밑 그림자: 가운데 차가 떠오를수록 작고 옅게
                Canvas(Modifier.size(s.carWidth * 0.78f, s.shadowHeight).graphicsLayer { alpha = 1f - off * 0.85f }) {
                    val k = if (page == pager.currentPage) 1f - lift * 0.2f else 1f
                    val w = size.width * k
                    drawOval(
                        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.14f), Color.Transparent), center, w / 2),
                        Offset((size.width - w) / 2, 0f), Size(w, size.height),
                    )
                }
                CharacterPicture(
                    state.cars[page % n].assetKey, MAX_STAGE, s.carWidth,
                    // 칸이 차보다 좁아도(1칸 창) 차는 제 폭으로 그린다
                    Modifier.wrapContentWidth(unbounded = true).padding(bottom = s.shadowHeight / 2).graphicsLayer {
                        val scale = 1f - off * (1f - s.SIDE_SCALE)
                        scaleX = scale; scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        alpha = 1f - off * (1f - s.SIDE_ALPHA)
                        translationY = -lift * (1f - off) * s.floatLift.toPx()
                    },
                )
            }
        }
    }
    // 차 이름 + 점 표시(지금 몇 번째 차인지)
    Column(atTop(s.pickerTop + s.pickerHeight + s.pickerToName), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(state.cars[pager.currentPage % n].name, style = AppType.largeBold, color = AppPalette.ink, maxLines = 1)
        Spacer(Modifier.height(s.nameToDots))
        Row(horizontalArrangement = Arrangement.spacedBy(s.dotGap), verticalAlignment = Alignment.CenterVertically) {
            state.cars.indices.forEach { i ->
                val on = i == pager.currentPage % n
                Box(
                    Modifier.size(if (on) s.dotOnWidth else s.dot, s.dot)
                        .background(if (on) AppPalette.ink else AppPalette.line, AppShapes.pill),
                )
            }
        }
    }
}

/** 프로필 이름(3), 이름 입력(4), 도움말(4-2). 프로필 이름은 이름 입력의 왼쪽 끝에 맞춘다 */
@Composable
private fun BoxScope.NameInput(state: AddProfileState, actions: AddProfileActions, enabled: Boolean) {
    val s = AddProfileScreenSizes
    // 프로필 이름(3): 입력 칸 위 가운데 (11/15)
    Text(
        stringResource(R.string.profile_name),
        atTop(s.textFieldTop - s.labelToField - s.labelHeight),
        style = AppType.mediumBold, color = AppPalette.ink, textAlign = TextAlign.Center,
    )
    ProfileTextField(
        value = state.name,
        onValueChange = actions::changeName,
        onClear = actions::clearName,
        enabled = enabled,
        modifier = atTop(s.textFieldTop),
    )
    Text(
        stringResource(R.string.profile_instruction),
        modifier = atTop(s.textFieldTop + ProfileComponentSizes.textFieldHeight + s.fieldToHelp),
        style = AppType.smallRegular,
        color = AppPalette.textTertiary,
        textAlign = TextAlign.Center,
    )
}

/** 창 가로 중앙, 창 위에서 [top] */
private fun BoxScope.atTop(top: Dp): Modifier = Modifier.align(Alignment.TopCenter).padding(top = top)

/** 가운데 차가 한 번 오르거나 내리는 시간 [가안]. 높이는 AddProfileScreenSizes.floatLift */
private const val FLOAT_MS = 1500

/** 끝없이 도는 차 고르기의 쪽 수 */
private const val LOOP_PAGES = 3000

/** car_picker의 차 그림 단계. 시안은 Lv5 그림을 쓴다 */
private const val MAX_STAGE = 5

private val previewCars = listOf(
    CarOption(CharacterId(6), "아이오닉 3", "ioniq3"),
    CarOption(CharacterId(7), "아이오닉 5", "ioniq5"),
    CarOption(CharacterId(3), "그랜저", "grandeur"),
)

private object NoAddProfileActions : AddProfileActions {
    override fun nextCar() = Unit
    override fun selectCar(index: Int) = Unit
    override fun changeName(text: String) = Unit
    override fun clearName() = Unit
    override fun save() = Unit
    override fun cancel() = Unit
    override fun delete() = Unit
    override fun confirmDelete() = Unit
    override fun dismissDelete() = Unit
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun EditProfilePreview() {
    AppTheme { AddProfileScreen(AddProfileState(previewCars, selected = 1, name = "우리집 캐스퍼", editingId = "p1"), NoAddProfileActions, driving = false) }
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun EditProfileDeletePreview() {
    AppTheme {
        AddProfileScreen(
            AddProfileState(previewCars, selected = 1, name = "우리집 캐스퍼", editingId = "p1", confirmingDelete = true),
            NoAddProfileActions, driving = false,
        )
    }
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun AddProfileCarPickPreview() {
    AppTheme { AddProfileScreen(AddProfileState(previewCars, selected = 2), NoAddProfileActions, driving = false) }
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun AddProfileDrivingPreview() {
    AppTheme { AddProfileScreen(AddProfileState(previewCars, selected = 2, name = "우리집 그랜저"), NoAddProfileActions, driving = true) }
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun AddProfileNameInputPreview() {
    AppTheme { AddProfileScreen(AddProfileState(previewCars, selected = 2, name = "우리집 그랜저"), NoAddProfileActions, driving = false) }
}
