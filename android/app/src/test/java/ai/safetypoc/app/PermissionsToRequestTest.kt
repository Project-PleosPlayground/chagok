package ai.safetypoc.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionsToRequestTest {
    private val all = listOf("granted.runtime", "missing.runtime", "missing.install", "undefined")
    private val runtime = setOf("granted.runtime", "missing.runtime")
    private val granted = setOf("granted.runtime", "missing.install")

    @Test fun `받지 못한 runtime 권한만 요청한다`() {
        assertEquals(listOf("missing.runtime"), permissionsToRequest(all, granted::contains, runtime::contains))
    }

    @Test fun `기기에 없거나 설치 때 받는 권한만 남았으면 요청하지 않는다`() {
        assertEquals(emptyList<String>(), permissionsToRequest(listOf("missing.install", "undefined"), granted::contains, runtime::contains))
    }
}
