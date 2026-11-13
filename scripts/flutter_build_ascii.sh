#!/usr/bin/env bash
# 简阅·Flutter APK 构建脚本（主 Agent 维护）
# 背景：Flutter 3.50 的 AOT 编译器在 Windows 非 ASCII 路径下无法读取 app.dill；
#       故采用「ASCII 镜像目录构建」：源码真身留在 flutter_app/，构建在 ASCII 副本中进行。
# 用法：bash scripts/flutter_build_ascii.sh [--debug]
set -uo pipefail
SRC="D:/软件/mvxxcb-安卓阅读器/flutter_app"
DST="D:/flutter_ascii_build/jianyue"
export PATH="/d/AndroidToolchain/flutter/bin:$PATH"
export PUB_HOSTED_URL="https://pub.flutter-io.cn"
export FLUTTER_STORAGE_BASE_URL="https://storage.flutter-io.cn"
export ANDROID_HOME="D:/AndroidToolchain/android-sdk"
export JAVA_HOME="D:/AndroidToolchain/jdk-21"
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

mkdir -p "$DST"
robocopy "$SRC" "$DST" /MIR /XD build .dart_tool /NFL /NDL /NJH /NJS
rc=$?; if [ $rc -ge 8 ]; then echo "robocopy 失败 rc=$rc"; exit $rc; fi

cd "$DST" || exit 1
flutter pub get || exit 1
VARIANT="--release"
[ "${1:-}" = "--debug" ] && VARIANT="--debug"
flutter build apk $VARIANT || exit 1

OUT="$DST/build/app/outputs/flutter-apk"
TARGET="$SRC/artifacts"
mkdir -p "$TARGET"
if [ "$VARIANT" = "--debug" ]; then
  cp "$OUT/app-debug.apk" "$TARGET/简阅-Flutter-debug.apk"
  echo "产物: $TARGET/简阅-Flutter-debug.apk"
else
  cp "$OUT/app-release.apk" "$TARGET/简阅-Flutter-1.0.0.apk"
  echo "产物: $TARGET/简阅-Flutter-1.0.0.apk"
fi