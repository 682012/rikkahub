package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavControllerOrNull
import me.rerere.rikkahub.ui.context.LocalWorkspaceId
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.utils.WorkspaceArtifactLink
import me.rerere.rikkahub.utils.openExternalScheme
import me.rerere.rikkahub.utils.openUrl
import me.rerere.ui.toast.ToastType

/**
 * Markdown 链接的统一出口。
 *
 * 之前 MaterialMarkdown 不传 onLinkClick 时走 `LocalUriHandler`，等于把 `sandbox:/workspace/x`
 * 这种地址直接甩给系统浏览器（打不开或者变成搜索），也绕开了 Context.openUrl 的 CustomTabs 逻辑。
 * 现在所有 Markdown（对话正文、悬浮窗预览、各种设置页说明）都从这里分发：
 *
 * - 工作区文件 → 用当前对话绑定的 workspaceId 跳到应用内预览页（不依赖全局“选一个工作区”）；
 * - http/https → 应用内网页预览页（[Screen.WebView]），没有导航栈时退回 CustomTabs；
 * - 其它 scheme → ACTION_VIEW，没有 handler 时给提示；
 * - 越界/格式错的链接 → 明确提示，不打开浏览器。
 */
@Composable
fun rememberMarkdownLinkHandler(): (url: String) -> Unit {
    val context = LocalContext.current
    val workspaceId = LocalWorkspaceId.current
    val navigator = LocalNavControllerOrNull.current
    val toaster = me.rerere.rikkahub.ui.context.LocalToasterOrNull.current
    val artifactOpener = me.rerere.rikkahub.ui.context.LocalWorkspaceArtifactOpener.current
    return remember(context, workspaceId, navigator, toaster, artifactOpener) {
        { url ->
            openMarkdownLink(
                context = context,
                url = url,
                workspaceId = workspaceId,
                navigator = navigator,
                onMessage = { message ->
                    if (toaster != null) toaster.show(message, type = ToastType.Error)
                    else android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                },
                artifactOpener = artifactOpener,
            )
        }
    }
}

/**
 * 链接分发本体（不依赖 Compose，便于单测直接调）。
 *
 * [workspaceId] 是当前对话绑定的工作区 id：为 null 时工作区文件只提示，不会去猜一个工作区，
 * 也不会交给浏览器。[navigator] 为 null（比如悬浮窗没有导航栈）时同样只提示。
 */
fun openMarkdownLink(
    context: Context,
    url: String,
    workspaceId: String?,
    navigator: Navigator?,
    onMessage: (String) -> Unit,
    artifactOpener: ((String, WorkspaceArtifactLink.WorkspaceFile) -> Unit)? = null,
) {
    when (val link = WorkspaceArtifactLink.parse(url)) {
        is WorkspaceArtifactLink.WorkspaceFile -> {
            if (workspaceId.isNullOrBlank()) {
                onMessage(WorkspaceArtifactLink.Reason.NO_WORKSPACE.message)
                return
            }
            if (navigator == null) {
                if (artifactOpener != null) artifactOpener(workspaceId, link)
                else onMessage("工作区文件需要在对话页面里打开: ${link.path}")
                return
            }
            navigator.navigate(Screen.WorkspaceArtifact(workspaceId, link.path, link.fragment))
        }

        // http/https 走应用内预览页（复用系统 WebView，不引入新内核、不常驻）；
        // 没有导航栈时（悬浮窗预览等）退回 CustomTabs，行为与之前一致
        is WorkspaceArtifactLink.Web ->
            if (navigator != null) navigator.navigate(Screen.WebView(url = link.raw))
            else context.openUrl(link.raw)

        is WorkspaceArtifactLink.ExternalScheme -> context.openExternalScheme(link.raw)
            .onFailure { error -> onMessage(error.message ?: "没有可以处理这个链接的 App") }

        is WorkspaceArtifactLink.Rejected -> onMessage(link.reason.message)
    }
}

/** 给 `MarkdownBlock` 这类已知对话上下文的调用方用：显式传入 workspaceId，不依赖 CompositionLocal */
@Composable
fun rememberWorkspaceMarkdownLinkHandler(workspaceId: String?): (url: String) -> Unit {
    val context = LocalContext.current
    val navigator = LocalNavControllerOrNull.current
    val toaster = me.rerere.rikkahub.ui.context.LocalToasterOrNull.current
    val artifactOpener = me.rerere.rikkahub.ui.context.LocalWorkspaceArtifactOpener.current
    return remember(context, workspaceId, navigator, toaster, artifactOpener) {
        { url ->
            openMarkdownLink(
                context = context,
                url = url,
                workspaceId = workspaceId,
                navigator = navigator,
                onMessage = { message ->
                    if (toaster != null) toaster.show(message, type = ToastType.Error)
                    else android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                },
                artifactOpener = artifactOpener,
            )
        }
    }
}
