package ai.safetypoc.app.ui.debug

import ai.safetypoc.app.AppConfig
import ai.safetypoc.app.DriveUiState
import ai.safetypoc.app.ui.component.ConfirmPopup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/** 1 m/s = 3.6 km/h (단위 환산). */
private const val KMH_PER_MPS = 3.6f

/** 확인 팝업을 띄운 초기화 */
private enum class PendingReset { PROFILES, ALL_DATA }

/** 시연·디버그용 화면 한 장: 엔진 시작·종료, 점수, 최근 이벤트, 저장 데이터 초기화 */
@Composable
fun DriveDebugScreen(
    state: DriveUiState, onStart: () -> Unit, onStop: () -> Unit, onGrantPoints: () -> Unit,
    onGrantXp: () -> Unit, onReplayDemoDrive: (long: Boolean, namsan: Boolean) -> Unit, onSeedHistory: () -> Unit, onTrimDrives: () -> Unit, onLowBattery: () -> Unit, onResetStage: () -> Unit, onResetProfiles: () -> Unit, onResetAllData: () -> Unit, modifier: Modifier = Modifier,
) {
    var pending by rememberSaveable { mutableStateOf<PendingReset?>(null) }
    Box(modifier) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("안전점수 ${state.partialScore}", style = MaterialTheme.typography.displayMedium)
            Text("세션: ${state.sessionState}", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStart, enabled = !state.running) { Text("시작") }
                // 주행 중에는 숨긴다(자리는 남긴다). 정차 중에만 보이고 누를 수 있다
                Button(onClick = onStop, enabled = state.running && state.stopped, modifier = Modifier.alpha(if (state.stopped) 1f else 0f)) { Text("종료") }
                Button(onClick = onGrantPoints) { Text("포인트 +${AppConfig.DEBUG_GRANT_POINTS}") }
                Button(onClick = onGrantXp) { Text("경험치 +${AppConfig.DEBUG_GRANT_XP}") }
                Button(onClick = { onReplayDemoDrive(false, false) }) { Text("시연 주행 재생") }
                Button(onClick = { onReplayDemoDrive(true, false) }) { Text("20분 주행 재생") }
                Button(onClick = { onReplayDemoDrive(true, true) }) { Text("남산 도착 주행") }
                Button(onClick = onSeedHistory) { Text("지난 기록 채우기") }
                Button(onClick = onTrimDrives) { Text("오늘·어제 3개만") }
                Button(onClick = onLowBattery, enabled = state.running) { Text("꼬르륵") }
            }
            // 주행 중에는 디버그 탭에 올 수 없다. 엔진 세션이 열려 있으면 SafetyApp 이 초기화하지 않는다
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onResetStage) { Text("시연 단계로") }
                Button(onClick = { pending = PendingReset.PROFILES }) { Text("프로필 초기화") }
                Button(onClick = { pending = PendingReset.ALL_DATA }) { Text("데이터 전체 초기화") }
            }
            Text("마지막 발화: ${state.lastCue}")
            Text("마지막 신호: ${state.lastSignal}", style = MaterialTheme.typography.bodySmall)
            state.lastResult?.let { r ->
                Text("지난 세션: ${r.score}점 (가속 ${r.accelScore} / 제동 ${r.brakeScore} / 매너 ${r.mannerScore}), " +
                    "%.1f km, 유효=${r.valid}".format(r.distanceKm))
            }
            Text("최근 이벤트", style = MaterialTheme.typography.titleMedium)
            state.recentEvents.forEach { ev ->
                Text("${ev.type}  단계 ${ev.severity}  %.0f km/h".format(ev.speedMps * KMH_PER_MPS))
            }
        }
        when (pending) {
            PendingReset.PROFILES -> ConfirmPopup(
                title = "프로필 초기화",
                body = "프로필·코인·아이템·성장을 지웁니다. 주행 기록은 남아요.",
                confirmText = "초기화", dismissText = "취소",
                onConfirm = { pending = null; onResetProfiles() }, onDismiss = { pending = null },
            )
            PendingReset.ALL_DATA -> ConfirmPopup(
                title = "데이터 전체 초기화",
                body = "저장된 데이터를 모두 지우고 앱을 다시 켭니다.",
                confirmText = "초기화", dismissText = "취소",
                onConfirm = { pending = null; onResetAllData() }, onDismiss = { pending = null },
            )
            null -> Unit
        }
    }
}
