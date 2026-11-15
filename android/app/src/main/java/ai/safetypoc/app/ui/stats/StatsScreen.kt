package ai.safetypoc.app.ui.stats

import ai.safetypoc.app.ui.theme.AppDimens

import ai.safetypoc.app.ui.report.ResultScreenSizes

import kotlin.math.roundToInt
import androidx.compose.foundation.layout.offset
import ai.safetypoc.app.ui.report.sentencesOf
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.layout.heightIn
import ai.safetypoc.app.ui.report.DriveMap
import ai.safetypoc.app.R
import ai.safetypoc.app.ui.component.BackButton
import ai.safetypoc.app.ui.component.CoinBar
import ai.safetypoc.app.ui.component.pressable
import ai.safetypoc.app.ui.report.scoreBand
import ai.safetypoc.app.ui.report.ScoreBand
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppType
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import engine.EventType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 통계 탭 (DashboardView1·2·3 로우파이 시안) [가안].
 * 왼쪽: 달력 + 고른 날의 주행 목록. 오른쪽: 이번 주 한마디 + 누적 안전점수(오늘) 또는 그날 분석(다른 날) + 1주일간 주행 기록.
 * 주행을 누르면 주행 상세. 뒤로 가기는 목록으로.
 */
@Composable
fun StatsScreen(state: StatsState, actions: StatsActions, modifier: Modifier = Modifier) {
    val detail = state.detail
    if (detail != null) {
        BackHandler { actions.closeDrive() }
        DriveDetailView(detail, state.coins, actions, modifier)
        return
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(StatsSizes.columnGap)) {
        // 왼쪽 열은 접힌 탭 버튼 아래부터, 오른쪽 열은 맨 위부터 (11/15)
        Column(Modifier.width(StatsSizes.leftWidth).fillMaxHeight().padding(top = ResultScreenSizes.cardsTop)) {
            CalendarCard(state, actions)
            Spacer(Modifier.height(StatsSizes.cardGap))
            Text(
                if (state.selected == state.today) stringResource(R.string.stats_today_drives)
                else stringResource(R.string.stats_day_drives, state.selected.year, state.selected.monthValue, state.selected.dayOfMonth),
                style = AppType.smallBold, color = AppPalette.ink,
            )
            Spacer(Modifier.height(StatsSizes.rowGap))
            if (state.dayDrives.isEmpty()) {
                Text(stringResource(R.string.stats_no_drives), style = AppType.smallRegular, color = AppPalette.textTertiary)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(StatsSizes.rowGap)) {
                    items(state.dayDrives, key = { it.id }) { DriveRowItem(it) { actions.openDrive(it.id) } }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(StatsSizes.cardGap)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                WeekCommentBox(state.weekComment, Modifier.weight(1f))
                CoinBar(state.coins)
            }
            val analysis = state.dayAnalysis
            if (analysis == null) CumulativeCard(state.cumulative) else DayAnalysisCard(analysis)
            WeekChartCard(state.chart, state.today, Modifier.weight(1f))
        }
    }
}

/* ---------- 달력 ---------- */

/**
 * 달력 [가안] 스위스 미니멀: 큰 월 숫자 + 영문 월 이름, 가는 괘선, 요일은 작은 회색 글자.
 * 주행한 날은 칸 전체를 파랑 한 톤으로 칠하고, 많이 달린 날일수록 진하다(아래 적음~많음 범례). 칸 아래 \"n회\".
 * 오늘은 파란 굵은 숫자와 짧은 막대, 고른 날은 칸 테두리.
 */
@Composable
private fun CalendarCard(state: StatsState, actions: StatsActions) {
    Column(Modifier.fillMaxWidth().background(AppPalette.white, AppShapes.radiusXl).border(1.dp, AppPalette.line, AppShapes.radiusXl).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("%02d".format(state.month.monthValue), style = MONTH_NUMBER, color = AppPalette.ink)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(bottom = 8.dp)) {
                Text(state.month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).uppercase(), style = MONTH_NAME, color = AppPalette.ink)
                Text(state.month.year.toString(), style = AppType.smallRegular, color = AppPalette.textTertiary)
            }
            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArrowButton("‹", actions::prevMonth); ArrowButton("›", actions::nextMonth)
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(AppPalette.ink))
        Row(Modifier.fillMaxWidth().height(StatsSizes.weekdayRow)) {
            WEEK.forEach { d ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(
                        d.getDisplayName(TextStyle.SHORT, Locale.KOREAN), style = WEEKDAY,
                        color = if (d == DayOfWeek.SUNDAY) AppPalette.red else AppPalette.textTertiary,
                    )
                }
            }
        }
        val first = state.month.atDay(1)
        val lead = first.dayOfWeek.value % 7   // 일요일 시작
        val cells = (0 until 42).map { first.minusDays(lead.toLong()).plusDays(it.toLong()) }
        cells.chunked(7).filter { w -> w.any { it.month == first.month } }.forEach { week ->
            Row(Modifier.fillMaxWidth()) { week.forEach { DayCell(it, state, actions) } }
            Box(Modifier.fillMaxWidth().height(1.dp).background(AppPalette.line))
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(AppPalette.line))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.stats_heat_less), style = AppType.smallRegular, color = AppPalette.textTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..HEAT_STEPS).forEach { Box(Modifier.size(22.dp, 14.dp).background(heatColor(it), AppShapes.radiusS)) }
            }
            Text(stringResource(R.string.stats_heat_more), style = AppType.smallRegular, color = AppPalette.textTertiary)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.stats_month_drives, state.drivesPerDay.filterKeys { it.month == state.month.month && it.year == state.month.year }.values.sum()),
                style = AppType.smallRegular, color = AppPalette.textTertiary)
        }
    }
}

@Composable
private fun RowScope.DayCell(date: LocalDate, state: StatsState, actions: StatsActions) {
    val inMonth = date.month == state.month.month
    val n = if (inMonth) state.drivesPerDay[date] ?: 0 else 0
    val selected = inMonth && date == state.selected
    val today = date == state.today
    // 칸 전체를 파랑 한 톤으로 칠한다. 많이 달린 날일수록 진하게(1번 · 2번 · 3번 이상)
    val fill = if (n > 0) heatColor(n) else Color.Transparent
    Box(
        // 칠한 칸은 모서리를 둥글게, 칸 사이를 조금 띄운다 (11/14)
        Modifier.weight(1f).height(StatsSizes.calendarCell).padding(StatsSizes.calendarCellGap)
            .clip(StatsSizes.calendarCellShape)
            .background(fill)
            .then(if (selected) Modifier.border(2.5.dp, AppPalette.primaryBlue, StatsSizes.calendarCellShape) else Modifier)
            .pressable(remember { MutableInteractionSource() }, enabled = inMonth) { actions.selectDate(date) },
    ) {
        // 오늘: 파란 굵은 숫자 + 아래 짧은 파란 막대
        Column(Modifier.align(Alignment.TopCenter).padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.dayOfMonth.toString(), style = AppType.smallBold,
                color = when {
                    !inMonth -> AppPalette.line
                    n >= DARK_FROM -> AppPalette.white
                    today -> AppPalette.primaryBlue
                    date.dayOfWeek == DayOfWeek.SUNDAY -> AppPalette.red
                    else -> AppPalette.ink
                },
            )
            if (today) Box(Modifier.padding(top = 3.dp).size(18.dp, 3.dp).background(if (n >= DARK_FROM) AppPalette.white else AppPalette.primaryBlue, AppShapes.pill))
        }
        if (n > 0) {
            Text(
                stringResource(R.string.stats_cell_drives, n), Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                style = CELL_NOTE, color = if (n >= DARK_FROM) AppPalette.white else AppPalette.primaryBlue,
            )
        }
    }
}

@Composable
private fun ArrowButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).border(1.dp, AppPalette.line, CircleShape).clip(CircleShape)
            .pressable(remember { MutableInteractionSource() }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = AppType.mediumBold, color = AppPalette.ink) }
}

private val WEEK = listOf(
    DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
)

/** 달력 칸 색: primary_blue 한 톤, 하루 주행 수(1 · 2 · 3 · 4번 이상)에 따라 진하게 */
private const val HEAT_STEPS = 4
private val HEAT_ALPHA = listOf(0.14f, 0.30f, 0.55f, 0.85f)
/** 이 횟수부터 칸이 진해 숫자를 흰색으로 */
private const val DARK_FROM = 3
private fun heatColor(n: Int): Color = AppPalette.primaryBlue.copy(alpha = HEAT_ALPHA[(n - 1).coerceIn(0, HEAT_STEPS - 1)])

private val MONTH_NUMBER = androidx.compose.ui.text.TextStyle(fontSize = 64.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp)
private val MONTH_NAME = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
private val CELL_NOTE = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
private val WEEKDAY = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)

/* ---------- 주행 목록 ---------- */

@Composable
private fun DriveRowItem(row: DriveRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(StatsSizes.rowHeight).background(AppPalette.surface, AppShapes.radiusL)
            .pressable(remember { MutableInteractionSource() }, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            Modifier.size(StatsSizes.rowScore).background(AppPalette.white, AppShapes.radiusM),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(row.score?.toString() ?: "-", style = AppType.largeBold, color = row.score?.let { scoreColor(it) } ?: AppPalette.textTertiary)
            Text(stringResource(R.string.stats_score_label), style = AppType.smallRegular.copy(fontSize = AppType.smallRegular.fontSize * 0.6f), color = AppPalette.textTertiary)
        }
        // 문장 설명 대신 언제부터 언제까지, 거리·시간·경로만 (11/15)
        Column(Modifier.weight(1f)) {
            Text("${clock(row.startWallMs)} – ${clock(row.endWallMs)}", style = AppType.mediumBold, color = AppPalette.ink, maxLines = 1)
            Text(rowMeta(row), style = AppType.smallRegular, color = AppPalette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", style = AppType.largeBold, color = AppPalette.textTertiary)
    }
}

@Composable
private fun rowMeta(row: DriveRow): String {
    val km = stringResource(R.string.stats_km, row.distanceKm)
    val minutes = ((row.endWallMs - row.startWallMs) / 60_000L).toInt()
    val route = if (row.origin != null || row.destination != null) " · ${row.origin ?: "-"} → ${row.destination ?: "-"}" else ""
    return "${minutes}분 · $km$route"
}

/* ---------- 오른쪽 카드 ---------- */

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().background(AppPalette.surface, AppShapes.radiusXl).padding(StatsSizes.cardPadding)) { content() }
}

/** 이번 주 한마디: 잘리지 않게 두 줄까지. 왼쪽에 "이번 주" 표 (11/14) */
@Composable
private fun WeekCommentBox(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.heightIn(min = StatsSizes.pillHeight).background(AppPalette.blueSoft, AppShapes.radiusL)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.stats_this_week),
            Modifier.background(AppPalette.primaryBlue, AppShapes.pill).padding(horizontal = 12.dp, vertical = 4.dp),
            style = AppType.smallBold, color = AppPalette.white, maxLines = 1,
        )
        // 문장이 끝나면 줄을 바꾼다 (11/14)
        Text(sentencesOf(text).joinToString("\n"), style = AppType.smallBold, color = AppPalette.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** 누적 안전점수: 점수 고리 + 지난주 대비 칩, 아래 이번 주 감점 행동 세 칸 (11/14) */
@Composable
private fun CumulativeCard(c: Cumulative) {
    Card {
        // 왼쪽: 제목·지난주 대비 아래 큰 점수 고리. 오른쪽: 이번 주 감점 행동 세 칸을 세로로 (11/15)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.stats_cumulative), style = AppType.mediumBold, color = AppPalette.ink)
                Ring(c.score, StatsSizes.ringLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.stats_vs_last_week), style = AppType.smallRegular, color = AppPalette.textTertiary)
                    val d = c.weekDelta
                    val (bg, fg) = when { d == null -> AppPalette.surface to AppPalette.textTertiary; d >= 0 -> AppPalette.greenSoft to AppPalette.greenDark; else -> AppPalette.redSoft to AppPalette.red }
                    Text(
                        when { d == null -> "-"; d >= 0 -> "▲ ${d}점"; else -> "▼ ${-d}점" },
                        Modifier.background(bg, AppShapes.pill).padding(horizontal = 14.dp, vertical = 4.dp),
                        style = AppType.smallBold, color = fg,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatKind.entries.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth().background(AppPalette.white, AppShapes.radiusL).padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(StatsSizes.dot).background(k.color(), CircleShape)); Spacer(Modifier.width(10.dp))
                        val label = if (k == StatKind.NO_SIGNAL) stringResource(R.string.stats_no_signal_short) else kindLabel(k)
                        Text(label, Modifier.weight(1f), style = AppType.smallRegular, color = AppPalette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.stats_times, c.weekCounts[k] ?: 0), style = AppType.mediumBold, color = AppPalette.ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayAnalysisCard(a: DayAnalysis) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.stats_day_analysis), style = AppType.mediumBold, color = AppPalette.ink)
                Text(
                    stringResource(R.string.stats_analysis_badge),
                    Modifier.background(AppPalette.blueSoft, AppShapes.pill).padding(horizontal = 14.dp, vertical = 4.dp),
                    style = AppType.smallBold, color = AppPalette.primaryBlue,
                )
            }
            Text(sentencesOf(a.summary).joinToString("\n"), style = AppType.smallRegular, color = AppPalette.ink)
            Column(Modifier.fillMaxWidth().background(AppPalette.white, AppShapes.radiusL).padding(20.dp)) {
                Text(stringResource(R.string.stats_next_tip), style = AppType.smallBold, color = AppPalette.primaryBlue)
                Text(a.tip, style = AppType.smallRegular, color = AppPalette.ink)
            }
        }
    }
}

@Composable
private fun WeekChartCard(chart: WeekChart, today: LocalDate, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxSize()) {
            Text(stringResource(R.string.stats_week_chart), style = AppType.mediumBold, color = AppPalette.ink)
            // 한 줄 요약: 이번 주 평균과 가장 잘한 날
            val scored = chart.days.indices.filter { chart.scores.getOrNull(it) != null }
            if (scored.isNotEmpty()) {
                val avg = scored.map { chart.scores[it]!! }.average().roundToInt()
                val best = scored.maxBy { chart.scores[it]!! }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.stats_week_summary, avg, chart.days[best].dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)),
                    style = AppType.smallRegular, color = AppPalette.textSecondary,
                )
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().weight(1f).background(AppPalette.white, AppShapes.radiusL).padding(20.dp)) {
                ScoreBars(chart, today, Modifier.fillMaxWidth().weight(1f))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    chart.days.forEach { d ->
                        val isToday = d == today
                        Text(
                            d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN), Modifier.weight(1f),
                            style = if (isToday) AppType.smallBold else AppType.smallRegular,
                            color = if (isToday) AppPalette.primaryBlue else AppPalette.textSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 1주일 점수 막대 (11/15 깔끔하게): 날마다 평균 안전점수를 둥근 막대 하나로. 앱 파랑 한 톤 — 평소 날은 연한 파랑,
 * 오늘은 진한 파랑. 막대 위 작은 점수, 오늘은 굵게. 이번 주 평균은 가는 점선과 "평균 N" 표시. 주행 없는 날은 작은 점
 */
@Composable
private fun ScoreBars(chart: WeekChart, today: LocalDate, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val n = chart.days.size.coerceAtLeast(1)
        val labelSpace = 30.dp
        fun frac(sc: Int) = ((sc - SCORE_FLOOR).toFloat() / (100 - SCORE_FLOOR)).coerceIn(0.06f, 1f)
        val scored = chart.scores.filterNotNull()
        val avg = scored.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        Canvas(Modifier.fillMaxSize()) {
            val step = size.width / n
            val barW = (step * BAR_WIDTH).coerceAtMost(28.dp.toPx())
            val top = labelSpace.toPx(); val bottom = size.height
            chart.days.forEachIndexed { i, d ->
                val cx = step * i + step / 2
                val sc = chart.scores.getOrNull(i)
                if (sc == null) {
                    drawCircle(AppPalette.line, 3.dp.toPx(), Offset(cx, bottom - 3.dp.toPx()))
                    return@forEachIndexed
                }
                val h = (bottom - top) * frac(sc)
                val color = if (d == today) AppPalette.primaryBlue else AppPalette.primaryBlue.copy(alpha = 0.22f)
                drawRoundRect(color, Offset(cx - barW / 2, bottom - h), Size(barW, h), CornerRadius(barW / 2))
            }
            avg?.let {
                val y = bottom - (bottom - top) * frac(it.roundToInt())
                drawLine(
                    AppPalette.textTertiary, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                )
            }
        }
        val step = maxWidth / n
        chart.days.forEachIndexed { i, d ->
            val sc = chart.scores.getOrNull(i) ?: return@forEachIndexed
            val y = maxHeight - (maxHeight - labelSpace) * frac(sc) - 26.dp
            val isToday = d == today
            Text(
                "$sc", Modifier.offset(x = step * i, y = y).width(step),
                style = if (isToday) AppType.smallBold else AppType.smallRegular,
                color = if (isToday) AppPalette.primaryBlue else AppPalette.textSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        avg?.let {
            val y = maxHeight - (maxHeight - labelSpace) * frac(it.roundToInt()) - 22.dp
            Text(
                stringResource(R.string.stats_week_avg, it.roundToInt()),
                Modifier.align(Alignment.TopEnd).offset(y = y).background(AppPalette.white).padding(horizontal = 4.dp),
                style = AppType.smallRegular.copy(fontSize = AppType.smallRegular.fontSize * 0.75f), color = AppPalette.textTertiary,
            )
        }
    }
}
/** 점수 막대 세로축 바닥 점수: 이보다 낮으면 가장 짧은 막대 */
private const val SCORE_FLOOR = 50
private const val BAR_WIDTH = 0.42f

/* ---------- 주행 상세 ---------- */

/**
 * 주행 상세 (11/15 개편): 왼쪽은 점수 요약 아래 지도, 오른쪽은 운전 습관 4칸 · 사건 목록.
 * 지도를 누르면 화면 가득 큰 지도가 열리고 끌기·확대로 사건 자리를 본다
 */
@Composable
private fun DriveDetailView(d: DriveDetail, coins: Int, actions: StatsActions, modifier: Modifier) {
    var bigMap by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(StatsSizes.cardGap)) {
        // 제목 줄은 접힌 탭 버튼 오른쪽, 내용은 그 아래 왼쪽 끝부터 (11/15)
        Row(Modifier.fillMaxWidth().padding(start = ResultScreenSizes.contentStart - AppDimens.screenMargin), verticalAlignment = Alignment.CenterVertically) {
            BackButton(actions::closeDrive, stringResource(R.string.stats_back))
            Text(stringResource(R.string.stats_detail_title), Modifier.weight(1f), style = AppType.mediumBold, color = AppPalette.ink)
            CoinBar(coins)
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(StatsSizes.cardGap)) {
            Column(Modifier.weight(DETAIL_LEFT_WEIGHT).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(StatsSizes.detailGap)) {
                SummaryCard(d)
                MapPanel(d, Modifier.fillMaxWidth().weight(1f), onOpen = { bigMap = true })
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(StatsSizes.detailGap)) {
                HabitTiles(d.habits)
                Card(Modifier.fillMaxWidth().weight(1f)) { EventList(d) }
            }
        }
    }
    if (bigMap && d.places.isNotEmpty()) BigMapDialog(d) { bigMap = false }
}
private const val DETAIL_LEFT_WEIGHT = 1.15f

/** 작은 지도: 끌기·확대는 막고, 누르면 큰 지도. 좌표가 없으면 구간 막대 */
@Composable
private fun MapPanel(d: DriveDetail, modifier: Modifier, onOpen: () -> Unit) {
    Box(modifier.clip(AppShapes.radiusL).background(AppPalette.surface)) {
        if (d.places.isNotEmpty()) {
            DriveMap(d.places, emptyList(), Modifier.fillMaxSize(), gestures = false)
            // 지도 클릭 리스너는 큰 지도를 한 번 닫은 뒤 다시 안 받는 때가 있어, 위에 투명한 누름 칸을 덮는다
            Box(Modifier.fillMaxSize().clickable(onClick = onOpen))
            MapHint(Modifier.align(Alignment.BottomEnd).padding(16.dp))
        } else {
            Box(Modifier.fillMaxSize().padding(StatsSizes.cardPadding), contentAlignment = Alignment.Center) { RouteBar(d) }
        }
        RouteStrip(d, Modifier.align(Alignment.TopStart).padding(16.dp))
    }
}

@Composable
private fun MapHint(modifier: Modifier) {
    Text(
        stringResource(R.string.stats_map_open), modifier.background(AppPalette.ink.copy(alpha = 0.72f), AppShapes.pill)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = AppType.smallRegular, color = AppPalette.white,
    )
}

/** 화면 가득 큰 지도: 끌기·두 손가락 확대. 위에 출발 → 도착 띠, 오른쪽 위 닫기 */
@Composable
private fun BigMapDialog(d: DriveDetail, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(StatsSizes.bigMapMargin).clip(AppShapes.radiusL).background(AppPalette.white)) {
            var zoomBy by remember { mutableStateOf<((Double) -> Unit)?>(null) }
            DriveMap(d.places, emptyList(), Modifier.fillMaxSize(), onZoomReady = { zoomBy = it })
            RouteStrip(d, Modifier.align(Alignment.TopStart).padding(20.dp))
            // 확대·축소 버튼: 두 손가락이 어려운 차 화면·에뮬레이터에서도 (11/15)
            Column(Modifier.align(Alignment.BottomEnd).padding(20.dp), verticalArrangement = Arrangement.spacedBy(StatsSizes.zoomGap)) {
                ZoomButton("+") { zoomBy?.invoke(1.0) }
                ZoomButton("−") { zoomBy?.invoke(-1.0) }
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(20.dp).size(StatsSizes.closeButton).shadow(6.dp, CircleShape)
                    .background(AppPalette.white, CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Text("✕", style = AppType.mediumBold, color = AppPalette.ink) }
            FlagLegend(d, Modifier.align(Alignment.BottomStart).padding(20.dp))
        }
    }
}

@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(StatsSizes.closeButton).shadow(6.dp, CircleShape).background(AppPalette.white, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = AppType.largeBold, color = AppPalette.ink) }
}

/** 깃발 뜻: 이번 주행에 나온 사건 종류만 색과 이름 */
@Composable
private fun FlagLegend(d: DriveDetail, modifier: Modifier) {
    Row(
        modifier.shadow(6.dp, AppShapes.radiusL).background(AppPalette.white, AppShapes.radiusL).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        d.places.map { it.type }.distinctBy { it.flagColor() }.forEach { Legend(it.flagColor(), eventLabel(it)) }
    }
}

/** 지도 위 흰 띠: 출발 시각·장소 → 도착 시각·장소 */
@Composable
private fun RouteStrip(d: DriveDetail, modifier: Modifier) {
    Row(
        modifier.shadow(6.dp, AppShapes.radiusL).background(AppPalette.white, AppShapes.radiusL)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        RouteEnd(d.row.origin ?: stringResource(R.string.stats_origin), clock(d.row.startWallMs), AppPalette.primaryBlue)
        Box(Modifier.width(36.dp).height(2.dp).background(AppPalette.line, AppShapes.pill))
        RouteEnd(d.row.destination ?: stringResource(R.string.stats_destination), clock(d.row.endWallMs), AppPalette.ink)
    }
}

@Composable
private fun RouteEnd(place: String, time: String, dot: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(StatsSizes.dot).background(dot, CircleShape)); Spacer(Modifier.width(10.dp))
        Column {
            Text(place, style = AppType.smallBold, color = AppPalette.ink)
            Text(time, style = AppType.smallRegular, color = AppPalette.textTertiary)
        }
    }
}

/** 점수 고리 + 날짜·시각 + 거리 + 달성 미션 */
@Composable
private fun SummaryCard(d: DriveDetail) {
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Ring(d.row.score, StatsSizes.ringDetail, compact = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 날짜 한 줄, 시각·거리 한 줄. 미션 칩 옆에서 날짜 줄이 꺾이지 않게 나눈다
                Text(detailDate(d.row), style = AppType.mediumBold, color = AppPalette.ink, maxLines = 1)
                Text(
                    "${clock(d.row.startWallMs)} – ${clock(d.row.endWallMs)} · " + stringResource(R.string.stats_km, d.row.distanceKm),
                    style = AppType.smallRegular, color = AppPalette.textSecondary, maxLines = 1,
                )
            }
            Column(
                Modifier.background(AppPalette.white, AppShapes.radiusL).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.stats_missions_done), style = AppType.smallRegular, color = AppPalette.textTertiary)
                Text(stringResource(R.string.stats_count, d.missions), style = AppType.mediumBold, color = AppPalette.ink)
            }
        }
    }
}

/** 운전 습관: 회색 카드 안에 흰 칸 2×2 (이름 · 값 · 3단계 막대) */
@Composable
private fun HabitTiles(habits: List<Habit>) {
    Card(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(StatsSizes.innerGap)) {
            Text(stringResource(R.string.stats_habits), style = AppType.mediumBold, color = AppPalette.ink)
            habits.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(StatsSizes.innerGap)) {
                    pair.forEach { h -> HabitTile(h, Modifier.weight(1f).fillMaxHeight()) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
@Composable
private fun HabitTile(h: Habit, modifier: Modifier) {
    Column(modifier.background(AppPalette.white, AppShapes.radiusL).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(habitLabel(h.kind), style = AppType.smallRegular, color = AppPalette.textSecondary, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(h.value, style = AppType.mediumBold, color = AppPalette.ink)
        Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
        // 3단계 막대: 나쁨 · 주의 · 좋음 칸 중 해당 칸만 색
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StatsSizes.habitGap)) {
            for (i in 0..2) {
                Box(
                    Modifier.weight(1f).height(StatsSizes.habitBar)
                        .background(if (i == h.level) habitColor(i) else AppPalette.line, AppShapes.pill),
                )
            }
        }
    }
}

/** 사건 목록: 회색 카드 안에 흰 줄 (색 점 · 종류 · 시각 · 도로명) */
@Composable
private fun EventList(d: DriveDetail) {
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.stats_events_title, d.events.size), style = AppType.mediumBold, color = AppPalette.ink)
        Spacer(Modifier.height(14.dp))
        if (d.events.isEmpty()) {
            Text(stringResource(R.string.stats_no_events), style = AppType.smallRegular, color = AppPalette.textTertiary)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(StatsSizes.innerGap)) {
                items(d.events) { e ->
                    Row(
                        Modifier.fillMaxWidth().background(AppPalette.white, AppShapes.radiusL).padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(StatsSizes.dot).background(e.type.flagColor(), CircleShape)); Spacer(Modifier.width(12.dp))
                        Text(eventLabel(e.type), Modifier.weight(1f), style = AppType.smallBold, color = AppPalette.ink)
                        Text(clock(e.atWallMs) + (e.place?.let { " · $it" } ?: ""), style = AppType.smallRegular, color = AppPalette.textTertiary)
                    }
                }
            }
        }
    }
}
private const val ROUTE_MARK_WIDTH = 0.08f

/** 구간 막대: 바탕은 안전(초록), 감점 행동 자리에 위험(빨강)·주의(주황) 칸. 사건 좌표가 없을 때 지도 대신 */
@Composable
private fun RouteBar(d: DriveDetail) {
    Column {
        Canvas(Modifier.fillMaxWidth().height(StatsSizes.routeBar)) {
            val r = size.height / 2
            drawRoundRect(AppPalette.green, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            d.marks.forEach { m ->
                val w = size.width * ROUTE_MARK_WIDTH
                val x = (size.width * m.at - w / 2).coerceIn(0f, size.width - w)
                drawRoundRect(m.type.routeColor(), Offset(x, 0f), Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(r))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Legend(AppPalette.green, stringResource(R.string.stats_safe)); Legend(AppPalette.red, stringResource(R.string.stats_danger))
            Legend(AppPalette.orange, stringResource(R.string.stats_caution))
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(StatsSizes.dot).background(color, CircleShape)); Spacer(Modifier.width(6.dp))
        Text(label, style = AppType.smallRegular, color = AppPalette.textSecondary)
    }
}

/* ---------- 공통 ---------- */

/** 점수 고리: 점수 ÷ 100 만큼 채우고 가운데 숫자. 색은 점수대(50 이하 빨강, 80 이하 노랑, 그 위 초록) */
@Composable
private fun Ring(score: Int?, size: Dp, compact: Boolean = false) {
    val color = score?.let { scoreColor(it) } ?: AppPalette.textTertiary
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = (if (compact) StatsSizes.ringStrokeCompact else StatsSizes.ringStroke).toPx()
            val tl = Offset(s / 2, s / 2); val arc = Size(this.size.width - s, this.size.height - s)
            drawArc(AppPalette.line, 0f, 360f, false, tl, arc, style = Stroke(s))
            val sweep = 360f * (score ?: 0).coerceIn(0, 100) / 100f
            if (sweep > 0f) drawArc(color, -90f, sweep, false, tl, arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        // 작은 고리(주행 상세)는 숫자만. "/ 100" 까지 넣으면 고리 안이 비좁다 (11/15)
        if (compact) {
            Text(score?.toString() ?: "-", style = AppType.mediumBold, color = color)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(score?.toString() ?: "-", style = AppType.largeBold, color = color)
                Text(stringResource(R.string.stats_of_100), style = AppType.smallRegular, color = AppPalette.textTertiary)
            }
        }
    }
}

private fun scoreColor(score: Int): Color = when (scoreBand(score)) {
    ScoreBand.RED -> AppPalette.red
    ScoreBand.YELLOW -> AppPalette.orange
    ScoreBand.GREEN -> AppPalette.greenDark
}

private fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(CLOCK)
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun detailDate(r: DriveRow): String {
    val d = Instant.ofEpochMilli(r.startWallMs).atZone(ZoneId.systemDefault())
    return "%04d.%02d.%02d (%s)".format(d.year, d.monthValue, d.dayOfMonth, d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN))
}

@Composable
private fun kindLabel(k: StatKind) = stringResource(
    when (k) { StatKind.ACCEL -> R.string.stats_accel; StatKind.BRAKE -> R.string.stats_brake; StatKind.NO_SIGNAL -> R.string.stats_no_signal },
)

@Composable
private fun habitLabel(k: HabitKind) = stringResource(
    when (k) {
        HabitKind.ACCEL -> R.string.stats_accel; HabitKind.BRAKE -> R.string.stats_brake
        HabitKind.NO_SIGNAL -> R.string.stats_no_signal; HabitKind.NIGHT -> R.string.stats_night
    },
)

@Composable
private fun eventLabel(t: EventType) = stringResource(
    when (t) {
        EventType.HARSH_ACCEL -> R.string.stats_accel
        EventType.HARSH_BRAKE -> R.string.stats_brake
        EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_WRONG_SIGNAL -> R.string.stats_no_signal
        EventType.LANE_CHANGE_SIGNALED -> R.string.event_lane_change_signaled
        EventType.WEAVING_EST -> R.string.event_weaving
        EventType.SPEEDING -> R.string.event_speeding
    },
)
