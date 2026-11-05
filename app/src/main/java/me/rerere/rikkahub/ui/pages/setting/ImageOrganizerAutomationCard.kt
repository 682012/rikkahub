package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.organization.*

@Composable
internal fun ImageOrganizerAutomationCard(state: ImageOrganizerState, vm: ImageOrganizerVM, selectBucket: (Set<String>?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var confirmAuto by remember { mutableStateOf(false) }
    var confirmPlan by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(state.auto.sources) }
    if (confirmAuto) AlertDialog(onDismissRequest = { confirmAuto = false },
        title = { Text("自动整理哪些图片？") }, text = {
            Column {
                Text("使用所选识别模型，会发送缩略图并按提供商计费。仅处理下方来源的新图，每批最多 ${state.auto.maxPerRun} 张；不移动或删除原文件。")
                ImageSource.entries.forEach { source ->
                    Row(Modifier.fillMaxWidth().clickable {
                        sources = if (source in sources) sources - source else sources + source
                    }) {
                        Checkbox(source in sources, onCheckedChange = { checked -> sources = if (checked) sources + source else sources - source })
                        Text(when (source) { ImageSource.UPLOAD -> "聊天上传"; ImageSource.GEN_MEDIA -> "生成图片"; ImageSource.MEDIA_CREATION -> "媒体创作" }, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                Text("约每15分钟由系统调度，省电模式可能延迟。强行停止应用后需重新打开。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(enabled = sources.isNotEmpty(), onClick = {
            vm.setAuto(state.auto.copy(enabled = true, sources = sources)); confirmAuto = false
        }) { Text("开启") } }, dismissButton = { TextButton(onClick = { confirmAuto = false }) { Text("取消") } })
    if (confirmPlan) AlertDialog(onDismissRequest = { confirmPlan = false }, title = { Text("AI 规划分类") },
        text = { Text("将最多200张已识别图片的名称、分类和描述交给所选模型，生成可改名的虚拟分组。此步骤不上传原图，会消耗模型额度。") },
        confirmButton = { TextButton(onClick = { confirmPlan = false; vm.generatePlan() }) { Text("生成方案") } },
        dismissButton = { TextButton(onClick = { confirmPlan = false }) { Text("取消") } })
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("后台整理与 AI 规划", modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
                Text(if (expanded) "收起" else "展开", modifier = Modifier.padding(vertical = 12.dp))
            }
            if (expanded) Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("自动整理新图片")
                        Text("关闭页面后任务仍会继续", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(state.auto.enabled, onCheckedChange = {
                        if (it) { sources = state.auto.sources; confirmAuto = true }
                        else vm.setAuto(state.auto.copy(enabled = false))
                    }, enabled = !state.planBusy)
                }
                if (state.jobStatus == ImageJobStatus.QUEUED) {
                    Text("已进入后台队列，联网后继续", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::cancelClassification) { Text("取消排队") }
                }
                if (state.jobStatus in setOf(ImageJobStatus.PAUSED, ImageJobStatus.FAILED)) {
                    Text(state.jobError ?: "上次任务已暂停，还剩 ${state.jobRemaining} 张。", style = MaterialTheme.typography.bodySmall)
                    Text("自动整理等待这单处理；已发出但结果不确定的请求不会自动重发。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::resume, enabled = !state.busy) { Text("继续未发送的图片") }
                    TextButton(onClick = vm::cancelClassification, enabled = !state.busy) { Text("放弃剩余任务") }
                }
                if (state.jobStatus == ImageJobStatus.CANCELLED) {
                    Text(state.jobError ?: "本次任务已取消，已完成的分类保留。", style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    TextButton(onClick = { vm.setWorkers(16) }, enabled = !state.busy) { Text("16路并发") }
                    TextButton(onClick = { confirmPlan = true }, enabled = !state.busy) { Text(if (state.planBusy) "规划中…" else "AI 规划分类") }
                    TextButton(onClick = { selectBucket(null) }) { Text("全部图片") }
                }
                state.plan?.buckets?.forEach { bucket ->
                    var name by remember(bucket.id, bucket.name) { mutableStateOf(bucket.name) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(value = name, onValueChange = { name = it.take(ImagePlan.MAX_NAME_LENGTH) }, singleLine = true,
                            modifier = Modifier.weight(1f), label = { Text("${bucket.itemIds.size} 张") })
                        TextButton(onClick = { vm.renameBucket(bucket.id, name) }, enabled = name.isNotBlank() && name != bucket.name) { Text("保存") }
                        TextButton(onClick = { selectBucket(bucket.itemIds.toSet()) }) { Text("查看") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
