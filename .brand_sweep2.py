# -*- coding: utf-8 -*-
"""文档品牌替换 + 类文件重命名（第一轮脚本未完成的部分）。"""
edits = {
    r'README.md': [
        ('# Pi Mobile', '# PiPilot（领航 Pi）'),
        ('Pi Mobile 是 [Pi 编程智能体]', 'PiPilot（领航 Pi）是 [Pi 编程智能体]'),
        ('git clone https://github.com/ayagmar/pi-mobile.git', 'git clone https://github.com/GuoZhenKuang/pipilot.git'),
        ('cd pi-mobile/bridge', 'cd pipilot/bridge'),
        ('![Pi Mobile 聊天与工具流式输出截图]', '![PiPilot 聊天与工具流式输出截图]'),
        ('![Pi Mobile 会话浏览截图]', '![PiPilot 会话浏览截图]'),
        ('安装 APK 或从源码构建：', '安装 APK 或从源码构建：\n\n> 本项目基于 [ayagmar/pi-mobile](https://github.com/ayagmar/pi-mobile) 早期版本发展而来，现由 GuoZhenKuang 独立维护与发布。'),
    ],
    r'README.en.md': [
        ('# Pi Mobile', '# PiPilot'),
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

renames = [
    (r'app/src/main/java/com/ayagmar/pimobile/PiMobileApplication.kt',
     [('class PiMobileApplication : Application()', 'class PipilotApplication : Application()')]),
    (r'app/src/main/java/com/ayagmar/pimobile/ui/PiMobileApp.kt',
     [('fun PiMobileApp(appGraph: AppGraph)', 'fun PipilotApp(appGraph: AppGraph)')]),
    (r'app/src/main/java/com/ayagmar/pimobile/MainActivity.kt',
     [('PiMobileApp(appGraph = appGraph)', 'PipilotApp(appGraph = appGraph)')]),
]
for src, pairs in renames:
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
