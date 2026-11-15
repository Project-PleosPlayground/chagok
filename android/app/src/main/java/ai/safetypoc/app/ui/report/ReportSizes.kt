package ai.safetypoc.app.ui.report

import ai.safetypoc.app.ui.nav.TabFrameSizes
import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.CoinBarSizes
import ai.safetypoc.app.ui.theme.TabButtonSizes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 주행 결과 화면 배치 값 (영역사양_주행결과화면). 번호는 영역 사양의 요소 번호다 */
object ResultScreenSizes {
    /** 2 tab_bar 오른쪽 끝에서 카드까지 [제안]: 카드 둘(672 + 16 + 636)이 2 오른쪽 ~ 오른쪽 여백 a 를 꼭 채우는 값 */
    val menuToCards = 40.dp
    /** 창 왼쪽 끝에서 3 제목과 5 카드 왼쪽 끝까지 */
    /** 탭을 작은 버튼으로 접어 두므로(11/14) 그 폭만큼만 비킨다 */
    val contentStart = AppDimens.screenMargin + TabFrameSizes.compactWidth + menuToCards
    /** 3 제목 줄 높이: 4 coin_bar 와 세로 중앙 */
    val headerHeight = CoinBarSizes.height
    /** 카드 위 끝: 접힌 탭 버튼(높이 tab_button) 아래 (11/15) */
    val cardsTop = TabButtonSizes.height + 16.dp
    /** 3 제목 아래에서 5·6 카드 위까지 */
    val headerToCards = 48.dp
    /** 3 제목과 부제 사이 */
    val titleToSubtitle = 16.dp
    /** 5·6 카드 사이 */
    val cardGap = 16.dp
    /** 5·6 카드 높이. 스크롤 없이 고정 */
    val cardHeight = 904.dp
    /** 5 카드 폭 (디자인 시스템 6절 safety_score_card). 창 폭이 달라도 두 카드는 이 비율로 나눈다 */
    const val leftCardWeight = 700f
    /** 6 카드 폭 (디자인 시스템 6절 score_card) */
    const val rightCardWeight = 860f
}

/** 5 safety_score_card 안 배치 (디자인 시스템 6절) */
object SafetyScoreCardSizes {
    /** 안쪽 여백 (네 변) */
    val padding = 24.dp
    /** 위 영역(제목 ~ 받은 보상) 높이. 가운데 선이 이 아래 경계에 있다 */
    val topAreaHeight = 456.dp
    /** 아래 영역(주행 요약) 높이 */
    val bottomAreaHeight = 448.dp
    /** 제목 높이 */
    val titleHeight = 32.dp
    /** 5-1 제목 아래에서 5-2 점수 고리까지 */
    val titleToRing = 32.dp
    /** 5-2 점수 고리 아래에서 5-3 별점까지 */
    val ringToStars = 24.dp
    /** 5-3 별점 아래에서 5-4 받은 보상까지 */
    val starsToChips = 8.dp
    /** 5-6 제목 아래에서 5-7 요약 칸까지, 요약 칸 사이 가로·세로 */
    val summaryGap = 16.dp
    /** 점수 고리 ~ 오른쪽 열, 오른쪽 열 줄 사이, 세부 점수 막대 [가안] (11/14) */
    val ringToSide = 28.dp
    val sideGap = 14.dp
    val barGap = 10.dp
    val barLabel = 56.dp
    val barValue = 44.dp
    val barHeight = 10.dp
}

/** score_ring 크기 (디자인 시스템 6절) */
object ScoreRingSizes {
    /** 지름 */
    val diameter = 240.dp
    /** 선 두께 */
    val stroke = 16.dp
    /** 가운데 점수 숫자 크기. 글자 표(5절)에 없는 크기 [제안] */
    val numberSize = 80.sp
    /** 점수 숫자 줄 높이 */
    val numberLineHeight = 96.sp
    /** 점수 0~100 중 가득 찬 고리의 점수 */
    const val fullScore = 100
    /** 12시 방향의 각도(도). 여기서 시계 방향으로 채운다 */
    const val startAngle = -90f
    /** 한 바퀴 각도(도) */
    const val fullSweep = 360f
    /** 바탕 고리: 점수대 색을 옅게 */
    const val trackAlpha = 0.15f
}

/** star_rating 크기 (디자인 시스템 6절) */
object StarRatingSizes {
    /** 별 한 변 */
    val star = 32.dp
    /** 별 사이 */
    val gap = 4.dp
    /** 별의 안쪽 꼭짓점 반지름 ÷ 바깥 꼭짓점 반지름 */
    const val innerRatio = 0.4f
    /** 별 꼭짓점 수 */
    const val points = 5
}

/** reward_chip_point, reward_chip_exp 크기 (디자인 시스템 6절) */
object RewardChipSizes {
    /** 칩 높이 */
    val height = 40.dp
    /** 칩 사이 */
    val gap = 16.dp
    /** 칩 안 왼쪽·오른쪽 여백 */
    val padding = 16.dp
    /** 포인트 동그라미 지름 */
    val dot = 20.dp
    /** 동그라미와 글자 사이 */
    val dotToText = 8.dp
}

/** summary_item_box, status_badge, summary_icon 크기 (디자인 시스템 6절) */
object SummaryBoxSizes {
    /** 칸 높이. 폭은 두 칸이 가로를 나눠 갖는다(304) */
    val height = 168.dp
    /** 칸 안쪽 여백 */
    val padding = 16.dp
    /** 테두리 두께 */
    val border = 1.dp
    /** 아이콘 원 지름 */
    val iconCircle = 40.dp
    /** 원 안 흰 아이콘 한 변 */
    val iconGlyph = 24.dp
    /** 아이콘 원과 이름 글 사이 */
    val iconToText = 12.dp
    /** 이름 글과 배지 사이 */
    val textToBadge = 8.dp
    /** 배지 높이 */
    val badgeHeight = 40.dp
    /** 배지 안 왼쪽·오른쪽 여백 */
    val badgePadding = 12.dp
    /** 이름 글 최대 줄 수 */
    const val labelMaxLines = 2
}

/** 요약 아이콘 모양. 점은 아이콘 한 변을 1로 본 위치 (x, y) */
object SummaryGlyphs {
    /** 선 두께 ÷ 한 변 */
    const val strokeRatio = 0.12f
    val chevronUp = listOf(0.25f to 0.62f, 0.5f to 0.38f, 0.75f to 0.62f)
    val chevronDown = listOf(0.25f to 0.38f, 0.5f to 0.62f, 0.75f to 0.38f)
    /** 교차하는 두 선과 화살촉 둘 */
    val shuffleLines = listOf(
        listOf(0.2f to 0.35f, 0.45f to 0.35f, 0.6f to 0.65f, 0.8f to 0.65f),
        listOf(0.2f to 0.65f, 0.45f to 0.65f, 0.6f to 0.35f, 0.8f to 0.35f),
        listOf(0.7f to 0.27f, 0.8f to 0.35f, 0.7f to 0.43f),
        listOf(0.7f to 0.57f, 0.8f to 0.65f, 0.7f to 0.73f),
    )
    /** 속도계: 반원 호의 중심·반지름과 바늘 끝 */
    val gaugeCenter = 0.5f to 0.64f
    const val gaugeRadius = 0.32f
    val gaugeNeedleTip = 0.65f to 0.42f
    const val gaugeStartAngle = 180f
    const val gaugeSweep = 180f
}

/** ai_badge, tip_box 와 6 score_card 안 배치 (디자인 시스템 6절) */
object ScoreCardSizes {
    /** 안쪽 여백 */
    val padding = 24.dp
    /** 6-1 머리(그라데이션) 높이 */
    val headHeight = 456.dp
    /** 머리 안 캐릭터 그림 둘레 여백 [가안] */
    val headArtPadding = 32.dp
    /** 6-2 제목 줄 높이 */
    val titleRowHeight = 40.dp
    /** 6-2 "종합 분석" 과 ai_badge 사이 */
    val titleToBadge = 8.dp
    /** 6-2 제목 줄 아래에서 6-3 분석 글까지 */
    val titleToBody = 16.dp
    /** 분석 글·팁 글 줄 높이 */
    val lineHeight = 32.sp
    /** 종합 분석 문장 사이 [가안] */
    val sentenceGap = 10.dp
    /** 스크롤 영역 오른쪽 끝(카드 오른쪽 끝)에서 scrollbar 까지 [임시] */
    val scrollbarInset = 8.dp
    /** ai_badge 높이 */
    val badgeHeight = 40.dp
    /** ai_badge 안 왼쪽·오른쪽 여백 */
    val badgePadding = 12.dp
    /** ai_badge bolt 아이콘 한 변 */
    val badgeIcon = 24.dp
    /** ai_badge 아이콘과 글자 사이 */
    val badgeIconToText = 4.dp
    /** 6-4 tip_box 높이 */
    val tipHeight = 136.dp
    /** tip_box 안쪽 여백 */
    val tipPadding = 16.dp
    /** tip_box pin 원 지름 */
    val tipPin = 32.dp
    /** pin 원과 글자 사이 */
    val tipPinToText = 8.dp
    /** tip_box 제목과 글 사이 */
    val tipTitleToBody = 8.dp
    /** tip_box 글 최대 줄 수 */
    const val tipMaxLines = 2
}

/** bolt, pin 아이콘 모양. 점은 아이콘 한 변을 1로 본 위치 (x, y) */
object BadgeGlyphs {
    val bolt = listOf(0.58f to 0.1f, 0.22f to 0.56f, 0.47f to 0.56f, 0.4f to 0.92f, 0.78f to 0.42f, 0.53f to 0.42f)
    /** pin: 위 원의 중심·반지름, 아래 삼각형, 가운데 흰 점 반지름 */
    val pinHead = 0.5f to 0.4f
    const val pinHeadRadius = 0.28f
    val pinTail = listOf(0.28f to 0.55f, 0.72f to 0.55f, 0.5f to 0.92f)
    const val pinHoleRadius = 0.1f
}

/** 3컷 만화 배치 값 [가안] */
internal object ComicSizes {
    val panelHeight = 184.dp
    val art = 164.dp
    val gap = 12.dp
    val inner = 10.dp
    val frame = 2.dp
    val shape = RoundedCornerShape(14.dp)
    /** 말풍선 선 굵기: 모든 말풍선 같게, 얇게 (11/15) */
    val bubbleEdge = 1.2.dp
    val bubbleShape = RoundedCornerShape(18.dp)
    val bubblePad = 18.dp
    /** 말풍선 안 글자 둘레 여백(가로, 세로). 글자에 달라붙지 않게 넉넉히 (11/14) */
    val bubblePadH = 28.dp
    val bubblePadV = 22.dp
    val bubbleRadius = 20.dp
    val tail = 16.dp
    val zero = 0.dp
    /** 장면 칸 위 말풍선: 왼쪽 여백, 칸 폭에 대한 최대 폭 (11/15) */
    val bubbleInset = 14.dp
    const val BUBBLE_WIDTH = 0.62f
    /** 그림을 칸 높이의 이만큼 아래로 내린다 (말풍선 자리) */
    const val ART_SHIFT_DOWN = 0.1f
    /** 2번 칸 집중선 색 */
    val focusLine = Color(0xFFB8BCC4)
    /** 4컷 배치: 1행 높이 비율, 넓은 칸 폭 비율 (11/15) */
    const val ROW1 = 0.47f
    const val WIDE = 0.58f
    /** 깜짝 말풍선 안 여백 */
    val burstPadH = 52.dp
    val burstPadV = 42.dp
    const val BURST_WIDTH = 0.9f
    /** 효과음 글자: 크기, 테두리 두께(px), 채움색, 기울기, 자리 */
    val sfxSize = 30.sp
    val sfxSpacing = (-0.5).sp
    const val sfxStroke = 9f
    val sfxFill = Color(0xFF1C1C1E)
    const val SFX_TILT = 8f
    val sfxInsetEnd = 18.dp
    val sfxInsetTop = 6.dp
    /** 컷 번호 */
    val numberInset = 10.dp
    val numberPadH = 10.dp
    val numberPadV = 2.dp
    /** 말풍선 글자: 진한 남색 */
    val bubbleText = Color(0xFF1B2A4A)
    val lineHeight = 30.sp
}

/** 장소 선물 팝업 (11/15) */
object PlaceGiftSizes {
    val width = 520.dp
    val padding = 36.dp
    val art = 260.dp
    val gap = 20.dp
    val titleToBody = 8.dp
    val badgePadH = 18.dp
    val badgePadV = 8.dp
}
