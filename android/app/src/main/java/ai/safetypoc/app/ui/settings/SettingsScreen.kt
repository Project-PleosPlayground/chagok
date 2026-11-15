package ai.safetypoc.app.ui.settings

import ai.safetypoc.app.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import ai.safetypoc.app.ui.theme.appShadow
import ai.safetypoc.app.ui.theme.AppGradients
import ai.safetypoc.app.ui.theme.LocalNarrowWindow
import ai.safetypoc.app.ui.theme.AppShadows
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.report.sentencesOf
import ai.safetypoc.app.ui.component.pressable
import ai.safetypoc.app.ui.art.CharacterStill
import ai.safetypoc.app.BuildConfig
import ai.safetypoc.app.ui.component.ButtonDark
import ai.safetypoc.app.ui.component.ButtonLight
import ai.safetypoc.app.ui.component.ButtonSize
import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * 설정 (#101, 11/14 다시 그림). 앱 톤(흰 바탕 · 연한 회색 판 · 파랑 강조)에 맞춘 애플 설정풍.
 * 왼쪽: 내 캐릭터·프로필 카드와 앱 정보. 오른쪽: 켜고 끄는 항목 묶음(파란 스위치). 주행 중이면 맨 위 안내와 함께 모두 비활성이다.
 * AI 주행 리포트는 짧은 설명 아래 "보내는 것 자세히"를 눌러 전체 고지를 펼친다.
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    characterAssetKey: String? = null,
    characterName: String? = null,
    stage: Int = 1,
    /** 캐릭터 이름 바꾸기 팝업을 연다. null 이면 이름 바꾸기 버튼이 없다 */
    onRenameCharacter: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SettingsSizes.sectionGap)) {
        Text(stringResource(R.string.settings_title), style = AppType.largeBold, color = AppPalette.ink)
        if (state.driving) {
            Text(
                stringResource(R.string.settings_driving), Modifier.fillMaxWidth().background(AppPalette.redSoft, AppShapes.radiusL)
                    .padding(horizontal = SettingsSizes.rowPadH, vertical = SettingsSizes.bannerPadV),
                style = AppType.smallBold, color = AppPalette.red,
            )
        }
        val profile = @Composable {
            ProfileCard(characterAssetKey, characterName, stage, enabled = !state.driving, onOpen = actions::openProfiles, onRename = onRenameCharacter)
        }
        val appInfo = @Composable {
            Group {
                InfoRow(stringResource(R.string.settings_app_name), stringResource(R.string.settings_app_value))
                Divider()
                InfoRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
            }
        }
        val toggles = @Composable { SettingToggles(state, actions) }
        if (LocalNarrowWindow.current) {
            // 1칸 창: 한 열로 쌓고 통째로 세로로 넘긴다. 순서 프로필 → 설정 → 앱 정보 [제안]
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SettingsSizes.sectionGap),
            ) {
                profile()
                toggles()
                appInfo()
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(SettingsSizes.sectionGap)) {
            Column(Modifier.width(SettingsSizes.leftWidth), verticalArrangement = Arrangement.spacedBy(SettingsSizes.sectionGap)) {
                profile()
                appInfo()
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                toggles()
            }
        }
    }
}

/** 설정 토글 묶음: AI 주행 리포트, 급제동 위치 기록, 캐릭터 효과음 */
@Composable
private fun SettingToggles(state: SettingsState, actions: SettingsActions) {
    Group {
        var more by remember { mutableStateOf(false) }
        ToggleRow(
            title = stringResource(R.string.ai_consent_title),
            body = stringResource(if (state.aiAvailable) R.string.settings_ai_short else R.string.settings_ai_unavailable),
            on = state.aiOn, enabled = !state.driving && state.aiAvailable, onSet = actions::setAi,
        ) {
            if (state.aiAvailable) {
                Text(
                    stringResource(if (more) R.string.settings_ai_less else R.string.settings_ai_more),
                    Modifier.padding(top = SettingsSizes.lineGap).pressable(remember { MutableInteractionSource() }) { more = !more },
                    style = AppType.smallBold, color = AppPalette.primaryBlue,
                )
                if (more) {
                    Text(
                        sentencesOf(stringResource(R.string.ai_consent_notice)).joinToString("\n"),
                        Modifier.padding(top = SettingsSizes.lineGap).fillMaxWidth().background(AppPalette.white, AppShapes.radiusL)
                            .padding(SettingsSizes.rowPadH),
                        style = AppType.smallRegular, color = AppPalette.textSecondary,
                    )
                }
            }
        }
        Divider()
        ToggleRow(
            title = stringResource(R.string.settings_location_title), body = stringResource(R.string.settings_location_body),
            on = state.locationOn, enabled = !state.driving, onSet = actions::setLocation,
        )
        Divider()
        ToggleRow(
            title = stringResource(R.string.settings_voice_title), body = stringResource(R.string.settings_voice_body),
            on = state.voiceOn, enabled = !state.driving, onSet = actions::setVoice,
        )
    }
}

/** 내 캐릭터 카드: 연한 파란 그라데이션 위 캐릭터, 아래 이름과 단계, 프로필 선택 버튼 */
@Composable
private fun ProfileCard(assetKey: String?, name: String?, stage: Int, enabled: Boolean, onOpen: () -> Unit, onRename: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().clip(AppShapes.radiusXl).background(AppPalette.surface)) {
        Box(Modifier.fillMaxWidth().height(SettingsSizes.artHeight).background(AppGradients.scoreCardHead), contentAlignment = Alignment.Center) {
            CharacterStill(assetKey, stage, Modifier.fillMaxSize().padding(SettingsSizes.artPadding)) {}
        }
        Column(Modifier.padding(SettingsSizes.rowPadH), verticalArrangement = Arrangement.spacedBy(SettingsSizes.lineGap)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SettingsSizes.lineGap)) {
                Text(name ?: stringResource(R.string.settings_profile_title), style = AppType.mediumBold, color = AppPalette.ink)
                Text(
                    stringResource(R.string.exp_level, stage),
                    Modifier.background(AppPalette.blueSoft, AppShapes.pill).padding(horizontal = SettingsSizes.chipPadH, vertical = SettingsSizes.chipPadV),
                    style = AppType.smallBold, color = AppPalette.primaryBlue,
                )
                Spacer(Modifier.weight(1f))
                if (onRename != null) {
                    Text(
                        stringResource(R.string.settings_rename),
                        Modifier.pressable(remember { MutableInteractionSource() }, enabled = enabled, onClick = onRename),
                        style = AppType.smallBold, color = if (enabled) AppPalette.primaryBlue else AppPalette.textTertiary,
                    )
                }
            }
            Text(stringResource(R.string.settings_profile_body), style = AppType.smallRegular, color = AppPalette.textSecondary)
            Spacer(Modifier.height(SettingsSizes.lineGap))
            ButtonDark(stringResource(R.string.settings_profile_button), onOpen, Modifier.align(Alignment.CenterHorizontally), enabled = enabled)
        }
    }
}

/** 연한 회색 판 하나에 줄을 묶는다 */
@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(AppPalette.surface, AppShapes.radiusXl).padding(vertical = SettingsSizes.groupPadV), content = content)
}

@Composable
private fun Divider() {
    Box(Modifier.padding(horizontal = SettingsSizes.rowPadH).fillMaxWidth().height(SettingsSizes.divider).background(AppPalette.line))
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = SettingsSizes.rowPadH, vertical = SettingsSizes.rowPadV), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = AppType.smallBold, color = AppPalette.ink)
        Text(value, style = AppType.smallRegular, color = AppPalette.textTertiary)
    }
}

/** 제목·설명 + 오른쪽 파란 스위치. [extra] 는 설명 아래에 붙는다 */
@Composable
private fun ToggleRow(
    title: String, body: String, on: Boolean, enabled: Boolean, onSet: (Boolean) -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = SettingsSizes.rowPadH, vertical = SettingsSizes.rowPadV), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.mediumBold, color = AppPalette.ink)
            Spacer(Modifier.height(SettingsSizes.lineGap))
            Text(body, style = AppType.smallRegular, color = AppPalette.textSecondary)
            extra()
        }
        Spacer(Modifier.width(SettingsSizes.sectionGap))
        BlueSwitch(on, enabled) { onSet(!on) }
    }
}

/** 애플풍 스위치: 켜면 파란 바탕에 흰 손잡이가 오른쪽으로 미끄러진다 */
@Composable
private fun BlueSwitch(on: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val x by animateDpAsState(if (on) SettingsSizes.switchWidth - SettingsSizes.switchHeight else 0.dp, label = "switch")
    val track by animateColorAsState(if (on) AppPalette.primaryBlue else AppPalette.lineStrong, label = "track")
    Box(
        Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA).size(SettingsSizes.switchWidth, SettingsSizes.switchHeight)
            .background(track, AppShapes.pill)
            .pressable(remember { MutableInteractionSource() }, enabled = enabled, onClick = onToggle)
            .padding(SettingsSizes.knobInset),
    ) {
        Box(
            Modifier.offset(x = x).size(SettingsSizes.switchHeight - SettingsSizes.knobInset * 2)
                .appShadow(AppShadows.soft, CircleShape).background(AppPalette.white, CircleShape),
        )
    }
}
private const val DISABLED_ALPHA = 0.4f

/** 설정 화면 배치 값 [가안] (11/14) */
private object SettingsSizes {
    val sectionGap = 24.dp
    val leftWidth = 440.dp
    val artHeight = 280.dp
    val artPadding = 24.dp
    val rowPadH = 28.dp
    val rowPadV = 22.dp
    val groupPadV = 6.dp
    val bannerPadV = 16.dp
    val lineGap = 8.dp
    val chipPadH = 12.dp
    val chipPadV = 4.dp
    val divider = 1.dp
    val switchWidth = 88.dp
    val switchHeight = 52.dp
    val knobInset = 4.dp
}

/** 켜기·끄기 한 쌍. 고른 쪽이 진하다 */
@Composable
internal fun OnOffButtons(on: Boolean, enabled: Boolean, onSet: (Boolean) -> Unit, size: ButtonSize = ButtonSize.Screen) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.grid)) {
        val onText = stringResource(R.string.ai_consent_on); val offText = stringResource(R.string.ai_consent_off)
        if (on) ButtonDark(onText, { onSet(true) }, size = size, enabled = enabled) else ButtonLight(onText, { onSet(true) }, size = size, enabled = enabled)
        if (!on) ButtonDark(offText, { onSet(false) }, size = size, enabled = enabled) else ButtonLight(offText, { onSet(false) }, size = size, enabled = enabled)
    }
}

private object NoSettingsActions : SettingsActions {
    override fun setAi(on: Boolean) = Unit
    override fun setVoice(on: Boolean) = Unit
    override fun setLocation(on: Boolean) = Unit
    override fun openProfiles() = Unit
}

@Preview(widthDp = 1700, heightDp = 1184)
@Composable
internal fun SettingsScreenPreview() {
    AppTheme { SettingsScreen(SettingsState(aiOn = true), NoSettingsActions) }
}
