package ai.safetypoc.app.ui.report

import ai.safetypoc.app.ui.component.ButtonDark

import ai.safetypoc.app.ui.art.ItemArt

import androidx.compose.runtime.remember

import androidx.compose.runtime.setValue

import androidx.compose.runtime.mutableStateOf

import androidx.compose.foundation.clickable

import ai.safetypoc.app.R
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.BiasAlignment
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.graphics.BitmapFactory
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import ai.safetypoc.app.ui.art.CharacterStill
import ai.safetypoc.app.ui.component.CoinBar
import ai.safetypoc.app.ui.component.Scrollbar
import ai.safetypoc.app.ui.component.formatPoints
import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppGradients
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShadows
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTints
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.DividerSizes
import ai.safetypoc.app.ui.theme.appShadow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import java.time.ZoneId

/**
 * 주행 결과 화면 (영역사양_주행결과화면). 주행이 끝나면 뜬다.
 * 왼쪽 위 tab_bar 는 [TabFrame] 이 놓는다. 이 화면은 창 전체를 받아 3 제목, 4 coin_bar, 5·6 카드를 놓는다.
 * 닫는 버튼은 없다: tab_bar 의 홈과 뒤로 가기가 이전 화면으로 보낸다. [actions] 는 AI 동의 묻기(#101)에만 쓴다.
 * [nowMs]·[zone] 은 부제의 "오늘" 을 가르는 데 쓴다.
 */
@Composable
fun DriveEndScreen(
    state: DriveEndState?,
    actions: DriveEndActions,
    modifier: Modifier = Modifier,
    /** score_card 머리에 그리는 캐릭터 (차종 그림 키, 단계). 키가 없으면 그라데이션만 */
    characterAssetKey: String? = null,
    stage: Int = 1,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Box(modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = ResultScreenSizes.contentStart, top = AppDimens.screenMargin).height(ResultScreenSizes.headerHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.drive_result_title), style = AppType.mediumBold, color = AppPalette.ink)
            if (state != null) {
                Spacer(Modifier.width(ResultScreenSizes.titleToSubtitle))
                Text(tripText(tripLine(state.session, nowMs, zone)), style = AppType.smallRegular, color = AppPalette.textSecondary, maxLines = 1)
            }
        }
        if (state != null) {
            CoinBar(state.points, Modifier.align(Alignment.TopEnd).padding(top = AppDimens.screenMargin, end = AppDimens.screenMargin))
            Row(
                Modifier.fillMaxSize().padding(
                    // 탭을 작은 버튼으로 접어 두므로 카드는 그 아래 왼쪽 끝부터 넓게 쓴다 (11/15)
                    start = AppDimens.screenMargin, end = AppDimens.screenMargin, bottom = AppDimens.screenMargin,
                    top = AppDimens.screenMargin + ResultScreenSizes.cardsTop,
                ),
                horizontalArrangement = Arrangement.spacedBy(ResultScreenSizes.cardGap),
                verticalAlignment = Alignment.Top,
            ) {
                SafetyScoreCard(state, Modifier.weight(ResultScreenSizes.leftCardWeight))
                ScoreCard(state, actions, characterAssetKey, stage, Modifier.weight(ResultScreenSizes.rightCardWeight))
            }
            // 장소 선물 팝업: 이 주행에서 처음 한 번. 닫으면 다시 뜨지 않는다 (11/15)
            var giftOpen by remember(state.session.endWallMs) { mutableStateOf(state.placeGifts.isNotEmpty()) }
            state.placeGifts.firstOrNull()?.takeIf { giftOpen }?.let { PlaceGiftPopup(it) { giftOpen = false } }
        } else {
            Text(
                stringResource(R.string.report_empty), style = AppType.smallRegular, color = AppPalette.textSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun tripText(t: TripLine): String =
    if (t.today) stringResource(R.string.drive_result_trip_today, t.time, t.minutes, t.km)
    else stringResource(R.string.drive_result_trip_date, t.date, t.time, t.minutes, t.km)

/** 요약 칸은 한 줄에 둘 */
private const val SUMMARY_COLUMNS = 2

/** 5 safety_score_card: 위 영역(점수·별·보상) / 가운데 선 / 아래 영역(주행 요약) */
@Composable
private fun SafetyScoreCard(state: DriveEndState, modifier: Modifier = Modifier) {
    val session = state.session
    val score = session.score.takeIf { session.valid }
    Column(
        modifier.fillMaxHeight().appShadow(AppShadows.soft, AppShapes.radiusL)
            .clip(AppShapes.radiusL).background(AppPalette.white),
    ) {
        Column(
            Modifier.fillMaxWidth().height(SafetyScoreCardSizes.topAreaHeight).padding(SafetyScoreCardSizes.padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CardTitle(stringResource(R.string.drive_result_score_title), Modifier.fillMaxWidth())
            // 왼쪽 점수 고리(숫자 아래 한마디), 오른쪽 별점·세부 점수 막대·받은 보상 (11/14)
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SafetyScoreCardSizes.ringToSide)) {
                ScoreRing(score, grade = score?.let { stringResource(gradeText(scoreBand(it))) })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SafetyScoreCardSizes.sideGap)) {
                    StarRating(score?.let(::starCount) ?: 0)
                    if (session.valid) for (line in session.lines) SubScoreBar(line)
                    RewardChips(state)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().height(SafetyScoreCardSizes.bottomAreaHeight).dividerOnTop().padding(SafetyScoreCardSizes.padding),
            verticalArrangement = Arrangement.spacedBy(SafetyScoreCardSizes.summaryGap),
        ) {
            CardTitle(stringResource(R.string.drive_result_summary_title), Modifier.fillMaxWidth())
            if (session.valid) {
                for (row in state.summary.chunked(SUMMARY_COLUMNS)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SafetyScoreCardSizes.summaryGap)) {
                        for (line in row) SummaryItemBox(line, Modifier.weight(1f))
                    }
                }
            } else {
                ResultNote(stringResource(R.string.drive_result_invalid))
            }
        }
    }
}

/** 5-4 받은 보상: 포인트 칩 다음 경험치 칩. 받은 것이 없는 칩은 그리지 않는다 */
@Composable
private fun RewardChips(state: DriveEndState) {
    Row(horizontalArrangement = Arrangement.spacedBy(RewardChipSizes.gap)) {
        if (state.coinsGained > 0) RewardChipPoint(stringResource(R.string.drive_result_reward_points, formatPoints(state.coinsGained)))
        state.expReward?.let { RewardChipExp(expText(it)) }
    }
}

@Composable
private fun expText(r: ExpReward): String = when (r) {
    is ExpReward.Percent -> stringResource(R.string.drive_result_reward_exp_percent, r.percent)
    is ExpReward.LevelUp -> stringResource(R.string.drive_result_reward_level_up, r.stage)
    is ExpReward.Points -> stringResource(R.string.drive_result_reward_exp_points, r.xp)
}

@Composable
private fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.height(SafetyScoreCardSizes.titleHeight), style = AppType.smallBold, color = AppPalette.ink,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** 영역 맨 위에 가운데 선(ink 10%)을 겹쳐 그린다 */
private fun Modifier.dividerOnTop(): Modifier = drawBehind {
    drawRect(AppTints.scoreCardDivider, size = Size(size.width, DividerSizes.thickness.toPx()))
}

/**
 * 6 score_card: 머리(그라데이션, 캐릭터 이미지 자리)는 고정이고, 그 아래 흰 영역 전체(제목 줄·분석 글·AI 동의 질문·팁)가 한 덩어리로 스크롤한다.
 * 내용이 영역보다 짧으면 팁이 영역 아래 안쪽 여백에 붙고, 길면 글 아래에서 이어져 스크롤로 닿는다. 정차 중 화면이라 스크롤할 수 있다(디자인 시스템 2절).
 */
@Composable
private fun ScoreCard(state: DriveEndState, actions: DriveEndActions, characterAssetKey: String?, stage: Int, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxHeight().appShadow(AppShadows.soft, AppShapes.radiusL)
            .clip(AppShapes.radiusL).background(AppPalette.surface),
    ) {
        // 카드 전체가 4컷 만화다. 연회색 바탕 위에 흰 칸을 테두리 없이 놓는다 (11/15 팀 피드백). 분석·팁 내용은 대사에 들어간다. AI 사용을 묻는 줄만 맨 아래에
        Column(Modifier.fillMaxSize().padding(ScoreCardSizes.padding)) {
            Row(Modifier.height(ScoreCardSizes.titleRowHeight), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.drive_result_analysis_title), style = AppType.smallBold, color = AppPalette.ink, maxLines = 1)
                if (state.aiGenerated) {
                    Spacer(Modifier.width(ScoreCardSizes.titleToBadge))
                    AiBadge()
                }
            }
            Spacer(Modifier.height(ScoreCardSizes.titleToBody))
            ComicStrip(state.comic, Modifier.fillMaxWidth().weight(1f))
            if (state.askAiConsent) {
                Spacer(Modifier.height(ScoreCardSizes.titleToBody))
                AiConsentAsk(actions)
            }
        }
    }
}

/** 문장 끝(. ! ? …) 뒤 공백에서 나눈다. 빈 줄은 뺀다 */
internal fun sentencesOf(text: String): List<String> =
    text.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

/** 3컷 만화: 굵은 테두리 칸 세 개를 위에서 아래로. 칸마다 그림과 말풍선 대사가 좌우로 놓인다(칸마다 랜덤) */
@Composable
private fun ComicStrip(panels: List<ComicPanel>, modifier: Modifier = Modifier) {
    // 4컷을 2×2 로, 칸 폭·높이를 엇갈려 만화처럼 (11/15). 1행: 좁게 | 넓게, 2행: 넓게 | 좁게
    val rows = panels.chunked(2)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ComicSizes.gap)) {
        rows.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().weight(if (r == 0) ComicSizes.ROW1 else 1f - ComicSizes.ROW1), horizontalArrangement = Arrangement.spacedBy(ComicSizes.gap)) {
                row.forEachIndexed { c, p ->
                    val i = r * 2 + c
                    // 1행: 좁게 | 넓게(2컷, 고양이까지 보이게), 2행: 넓게 | 좁게
                    val wide = (r == 0) != (c == 0)
                    ComicPanelBox(i, p, Modifier.fillMaxHeight().weight(if (wide) ComicSizes.WIDE else 1f - ComicSizes.WIDE))
                }
            }
        }
    }
}

/** 칸마다 말풍선 자리·꼬리 방향·효과음 자리를 다르게 */
/** 칸마다 말풍선 자리·최대 폭·꼬리 가로 위치, 효과음 자리·기울기 */
private class PanelLook(
    val bubble: BiasAlignment, val width: Float, val tailX: Float,
    val sfx: BiasAlignment, val sfxTilt: Float,
)
private val PANEL_LOOKS = listOf(
    // 새 그림(11/15)은 위 1/3 이 비고 로봇 머리가 가운데보다 조금 오른쪽. 말풍선은 위 왼쪽에서 꼬리를 로봇 쪽으로,
    // 효과음은 말풍선과 겹치지 않는 쪽 빈자리에. 아래 줄(3·4컷)은 칸이 높아 말풍선을 머리 가까이로 내린다
    PanelLook(BiasAlignment(-1f, -1f), width = 0.82f, tailX = 0.66f, sfx = BiasAlignment(-0.95f, 0.05f), sfxTilt = -6f),
    PanelLook(BiasAlignment(-1f, -1f), width = 0.9f, tailX = 0.7f, sfx = BiasAlignment(0.95f, -0.15f), sfxTilt = 8f),
    PanelLook(BiasAlignment(-1f, -0.45f), width = 0.7f, tailX = 0.8f, sfx = BiasAlignment(-0.9f, 0.22f), sfxTilt = -5f),
    PanelLook(BiasAlignment(-1f, -1f), width = 0.86f, tailX = 0.62f, sfx = BiasAlignment(-0.95f, 0.1f), sfxTilt = -6f),
)

@Composable
private fun ComicPanelBox(i: Int, p: ComicPanel, modifier: Modifier) {
    val look = PANEL_LOOKS[i % PANEL_LOOKS.size]
    val burst = p.art == ComicArt.STARTLED
    BoxWithConstraints(modifier.clip(AppShapes.radiusM).background(AppPalette.white)) {
        // 그림을 조금 아래로 내려 위쪽 말풍선 자리를 넓힌다(아래 도로 선이 조금 잘린다). 급제동 칸은 아래 고양이가 잘리지 않게 그대로
        AssetImage(
            p.art.path, Modifier.fillMaxSize().graphicsLayer { translationY = if (burst) 0f else size.height * ComicSizes.ART_SHIFT_DOWN },
            BiasAlignment(p.art.biasX, 1f),
        )
        if (burst) FocusLines(Modifier.fillMaxSize())
        val shape = if (burst) burstBubble() else ovalBubble(tailDown = true, tailX = look.tailX)
        Box(
            Modifier.align(look.bubble).padding(ComicSizes.bubbleInset)
                .widthIn(max = maxWidth * if (burst) ComicSizes.BURST_WIDTH else look.width)
                .appShadow(AppShadows.soft, shape)
                .background(AppPalette.white, shape)
                .border(ComicSizes.bubbleEdge, AppPalette.ink, shape)
                .padding(
                    horizontal = if (burst) ComicSizes.burstPadH else ComicSizes.bubblePadH,
                    vertical = if (burst) ComicSizes.burstPadV else ComicSizes.bubblePadV,
                )
                .padding(bottom = if (!burst) ComicSizes.tail else ComicSizes.zero),
        ) {
            // 문장마다 줄을 바꾸고 어절 단위로 끊으며, 가운데 정렬로 말풍선 안에 고르게 (11/15)
            Text(
                sentencesOf(p.line).joinToString("\n"),
                style = AppType.smallBold.copy(lineHeight = ComicSizes.lineHeight, lineBreak = LineBreak.Heading, textAlign = TextAlign.Center),
                color = ComicSizes.bubbleText,
            )
        }
        SfxText(p.art.sfx, Modifier.align(look.sfx).padding(horizontal = ComicSizes.sfxInsetEnd, vertical = ComicSizes.sfxInsetTop), tilt = look.sfxTilt)
        // 컷 번호
        Text(
            "${i + 1}", Modifier.align(Alignment.TopStart).padding(ComicSizes.numberInset)
                .background(AppPalette.ink, AppShapes.pill).padding(horizontal = ComicSizes.numberPadH, vertical = ComicSizes.numberPadV),
            style = AppType.smallBold, color = AppPalette.white,
        )
    }
}

/** 만화 말풍선: 타원 + 오른쪽으로 휜 꼬리(위 또는 아래). 꼬리 자리만큼 아래/위가 비어 있다 */
private fun ovalBubble(tailDown: Boolean, tailX: Float = 0.72f): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val t = with(density) { ComicSizes.tail.toPx() }
        val body = if (tailDown) Rect(0f, 0f, size.width, size.height - t) else Rect(0f, t, size.width, size.height)
        val oval = Path().apply { addOval(body) }
        // 꼬리: 뿌리는 넓고 끝은 뾰족하게, 바깥쪽(오른쪽)으로 살짝 휜다. 뿌리를 몸 안쪽까지 넣어 이음매가 안 보이게 (11/15)
        val x = size.width * tailX
        val root = t * 1.1f
        val tail = Path().apply {
            if (tailDown) {
                val baseY = body.bottom - t * 0.9f
                moveTo(x - root, baseY)
                quadraticBezierTo(x - root * 0.1f, body.bottom + t * 0.35f, x + t * 0.9f, size.height)
                quadraticBezierTo(x + root * 0.35f, body.bottom + t * 0.1f, x + root, baseY)
            } else {
                val baseY = body.top + t * 0.9f
                moveTo(x - root, baseY)
                quadraticBezierTo(x - root * 0.1f, body.top - t * 0.35f, x + t * 0.9f, 0f)
                quadraticBezierTo(x + root * 0.35f, body.top - t * 0.1f, x + root, baseY)
            }
            close()
        }
        return Outline.Generic(Path().apply { op(oval, tail, PathOperation.Union) })
    }
}

/**
 * 집중선(2번 급제동 칸만, 11/15): 칸 가장자리에서 가운데로 모이는 가는 회색 선.
 * 차·고양이가 있는 아래 절반은 비우고 위쪽 절반에만, 가장자리 띠 안에서 끝나 그림을 덮지 않는다
 */
@Composable
private fun FocusLines(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val c = Offset(size.width / 2, size.height * 0.5f)
        val rng = kotlin.random.Random(7)
        val n = 34
        for (k in 0 until n) {
            // 위쪽 반원(왼쪽 수평 → 위 → 오른쪽 수평 조금 위)만
            val a = Math.toRadians(182.0 + 170.0 * k / n + rng.nextDouble(-2.0, 2.0))
            val dx = kotlin.math.cos(a).toFloat(); val dy = kotlin.math.sin(a).toFloat()
            // 칸 가장자리까지의 거리
            val tx = if (dx > 0) (size.width - c.x) / dx else if (dx < 0) -c.x / dx else Float.MAX_VALUE
            val ty = if (dy > 0) (size.height - c.y) / dy else if (dy < 0) -c.y / dy else Float.MAX_VALUE
            val edge = minOf(tx, ty)
            val inner = edge * (0.72f + rng.nextFloat() * 0.12f)
            drawLine(
                ComicSizes.focusLine, Offset(c.x + dx * inner, c.y + dy * inner), Offset(c.x + dx * edge, c.y + dy * edge),
                strokeWidth = (1.2f + rng.nextFloat() * 1.6f) * density, cap = StrokeCap.Round,
            )
        }
    }
}

/** 깜짝 말풍선: 뾰족뾰족 터지는 모양 */
private fun burstBubble(): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cx = size.width / 2; val cy = size.height / 2
        val spikes = 14
        val path = Path()
        for (k in 0 until spikes * 2) {
            val a = Math.PI * k / spikes - Math.PI / 2
            val f = if (k % 2 == 0) 1f else 0.84f
            val x = cx + (cx * f * kotlin.math.cos(a)).toFloat(); val y = cy + (cy * f * kotlin.math.sin(a)).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** 장소 선물 팝업: 받은 배경 그림을 크게, "남산 도착 선물!" 과 아이템 이름 */
@Composable
private fun PlaceGiftPopup(gift: core.flow.PlaceGift, onClose: () -> Unit) {
    val scrim = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(AppTints.scrim).clickable(scrim, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(PlaceGiftSizes.width).appShadow(AppShadows.soft, AppShapes.radiusXl)
                .background(AppPalette.white, AppShapes.radiusXl).padding(PlaceGiftSizes.padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.place_gift_badge, gift.placeName), Modifier.background(AppPalette.blueSoft, AppShapes.pill)
                .padding(horizontal = PlaceGiftSizes.badgePadH, vertical = PlaceGiftSizes.badgePadV), style = AppType.smallBold, color = AppPalette.primaryBlue)
            Spacer(Modifier.height(PlaceGiftSizes.gap))
            ItemArt(gift.imageKey, contentDescription = gift.itemName, modifier = Modifier.size(PlaceGiftSizes.art))
            Spacer(Modifier.height(PlaceGiftSizes.gap))
            Text(stringResource(R.string.place_gift_title, gift.itemName), style = AppType.largeBold, color = AppPalette.ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(PlaceGiftSizes.titleToBody))
            Text(stringResource(R.string.place_gift_body), style = AppType.smallRegular, color = AppPalette.textSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(PlaceGiftSizes.gap))
            ButtonDark(stringResource(R.string.place_gift_ok), onClose)
        }
    }
}

/** 만화 효과음 글자: 진한 글자에 흰 테두리, 굵은 기울임, 살짝 회전. 노랑 대신 차분하게 (11/15) */
@Composable
private fun SfxText(text: String, modifier: Modifier, tilt: Float) {
    val style = AppType.smallBold.copy(fontSize = ComicSizes.sfxSize, fontStyle = FontStyle.Italic, letterSpacing = ComicSizes.sfxSpacing)
    Box(modifier.graphicsLayer { rotationZ = tilt }) {
        Text(text, style = style.copy(drawStyle = Stroke(width = ComicSizes.sfxStroke, join = StrokeJoin.Round)), color = AppPalette.white)
        Text(text, style = style, color = ComicSizes.sfxFill)
    }
}

/** assets 그림을 칸에 꽉 채워 그린다(넘치는 부분은 잘린다). 읽는 동안·없으면 빈칸 */
@Composable
private fun AssetImage(path: String, modifier: Modifier, align: BiasAlignment = BiasAlignment(0.8f, 0.7f)) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    Box(modifier.background(AppPalette.white)) {
        bitmap?.let { Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = align) }
    }
}


/** 점수대 한마디 */
private fun gradeText(band: ScoreBand): Int = when (band) {
    ScoreBand.GREEN -> R.string.drive_result_grade_great
    ScoreBand.YELLOW -> R.string.drive_result_grade_ok
    ScoreBand.RED -> R.string.drive_result_grade_low
}

/** 세부 점수 한 줄: 이름, 둥근 막대(점수대 색), 점수 */
@Composable
private fun SubScoreBar(line: SubScoreLine) {
    val color = scoreBand(line.score).color()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SafetyScoreCardSizes.barGap)) {
        Text(stringResource(subScoreLabel(line.kind)), Modifier.width(SafetyScoreCardSizes.barLabel), style = AppType.smallRegular, color = AppPalette.textSecondary, maxLines = 1)
        Box(Modifier.weight(1f).height(SafetyScoreCardSizes.barHeight).background(AppPalette.line, AppShapes.pill)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(line.score.coerceIn(0, 100) / 100f).background(color, AppShapes.pill))
        }
        Text(line.score.toString(), Modifier.width(SafetyScoreCardSizes.barValue), style = AppType.smallBold, color = AppPalette.ink, textAlign = TextAlign.End)
    }
}

private fun subScoreLabel(k: SubScore): Int = when (k) {
    SubScore.ACCEL -> R.string.sub_score_accel
    SubScore.BRAKE -> R.string.sub_score_brake
    SubScore.MANNER -> R.string.sub_score_manner
}

