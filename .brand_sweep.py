# -*- coding: utf-8 -*-
"""PiPilot 品牌替换：仅用户可见品牌，协议常量 pi-mobile-* 保持不变。"""
import io

edits = {
    r'app/src/main/res/values/strings.xml': [
        ('<string name="app_name">pi-mobile</string>', '<string name="app_name">PiPilot</string>'),
    ],
    r'app/src/main/AndroidManifest.xml': [
        ('android:name=".PiMobileApplication"', 'android:name=".PipilotApplication"'),
    ],
    r'app/src/main/java/com/ayagmar/pimobile/hosts/HostPairingPayload.kt': [
        ('"这不是 Pi Mobile 配对二维码"', '"这不是 PiPilot 配对二维码"'),
    ],
    r'app/src/main/java/com/ayagmar/pimobile/sessions/ShareNavigationCoordinator.kt': [
        ('"该 Pi Mobile 链接无效或不受支持"', '"该 PiPilot 链接无效或不受支持"'),
    ],
    r'app/src/main/java/com/ayagmar/pimobile/ui/PiMobileApp.kt': [
        ('?: "Pi Mobile")', '?: "PiPilot")'),
    ],
    r'app/src/main/java/com/ayagmar/pimobile/ui/chat/ClipboardSupport.kt': [
        ('private const val CLIPBOARD_LABEL = "Pi Mobile"', 'private const val CLIPBOARD_LABEL = "PiPilot"'),
    ],
    r'app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatScreen.kt': [
        ('ClipData.newPlainText("Pi Mobile", pendingText)', 'ClipData.newPlainText("PiPilot", pendingText)'),
    ],
    r'app/src/main/java/com/ayagmar/pimobile/ui/chat/HandoffSummary.kt': [
        ('add("Pi Mobile 交接摘要")', 'add("PiPilot 交接摘要")'),
    ],
    r'app/src/main/java/com/ayagmar/pimobile/ui/hosts/HostsScreen.kt': [
        ('"继续之前，请在电脑上启动 Pi Mobile Bridge，"', '"继续之前，请在电脑上启动 PiPilot Bridge，"'),
    ],
    r'app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatImagePresentation.kt': [
        ('File(shareDirectory, "pi-mobile-image.$extension")', 'File(shareDirectory, "pipilot-image.$extension")'),
    ],
    r'app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatComposer.kt': [
        ('presentation.displayName ?: "pi-mobile-image"', 'presentation.displayName ?: "pipilot-image"'),
    ],
    r'settings.gradle.kts': [
        ('rootProject.name = "pi-mobile"', 'rootProject.name = "pipilot"'),
    ],
    r'bridge/package.json': [
        ('"name": "pi-mobile-bridge"', '"name": "pipilot-bridge"'),
    ],
    r'bridge/src/pair.ts': [
        ('Scan this code in Pi Mobile', 'Scan this code in PiPilot'),
    ],
    r'bridge/src/share-links.ts': [
        ('<title>Open in Pi Mobile</title>', '<title>Open in PiPilot</title>'),
        ('<h1>Open in Pi Mobile</h1>', '<h1>Open in PiPilot</h1>'),
        ('">Open in Pi Mobile</a></p>', '">Open in PiPilot</a></p>'),
        ('open Pi Mobile and review the host details', 'open PiPilot and review the host details'),
    ],
    r'bridge/src/extensions/pi-mobile-tree.ts': [
        ('Internal Pi Mobile tree navigation command', 'Internal PiPilot tree navigation command'),
    ],
    r'bridge/src/extensions/pi-mobile-workflows.ts': [
        ('Internal Pi Mobile workflow command', 'Internal PiPilot workflow command'),
    ],
    r'bridge/src/config.ts': [
        ('path.join(os.homedir(), ".pi-mobile")', 'path.join(os.homedir(), ".pipilot")'),
    ],
    r'.github/workflows/ci.yml': [
        ('name: pi-mobile-debug', 'name: pipilot-debug'),
    ],
    r'README.md': [
        ('# Pi Mobile\n', '# PiPilot（领航 Pi）\n'),
        ('Pi Mobile 是 [Pi 编程智能体]', 'PiPilot（领航 Pi）是 [Pi 编程智能体]'),
        ('git clone https://github.com/ayagmar/pi-mobile.git', 'git clone https://github.com/GuoZhenKuang/pipilot.git'),
        ('cd pi-mobile/bridge', 'cd pipilot/bridge'),
        ('![Pi Mobile 聊天与工具流式输出截图]', '![PiPilot 聊天与工具流式输出截图]'),
        ('![Pi Mobile 会话浏览截图]', '![PiPilot 会话浏览截图]'),
        ('安装 APK 或从源码构建：', '安装 APK 或从源码构建：\n\n> 本项目基于 [ayagmar/pi-mobile](https://github.com/ayagmar/pi-mobile) 早期版本发展而来，现由 GuoZhenKuang 独立维护与发布。'),
    ],
    r'README.en.md': [
        ('# Pi Mobile\n', '# PiPilot\n'),
        ('Pi Mobile is an Android client', 'PiPilot is an Android client'),
        ('git clone https://github.com/ayagmar/pi-mobile.git', 'git clone https://github.com/GuoZhenKuang/pipilot.git'),
        ('cd pi-mobile/bridge', 'cd pipilot/bridge'),
        ('![Pi Mobile chat and tool streaming screenshot]', '![PiPilot chat and tool streaming screenshot]'),
        ('![Pi Mobile session browsing screenshot]', '![PiPilot session browsing screenshot]'),
        ('Install the APK or build from source:', 'Install the APK or build from source:\n\n> This project started from an early version of [ayagmar/pi-mobile](https://github.com/ayagmar/pi-mobile) and is now independently maintained and released by GuoZhenKuang.'),
    ],
    r'AGENTS.md': [
        ('# Pi Mobile 贡献者与智能体指南', '# PiPilot 贡献者与智能体指南'),
    ],
}

fail = False
for path, pairs in edits.items():
    with open(path, encoding='utf-8', newline='') as fh:
        c = fh.read()
    for old, new in pairs:
        n = c.count(old)
        if n != 1:
            print(f'MISMATCH {path}: "{old[:60]}" x{n}')
            fail = True
            continue
        c = c.replace(old, new)
    with open(path, 'w', encoding='utf-8', newline='') as fh:
        fh.write(c)

# 类与文件重命名：PiMobileApplication -> PipilotApplication, PiMobileApp(composable) -> PipilotApp
renames = [
    (r'app/src/main/java/com/ayagmar/pimobile/PiMobileApplication.kt',
     r'app/src/main/java/com/ayagmar/pimobile/PipilotApplication.kt',
     [('class PiMobileApplication : Application()', 'class PipilotApplication : Application()')]),
    (r'app/src/main/java/com/ayagmar/pimobile/ui/PiMobileApp.kt',
     r'app/src/main/java/com/ayagmar/pimobile/ui/PipilotApp.kt',
     [('fun PiMobileApp(appGraph: AppGraph)', 'fun PipilotApp(appGraph: AppGraph)')]),
    (r'app/src/main/java/com/ayagmar/pimobile/MainActivity.kt', None,
     [('PiMobileApp(appGraph = appGraph)', 'PipilotApp(appGraph = appGraph)')]),
]
for src, dst, pairs in renames:
    with open(src, encoding='utf-8', newline='') as fh:
        c = fh.read()
    for old, new in pairs:
        n = c.count(old)
        if n != 1:
            print(f'MISMATCH rename {src}: "{old[:60]}" x{n}')
            fail = True
            continue
        c = c.replace(old, new)
    with open(src, 'w', encoding='utf-8', newline='') as fh:
        fh.write(c)

print('DONE' if not fail else 'FAIL')
raise SystemExit(1 if fail else 0)
