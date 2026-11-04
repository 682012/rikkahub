# 来源、署名与许可边界

## 角色与图片

六位均为社区二创或本次衍生表达，不是 OpenAI、Anthropic、DeepSeek、Google、智谱或阿里发布的统一官方角色与人格。

- GPT / Claude / Gemini / GLM / Qwen：角色参考署名 **ZipZipPipe**。
- DeepSeek：参考涉及 **上善、ZipZipPipe、Small-tailqwq** 等来源；部分具体素材带 CC BY-NC-SA 4.0 标注，应按各自原帖核对，不把一张图的条款扩大成整包统一授权。
- GLM / Qwen 使用过 GenderSwapAI 的三视图 AI adaptation 作为身份参考。
- 抱玩偶坐姿、成年/小形态系列，以及这次的点击反应属于在这些参考基础上的衍生表现。

参考索引：

| 角色 | 人物页 |
| --- | --- |
| GPT | https://genderswapai.com/ai-gijinka/gpt |
| Claude | https://genderswapai.com/ai-gijinka/claude |
| DeepSeek | https://genderswapai.com/ai-gijinka/deepseek |
| Gemini | https://genderswapai.com/ai-gijinka/gemini |
| GLM | https://genderswapai.com/ai-gijinka/glm |
| Qwen | https://genderswapai.com/ai-gijinka/qwen |

ZipZipPipe 作者主页：https://space.bilibili.com/4168597/dynamic 。这些链接是来源指针，本交接轮没有重新抓取所有原帖条款。

本包没有取得、也不额外授予角色设计或图片的统一商业授权。保留署名，以个人非商业适配为当前使用场景；若公开发布、商用或重新分发到更广范围，应先回原作者页面确认条件。**脚本开源许可不转授角色图片**。

## RikkaHub 参考源码与补丁

`reference/r10/` 的相关源码来自用户现有的 RikkaHub fork 参考树，其根许可实读为 **GNU AGPL v3**，完整文本保存在 `licenses/RikkaHub-AGPL-3.0.txt`。

这里只提供与适配有关的 22 份源码/资源/测试快照，供理解与差异比对，不是完整可构建项目。其原许可与署名保持不变，不能因为装进这个包就改成 MIT。

`patches/r10-static-tap-random.patch` 是针对该参考源码的极小衍生修改，不为其宣称独立于原项目的宽松重许可。对外发布修改后的 App / 源码时应遵守目标项目的实际许可证义务。

## 离线工具与文档

- `tools/image_ops.py` 复用同系立绘工坊的只读像素检查模块；原 MIT 许可见 `licenses/character-cutout-MIT.txt`。
- 本交接包新写的 `tools/verify_bundle.py`、`tools/check_images.py` 以及说明文档，按 `licenses/handoff-tools-MIT.txt` 许可。
- 不把上述 MIT 许可扩展到 `assets/` 图片、`reference/` 源码或它们的衍生作品。

`preview.html` 不依赖在线字体、CDN、模型或外部脚本。包内没有私钥、API Key、账号会话、签名 keystore、APK 或用户聊天数据库。
