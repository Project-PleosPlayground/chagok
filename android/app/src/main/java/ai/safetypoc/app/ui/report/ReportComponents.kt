package ai.safetypoc.app.ui.report

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTints
import ai.safetypoc.app.ui.theme.AppType
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 점수 구간의 색 (디자인 시스템 6절 score_ring) */
internal fun ScoreBand.color(): Color = when (this) {
    ScoreBand.RED -> AppPalette.red
    ScoreBand.YELLOW -> AppPalette.yellow
    ScoreBand.GREEN -> AppTints.resultGreen
}

internal fun SummaryStatus.color(): Color = when (this) {
    SummaryStatus.CAUTION -> AppPalette.red
    SummaryStatus.GOOD -> AppTints.resultGreen
}

internal fun SummaryStatus.softColor(): Color = when (this) {
    SummaryStatus.CAUTION -> AppPalette.redSoft
    SummaryStatus.GOOD -> AppPalette.greenSoft
}

/**
 * score_ring: 바탕 고리(line) 위에 12시에서 시계 방향으로 점수 ÷ 100 만큼 채우고, 가운데에 점수 숫자.
 * [score] 가 null 이면(점수를 매기지 않은 주행) 채움 없이 "-" 를 보인다.
 */
@Composable
fun ScoreRing(score: Int?, modifier: Modifier = Modifier, grade: String? = null) {
    val color = score?.let { scoreBand(it).color() } ?: AppPalette.textTertiary
    Box(modifier.size(ScoreRingSizes.diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(ScoreRingSizes.diameter)) {
            val stroke = ScoreRingSizes.stroke.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(color.copy(alpha = ScoreRingSizes.trackAlpha), 0f, ScoreRingSizes.fullSweep, false, topLeft, arcSize, style = Stroke(stroke))
            val sweep = ScoreRingSizes.fullSweep * (score ?: 0).coerceIn(0, ScoreRingSizes.fullScore) / ScoreRingSizes.fullScore
            if (sweep > 0f) drawArc(color, ScoreRingSizes.startAngle, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = score?.toString() ?: stringResource(R.string.drive_result_score_none),
                style = AppType.largeBold.copy(fontSize = ScoreRingSizes.numberSize, lineHeight = ScoreRingSizes.numberLineHeight),
                color = color,
            )
            grade?.let { Text(it, style = AppType.smallBold, color = color) }
        }
    }
}

/** star_rating: 별 5개 중 [filled] 개를 yellow 로, 나머지는 platform_gray 로 */
@Composable
fun StarRating(filled: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(StarRatingSizes.gap)) {
        for (i in 0 until STAR_COUNT) {
            Canvas(Modifier.size(StarRatingSizes.star)) {
                drawPath(starPath(size.minDimension), if (i < filled) AppPalette.yellow else AppPalette.platformGray, style = Fill)
            }
        }
    }
}

private fun starPath(side: Float): Path {
    val outer = side / 2
    val inner = outer * StarRatingSizes.innerRatio
    val steps = StarRatingSizes.points * 2
    return Path().apply {
        for (i in 0 until steps) {
            val r = if (i % 2 == 0) outer else inner
            val angle = -PI / 2 + i * PI / StarRatingSizes.points
            val x = outer + (r * cos(angle)).toFloat()
            val y = outer + (r * sin(angle)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/** reward_chip_point: yellow 동그라미 + 글자. 바탕 orange_soft */
@Composable
fun RewardChipPoint(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.height(RewardChipSizes.height).clip(AppShapes.pill).background(AppPalette.orangeSoft)
            .padding(horizontal = RewardChipSizes.padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(RewardChipSizes.dot).clip(AppShapes.circle).background(AppPalette.yellow))
        Spacer(Modifier.width(RewardChipSizes.dotToText))
        Text(text, style = AppType.smallBold, color = AppTints.rewardPointText, maxLines = 1)
    }
}

/** reward_chip_exp: 글자만. 바탕 blue_soft */
@Composable
fun RewardChipExp(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.height(RewardChipSizes.height).clip(AppShapes.pill).background(AppPalette.blueSoft)
            .padding(horizontal = RewardChipSizes.padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AppType.smallBold, color = AppPalette.primaryBlue, maxLines = 1)
    }
}

/** status_badge: "주의"(red) / "좋아요"(green) */
@Composable
fun StatusBadge(status: SummaryStatus, modifier: Modifier = Modifier) {
    Box(
        modifier.height(SummaryBoxSizes.badgeHeight).clip(AppShapes.radiusS).background(status.softColor())
            .padding(horizontal = SummaryBoxSizes.badgePadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(if (status == SummaryStatus.CAUTION) R.string.status_caution else R.string.status_good),
            style = AppType.smallBold, color = status.color(), maxLines = 1,
        )
    }
}

/** summary_icon: 상태 색 원 + 흰 아이콘 */
@Composable
fun SummaryIcon(kind: SummaryKind, status: SummaryStatus, modifier: Modifier = Modifier) {
    Box(modifier.size(SummaryBoxSizes.iconCircle).clip(AppShapes.circle).background(status.color()), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(SummaryBoxSizes.iconGlyph)) {
            val line = Stroke(size.minDimension * SummaryGlyphs.strokeRatio, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (kind) {
                SummaryKind.ACCEL -> drawPolyline(SummaryGlyphs.chevronUp, line)
                SummaryKind.BRAKE -> drawPolyline(SummaryGlyphs.chevronDown, line)
                SummaryKind.NO_SIGNAL -> SummaryGlyphs.shuffleLines.forEach { drawPolyline(it, line) }
                SummaryKind.SPEEDING -> drawGauge(line)
            }
        }
    }
}

private fun DrawScope.at(p: Pair<Float, Float>) = Offset(p.first * size.width, p.second * size.height)

private fun DrawScope.drawPolyline(points: List<Pair<Float, Float>>, style: Stroke) {
    val path = Path().apply { points.forEachIndexed { i, p -> at(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
    drawPath(path, AppPalette.white, style = style)
}

private fun DrawScope.drawGauge(style: Stroke) {
    val center = at(SummaryGlyphs.gaugeCenter)
    val r = SummaryGlyphs.gaugeRadius * size.width
    drawArc(
        AppPalette.white, SummaryGlyphs.gaugeStartAngle, SummaryGlyphs.gaugeSweep, false,
        Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = style,
    )
    drawLine(AppPalette.white, center, at(SummaryGlyphs.gaugeNeedleTip), style.width, StrokeCap.Round)
}

/** summary_item_box: 아이콘 + 이름·횟수 + 배지. 좋아요는 회색(surface) 바탕·ink 10% 테두리, 주의는 연한 빨강 바탕. 횟수는 상태색으로 크게 (11/14) */
@Composable
fun SummaryItemBox(line: SummaryLine, modifier: Modifier = Modifier) {
    val status = line.status
    Row(
        // 주의 칸만 아주 연한 빨강 바탕·테두리로 강조하고, 좋아요 칸은 처음처럼 회색 바탕 (11/14)
        modifier.height(SummaryBoxSizes.height).clip(AppShapes.radiusM)
            .background(if (status == SummaryStatus.CAUTION) status.softColor() else AppPalette.surface)
            .border(SummaryBoxSizes.border, if (status == SummaryStatus.CAUTION) status.color().copy(alpha = 0.25f) else AppTints.summaryItemBorder, AppShapes.radiusM)
            .padding(SummaryBoxSizes.padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryIcon(line.kind, status)
        Spacer(Modifier.width(SummaryBoxSizes.iconToText))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(summaryLabel(line.kind)), style = AppType.smallRegular, color = AppPalette.textSecondary,
                maxLines = SummaryBoxSizes.labelMaxLines, overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.summary_count, line.count), style = AppType.mediumBold, color = status.color(), maxLines = 1)
        }
        Spacer(Modifier.width(SummaryBoxSizes.textToBadge))
        StatusBadge(status)
    }
}

private fun summaryLabel(kind: SummaryKind) = when (kind) {
    SummaryKind.ACCEL -> R.string.summary_accel
    SummaryKind.BRAKE -> R.string.summary_brake
    SummaryKind.NO_SIGNAL -> R.string.summary_no_signal
    SummaryKind.SPEEDING -> R.string.summary_speeding
}

/** ai_badge: bolt 아이콘 + "AI 분석". 바탕 primary_blue 10% */
@Composable
fun AiBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.height(ScoreCardSizes.badgeHeight).clip(AppShapes.pill).background(AppTints.aiBadge)
            .padding(horizontal = ScoreCardSizes.badgePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(ScoreCardSizes.badgeIcon)) {
            val path = Path().apply { BadgeGlyphs.bolt.forEachIndexed { i, p -> at(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }; close() }
            drawPath(path, AppPalette.primaryBlue, style = Fill)
        }
        Spacer(Modifier.width(ScoreCardSizes.badgeIconToText))
        Text(stringResource(R.string.ai_badge), style = AppType.smallBold, color = AppPalette.primaryBlue, maxLines = 1)
    }
}

/** tip_box: pin 원 + "다음 주행 팁" 아래 글. 바탕 blue_soft */
@Composable
fun TipBox(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.height(ScoreCardSizes.tipHeight).clip(AppShapes.radiusM).background(AppPalette.blueSoft).padding(ScoreCardSizes.tipPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PinCircle(ScoreCardSizes.tipPin)
            Spacer(Modifier.width(ScoreCardSizes.tipPinToText))
            Text(stringResource(R.string.drive_result_tip_title), style = AppType.smallBold, color = AppPalette.ink, maxLines = 1)
        }
        Spacer(Modifier.height(ScoreCardSizes.tipTitleToBody))
        Row {
            Spacer(Modifier.width(ScoreCardSizes.tipPin + ScoreCardSizes.tipPinToText))
            Text(
                text, style = AppType.smallRegular.copy(lineHeight = ScoreCardSizes.lineHeight), color = AppTints.scoreCardBody,
                maxLines = ScoreCardSizes.tipMaxLines, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PinCircle(diameter: Dp) {
    Canvas(Modifier.size(diameter)) {
        drawCircle(AppPalette.primaryBlue)
        val head = at(BadgeGlyphs.pinHead)
        val headRadius = BadgeGlyphs.pinHeadRadius * size.width
        drawCircle(AppPalette.white, headRadius, head)
        val tail = Path().apply { BadgeGlyphs.pinTail.forEachIndexed { i, p -> at(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }; close() }
        drawPath(tail, AppPalette.white, style = Fill)
        drawCircle(AppPalette.primaryBlue, BadgeGlyphs.pinHoleRadius * size.width, head)
    }
}

/** 가운데 정렬 안내 글 한 줄(점수 없는 주행) */
@Composable
internal fun ResultNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth(), style = AppType.smallRegular, color = AppPalette.textSecondary, textAlign = TextAlign.Center)
}
