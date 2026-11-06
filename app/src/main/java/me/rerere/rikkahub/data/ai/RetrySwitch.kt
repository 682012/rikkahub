package me.rerere.rikkahub.data.ai

/**
 * 「网络 → 自动重试」总开关的**实时**值，给请求层的 [me.rerere.ai.agent.withRetry] 读。
 *
 * 为什么不是直接把 `Settings` 传给请求层：请求层拿到的是一轮开始时的设置快照，运行中用户在设置页
 * 关掉总开关，快照不会变——以前的表现就是"开关已经关了，后台还在按旧快照重试"。这里只维护一个
 * volatile 布尔，写入端是 ChatService 对 `settingsStore.settingsFlow` 的事件订阅（值变化才写），
 * 读取端是每次重试前和退避等待里（每 1 秒）的一次判断：不轮询整个设置对象，也不复制快照。
 *
 * 它只决定"要不要再发一次"：已经在跑的第一次请求不会被它打断，用户取消生成仍然由协程取消负责。
 */
object RetrySwitch {
    @Volatile
    private var enabled: Boolean = true

    /** 现在允不允许自动重试。默认 true，和老的默认设置一致。 */
    fun isEnabled(): Boolean = enabled

    /** 设置变化时写入；只有 enabledAutoRetry 真的变了才会被调用。 */
    fun install(value: Boolean) {
        enabled = value
    }

    /** 测试用：恢复默认状态，避免用例之间互相影响。 */
    fun reset() {
        enabled = true
    }
}
