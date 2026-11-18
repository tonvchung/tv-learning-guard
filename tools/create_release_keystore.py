#!/usr/bin/env python3
"""首次发布前创建本地签名密钥；密码仅写入已被 Git 忽略的本地配置。"""
from __future__ import annotations

import os
import secrets
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SIGNING = ROOT / "local/signing"
KEYSTORE = SIGNING / "learning-guard-release.jks"
PROPERTIES = SIGNING / "keystore.properties"
BACKUP = ROOT / "local-backups/signing"


def main() -> int:
    if KEYSTORE.exists() or PROPERTIES.exists():
        print("签名材料已存在，未覆盖。", file=sys.stderr)
        return 2
    if len(sys.argv) != 2:
        print("用法：create_release_keystore.py <keytool路径>", file=sys.stderr)
        return 2
    SIGNING.mkdir(parents=True, exist_ok=True)
    BACKUP.mkdir(parents=True, exist_ok=True)
    store_password = secrets.token_urlsafe(32)
    key_password = secrets.token_urlsafe(32)
    subprocess.run([
        sys.argv[1], "-genkeypair", "-v", "-keystore", str(KEYSTORE),
        "-storetype", "JKS", "-storepass", store_password, "-keypass", key_password,
        "-alias", "learning-guard", "-keyalg", "RSA", "-keysize", "3072",
        "-validity", "10000", "-dname", "CN=IcyBard Learning Guard, OU=Home, O=Personal, C=CN",
    ], check=True, stdout=subprocess.DEVNULL)
    PROPERTIES.write_text(
        "storeFile=local/signing/learning-guard-release.jks\n"
        f"storePassword={store_password}\n"
        "keyAlias=learning-guard\n"
        f"keyPassword={key_password}\n",
        encoding="utf-8",
    )
    try:
        os.chmod(PROPERTIES, 0o600)
        os.chmod(KEYSTORE, 0o600)
    except OSError:
        pass
    shutil.copy2(KEYSTORE, BACKUP / KEYSTORE.name)
    shutil.copy2(PROPERTIES, BACKUP / PROPERTIES.name)
    print("生产密钥及本地签名配置已创建，并在工作区忽略目录保留备份。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
