#!/usr/bin/env python3
"""把 HB 上的 fork 源码以单次 commit 推到 GitHub。

不 clone 整仓：读取分支当前 head 作为 parent，用 Git Data API 逐个上传
改动文件，再建 tree/commit 并更新分支。适合上行带宽很差的机器。

用法：
    python3 push-github.py "commit message"

前置：
    /workspace/.secrets/gh_token 可读写 682012/rikkahub
    /workspace/.secrets/keys/id_hb 可登录 HB
"""
import base64
import json
import subprocess
import sys
import urllib.request

TOKEN = open('/workspace/.secrets/gh_token').read().strip()
API = 'https://api.github.com'
REPO = '682012/rikkahub'
BRANCH = 'fork/3.0.0'
BASE_TAG_SHA = '55a108a4477fa308d6eacbe22ebf2fb0ceea1420'  # 官方 3.0.0
SRC = '/data/data1/rk3mod/src'
SSH = ['ssh', '-i', '/workspace/.secrets/keys/id_hb', '-p', '20031',
       '-o', 'StrictHostKeyChecking=no', 'root@43.248.3.158']

FILES = [
    'app/src/main/java/me/rerere/rikkahub/data/datastore/Settings.kt',
    'app/src/main/java/me/rerere/rikkahub/data/datastore/ForkSettings.kt',
    'app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt',
    'app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt',
    'app/src/main/java/me/rerere/rikkahub/data/ai/tools/SubagentTools.kt',
    'app/src/main/java/me/rerere/rikkahub/data/ai/tools/ConversationTools.kt',
    'app/src/main/java/me/rerere/rikkahub/data/ai/subagent/SubagentModels.kt',
    'app/src/main/java/me/rerere/rikkahub/data/ai/subagent/SubagentRunner.kt',
    'app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt',
    'app/src/main/java/me/rerere/rikkahub/data/repository/RemoteExecutionClient.kt',
    'app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt',
    'app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt',
    'app/src/main/java/me/rerere/rikkahub/data/db/dao/MessageNodeDAO.kt',
    'app/src/main/java/me/rerere/rikkahub/data/db/dao/UsageRecordDAO.kt',
    'app/src/main/java/me/rerere/rikkahub/data/db/entity/UsageRecordEntity.kt',
    'app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_27_28.kt',
    'app/src/main/java/me/rerere/rikkahub/data/files/FilesManager.kt',
    'app/src/main/java/me/rerere/rikkahub/data/sync/BackupManager.kt',
    'app/src/main/java/me/rerere/rikkahub/data/sync/DatabaseBackup.kt',
    'app/src/main/java/me/rerere/rikkahub/di/AppModule.kt',
    'app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt',
    'app/src/main/java/me/rerere/rikkahub/service/ChatService.kt',
    'app/src/main/java/me/rerere/rikkahub/service/WorkspaceJobForegroundService.kt',
    'app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingForkPage.kt',
    'app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt',
    'app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt',
    'app/src/main/java/me/rerere/rikkahub/ui/pages/chat/UsageSheet.kt',
    'app/src/main/java/me/rerere/rikkahub/RouteActivity.kt',
    'app/src/main/AndroidManifest.xml',
    'app/build.gradle.kts',
    'workspace/src/main/java/me/rerere/workspace/WorkspaceJob.kt',
    'workspace/src/main/java/me/rerere/workspace/WorkspaceJobManager.kt',
    'workspace/src/main/java/me/rerere/workspace/WorkspaceShellRunner.kt',
    'README.md',
    'DISCLAIMER.md',
    'NOTICE.md',
    'fork/README.md',
    'fork/WORKFLOW.md',
    'fork/rk-exec-server.py',
    'fork/scripts/sync-upstream.sh',
    'fork/scripts/push-github.py',
    '.gitignore',
]


def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(
        API + path,
        data=data,
        method=method,
        headers={
            'Authorization': f'Bearer {TOKEN}',
            'Accept': 'application/vnd.github+json',
            'Content-Type': 'application/json',
            'User-Agent': 'rikkahub-fork',
        },
    )
    with urllib.request.urlopen(request, timeout=90) as response:
        return json.loads(response.read())


def read_file(path):
    result = subprocess.run(SSH + ['cat', f'{SRC}/{path}'], capture_output=True)
    if result.returncode != 0:
        raise SystemExit(f'read failed: {path}: {result.stderr.decode()[:200]}')
    return result.stdout


def branch_head():
    try:
        ref = req('GET', f'/repos/{REPO}/git/ref/heads/{BRANCH}')
        return ref['object']['sha']
    except Exception:
        return BASE_TAG_SHA


def main():
    message = sys.argv[1] if len(sys.argv) > 1 else 'fork: update'
    base_sha = branch_head()
    base = req('GET', f'/repos/{REPO}/git/commits/{base_sha}')
    print('base', base_sha[:8], 'tree', base['tree']['sha'])

    items = []
    for path in FILES:
        content = read_file(path)
        blob = req('POST', f'/repos/{REPO}/git/blobs', {
            'content': base64.b64encode(content).decode(),
            'encoding': 'base64',
        })
        items.append({'path': path, 'mode': '100644', 'type': 'blob', 'sha': blob['sha']})
        print('  blob', path, len(content))

    tree = req('POST', f'/repos/{REPO}/git/trees', {'base_tree': base['tree']['sha'], 'tree': items})
    commit = req('POST', f'/repos/{REPO}/git/commits', {
        'message': message,
        'tree': tree['sha'],
        'parents': [base_sha],
    })
    req('PATCH', f'/repos/{REPO}/git/refs/heads/{BRANCH}', {'sha': commit['sha'], 'force': True})
    print('commit', commit['sha'])


if __name__ == '__main__':
    main()
