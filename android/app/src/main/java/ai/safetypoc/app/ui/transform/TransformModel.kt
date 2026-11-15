package ai.safetypoc.app.ui.transform

import ai.safetypoc.app.settings.Flags
import ai.safetypoc.app.ui.art.CharacterArt
import ai.safetypoc.app.settings.MemoryFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 변신 영상 한 편: [assetKey] 차종이 [fromStage] 단계에서 [toStage] 단계가 되는 장면. [path] 는 assets 안 경로.
 * 여러 단계를 이어 붙인 영상([fromStage] + 1 < [toStage])은 단계마다 같은 길이로 나뉜다.
 */
data class TransformClip(val toStage: Int, val path: String, val assetKey: String, val fromStage: Int = toStage - 1) {
    /** 이 영상에 든 단계 수 */
    val steps: Int get() = (toStage - fromStage).coerceAtLeast(1)
}

/** 변신 영상 재생 상태. [clips] 를 차례로 틀고, 비어 있으면 아무것도 띄우지 않는다 */
data class TransformState(val clips: List<TransformClip> = emptyList(), val index: Int = 0) {
    val current: TransformClip? get() = clips.getOrNull(index)
}

/** 변신 영상 화면에서 하는 동작 */
interface TransformActions {
    /** 한 편이 끝났거나 "다음"을 눌렀다. 마지막 편이면 닫는다 */
    fun next()
    fun close()
}

/**
 * 변신 영상(assets/character/<차종>/transform_lv<단계>.mp4)을 고르고 재생 순서를 쥔다.
 * - 레벨업: 오른 단계의 한 편만 튼다
 * - 홈 캐릭터 누르기: 1단계부터 지금 단계까지 끊김 없이 튼다. 미리 이어 붙인 영상(transform_seq<단계>.mp4)이 있으면 그 한 편, 없으면 단계별 영상을 차례로. 한 번이라도 누르면 "눌러 봐!" 말풍선을 다시 띄우지 않는다([hintSeen])
 * 주행 중에는 화면에 띄우지 않는다. 그 판단은 그리는 쪽(AppRoot)이 한다. 상태는 남아 있어 정차하면 이어서 나온다.
 */
class TransformModel(
    /** assets 안에 그 파일이 있는지 */
    private val exists: (String) -> Boolean,
    private val flags: Flags = MemoryFlags(),
) : TransformActions {
    private val _state = MutableStateFlow(TransformState())
    val state: StateFlow<TransformState> = _state.asStateFlow()

    private val _hintSeen = MutableStateFlow(flags.get(HINT_SEEN, false))
    /** 캐릭터를 눌러 변신을 본 적이 있는지. 앱을 다시 깔기 전까지 남는다 */
    val hintSeen: StateFlow<Boolean> = _hintSeen.asStateFlow()

    /** 2단계부터 [stage] 단계까지, 영상이 있는 것만 */
    fun clipsUpTo(assetKey: String?, stage: Int): List<TransformClip> =
        (2..stage).mapNotNull { clip(assetKey, it) }

    fun onLeveledUp(assetKey: String?, toStage: Int) {
        clip(assetKey, toStage)?.let { _state.value = TransformState(listOf(it)) }
    }

    /** 캐릭터를 눌렀을 때. 말풍선은 이때 지운다 */
    fun replay(assetKey: String?, stage: Int) {
        val clips = clipsUpTo(assetKey, stage)
        if (clips.isEmpty()) return
        val seq = if (clips.size > 1 && clips.all { it.assetKey == clips[0].assetKey }) seqPath(clips[0].assetKey, stage).takeIf(exists) else null
        _state.value = TransformState(seq?.let { listOf(TransformClip(stage, it, clips[0].assetKey, fromStage = 1)) } ?: clips)
        if (!_hintSeen.value) { _hintSeen.value = true; flags.put(HINT_SEEN, true) }
    }

    override fun next() {
        val s = _state.value
        _state.value = if (s.index + 1 < s.clips.size) s.copy(index = s.index + 1) else TransformState()
    }

    override fun close() {
        _state.value = TransformState()
    }

    /** 그 차종 영상이 없으면 [FALLBACK_ASSET_KEY] 영상을 쓴다 */
    private fun clip(assetKey: String?, toStage: Int): TransformClip? =
        listOfNotNull(assetKey, FALLBACK_ASSET_KEY).distinct()
            .map { TransformClip(toStage, path(it, toStage), it) }
            .firstOrNull { exists(it.path) }

    companion object {
        const val ASSET_DIR = CharacterArt.ASSET_DIR
        /** SharedPreferences 이름 */
        const val PREFS = "transform"
        private const val HINT_SEEN = "hint_seen"
        /** [임시] 영상은 그랜저만 있다. 다른 차종 영상이 나오기 전까지 그랜저 영상을 쓴다 */
        const val FALLBACK_ASSET_KEY = "grandeur"
        fun path(assetKey: String, toStage: Int) = "$ASSET_DIR/$assetKey/transform_lv$toStage.mp4"
        /** 2단계부터 [toStage] 단계까지 이어 붙인 영상 */
        fun seqPath(assetKey: String, toStage: Int) = "$ASSET_DIR/$assetKey/transform_seq$toStage.mp4"
    }
}

/** 이름 뒤 주격 조사: 받침이 있으면 "이", 없으면 "가". 한글이 아니면 "이(가)" 대신 "가" */
internal fun subjectParticle(name: String): String {
    val c = name.lastOrNull() ?: return "가"
    if (c !in '가'..'힣') return "가"
    return if ((c - '가') % 28 != 0) "이" else "가"
}
