# Fork 维护流程（可复用模板）

适用于"在上游 App 上叠加私有功能，并保持可持续合并/回退"的场景。RikkaHub fork 是第一个实例，换项目时替换路径和包名即可。

## 0. 一次性准备

- 上游 fork 到自己的账号，从目标 tag 建工作分支（不要用 master）
- 固定签名 keystore，两份备份
- 固定包名与 versionCode 规则
- 准备两条构建命令：快速（跳过 R8）与正式
- 凭据集中存放，仓库只放代码

## 1. 日常改动

```bash
# 改代码（HB 或本地）
# 快速验证
gradle :app:assembleRelease -PfastBuild --daemon

# 推到 GitHub（Git Data API，逐个文件，不 clone）
python3 fork/scripts/push-github.py "fork: 一句话说明"

# 正式包
gradle :app:assembleRelease --daemon

# 发布到手机
scp <apk> /sdcard/Download/<name>-N.apk
```

规则：

- 每次推送必须能编译通过，commit message 写清改了什么
- 新文件要加进 `push-github.py` 的 `FILES` 列表，否则不会被推
- 版本号先 bump 再构建，避免同 versionCode 覆盖
- 出包后校验 md5，再让人安装

## 2. 新功能接入清单

按这个顺序找落点，能少改上游文件：

1. 配置：新建独立 Settings 文件（如 `ForkSettings.kt`），`Settings.kt` 只加一行字段
2. 数据：新表/DAO 放独立文件，`AppDatabase` 只加 entity + version + migration
3. 工具：新建 `XxxTools.kt`，在 `ChatToolFactory` 的 buildList 里追加
4. 执行：新逻辑放独立类，用 Koin 单例注入，避免改核心 service
5. UI：新建页面 + 路由一个 entry；入口放在设置页/菜单
6. 权限/服务：Manifest 只加必要声明

改动越集中，上游升级时 patch 越少。

## 3. 上游升级

```bash
FORK_ROOT=<repo> ./fork/scripts/sync-upstream.sh <new-tag>
```

脚本输出：

- `MISSING upstream`：上游删了文件，需要重新找落点
- `changed: <path>`：本地与上游都改过，需要手工合并
- `patches/<file>.patch`：本地相对上游的差异

合并顺序：先跑通编译，再跑快速包，最后对照功能清单回归。

## 4. 回退

- 代码：切到上一个 tag，重新构建
- 数据：安装前备份；schema 升级不可逆，回退版本要恢复备份
- 签名：keystore 不变才能覆盖；变了只能卸载重装 + 导入备份

## 5. 迁移到新环境

按顺序核对：

1. 代码：仓库地址、分支、基线 commit
2. 构建机：JDK/SDK/Gradle 版本、镜像配置、子模块
3. 签名：keystore + 口令（最关键）
4. 发布通道：scp 路径、网页反向代理
5. 后端服务：地址、token、systemd/nginx
6. 数据：包名是否变化，备份导入路径
7. 配置：依赖模型/外部服务的参数（如 contextLength）

## 6. 故障排查速查

- 构建卡住：检查 Maven 镜像、子模块是否存在、Gradle 是否在下载
- R8 太慢：日常用 `-PfastBuild`，只在交付时跑全量
- 安装失败：签名不一致或 versionCode 回退
- 启动崩溃：先看 logcat `FATAL EXCEPTION`，再看是否缺 Firebase/权限
- 备份导入失败：
  - `Room cannot verify...`：同版本不同分支，走结构对齐
  - `Unexpected JSON token`：settings.json 有 null 或未知枚举/多态类型
  - `A migration from X to Y`：缺迁移步骤
- 长任务被杀：本地看前台服务与 wakelock，远程看 SSH/HTTP 通道

## 7. 文档与凭据

- 流程：本文件
- 凭据：不写进仓库，集中存放并在索引里记录路径、指纹、用途
- 每次发布在仓库 tag + 本地知识库各留一条记录
