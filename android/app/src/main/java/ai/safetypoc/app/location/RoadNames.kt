package ai.safetypoc.app.location

import android.content.Context
import android.location.Geocoder
import core.session.SessionRecord
import java.util.Locale

/**
 * 이벤트 좌표 → 도로명. 기기 지오코더로 기기 안에서만 바꾸고, 결과는 기기 안 기록(EventRecord.roadName)에만 붙인다.
 * AI 중계 서버로는 보내지 않는다(설정 화면 약속). 지오코더가 없거나 실패하면 이름 없이 둔다.
 * 네트워크를 쓸 수 있어 막히므로 IO 스레드에서 부른다.
 */
class RoadNames(private val context: Context) {
    private val geocoder by lazy { if (Geocoder.isPresent()) Geocoder(context, Locale.KOREA) else null }

    /** 도로명이 비어 있고 좌표가 있는 이벤트에 이름을 붙인 기록. 붙인 게 없으면 null */
    @Suppress("DEPRECATION")   // getFromLocation(콜백 없는 형태)은 API 33 에서 deprecated 지만 IO 스레드에서 쓰기엔 간단하다
    fun fill(r: SessionRecord): SessionRecord? {
        val g = geocoder ?: return null
        var changed = false
        val events = r.events.map { e ->
            if (e.roadName != null || e.lat == null || e.lng == null) return@map e
            val a = runCatching { g.getFromLocation(e.lat!!, e.lng!!, 1) }.getOrNull()?.firstOrNull() ?: return@map e
            val name = a.thoroughfare ?: a.subLocality ?: a.locality ?: return@map e
            changed = true
            e.copy(roadName = name)
        }
        return if (changed) r.copy(events = events) else null
    }
}
