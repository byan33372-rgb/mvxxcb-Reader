#!/usr/bin/env bash
# 单元测试标准执行脚本（可移植版）
#
# 背景：Windows 中文路径下 JVM 启动器按系统 GBK 解析 Gradle UTF-8 @argfile
#       → 单测类 CNFE；与 JDK 版本无关。
# 方案：源码路径含非 ASCII 字符（且为 Windows 环境）时，robocopy 镜像到
#       ASCII 目录执行；否则原地执行。真树保持唯一源。
#
# 用法：bash scripts/run-unit-tests.sh [--tests "com.mvxxcb.reader.data.*"] [其他 gradle 参数]
#
# 可覆盖的环境变量：
#   GRADLE_CMD         Gradle 可执行文件（默认依次探测：./gradlew → PATH → GRADLE_HOME）
#   JAVA_HOME          JDK 17+（未设置时由 Gradle 自行探测）
#   ANDROID_HOME / ANDROID_SDK_ROOT  Android SDK（镜像构建时写入 local.properties）
#   JIANYUE_BUILD_DIR  ASCII 镜像根目录（仅在需要镜像时生效；默认自动挑选）
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

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

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

# Gradle 解析：显式覆盖 → 仓库 wrapper → PATH → GRADLE_HOME
GRADLE="${GRADLE_CMD:-}"
if [ -z "$GRADLE" ]; then
  if [ -x "$REPO_ROOT/gradlew" ]; then
    GRADLE="$REPO_ROOT/gradlew"
  elif [ -f "$REPO_ROOT/gradlew.bat" ]; then
    GRADLE="$REPO_ROOT/gradlew.bat"
  elif command -v gradle >/dev/null 2>&1; then
    GRADLE="gradle"
  elif [ -n "${GRADLE_HOME:-}" ] && [ -x "$GRADLE_HOME/bin/gradle" ]; then
    GRADLE="$GRADLE_HOME/bin/gradle"
  else
    echo "错误：未找到 Gradle。" >&2
    echo "请安装 Gradle 8.10.2 并加入 PATH，或设置 GRADLE_CMD 指向可执行文件；" >&2
    echo "也可在仓库根目录生成 wrapper：gradle wrapper --gradle-version 8.10.2" >&2
    exit 1
  fi
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

BUILD_DIR="$REPO_ROOT"
if [ "$ON_WINDOWS" = 1 ] && ! is_ascii "$REPO_ROOT"; then
  MIRROR_ROOT="$(pick_mirror_root)" || {
    echo "错误：源码路径含非 ASCII 字符，且找不到可写的 ASCII 镜像目录。" >&2
    echo "请设置 JIANYUE_BUILD_DIR 指向一个纯 ASCII 且可写的目录后重试。" >&2
    exit 1
  }
  BUILD_DIR="$MIRROR_ROOT/reader-verify"
  echo "源码路径含非 ASCII 字符，启用 ASCII 镜像执行：$BUILD_DIR"
  mkdir -p "$BUILD_DIR"
  robocopy "$(cygpath -w "$REPO_ROOT")" "$(cygpath -w "$BUILD_DIR")" \
    /MIR /XD build .gradle .git qa-standalone .idea \
    /NFL /NDL /NJH /NJS
  rc=$?
  if [ $rc -ge 8 ]; then
    echo "robocopy 失败 rc=$rc" >&2
    exit $rc
  fi
fi

# local.properties 仅在镜像执行或缺失时生成，避免覆盖开发机的既有配置。
if [ -n "$SDK_ROOT" ] && [ ! -f "$BUILD_DIR/local.properties" ]; then
  SDK_FWD="$(cygpath -m "$SDK_ROOT" 2>/dev/null || printf '%s' "$SDK_ROOT" | tr '\\' '/')"
  printf 'sdk.dir=%s\n' "$SDK_FWD" > "$BUILD_DIR/local.properties"
fi

cd "$BUILD_DIR" || exit 1
"$GRADLE" :app:testDebugUnitTest --console=plain "$@"
exit $?