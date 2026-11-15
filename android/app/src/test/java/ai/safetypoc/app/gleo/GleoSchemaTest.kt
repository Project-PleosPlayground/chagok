package ai.safetypoc.app.gleo

import ai.safetypoc.app.BuildConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** `assets/schema.json` 과 매니페스트 수신기가 Pleos App Schema 규칙과 [GleoFunction] 에 맞는지 본다 */
class GleoSchemaTest {
    private val schema = JSONObject(File("src/main/assets/schema.json").readText())
    private val functions = schema.getJSONArray("actionCalls").let { a -> (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function") } }

    @Test fun `패키지 이름과 버전이 앱과 같다`() {
        assertEquals(BuildConfig.APPLICATION_ID, schema.getString("packageName"))
        assertEquals(BuildConfig.VERSION_NAME, schema.getString("version"))
    }

    @Test fun `함수는 3개 이하이고 이름이 GleoFunction 과 같다`() {
        assertTrue(functions.size <= MAX_FUNCTIONS)
        assertEquals(GleoFunction.entries.map { it.name }.toSet(), functions.map { it.getString("name") }.toSet())
    }

    @Test fun `함수 이름은 대문자와 밑줄, 설명은 영어다`() {
        val ascii = Regex("[\\x20-\\x7E]+")
        assertTrue(ascii.matches(schema.getString("appDescription")))
        for (f in functions) {
            assertTrue(f.getString("name"), Regex("[A-Z]+(_[A-Z]+)+").matches(f.getString("name")))
            assertTrue(f.getString("name"), ascii.matches(f.getString("description")))
            assertEquals("service", f.getString("component"))
            assertEquals("object", f.getJSONObject("parameters").getString("type"))
        }
    }

    @Test fun `매니페스트 수신기가 함수 이름을 모두 받는다`() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val receivers = doc.getElementsByTagName("receiver").let { n -> (0 until n.length).map { n.item(it) as Element } }
        val receiver = receivers.single { it.getAttributeNS(ANDROID_NS, "name") == ".gleo.GleoActionReceiver" }
        assertEquals("true", receiver.getAttributeNS(ANDROID_NS, "exported"))
        val actions = receiver.getElementsByTagName("action").let { n -> (0 until n.length).map { (n.item(it) as Element).getAttributeNS(ANDROID_NS, "name") } }
        assertEquals(GleoFunction.entries.map { it.name }.toSet(), actions.toSet())
    }

    private companion object {
        /** 앱 하나가 Gleo 에 등록할 수 있는 함수 수 (Pleos App Schema `actionCalls`) */
        const val MAX_FUNCTIONS = 3
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
