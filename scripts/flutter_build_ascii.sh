#!/usr/bin/env bash
# 简阅·Flutter APK 构建脚本（可移植版）
#
# 设计目标：仓库不假设任何机器路径，换机后按标准环境即可构建。
#   1) 源码位置由脚本自身位置推导（本分支 Flutter 工程即仓库根目录）；
#   2) Flutter / JDK / Android SDK 一律取自环境变量与 PATH，可在
#      scripts/local-env.sh（已 gitignore）中做机器级覆盖；
#   3) 仅当源码路径含非 ASCII 字符（且为 Windows 环境）时才启用
#      「ASCII 镜像构建」——规避 Flutter AOT 在非 ASCII 路径下读不了
#      app.dill 的已知问题；源码路径本来就是 ASCII 时直接原地构建；
#   4) 产物版本号自动取自 pubspec.yaml，不再在脚本里写死。
#
# 用法：bash scripts/flutter_build_ascii.sh [--debug]
#
# 可覆盖的环境变量：
#   FLUTTER_BIN        Flutter 可执行文件（默认 PATH 上的 flutter）
#   JAVA_HOME          JDK 17+（未设置时由 Flutter/Gradle 自行探测）
#   ANDROID_HOME / ANDROID_SDK_ROOT  Android SDK（至少设置其一更稳妥）
#   JIANYUE_BUILD_DIR  ASCII 镜像根目录（仅在需要镜像时生效；默认自动挑选）
#   JIANYUE_CN_MIRROR  设为 1 时使用国内 pub 镜像（默认尊重现有环境）
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
SRC="$REPO_ROOT"

# 机器级个性化配置（不入库；模板见 local-env.example.sh）
if [ -f "$SCRIPT_DIR/local-env.sh" ]; then
  # shellcheck disable=SC1091
  . "$SCRIPT_DIR/local-env.sh"
fi

# 纯 ASCII 判定（C locale 下 [:print:] 即 0x20-0x7E）
is_ascii() {
  local s="$1" LC_ALL=C
  case "$s" in
    *[![:print:]]*) return 1 ;;
    *) return 0 ;;
  esac
}

case "$(uname -s)" in
  CYGWIN*|MINGW*|MSYS*) ON_WINDOWS=1 ;;
  *) ON_WINDOWS=0 ;;
esac

if [ "$ON_WINDOWS" = 1 ]; then
  export MSYS_NO_PATHCONV=1
  export MSYS2_ARG_CONV_EXCL="*"
fi

FLUTTER="${FLUTTER_BIN:-flutter}"
if ! command -v "$FLUTTER" >/dev/null 2>&1; then
  echo "错误：未找到 Flutter（$FLUTTER）。" >&2
  echo "请安装 Flutter ≥3.38 并加入 PATH，或设置 FLUTTER_BIN 指向可执行文件。" >&2
  exit 1
fi

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK_ROOT" ]; then
  echo "提示：未设置 ANDROID_HOME/ANDROID_SDK_ROOT，将由 Flutter/Gradle 自行探测 Android SDK。"
fi
if [ -z "${JAVA_HOME:-}" ]; then
  echo "提示：未设置 JAVA_HOME，将使用 Flutter/Gradle 探测到的 JDK（需 17+）。"
fi

if [ "${JIANYUE_CN_MIRROR:-0}" = "1" ]; then
  export PUB_HOSTED_URL="${PUB_HOSTED_URL:-https://pub.flutter-io.cn}"
  export FLUTTER_STORAGE_BASE_URL="${FLUTTER_STORAGE_BASE_URL:-https://storage.flutter-io.cn}"
fi

# 选取可写的 ASCII 镜像根目录
pick_mirror_root() {
  local cand
  for cand in "${JIANYUE_BUILD_DIR:-}" "${TMPDIR:-}" \
              "$(cygpath -u "${LOCALAPPDATA:-}/Temp" 2>/dev/null)" \
              "${HOME:-}" "/c/jianyue_build" "/d/jianyue_build"; do
    [ -n "$cand" ] || continue
    is_ascii "$cand" || continue
    if mkdir -p "$cand" 2>/dev/null && [ -w "$cand" ]; then
      printf '%s\n' "$cand"
      return 0
    fi
  done
  return 1
}

BUILD_DIR="$SRC"
if [ "$ON_WINDOWS" = 1 ] && ! is_ascii "$SRC"; then
  MIRROR_ROOT="$(pick_mirror_root)" || {
    echo "错误：源码路径含非 ASCII 字符，且找不到可写的 ASCII 镜像目录。" >&2
    echo "请设置 JIANYUE_BUILD_DIR 指向一个纯 ASCII 且可写的目录后重试。" >&2
    exit 1
  }
  BUILD_DIR="$MIRROR_ROOT/jianyue"
  echo "源码路径含非 ASCII 字符，启用 ASCII 镜像构建：$BUILD_DIR"
  mkdir -p "$BUILD_DIR"
  robocopy "$(cygpath -w "$SRC")" "$(cygpath -w "$BUILD_DIR")" \
    /MIR /XD build .dart_tool .gradle .idea .git \
    /NFL /NDL /NJH /NJS
  rc=$?
  if [ $rc -ge 8 ]; then
    echo "robocopy 失败 rc=$rc" >&2
    exit $rc
  fi
fi

cd "$BUILD_DIR" || exit 1
"$FLUTTER" pub get || exit 1

VARIANT="--release"
if [ "${1:-}" = "--debug" ]; then
  VARIANT="--debug"
fi
"$FLUTTER" build apk "$VARIANT" || exit 1

VERSION="$(sed -n 's/^version:[[:space:]]*\([0-9][0-9.]*\).*/\1/p' "$SRC/pubspec.yaml" | head -n1)"
[ -n "$VERSION" ] || VERSION="dev"

OUT="$BUILD_DIR/build/app/outputs/flutter-apk"
TARGET="$SRC/artifacts"
mkdir -p "$TARGET"
if [ "$VARIANT" = "--debug" ]; then
  cp "$OUT/app-debug.apk" "$TARGET/简阅-Flutter-debug.apk"
  echo "产物: $TARGET/简阅-Flutter-debug.apk"
else
  cp "$OUT/app-release.apk" "$TARGET/简阅-Flutter-$VERSION.apk"
  echo "产物: $TARGET/简阅-Flutter-$VERSION.apk"
fi