# r19 HTTP 独立行为对照

`TtsHttpTest.kt` 使用本地 JDK HttpServer，不归入 Android 模块的自动 JVM 单测。它按实际 Android OkHttp 5.5 编译同一份 TtsHttp 源码，运行阶段改用 ABI 兼容的 JVM OkHttp 4.12；这是 HTTP 行为对照，不能宣称为 Android 5.5 运行时点验。

检查完整响应、HTTP 错误码、取消在途正文读取与单段音频大小上限。无真实供应商请求、不花 API 额度。正式 APK 的 Android 集成由 R8 release 编译检查；设备播放、系统音色和真实上游速度另行实用验证。
