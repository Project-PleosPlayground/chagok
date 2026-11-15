package ai.safetypoc.app.settings

import android.content.SharedPreferences

/** 켜기·끄기 값 저장소. 화면 로직을 JVM 테스트에서 돌릴 수 있게 SharedPreferences 를 감싼다 */
interface Flags {
    fun get(key: String, default: Boolean): Boolean
    fun put(key: String, value: Boolean)
    /** 값 하나를 지운다. 다음 [get] 은 기본값 */
    fun remove(key: String)
    /** 값을 전부 지운다. 다음 [get] 은 기본값 */
    fun clear()
}

class PrefsFlags(private val prefs: SharedPreferences) : Flags {
    override fun get(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun put(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
    override fun clear() { prefs.edit().clear().apply() }
}

/** 테스트·미리보기용 */
class MemoryFlags : Flags {
    private val values = HashMap<String, Boolean>()
    override fun get(key: String, default: Boolean) = values[key] ?: default
    override fun put(key: String, value: Boolean) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
    override fun clear() { values.clear() }
}
