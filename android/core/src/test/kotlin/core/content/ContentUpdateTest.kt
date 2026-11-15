package core.content

import core.Fx
import core.character.InMemoryProfileStore
import core.flow.SessionEndPipeline
import core.mission.InMemoryMissionStore
import core.mission.MissionEngine
import core.session.EndAnimation
import core.session.InMemorySessionStore
import core.session.VehicleStatus
import engine.EventType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContentUpdateTest {
    private val bundled = BundledContent.pack
    private val appVersion = 5

    private fun pack(version: Int, minApp: Int = 1) = bundled.copy(contentVersion = version, minAppVersion = minApp)
    private fun bytes(p: ContentPack) = ContentCodec.encode(p).toByteArray(Charsets.UTF_8)
    private fun manifestFor(p: ContentPack, b: ByteArray = bytes(p)) =
        ContentManifest(p.contentVersion, p.minAppVersion, "packs/${p.contentVersion}.json", ContentUpdate.sha256Hex(b))

    @Test fun `내장 콘텐츠는 기존 가안 목록과 같다`() {
        assertEquals(9, bundled.missions.conditions.size)
        assertEquals(4, bundled.missions.tierRewards.size)
        assertEquals(28, bundled.items.size)   // 펫 15, 소품 7(산타 모자 11/14), 배경 2(트리·눈 11/15), 장소 선물 4(11/15)
        assertEquals(DefaultMissions.catalog, bundled.missions)
        assertEquals(DefaultItems.all, bundled.items)
    }

    @Test fun `점수 구간은 콘텐츠에서 고른다`() {
        assertEquals(EndAnimation.DANCE, bundled.animationFor(95))
        assertEquals(EndAnimation.OK, bundled.animationFor(75))
        assertEquals(EndAnimation.OTL, bundled.animationFor(10))
        val custom = bundled.copy(scoreBands = listOf(ScoreBand(50, EndAnimation.DANCE), ScoreBand(0, EndAnimation.OTL)))
        assertEquals(EndAnimation.DANCE, custom.animationFor(60))
    }

    @Test fun `새 묶음은 해시가 맞으면 받는다`() {
        val p = pack(bundled.contentVersion + 1); val b = bytes(p)
        val v = ContentUpdate.verify(b, manifestFor(p, b), bundled.contentVersion, appVersion)
        assertEquals(p, (v as ContentVerdict.Accept).pack)
    }

    @Test fun `해시가 다르면 버린다`() {
        val p = pack(bundled.contentVersion + 1); val b = bytes(p)
        val bad = manifestFor(p, b).copy(sha256 = ContentUpdate.sha256Hex("다른 파일".toByteArray()))
        assertEquals(ContentVerdict.Reject(ContentRejection.HASH_MISMATCH), ContentUpdate.verify(b, bad, bundled.contentVersion, appVersion))
    }

    @Test fun `형식이 깨지면 버린다`() {
        val b = "{ 깨진 json".toByteArray()
        val m = ContentManifest(bundled.contentVersion + 1, 1, "x.json", ContentUpdate.sha256Hex(b))
        assertEquals(ContentVerdict.Reject(ContentRejection.PARSE_ERROR), ContentUpdate.verify(b, m, bundled.contentVersion, appVersion))
    }

    @Test fun `파일 안 버전이 manifest 와 다르면 버린다`() {
        val p = pack(bundled.contentVersion + 1); val b = bytes(p)
        val m = manifestFor(p, b).copy(contentVersion = bundled.contentVersion + 2)
        assertEquals(ContentVerdict.Reject(ContentRejection.VERSION_MISMATCH), ContentUpdate.verify(b, m, bundled.contentVersion, appVersion))
    }

    @Test fun `새것이 아니거나 앱이 낮으면 받지 않는다`() {
        val same = manifestFor(pack(bundled.contentVersion))
        assertEquals(ContentRejection.NOT_NEWER, ContentUpdate.shouldDownload(same, bundled.contentVersion, appVersion))
        val needsNewApp = manifestFor(pack(bundled.contentVersion + 1, minApp = appVersion + 1))
        assertEquals(ContentRejection.APP_TOO_OLD, ContentUpdate.shouldDownload(needsNewApp, bundled.contentVersion, appVersion))
        assertNull(ContentUpdate.shouldDownload(manifestFor(pack(bundled.contentVersion + 1)), bundled.contentVersion, appVersion))
    }

    @Test fun `앱 시작 때는 쓸 수 있는 가장 새 묶음`() {
        val newer = ContentCodec.encode(pack(bundled.contentVersion + 1))
        assertEquals(bundled.contentVersion + 1, ContentUpdate.pickAtStartup(bundled, newer, appVersion).contentVersion)
        val older = ContentCodec.encode(pack(bundled.contentVersion - 1))
        assertEquals(bundled, ContentUpdate.pickAtStartup(bundled, older, appVersion))
        val tooNew = ContentCodec.encode(pack(bundled.contentVersion + 1, minApp = appVersion + 1))
        assertEquals(bundled, ContentUpdate.pickAtStartup(bundled, tooNew, appVersion))
        assertEquals(bundled, ContentUpdate.pickAtStartup(bundled, "깨진 파일", appVersion))
        assertEquals(bundled, ContentUpdate.pickAtStartup(bundled, null, appVersion))
    }

    @Test fun `모르는 필드가 있어도 읽는다`() {
        val text = ContentCodec.encode(pack(bundled.contentVersion + 1)).replaceFirst("{", "{\"newField\": 1, ")
        assertEquals(bundled.contentVersion + 1, ContentCodec.decode(text).contentVersion)
    }

    @Test fun `받은 묶음은 탭 전환 때 적용되고 더 오래된 묶음은 올리지 않는다`() {
        val state = ContentState(bundled)
        assertNull(state.applyStaged())
        assertFalse(state.stage(pack(bundled.contentVersion)))
        assertTrue(state.stage(pack(bundled.contentVersion + 2)))
        assertFalse(state.stage(pack(bundled.contentVersion + 1)))
        assertEquals(bundled, state.active)
        assertEquals(bundled.contentVersion + 2, state.applyStaged()?.contentVersion)
        assertEquals(bundled.contentVersion + 2, state.active.contentVersion)
        assertNull(state.staged)
    }

    @Test fun `종료 화면 애니메이션은 지금 쓰는 콘텐츠를 따른다`() {
        val sessions = InMemorySessionStore(); val profiles = InMemoryProfileStore()
        val missions = MissionEngine(InMemoryMissionStore(), sessions, missions = { BundledContent.pack.missions })
        val state = ContentState(bundled)
        val pipeline = SessionEndPipeline(sessions, profiles, missions, content = { state.active })
        val r = Fx.record("s1", Fx.MON + 9 * 3_600_000L, events = listOf(Fx.ev(EventType.HARSH_BRAKE)))
        val before = pipeline.onSessionEnd(r, VehicleStatus(), r.endWallMs).animation
        state.stage(bundled.copy(contentVersion = bundled.contentVersion + 1, scoreBands = listOf(ScoreBand(0, EndAnimation.DANCE))))
        state.applyStaged()
        val after = pipeline.onSessionEnd(r.copy(id = "s2"), VehicleStatus(), r.endWallMs).animation
        assertEquals(bundled.animationFor(r.score), before)
        assertEquals(EndAnimation.DANCE, after)
    }
}
