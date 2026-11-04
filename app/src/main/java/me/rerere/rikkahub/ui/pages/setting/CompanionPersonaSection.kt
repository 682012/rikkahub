package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.companion.CompanionCatalog
import me.rerere.rikkahub.data.companion.CompanionPersonas
import me.rerere.rikkahub.data.companion.PersonaSource
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.floating.floatingCompanionDefaultName
import org.koin.compose.koinInject

/**
 * r16 人设区：看内置全文、看当前生效、复制、以此为基础编辑、恢复内置。
 *
 * 这一块**不看 AI 开关**：AI 回应关着也能看和复制，全程零请求。
 * 真正发请求的那一侧用的是同一个 `CompanionPersonas.resolve`，所以这里显示的就是实际会用的那一份。
 */
@Composable
fun CompanionPersonaSection(
    companion: FloatingCompanionSettings,
    saveOverride: suspend (ownerCharacterKey: String, prompt: String) -> Unit,
    restoreOverride: suspend (ownerCharacterKey: String) -> Unit,
) {
    val current = remember(companion) { companion.normalized() }
    // 打开时记下的所有者：保存/恢复都只认这一格，不看"此刻选中的是谁"。
    val ownerKey = current.characterKey
    val catalogRevision by CompanionPersonas.revision.collectAsState()
    val effective = remember(current, catalogRevision) { CompanionPersonas.resolve(current) }
    val builtIn = remember(current.effectivePreset, catalogRevision) {
        CompanionPersonas.builtInResolved(current.effectivePreset)
    }
    val defaultName = floatingCompanionDefaultName(current.effectivePreset)
    val summary = companionPersonaSummary(current.effectivePreset)
    val hasOverride = current.personaOverrideOf(ownerKey).isNotBlank()
    val languageTag = remember { Locale.getDefault().toLanguageTag() }

    var showBuiltIn by remember { mutableStateOf(false) }
    var showEffective by remember { mutableStateOf(false) }
    var editorRequest by remember { mutableStateOf<CompanionPersonaEditorRequest?>(null) }
    var restoreRequest by remember { mutableStateOf<CompanionPersonaRestoreRequest?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    CardGroup(title = { Text(stringResource(R.string.r16_persona_section_title)) }) {
        formItem(
            headlineContent = { Text(stringResource(R.string.r16_persona_item_title, effective.name)) },
            supportingContent = { Text(stringResource(R.string.r16_persona_section_desc)) },
        ) {
            // 原来那一段独立的小结保留：内置角色的一句话说明，不承担全文展示。
            Text(
                text = stringResource(R.string.r16_persona_builtin_summary, summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = personaStateLine(effective.source, effective.version, effective.prompt.length),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { showBuiltIn = true },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.r16_persona_view_builtin),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp,
                    )
                }
                OutlinedButton(
                    onClick = { showEffective = true },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.r16_persona_view_effective),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp,
                    )
                }
            }
            FilledTonalButton(
                onClick = {
                    // 草稿起点是"当前生效的全文"，不是列表里的省略字符串。
                    editorRequest = CompanionPersonaEditorRequest(
                        ownerCharacterKey = ownerKey,
                        ownerLabel = effective.name,
                        initialText = effective.prompt,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.r16_persona_edit), fontSize = 14.sp)
            }
            if (hasOverride) {
                TextButton(
                    onClick = { restoreRequest = CompanionPersonaRestoreRequest(ownerKey, effective.name) },
                    modifier = Modifier
                        .align(Alignment.End)
                        .heightIn(min = 48.dp),
                ) {
                    Text(text = stringResource(R.string.r16_persona_restore))
                }
            }
            if (failure != null) {
                Text(
                    text = stringResource(R.string.r16_persona_save_failed, failure.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    if (showBuiltIn) {
        CompanionPersonaViewer(
            resolved = builtIn,
            title = stringResource(R.string.r16_persona_view_builtin_title, builtIn.name.ifBlank { defaultName }),
            languageTag = languageTag,
            // 和请求侧同一个判断：只有旧短兜底才叠内置风格，完整卡自带口吻。
            useBuiltInStyle = builtIn.source == PersonaSource.FALLBACK,
            onDismiss = { showBuiltIn = false },
            onEdit = null,
        )
    }
    if (showEffective) {
        CompanionPersonaViewer(
            resolved = effective,
            title = stringResource(R.string.r16_persona_view_effective_title, effective.name),
            languageTag = languageTag,
            useBuiltInStyle = effective.source == PersonaSource.FALLBACK,
            onDismiss = { showEffective = false },
            onEdit = {
                showEffective = false
                editorRequest = CompanionPersonaEditorRequest(
                    ownerCharacterKey = ownerKey,
                    ownerLabel = effective.name,
                    initialText = effective.prompt,
                )
            },
        )
    }
    editorRequest?.let { request ->
        CompanionPersonaEditor(
            request = request,
            save = { draft -> saveOverride(request.ownerCharacterKey, draft) },
            onSaved = {
                editorRequest = null
                failure = null
            },
            onClose = { editorRequest = null },
        )
    }
    restoreRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { restoreRequest = null },
            title = { Text(stringResource(R.string.r16_persona_restore_title)) },
            text = { Text(stringResource(R.string.r16_persona_restore_text, request.ownerLabel)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            // 只清这一格的 persona；名字、图片、模型、开关都不经过这里。
                            val result = runCatching { restoreOverride(request.ownerCharacterKey) }
                            restoreRequest = null
                            failure = result.exceptionOrNull()?.let { it.message ?: it::class.simpleName }
                        }
                    },
                ) {
                    Text(stringResource(R.string.r16_persona_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreRequest = null }) {
                    Text(stringResource(R.string.r16_persona_restore_cancel))
                }
            },
        )
    }
}

/** 内置角色的一句话说明；没有内置卡的角色给一句通用说明。 */
@Composable
internal fun companionPersonaSummary(preset: FloatingCompanionPreset): String =
    CompanionCatalog.profile(preset)?.shortDescription ?: stringResource(
        when (CompanionPersonas.summaryKey(preset)) {
            "dragon_girl" -> R.string.companion_persona_builtin_dragon_girl
            "sun_reader" -> R.string.companion_persona_builtin_sun_reader
            "whale_maid" -> R.string.companion_persona_builtin_whale_maid
            "cat" -> R.string.companion_persona_builtin_cat
            "gemini_cat" -> R.string.r12_persona_summary_gemini
            "glm_fox" -> R.string.r12_persona_summary_glm
            "qwen_girl" -> R.string.r12_persona_summary_qwen
            "logo" -> R.string.companion_persona_builtin_logo
            else -> R.string.companion_persona_builtin_custom
        }
    )

/** 设置里那一行状态：来源 + 版本 + UTF-16 长度（不假装是 token 数）。 */
@Composable
private fun personaStateLine(source: PersonaSource, version: String, length: Int): String = when (source) {
    PersonaSource.BUILTIN -> {
        val shownVersion = if (version.isBlank()) {
            stringResource(R.string.r16_persona_version_unknown)
        } else {
            version
        }
        stringResource(R.string.r16_persona_state_builtin, shownVersion, length)
    }

    PersonaSource.USER_OVERRIDE -> stringResource(R.string.r16_persona_state_override, length)
    PersonaSource.FALLBACK -> stringResource(R.string.r16_persona_state_fallback, length)
}

/**
 * 保存/恢复人设覆盖的唯一入口。
 *
 * 直接走 [SettingsStore.update]：拿到的永远是最新 Settings，只替换打开编辑器时记下的那一格，
 * 不会把界面手里的旧整份设置写回去（那会顺手覆盖别的角色、开关和别处刚改的值）。
 */
class CompanionPersonaOwnerSaver(private val store: SettingsStore) {
    /** 保存 [ownerCharacterKey] 这一格；超限由 [CompanionPersonaDraft] 明确拒绝，不截断。 */
    suspend fun save(ownerCharacterKey: String, prompt: String) {
        store.update { latest ->
            latest.withCompanion(CompanionPersonaDraft.apply(latest.displaySetting.floatingCompanion, ownerCharacterKey, prompt))
        }
    }

    /** 恢复内置：只清这一格的覆盖。 */
    suspend fun restoreBuiltIn(ownerCharacterKey: String) {
        store.update { latest ->
            latest.withCompanion(
                CompanionPersonaDraft.clear(latest.displaySetting.floatingCompanion, ownerCharacterKey),
            )
        }
    }
}

private fun Settings.withCompanion(companion: FloatingCompanionSettings): Settings =
    copy(displaySetting = displaySetting.copy(floatingCompanion = companion))

@Composable
fun rememberCompanionPersonaOwnerSaver(): CompanionPersonaOwnerSaver {
    val store: SettingsStore = koinInject()
    return remember(store) { CompanionPersonaOwnerSaver(store) }
}

/** 打开编辑器那一刻的快照：所有者 + 全文起点。之后换角色也不会改这份。 */
internal data class CompanionPersonaEditorRequest(
    val ownerCharacterKey: String,
    val ownerLabel: String,
    val initialText: String,
)

internal data class CompanionPersonaRestoreRequest(
    val ownerCharacterKey: String,
    val ownerLabel: String,
)
