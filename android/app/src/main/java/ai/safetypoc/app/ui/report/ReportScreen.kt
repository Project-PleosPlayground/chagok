package ai.safetypoc.app.ui.report

import ai.safetypoc.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import engine.EventType

/** 리포트 (기능 뼈대, 디자인 없음): 마지막 주행의 점수와 세부 점수 */
@Composable
fun ReportScreen(state: ReportState, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.report_title), style = MaterialTheme.typography.headlineMedium)
        state.session?.let { SessionScoresView(it) } ?: Text(stringResource(R.string.report_empty))
        HotspotsView(state)
    }
}

/** 자주 놀라는 곳 (#13). [임시] 디자인 전: 지도(핀·반경 원) 아래 목록. 장소 이름은 기록에 도로명이 있을 때만 */
@Composable
private fun HotspotsView(state: ReportState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.report_hotspots_title), style = MaterialTheme.typography.titleLarge)
        if (state.places.isEmpty()) { Text(stringResource(R.string.report_hotspots_none)); return }
        if (state.hotspots.isEmpty()) Text(stringResource(R.string.report_hotspots_not_yet, state.places.size))
        DriveMap(state.places, state.hotspots, Modifier.fillMaxWidth().height(MAP_HEIGHT).clip(RoundedCornerShape(16.dp)))
        state.hotspots.forEachIndexed { i, h ->
            val kinds = h.types.entries.sortedByDescending { it.value }.map { stringResource(R.string.report_event_count, eventLabel(it.key), it.value) }.joinToString(" · ")
            val name = h.roadName ?: stringResource(R.string.report_hotspot_unnamed, i + 1)
            Text(stringResource(R.string.report_hotspot_line, name, h.drives, kinds), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SessionScoresView(s: SessionScores) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (s.valid) stringResource(R.string.report_score, s.score) else stringResource(R.string.report_invalid),
            style = MaterialTheme.typography.displaySmall,
        )
        Text(stringResource(R.string.report_distance, s.distanceKm, s.durationMs / MS_PER_MINUTE), style = MaterialTheme.typography.bodyMedium)
        if (s.valid) for (line in s.lines) {
            val events = line.events.map { stringResource(R.string.report_event_count, eventLabel(it.type), it.count) }.joinToString(" · ")
            Text(stringResource(R.string.report_sub_score, subScoreLabel(line.kind), line.score, events), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun subScoreLabel(k: SubScore) = stringResource(
    when (k) {
        SubScore.ACCEL -> R.string.sub_score_accel
        SubScore.BRAKE -> R.string.sub_score_brake
        SubScore.MANNER -> R.string.sub_score_manner
    },
)

@Composable
private fun eventLabel(t: EventType) = stringResource(
    when (t) {
        EventType.HARSH_ACCEL -> R.string.event_harsh_accel
        EventType.HARSH_BRAKE -> R.string.event_harsh_brake
        EventType.LANE_CHANGE_SIGNALED -> R.string.event_lane_change_signaled
        EventType.LANE_CHANGE_NO_SIGNAL -> R.string.event_lane_change_no_signal
        EventType.LANE_CHANGE_WRONG_SIGNAL -> R.string.event_lane_change_wrong_signal
        EventType.WEAVING_EST -> R.string.event_weaving
        EventType.SPEEDING -> R.string.event_speeding
    },
)

private const val MS_PER_MINUTE = 60_000L

/** 지도 높이 [임시] */
private val MAP_HEIGHT = 480.dp
