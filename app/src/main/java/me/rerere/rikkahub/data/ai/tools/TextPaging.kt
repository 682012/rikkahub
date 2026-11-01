package me.rerere.rikkahub.data.ai.tools

/**
 * 文本分页的边界对齐。
 *
 * 两条分页路径共用这里：工具输出外置后的 [ToolOutputStore] 读回，以及子代理结果的
 * `subagent_result` 分页。分开实现过一次，结果只有工具输出那条处理了代理对，子代理那条会把
 * emoji 切成半个字符（JSON 里是孤立代理项，部分 provider 会拒收）。
 *
 * 坐标统一是**字符**（UTF-16 code unit），与各处的 `totalChars` / `offset` / `limit` 同一套单位。
 */

/**
 * 页首位置。
 *
 * 模型手写的 offset 可能正好落在代理对中间（`🙂` 的后半个码元），那样输出里会出现半个 emoji。
 * 往前回退一位对齐到码点边界；正式分页用的 nextOffset 本身就是边界，不会触发回退。
 */
internal fun alignPageStart(text: CharSequence, start: Int): Int =
    if (start in 1 until text.length && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) {
        start - 1
    } else {
        start
    }

/**
 * 页尾位置。
 *
 * 只在「不切开代理对」上做一点微调：end 落在代理对中间时往后让一位，保证这一页不会输出半个
 * emoji。因为 nextOffset 就取 end，连续翻页依旧不丢字、不重复。
 */
internal fun pageEndFor(text: CharSequence, start: Int, limit: Int): Int {
    var end = (start + limit).coerceAtMost(text.length)
    if (end in 1 until text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) {
        end += 1
    }
    return end
}
