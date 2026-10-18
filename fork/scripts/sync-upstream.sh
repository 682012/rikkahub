#!/usr/bin/env bash
# 拉取上游 tag，输出与 fork 的差异清单和 patch，便于重新应用侵入点。
# 用法: FORK_ROOT=/data/data1/rk3mod/src ./sync-upstream.sh 3.0.1
set -euo pipefail

TAG="${1:?usage: sync-upstream.sh <tag>}"
FORK_ROOT="${FORK_ROOT:-/data/data1/rk3mod/src}"
WORK="${WORK:-/tmp/rk-upstream-$TAG}"

FILES=(
  app/src/main/java/me/rerere/rikkahub/data/datastore/Settings.kt
  app/src/main/java/me/rerere/rikkahub/data/datastore/ForkSettings.kt
  app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt
  app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt
  app/src/main/java/me/rerere/rikkahub/data/ai/tools/SubagentTools.kt
  app/src/main/java/me/rerere/rikkahub/data/ai/tools/ConversationTools.kt
  app/src/main/java/me/rerere/rikkahub/data/ai/subagent/SubagentRunner.kt
  app/src/main/java/me/rerere/rikkahub/data/ai/subagent/SubagentModels.kt
  app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt
  app/src/main/java/me/rerere/rikkahub/data/repository/RemoteExecutionClient.kt
  app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt
  app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt
  app/src/main/java/me/rerere/rikkahub/data/db/dao/MessageNodeDAO.kt
  app/src/main/java/me/rerere/rikkahub/data/db/dao/UsageRecordDAO.kt
  app/src/main/java/me/rerere/rikkahub/data/db/entity/UsageRecordEntity.kt
  app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_27_28.kt
  app/src/main/java/me/rerere/rikkahub/data/files/FilesManager.kt
  app/src/main/java/me/rerere/rikkahub/data/sync/BackupManager.kt
  app/src/main/java/me/rerere/rikkahub/data/sync/DatabaseBackup.kt
  app/src/main/java/me/rerere/rikkahub/di/AppModule.kt
  app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt
  app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
  app/src/main/java/me/rerere/rikkahub/service/WorkspaceJobForegroundService.kt
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingForkPage.kt
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/UsageSheet.kt
  app/src/main/java/me/rerere/rikkahub/RouteActivity.kt
  app/src/main/AndroidManifest.xml
  app/build.gradle.kts
  workspace/src/main/java/me/rerere/workspace/WorkspaceJob.kt
  workspace/src/main/java/me/rerere/workspace/WorkspaceJobManager.kt
  workspace/src/main/java/me/rerere/workspace/WorkspaceShellRunner.kt
)

mkdir -p "$WORK/src" "$WORK/patches"
echo "downloading $TAG..."
curl -fsSL "https://gh-proxy.com/https://github.com/rikkahub/rikkahub/archive/refs/tags/$TAG.tar.gz" \
  | tar xz -C "$WORK/src" --strip-components=1

echo
echo "== changed files =="
for f in "${FILES[@]}"; do
  if [ ! -f "$WORK/src/$f" ]; then
    echo "MISSING upstream: $f"
    continue
  fi
  if ! diff -q "$WORK/src/$f" "$FORK_ROOT/$f" >/dev/null 2>&1; then
    echo "changed: $f"
    name=$(echo "$f" | tr '/' '_')
    diff -u "$WORK/src/$f" "$FORK_ROOT/$f" > "$WORK/patches/$name.patch" || true
  fi
done

echo
echo "patches: $WORK/patches"
echo "merge manually, then build with -PfastBuild for quick checks."
