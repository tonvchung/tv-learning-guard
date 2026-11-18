#!/usr/bin/env python3
"""Install and initialize Learning Guard without overwriting other TV services."""

import argparse
import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

PACKAGE = "com.icybard.learningguard"
ACCESSIBILITY = f"{PACKAGE}/{PACKAGE}.GuardAccessibilityService"
NOTIFICATION_LISTENER = f"{PACKAGE}/{PACKAGE}.GuardNotificationListenerService"
SETUP_ACTIVITY = f"{PACKAGE}/.SetupActivity"
ROOT = Path(__file__).resolve().parents[2]


def find_adb():
    candidates = []
    if os.environ.get("ADB"):
        candidates.append(Path(os.environ["ADB"]))
    if os.environ.get("ANDROID_HOME"):
        candidates.append(Path(os.environ["ANDROID_HOME"]) / "platform-tools" / "adb.exe")
        candidates.append(Path(os.environ["ANDROID_HOME"]) / "platform-tools" / "adb")
    on_path = shutil.which("adb")
    if on_path:
        candidates.append(Path(on_path))
    # Reuse this workspace's existing tools when available; never hardcode a TV address.
    candidates.append(ROOT.parent / ".tools" / "android-sdk" / "platform-tools" / "adb.exe")
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise SystemExit("找不到 adb，请设置 ADB 或 ANDROID_HOME。")


class Adb:
    def __init__(self, executable, serial):
        self.executable = executable
        self.serial = serial

    def run(self, *args, check=True):
        command = [self.executable, "-s", self.serial, *map(str, args)]
        result = subprocess.run(command, text=True, encoding="utf-8", errors="replace", capture_output=True)
        if check and result.returncode != 0:
            raise RuntimeError(f"ADB 命令失败（退出码 {result.returncode}）：{result.stderr.strip() or result.stdout.strip()}")
        return result.stdout.strip()

    def shell(self, *args, check=True):
        return self.run("shell", *args, check=check)


def component_key(value):
    package, separator, class_name = value.partition("/")
    if separator and class_name.startswith("."):
        class_name = package + class_name
    return f"{package}/{class_name}"


def merged_components(original, component):
    values = [] if original in ("", "null") else [item for item in original.split(":") if item]
    if component_key(component) not in {component_key(item) for item in values}:
        values.append(component)
    return ":".join(values)


def main():
    parser = argparse.ArgumentParser(description="学习守护 ADB 初始化（不执行 Device Owner）")
    parser.add_argument("--serial", default=os.environ.get("ADB_SERIAL"), help="ADB 设备序列号或 IP:端口")
    parser.add_argument("--connect", help="可选：先连接的电视 IP:端口，不写入任何文件")
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/release/app-release.apk")
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--keep-adb", action="store_true", help="在这台 Android 9 电视上保持网络 ADB；不修改其他电视的设置")
    args = parser.parse_args()
    serial = args.serial or args.connect
    if not serial:
        parser.error("请通过 --serial 或 --connect 显式指定设备")

    adb_path = find_adb()
    if args.connect:
        subprocess.run([adb_path, "connect", args.connect], check=True)
    adb = Adb(adb_path, serial)
    state = adb.run("get-state")
    if state != "device":
        raise SystemExit(f"设备未就绪：{state}")

    if not args.skip_install:
        if not args.apk.is_file():
            raise SystemExit(f"APK 不存在：{args.apk}")
        print("安装守护 APK…")
        adb.run("install", "-r", str(args.apk.resolve()))

    keys = ("enabled_accessibility_services", "enabled_notification_listeners", "accessibility_enabled")
    original = {key: adb.shell("settings", "get", "secure", key) for key in keys}
    backup_dir = ROOT / "local-backups"
    backup_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = backup_dir / f"secure-settings-{timestamp}.json"
    backup.write_text(json.dumps({"serial": serial, "settings": original}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"原系统设置已备份到本地忽略目录：{backup.name}")

    print("授予使用情况访问与安全设置权限…")
    adb.shell("pm", "grant", PACKAGE, "android.permission.WRITE_SECURE_SETTINGS")
    adb.shell("appops", "set", PACKAGE, "android:get_usage_stats", "allow")
    print("解除后台运行与待机限制…")
    adb.shell("appops", "set", PACKAGE, "RUN_IN_BACKGROUND", "allow")
    adb.shell("appops", "set", PACKAGE, "RUN_ANY_IN_BACKGROUND", "allow")
    adb.shell("am", "set-standby-bucket", PACKAGE, "active", check=False)
    adb.shell("dumpsys", "deviceidle", "whitelist", f"+{PACKAGE}", check=False)
    if args.keep_adb:
        adb.shell("settings", "put", "global", "learning_guard_keep_adb", "1")
        adb.shell("settings", "put", "global", "adb_enabled", "1")
        print("已启用本机网络 ADB 保持；需要彻底关闭时先设置 learning_guard_keep_adb=0。")

    accessibility = merged_components(original["enabled_accessibility_services"], ACCESSIBILITY)
    listeners = merged_components(original["enabled_notification_listeners"], NOTIFICATION_LISTENER)
    print("合并启用守护服务，保留其他已启用服务…")
    adb.shell("settings", "put", "secure", "enabled_accessibility_services", accessibility)
    adb.shell("settings", "put", "secure", "enabled_notification_listeners", listeners)
    adb.shell("settings", "put", "secure", "accessibility_enabled", "1")
    # Some Android 9 TV builds need the system command as well as the secure setting.
    adb.shell("cmd", "notification", "allow_listener", NOTIFICATION_LISTENER, "0", check=False)

    print("启动首次 PIN 设置与权限自检…")
    adb.shell("am", "start", "-W", "-n", SETUP_ACTIVITY)
    print("初始化命令已执行。后续日常设置请从答题页齿轮扫码，不要分享家长 PIN。")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"初始化失败：{error}", file=sys.stderr)
        raise SystemExit(1)
