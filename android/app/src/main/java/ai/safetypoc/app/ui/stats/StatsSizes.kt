package ai.safetypoc.app.ui.stats

import ai.safetypoc.app.ui.theme.AppPalette
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import engine.EventType

/** 통계 탭 배치 값 [가안] (DashboardView 로우파이 시안) */
object StatsSizes {
    val leftWidth = 560.dp
    val columnGap = 32.dp
    val cardPadding = 28.dp
    val cardGap = 24.dp
    val calendarCell = 84.dp
    val dayCircle = 36.dp
    val calendarHeader = 56.dp
    val weekdayRow = 36.dp
    val todayDot = 32.dp
    val rowHeight = 104.dp
    val rowGap = 12.dp
    val rowScore = 76.dp
    val ringSmall = 160.dp
    /** 누적 안전점수 큰 고리 (11/15) */
    val ringLarge = 200.dp
    val ringStroke = 14.dp
    val ringDetail = 96.dp
    val ringStrokeCompact = 9.dp
    val chartHeight = 300.dp
    val pillHeight = 64.dp
    val routeBar = 18.dp
    /** 달력 칸 둘레 여백(칸 사이의 절반) [가안] */
    val calendarCellGap = 3.dp
    /** 달력 칠한 칸 모서리 [가안] */
    val calendarCellShape = RoundedCornerShape(10.dp)
    /** 주행 상세 지도 높이 [가안] */
    val routeMap = 220.dp
    val habitBar = 6.dp
    val habitGap = 6.dp
    val dot = 10.dp
    val missionBox = 200.dp
    /** 주행 상세 오른쪽 칸 사이 */
    val detailGap = 16.dp
    /** 회색 카드 안 흰 칸 사이 */
    val innerGap = 10.dp
    /** 큰 지도 둘레 여백 */
    val bigMapMargin = 40.dp
    val closeButton = 56.dp
    val zoomGap = 12.dp
    /** 하루 주행 수에 따른 달력 칸 진하기: 1번, 2번, 3번 이상 (시안 투명도 16·24·48) */
    val heat = listOf(0.18f, 0.30f, 0.48f)
}

/** 감점 행동 색: 급가속 빨강, 급감속 파랑, 방향지시등 미시행 초록 (시안 추이그래프) */
fun StatKind.color(): Color = when (this) {
    StatKind.ACCEL -> AppPalette.red
    StatKind.BRAKE -> AppPalette.primaryBlue
    StatKind.NO_SIGNAL -> AppPalette.greenDark
}

/** 주행 구간 막대 색: 급제동·급가속은 위험(빨강), 깜빡이 없는 차선변경은 주의(주황) */
fun EventType.routeColor(): Color = when (this) {
    EventType.HARSH_ACCEL, EventType.HARSH_BRAKE -> AppPalette.red
    else -> AppPalette.orange
}

/** 지도 깃발과 같은 사건 종류별 색 (DriveMap.flagOf 와 짝) */
fun EventType.flagColor(): Color = when (this) {
    EventType.HARSH_BRAKE -> AppPalette.red
    EventType.HARSH_ACCEL -> AppPalette.orange
    EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_WRONG_SIGNAL -> FLAG_PINK
    EventType.LANE_CHANGE_SIGNALED -> AppPalette.green
    EventType.WEAVING_EST, EventType.SPEEDING -> AppPalette.primaryBlue
}
private val FLAG_PINK = Color(0xFFFF5FA8)

/** 운전 습관 3단계 색: 0 나쁨 빨강, 1 주의 주황, 2 좋음 초록 */
fun habitColor(level: Int): Color = when (level) { 0 -> AppPalette.red; 1 -> AppPalette.orange; else -> AppPalette.green }
