package me.rerere.rikkahub.data.devicecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTreeRulesTest {

    @Test
    fun `password input types are sensitive and plain text is not`() {
        assertTrue(DeviceTreeRules.isPasswordInputType(0x00000080))
        assertTrue(DeviceTreeRules.isPasswordInputType(0x00000091)) // visible password + flag bits
        assertTrue(DeviceTreeRules.isPasswordInputType(0x00000010))
        assertFalse(DeviceTreeRules.isPasswordInputType(0))
        assertFalse(DeviceTreeRules.isPasswordInputType(0x00000001)) // TYPE_CLASS_TEXT
    }

    @Test
    fun `password node is recognised from flag id hint and class`() {
        assertTrue(
            DeviceTreeRules.isSensitive("android.widget.EditText", inputType = 0, isPasswordFlag = true, hintText = null),
        )
        assertTrue(
            DeviceTreeRules.isSensitive(
                "android.widget.EditText",
                inputType = 0,
                isPasswordFlag = false,
                hintText = null,
                viewIdResourceName = "com.app:id/password_input",
            ),
        )
        assertTrue(
            DeviceTreeRules.isSensitive("android.widget.EditText", 0, false, "登录密码"),
        )
        assertFalse(
            DeviceTreeRules.isSensitive("android.widget.Button", 0x00000001, false, "spinning wheel"),
        )
    }

    @Test
    fun `sensitive text is dropped entirely`() {
        assertEquals("", DeviceTreeRules.redactText("hunter2", sensitive = true))
        assertEquals("visible", DeviceTreeRules.redactText("visible", sensitive = false))
        assertEquals("", DeviceTreeRules.redactText(null, sensitive = false))
    }

    @Test
    fun `truncation reports whether it cut anything`() {
        val (short, shortCut) = DeviceTreeRules.truncate("abc", 10)
        assertEquals("abc", short)
        assertFalse(shortCut)

        val (cut, cutFlag) = DeviceTreeRules.truncate("abcdef", 3)
        assertEquals("abc", cut)
        assertTrue(cutFlag)
    }

    @Test
    fun `node ids are path based and validated`() {
        assertEquals("3", DeviceTreeRules.childId("", 3))
        assertEquals("0/1/2", DeviceTreeRules.childId("0/1", 2))
        assertTrue(DeviceTreeRules.isNodeIdWellFormed("0/1/2"))
        assertTrue(DeviceTreeRules.isNodeIdWellFormed("0"))
        assertFalse(DeviceTreeRules.isNodeIdWellFormed(""))
        assertFalse(DeviceTreeRules.isNodeIdWellFormed("0/-1"))
        assertFalse(DeviceTreeRules.isNodeIdWellFormed("0/a"))
    }
}
