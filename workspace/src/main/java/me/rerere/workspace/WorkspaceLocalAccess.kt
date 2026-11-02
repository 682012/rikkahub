package me.rerere.workspace

import java.io.File

/**
 * 单个工作区 (按 root 区分) 额外的本机访问能力。
 *
 * - [bindMounts] 是该 root 专属的动态挂载。它和 [WorkspaceManager] 构造时传入的静态挂载合并成
 *   这张 root 的挂载表, 同一份表同时喂给 PRoot 的 `-b` 参数 (前台命令/后台命令/job/交互式终端)
 *   和所有文件工具的路径解析, 所以 shell 里看得到的目录, 文件工具也一定读得到, 反之亦然。
 * - [trustedRootfs] 只是让该 root 的 [WorkspaceLayout.writableRoots] 含 `"/"`, 文件工具因此不再逐次
 *   请求审批。它不代表拿到了系统 root: 命令仍然跑在 PRoot 的 Rootfs 里, `/` 指的是 Rootfs 的 `/`。
 *
 * 跨工作区不共享: root A 的动态挂载永远不会出现在 root B 的挂载表里。
 */
data class WorkspaceLocalAccess(
    val bindMounts: List<WorkspaceBindMount> = emptyList(),
    val trustedRootfs: Boolean = false,
)

/**
 * Rootfs 内不允许被动态挂载覆盖、占用或当父目录使用的路径。
 *
 * 这些位置由应用自己映射 (/workspace 是文件区, /skills 等是应用目录), 或被 PRoot 透传给内核
 * (/proc、/sys、/dev); 让用户挂上去会把文件工具的路径解析和 shell 内的实际视图拆成两套。
 */
val RESERVED_BIND_MOUNT_TARGETS: List<String> = listOf(
    "/workspace",
    "/skills",
    "/builtin_skills",
    "/upload",
    "/tool_outputs",
    "/proc",
    "/sys",
    "/dev",
)

/**
 * 把动态挂载的 target 规范化成 Rootfs 内的绝对路径。
 *
 * 拒绝: 空 target、相对路径、NUL、换行、冒号 (PRoot `-b host:guest` 的分隔符)、`..` 段、
 * 以及落到 `/` 的 target (等于覆盖整个 Rootfs)。
 */
fun normalizeBindMountTarget(target: String): String {
    require(target.isNotBlank()) { "Bind mount target must not be blank" }
    require('\u0000' !in target) { "Bind mount target must not contain NUL" }
    require('\n' !in target && '\r' !in target) { "Bind mount target must not contain a newline" }
    require(':' !in target) { "Bind mount target must not contain ':': $target" }
    require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }

    val segments = target.split('/')
    require(segments.none { it == ".." }) { "Bind mount target must not contain '..': $target" }
    val parts = segments.filter { it.isNotEmpty() && it != "." }
    require(parts.isNotEmpty()) { "Bind mount target must not be '/': $target" }
    return "/" + parts.joinToString("/")
}

/**
 * 两个 Rootfs 绝对路径是否相交: 相等, 或一个是另一个的父目录。
 * `/skills` 与 `/skillsets` 不相交。
 */
fun rootfsPathsOverlap(a: String, b: String): Boolean =
    a == b || a.startsWith("$b/") || b.startsWith("$a/")

/** 动态挂载 source 必须是绝对路径且带文件名, 相对路径在 PRoot 里会按进程 cwd 解析, 结果不可预测 */
fun requireValidBindMountSource(source: File) {
    val path = source.path
    require(path.isNotBlank()) { "Bind mount source must not be blank" }
    require('\u0000' !in path) { "Bind mount source must not contain NUL" }
    require('\n' !in path && '\r' !in path) { "Bind mount source must not contain a newline" }
    require(':' !in path) { "Bind mount source must not contain ':'" }
    require(source.isAbsolute) { "Bind mount source must be an absolute path: $path" }
}

/**
 * 校验一条动态挂载, 返回规范化后的 target; 不合法时抛 [IllegalArgumentException]。
 *
 * 只做规则校验, 不碰文件系统, 也不创建/修改 source (宿主目录权限和内容由用户自己管)。
 * [existingTargets] 用于查重, 传当前已生效的 target 列表 (静态 + 同 root 其它动态挂载)。
 */
fun validateLocalBindMount(
    mount: WorkspaceBindMount,
    existingTargets: List<String> = emptyList(),
): String {
    requireValidBindMountSource(mount.source)
    val target = normalizeBindMountTarget(mount.target)
    RESERVED_BIND_MOUNT_TARGETS.forEach { reserved ->
        require(!rootfsPathsOverlap(target, reserved)) {
            "Bind mount target $target conflicts with reserved path $reserved"
        }
    }
    existingTargets.forEach { existing ->
        require(!rootfsPathsOverlap(target, existing)) {
            "Bind mount target $target conflicts with existing mount $existing"
        }
    }
    return target
}

/**
 * 判断某个 Rootfs 路径在文件工具里是否免审批。
 *
 * [WorkspaceLayout.writableRoots] 含 `"/"` 表示这个 root 是信任根 (见 [WorkspaceLocalAccess.trustedRootfs]),
 * 但 [WorkspaceLayout.isWritable] 只做字符串前缀比较, 不把 `"/"` 当通配, 所以统一走这里判断。
 */
fun WorkspaceLayout.isPathWritable(path: String): Boolean =
    writableRoots.contains("/") || isWritable(path)

/**
 * 一个 root 的挂载表: 静态挂载 + 该 root 的动态挂载, 已校验、已按 target 长度降序排好。
 *
 * 这是路径解析的唯一入口: [WorkspaceManager.resolveRootfsPath] 和所有 shell/终端上下文都取这里的
 * [mounts], 不允许各处再各自拼一份。
 */
internal class WorkspaceMountTable private constructor(
    val mounts: List<WorkspaceBindMount>,
) {
    /** 按 target 长度降序, 保证 /a/b 优先于 /a 匹配 */
    private val sorted = mounts.sortedByDescending { it.target.length }

    /** 免审批的挂载目标 */
    val writableTargets: List<String> = mounts.filter { it.writable }.map { it.target }

    /** Rootfs 绝对路径落到哪个宿主目录; 没有挂载命中时返回 null, 由调用方回落到 Rootfs 内部 */
    fun resolve(path: String): RootfsLocation? {
        sorted.forEach { mount ->
            val target = mount.target
            if (path == target) return RootfsLocation(mount.source, "")
            if (path.startsWith("$target/")) {
                return RootfsLocation(mount.source, path.removePrefix("$target/"))
            }
        }
        return null
    }

    companion object {
        /**
         * 合并静态与动态挂载。
         *
         * 静态挂载是应用配置的, 原样保留 (只规范化尾部斜杠), 不做规则校验, 避免一处配置把整个工作区打死。
         * 动态挂载逐条校验, 不合法直接抛错而不是静默忽略, 否则配置错了只会表现为"文件读不到"。
         *
         * 动态 source 不可访问时**不**进入挂载表: 既不声明为已挂载, 也不创建它。PRoot 侧同样按
         * `source.exists()` 过滤 `-b`, 两边因此对齐 —— 这个路径在 shell 和文件工具里都一样不存在。
         */
        fun merge(
            staticMounts: List<WorkspaceBindMount>,
            dynamicMounts: List<WorkspaceBindMount>,
        ): WorkspaceMountTable {
            val accepted = ArrayList<WorkspaceBindMount>(staticMounts.size + dynamicMounts.size)
            staticMounts.forEach { mount ->
                accepted += mount.copy(target = mount.target.trimEnd('/').ifEmpty { "/" })
            }
            val staticTargets = accepted.map { it.target }
            val targets = staticTargets.toMutableSet()

            dynamicMounts.forEach { mount ->
                // User mounts may nest: PRoot and file resolution both use the longest target.
                // Only application-owned static mappings must not be shadowed.
                val target = validateLocalBindMount(mount, staticTargets)
                require(targets.add(target)) { "Duplicate mount target: $target" }
                if (mount.source.exists()) {
                    accepted += mount.copy(target = target)
                }
            }
            return WorkspaceMountTable(accepted)
        }
    }
}
