package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import me.rerere.bettertext.latex.LatexBlock
import me.rerere.bettertext.latex.LatexSettings
import me.rerere.bettertext.latex.rememberLatexLayout

/**
 * 块级公式，居中显示，太宽时横向滚动；字号和颜色默认跟随当前文字样式。
 *
 * 用的是 BetterText 自带的离线 KaTeX 移植（不加载 WebView、不下载字体），支持 `aligned`、
 * `cases`、`matrix` 这些常见环境。公式本身解析不了时（例如模型写了 KaTeX 不认识的命令），
 * 退回显示公式源码：等宽小字、可选中，不会崩，也不会只留一个红块。
 */
@Composable
fun MathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    val style = LocalTextStyle.current
    val size = fontSize.takeOrElse { style.fontSize }
    val color = style.color.takeOrElse { LocalContentColor.current }
    val layout = rememberLatexLayout(latex, LatexSettings(displayMode = true))
    if (layout.error != null) {
        // 解析失败：显示源码，用户还能看出模型写了什么，选中复制也方便
        Text(
            text = latex,
            modifier = modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    LatexBlock(
        layout = layout,
        modifier = modifier.padding(8.dp),
        fontSize = size,
        color = color,
    )
}
