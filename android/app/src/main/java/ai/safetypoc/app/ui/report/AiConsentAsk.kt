package ai.safetypoc.app.ui.report

import ai.safetypoc.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * 첫 주행 끝에 한 번: AI 한마디를 켤지. 보내는 항목은 펼쳐서 본다 (#101).
 * [임시] 영역사양_주행결과화면에 이 질문의 자리와 모양이 없어 기능 뼈대(Material 기본 버튼) 그대로 종합 분석 카드 안에 둔다.
 */
@Composable
fun AiConsentAsk(actions: DriveEndActions) {
    var more by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_consent_ask), style = MaterialTheme.typography.titleMedium)
        if (more) Text(stringResource(R.string.ai_consent_notice), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { actions.answerAiConsent(true) }) { Text(stringResource(R.string.ai_consent_ask_yes)) }
            OutlinedButton(onClick = { actions.answerAiConsent(false) }) { Text(stringResource(R.string.ai_consent_ask_no)) }
            if (!more) TextButton(onClick = { more = true }) { Text(stringResource(R.string.ai_consent_ask_more)) }
        }
    }
}
