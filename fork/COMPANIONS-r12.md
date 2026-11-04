# r12 · 六小只与 2D 动态

六只全部内置：GPT 小白龙、Claude 小橙、DeepSeek 小鲸、Gemini 小星、GLM 眠眠、Qwen 小云。形象身份与实际调用模型分开；选择角色不会切换供应商，也不会开启 AI 或自动互动。

## 使用

设置 → 偏好设置 → 通知 → 悬浮角色，横滑选角色。在「角色动态」中选「2D 动态」或「静态」。

- 2D 动态：整图轻呼吸、轻摆、点击回弹；点击显示同一人物的动作图，约 400ms 回待机。
- 静态：不运行附加运动，自定义 GIF/WebP 使用首帧；仍保留点击切图、台词和原快速回复入口。
- 收起、离开前台或系统关闭动画时停止持续运动；拖动不触发点击。
- 六只各 8 句固定台词，洗牌轮换；AI 关闭时不发请求。AI 开启后使用真实模型状态与输出，不拿固定台词冒充生成结果。
- 原 56–112dp 范围与用户大小保留；小屏可以收在边缘，想看清脸与玩偶建议手动选 96dp。

这次是已有 PNG 的轻量 2D 动态，不包含 Live2D 模型、骨骼、独立眼睑、网格变形或物理模拟。没有新增渲染引擎、定时轮询或付费生图。

## 配置兼容

名字、台词、个性、待机覆盖图、点击覆盖图按角色保存。升级后旧非空内容仍留在当前角色，首次切走时保存到该角色的覆盖表，切回来恢复。空项使用素材包已确认的默认值，不覆盖用户文本。

旧 `cat` 仍是小鲸、`dragon` 仍是小白龙；原三项存储键不改，另外新增 `gemini_cat`、`glm_fox`、`qwen_girl`。`custom` 与 `logo` 保留。

模型模式/指定模型、AI 开关、自动互动、大小、动态开关、收起与面板开关保持原值。切角色、改名字或个性会取消旧角色的在途回应并清理它的临时互动记忆；不会清主聊天记录，也不会重置自动互动预算/冷却。

文件备份涵盖所有角色的私有图片，恢复时重定位全部路径；兼容 `file:/` 与 `file:///` 两种本地 URI，仍由应用私有目录白名单检查实际访问。

## 素材与代码

12 张透明 PNG 原字节保存在 `app/src/main/assets/companions/six/<id>/`。原三张 WebP 未删除。完整图像指纹和原文资料在 `fork/companions/companions.json`，Kotlin 清单在 `data/companion/CompanionCatalog.kt`。六份个性、48 句台词沿用包内原文，未重新绘图。

主要实现：`PortraitCompanionBubble.kt`、`PortraitCompanionMotion.kt`、`PortraitCompanionAssets.kt`、`FloatingCompanionSettings.kt`、`CompanionAiController.kt`、`AppearanceBackup.kt`。

## 来源与许可

这些是社区二创形象，不是各厂商官方角色。GPT / Claude / Gemini / GLM / Qwen 设计参考 ZipZipPipe；DeepSeek 参考涉及上善、ZipZipPipe、Small-tailqwq；GLM/Qwen 曾以 GenderSwapAI 三视图作为身份参考。待机抱玩偶与点击姿态为已确认的衍生表达。

本次按个人非商业适配使用，未取得统一商用授权。图片的原帖及具体许可独立于 App 的 AGPL-3.0，不能把代码或工具许可转授给图片。完整来源说明见 `fork/companions/来源与许可.md`。公开再分发前应核对原作者条款，本轮不因构建成功就自动公开发布图片或 APK。

## 验证口径

本次只做与改动相符的检查，结果以交付目录 `APK-VERIFICATION.json`、定向测试记录和 `STATUS.md` 为准。离线 Fit 合成仅检查素材轮廓、比例和浅深底，不是 Android 真机截图。未安装用户手机，未实测真实供应商的六种角色回复表现。
