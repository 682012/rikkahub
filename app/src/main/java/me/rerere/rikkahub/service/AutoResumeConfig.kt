package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.datastore.Settings

/** 一次决策用到的设置快照，纯数据，方便回归测试直接构造。 */
data class AutoResumeConfig(
    val enabled: Boolean,
    val maxAttempts: Int,
) {
    companion object {
        /** 设置里读出来的真实开关；次数一律夹进 [ChatAutoResume.MIN_ATTEMPTS]..[ChatAutoResume.MAX_ATTEMPTS]。 */
        fun of(settings: Settings): AutoResumeConfig {
            val network = settings.networkSetting
            return AutoResumeConfig(
                enabled = network.autoResumeEnabled,
                maxAttempts = ChatAutoResume.clampAttempts(network.autoResumeMaxAttempts),
            )
        }
    }
}
