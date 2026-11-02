package me.rerere.rikkahub.ui.pages.setting.components

import android.view.WindowManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.WebSessionException
import me.rerere.ai.provider.WebSessionService
import me.rerere.ai.provider.webService
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Login03
import me.rerere.hugeicons.stroke.Logout03
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.account.WebSessionImportException
import me.rerere.rikkahub.data.ai.account.WebSessionVault
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject
import java.util.UUID

/**
 * 网页账号（Gemini / Claude）配置界面。
 *
 * 约束:
 * - Cookie 只经 [WebSessionVault] 加密落盘, 不进 ProviderSetting, 不进备份, 界面不显示明文;
 * - 不提供 WebView 伪登录, 只打开官方站点的 Custom Tabs; 浏览器登录不会自动同步会话;
 * - 本地有会话不等于已登录: 只有真实请求验证通过后才显示已验证;
 * - 验证失败保留旧模型, 不打印异常正文 (可能含凭据), 只显示固定文案或 parser 的规则提示;
 * - 清除会话只删本机密文, 不删模型, 并取消在途验证。
 */
@Composable
fun ProviderConfigureWebAccount(
    provider: ProviderSetting.WebAccount,
    onEdit: (provider: ProviderSetting) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = koinInject<WebSessionVault>()
    val providerManager = koinInject<ProviderManager>()
    val toaster = LocalToaster.current
    val service = provider.webService

    val providerId = provider.id
    val latestProvider by rememberUpdatedState(provider)
    val importFailedText = stringResource(R.string.setting_provider_web_import_failed)
    val savedText = stringResource(R.string.setting_provider_web_import_success)
    val emptyImportText = stringResource(R.string.setting_provider_web_import_empty)
    val cancelLabel = stringResource(R.string.cancel)
    val deleteLabel = stringResource(R.string.delete)
    val enableLabel = stringResource(R.string.setting_provider_page_enable)
    val clearTitle = stringResource(R.string.setting_provider_web_clear_title)
    val clearDescription = stringResource(R.string.setting_provider_web_clear_desc)
    val bridgeTitle = stringResource(R.string.setting_provider_web_tool_confirm_title)
    val bridgeDescription = stringResource(R.string.setting_provider_web_tool_confirm_desc)

    // 本地是否有可解密的会话: 有也不冒充已登录, 只显示"已保存, 待验证"
    var hasSession by remember(providerId, service) { mutableStateOf(false) }
    var verifyState by remember(providerId) { mutableStateOf(WebVerifyState.IDLE) }
    var verifyJob by remember(providerId) { mutableStateOf<Job?>(null) }
    var showImport by remember(providerId) { mutableStateOf(false) }
    var showClearConfirm by remember(providerId) { mutableStateOf(false) }
    var showToolConfirm by remember(providerId) { mutableStateOf(false) }

    LaunchedEffect(providerId, service) {
        hasSession = withContext(Dispatchers.IO) { vault.hasSession(providerId, service) }
    }

    // 离开界面就停掉在途验证, 避免旧任务回来把状态写成"重新登录"
    DisposableEffect(providerId) {
        onDispose { verifyJob?.cancel() }
    }

    fun updateModelsWithToolBridge(enabled: Boolean) {
        onEdit(
            provider.withToolBridge(enabled).copyProvider(
                models = provider.models.map { it.withToolAbility(enabled) }
            )
        )
    }

    CardGroup {
        formItem(headlineContent = { Text(stringResource(R.string.setting_provider_page_name)) }) {
            OutlinedTextField(
                value = provider.name,
                onValueChange = { onEdit(provider.copyProvider(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }

    CardGroup(title = { Text(stringResource(R.string.setting_provider_web_session_title)) }) {
        item(
            leadingContent = { CardGroupIcon(HugeIcons.Key01) },
            headlineContent = { Text(sessionHeadline(hasSession, verifyState)) },
            supportingContent = { Text(sessionSupporting(hasSession, verifyState)) },
        )
        item(
            onClick = { CustomTabsOAuthAuthorizationLauncher.launch(context, service.home) },
            leadingContent = { CardGroupIcon(HugeIcons.Login03) },
            headlineContent = { Text(stringResource(R.string.setting_provider_web_open_login)) },
            supportingContent = { Text(stringResource(R.string.setting_provider_web_open_login_desc)) },
        )
        item(
            modifier = Modifier.testTag("web-session-import"),
            onClick = { showImport = true },
            leadingContent = { CardGroupIcon(HugeIcons.Copy01) },
            headlineContent = { Text(stringResource(R.string.setting_provider_web_import)) },
            supportingContent = { Text(stringResource(R.string.setting_provider_web_import_desc)) },
        )
        if (hasSession) {
            item(
                onClick = {
                    if (verifyState == WebVerifyState.VERIFYING) {
                        verifyJob?.cancel()
                        verifyJob = null
                        verifyState = WebVerifyState.IDLE
                    } else {
                        verifyJob?.cancel()
                        verifyJob = scope.launch {
                            verifyState = WebVerifyState.VERIFYING
                            val request = latestProvider
                            try {
                                val fetched = providerManager.getProviderByType(request).listModels(request)
                                if (!isActive) return@launch
                                val latest = latestProvider
                                if (latest.id != request.id || (latest as? ProviderSetting.ClaudeWeb)?.organizationId !=
                                    (request as? ProviderSetting.ClaudeWeb)?.organizationId) {
                                    verifyState = WebVerifyState.IDLE
                                    return@launch
                                }
                                onEdit(
                                    latest.copyProvider(
                                        models = mergeWebSessionModels(
                                            existing = latest.models,
                                            fetched = fetched,
                                            toolBridgeEnabled = latest.toolBridgeEnabled,
                                        )
                                    )
                                )
                                verifyState = WebVerifyState.VERIFIED
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                // 不回显异常正文: 响应体/请求头可能带凭据
                                if (isActive) verifyState = WebVerifyState.FAILED
                            }
                        }
                    }
                },
                leadingContent = {
                    if (verifyState == WebVerifyState.VERIFYING) {
                        ContainedLoadingIndicator(modifier = Modifier.size(40.dp))
                    } else {
                        CardGroupIcon(HugeIcons.Refresh03)
                    }
                },
                headlineContent = { Text(stringResource(R.string.setting_provider_web_verify)) },
                supportingContent = { Text(stringResource(R.string.setting_provider_web_verify_desc)) },
            )
            item(
                modifier = Modifier.testTag("web-session-clear"),
                onClick = { showClearConfirm = true },
                leadingContent = {
                    CardGroupIcon(
                        icon = HugeIcons.Logout03,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                },
                headlineContent = { Text(stringResource(R.string.setting_provider_web_clear)) },
            )
        }
    }

    CardGroup(title = { Text(stringResource(R.string.setting_provider_web_tool_bridge)) }) {
        switchItem(
            modifier = Modifier.testTag("web-session-bridge-switch"),
            checked = provider.toolBridgeEnabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    showToolConfirm = true
                } else {
                    updateModelsWithToolBridge(false)
                }
            },
            headlineContent = { Text(stringResource(R.string.setting_provider_web_tool_bridge)) },
            supportingContent = { Text(stringResource(R.string.setting_provider_web_tool_bridge_desc)) },
        )
        item(
            leadingContent = { CardGroupIcon(HugeIcons.CheckList) },
            headlineContent = { Text(stringResource(R.string.setting_provider_web_text_only)) },
        )
    }

    if (provider is ProviderSetting.ClaudeWeb) {
        CardGroup(title = { Text(stringResource(R.string.setting_provider_web_org_title)) }) {
            val organizationId = provider.organizationId.orEmpty()
            val invalid = organizationId.isNotBlank() && !organizationId.trim().isUuid()
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_provider_web_org_title)) },
                supportingContent = { Text(stringResource(R.string.setting_provider_web_org_desc)) },
            ) {
                OutlinedTextField(
                    value = organizationId,
                    onValueChange = { raw ->
                        onEdit(
                            provider.copy(
                                organizationId = raw.trim().ifBlank { null }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = invalid,
                    supportingText = if (invalid) {
                        { Text(stringResource(R.string.setting_provider_web_org_invalid)) }
                    } else null,
                )
            }
        }
    }

    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(
            text = stringResource(R.string.setting_provider_web_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (provider is ProviderSetting.ClaudeWeb) {
            Text(
                text = stringResource(R.string.setting_provider_web_claude_terms),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showImport) {
        WebSessionImportDialog(
            service = service,
            onDismiss = { showImport = false },
            onImport = { raw ->
                verifyJob?.cancel()
                verifyJob = null
                vault.importSession(providerId, service, raw)
                hasSession = withContext(Dispatchers.IO) { vault.hasSession(providerId, service) }
                // 新会话还没验证过, 回到"已保存, 待验证"
                verifyState = WebVerifyState.IDLE
                toaster.show(savedText, type = ToastType.Success)
            },
            emptyError = emptyImportText,
            fallbackError = importFailedText,
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(clearTitle) },
            text = { Text(clearDescription) },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(cancelLabel)
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("web-session-clear-confirm"),
                    onClick = {
                        showClearConfirm = false
                        // 先取消在途验证, 否则旧任务回来会改写状态
                        verifyJob?.cancel()
                        verifyJob = null
                        verifyState = WebVerifyState.IDLE
                        scope.launch {
                            try {
                                vault.clearSession(providerId)
                                hasSession = false
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) {
                                toaster.show(context.getString(R.string.setting_provider_web_import_failed), type = ToastType.Error)
                                hasSession = vault.hasSession(providerId, service)
                            }
                        }
                    }
                ) {
                    Text(deleteLabel)
                }
            },
        )
    }

    if (showToolConfirm) {
        AlertDialog(
            onDismissRequest = { showToolConfirm = false },
            title = { Text(bridgeTitle, Modifier.testTag("web-session-bridge-confirm-title")) },
            text = { Text(bridgeDescription) },
            dismissButton = {
                TextButton(modifier = Modifier.testTag("web-session-bridge-cancel"), onClick = { showToolConfirm = false }) {
                    Text(cancelLabel)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showToolConfirm = false
                        updateModelsWithToolBridge(true)
                    }
                ) {
                    Text(enableLabel)
                }
            },
        )
    }
}

private enum class WebVerifyState { IDLE, VERIFYING, VERIFIED, FAILED }

@Composable
private fun sessionHeadline(hasSession: Boolean, verifyState: WebVerifyState): String = when {
    verifyState == WebVerifyState.VERIFYING -> stringResource(R.string.setting_provider_web_session_verifying)
    !hasSession -> stringResource(R.string.setting_provider_web_session_unconfigured)
    verifyState == WebVerifyState.VERIFIED -> stringResource(R.string.setting_provider_web_session_verified)
    verifyState == WebVerifyState.FAILED -> stringResource(R.string.setting_provider_web_session_failed)
    else -> stringResource(R.string.setting_provider_web_session_saved)
}

@Composable
private fun sessionSupporting(hasSession: Boolean, verifyState: WebVerifyState): String = when {
    verifyState == WebVerifyState.VERIFYING -> stringResource(R.string.setting_provider_web_session_verifying_desc)
    !hasSession -> stringResource(R.string.setting_provider_web_session_unconfigured_desc)
    verifyState == WebVerifyState.VERIFIED -> stringResource(R.string.setting_provider_web_session_verified_desc)
    verifyState == WebVerifyState.FAILED -> stringResource(R.string.setting_provider_web_session_failed_desc)
    else -> stringResource(R.string.setting_provider_web_session_saved_desc)
}

/**
 * 粘贴会话的对话框。
 *
 * - 原文只用 remember: 关闭/完成即丢弃, 不写 saved state, 不进 ViewModel;
 * - 输入框默认遮挡 (PasswordVisualTransformation), 键盘按密码类型, 不挂 autofill 语义;
 * - 窗口打开时加 FLAG_SECURE 防截屏/最近任务预览, 关闭后恢复原状态;
 * - 不自动读剪贴板, 只接受用户手动粘贴。
 */
@Composable
private fun WebSessionImportDialog(
    service: WebSessionService,
    onDismiss: () -> Unit,
    onImport: suspend (raw: String) -> Unit,
    emptyError: String,
    fallbackError: String,
) {
    var raw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val titleLabel = stringResource(R.string.setting_provider_web_import_title)
    val descriptionLabel = stringResource(R.string.setting_provider_web_import_desc)
    val inputLabel = stringResource(R.string.setting_provider_web_import_label)
    val hintLabel = stringResource(R.string.setting_provider_web_import_hint)
    val privacyLabel = stringResource(R.string.setting_provider_web_import_privacy)
    val saveLabel = stringResource(R.string.setting_provider_web_import_confirm)
    val cancelLabel = stringResource(R.string.cancel)

    AlertDialog(
        onDismissRequest = { if (!busy) { raw = ""; onDismiss() } },
        title = { Text(titleLabel) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 必须在这个对话框窗口内取 LocalView, 才会把 FLAG_SECURE 加在对话框窗口上
                SecureDialogWindow()
                Text(
                    text = descriptionLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = raw,
                    onValueChange = {
                        if (it.length <= 65_536) {
                            raw = it
                            error = null
                        } else error = fallbackError
                    },
                    modifier = Modifier
                        .testTag("web-session-cookie-input")
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 200.dp),
                    label = { Text(inputLabel) },
                    placeholder = { Text(hintLabel) },
                    enabled = !busy,
                    isError = error != null,
                    minLines = 3,
                    maxLines = 8,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    supportingText = {
                        Text(
                            text = error
                                ?: privacyLabel,
                            color = if (error != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    raw = ""
                    onDismiss()
                },
                enabled = !busy,
            ) {
                Text(cancelLabel)
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("web-session-save"),
                onClick = {
                    val text = raw
                    if (text.isBlank()) {
                        error = emptyError
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        error = null
                        try {
                            onImport(text)
                            raw = ""
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // 只回显解析器给出的规则提示 (不含用户输入); 其它异常一律固定文案
                            error = when (e) {
                                is WebSessionImportException, is WebSessionException ->
                                    e.message?.takeIf { it.isNotBlank() } ?: fallbackError

                                else -> fallbackError
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
            ) {
                Text(saveLabel)
            }
        },
    )
}

// 让承载 Cookie 的对话框不可截屏, 关闭时恢复原 flag
@Composable
private fun SecureDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val wasSecure = window?.attributes?.flags
            ?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (window == null) return@onDispose
            if (wasSecure) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

private fun String.isUuid(): Boolean =
    runCatching { UUID.fromString(this.trim()).toString().equals(this.trim(), ignoreCase = true) }.getOrDefault(false)

private fun Model.withToolAbility(enabled: Boolean): Model {
    val abilities = if (enabled) {
        (abilities + ModelAbility.TOOL).distinct()
    } else {
        abilities.filterNot { it == ModelAbility.TOOL }
    }
    return if (abilities == this.abilities) this else copy(abilities = abilities)
}

/**
 * 验证成功后合并模型: 同 modelId 保留本地设置 (显示名、上下文、压缩、并发等),
 * 新模型按官方返回加入, 没再返回的本地模型也保留, 不静默丢配置。
 * 工具能力按提示词工具开关增删, 不虚报原生函数调用能力。
 */
internal fun mergeWebSessionModels(
    existing: List<Model>,
    fetched: List<Model>,
    toolBridgeEnabled: Boolean,
): List<Model> {
    val localById = existing.associateBy { it.modelId }
    val merged = LinkedHashMap<String, Model>()
    fetched.forEach { remote ->
        val local = localById[remote.modelId]
        merged[remote.modelId] = (local ?: remote).withToolAbility(toolBridgeEnabled)
    }
    existing.forEach { model ->
        merged.putIfAbsent(model.modelId, model.withToolAbility(toolBridgeEnabled))
    }
    return merged.values.toList()
}
