package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.workspace.WorkspaceAccessSettings
import me.rerere.rikkahub.data.workspace.WorkspacePrivilegeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkspaceAccessUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rootIsNeverRequestedUntilTheUserConfirms() {
        var requested: WorkspacePrivilegeMode? = null
        var config by mutableStateOf(WorkspaceAccessSettings())
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    WorkspaceAccessCard(config, false, "Not authorized", false,
                        onAuthorize = { requested = it; config = config.copy(androidShellEnabled = true, androidPrivilege = it) }, onRevoke = {}, onTrustedChange = { config = config.copy(trustedLinux = it) },
                        onStorageAccess = {}, onPickDirectory = {}, onAddPath = { _, _ -> }, onRemoveMount = {}, onProbe = {})
                }
            }
        }
        compose.onNodeWithTag(WorkspaceAccessTestTags.MODE_ROOT).performClick()
        compose.runOnIdle { assertNull(requested) }
        val confirm = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.common_confirm)
        compose.onNodeWithText(confirm).performClick()
        compose.runOnIdle { assertEquals(WorkspacePrivilegeMode.ROOT, requested); requested = null }
        // A persisted selection must still let the user explicitly reauthorize after restart/revocation.
        compose.onNodeWithTag(WorkspaceAccessTestTags.MODE_ROOT).performClick()
        compose.runOnIdle { assertNull(requested) }
        compose.onNodeWithText(confirm).performClick()
        compose.runOnIdle { assertEquals(WorkspacePrivilegeMode.ROOT, requested) }
    }
}
