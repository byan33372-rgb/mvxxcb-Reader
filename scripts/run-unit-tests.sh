#!/usr/bin/env bash
# 单元测试标准执行脚本（主 Agent 维护）
# 背景：Windows 中文路径下 JVM 启动器按系统 GBK 解析 Gradle UTF-8 @argfile → 单测类 CNFE。
#       JDK17/JDK21 均受影响（sun.jnu.encoding 与 JEP 400 无关）。
# 方案：robocopy 镜像同步真树到 ASCII 验证目录（排除构建产物），在副本中执行单测。
#       真树保持唯一源，build/assembleDebug 在真树执行不受影响。
# 用法: bash scripts/run-unit-tests.sh [--tests "com.mvxxcb.reader.data.*"] [其他 gradle 参数]
set -uo pipefail
SRC="D:/软件/mvxxcb-安卓阅读器"
DST="D:/reader-jianyue-verify"
export JAVA_HOME="D:/AndroidToolchain/jdk-21"
export ANDROID_HOME="D:/AndroidToolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

robocopy "$SRC" "$DST" /MIR /XD build .gradle .git qa-standalone /NFL /NDL /NJH /NJS; rc=$?
if [ $rc -ge 8 ]; then echo "robocopy 失败 rc=$rc"; exit $rc; fi
printf 'sdk.dir=D:/AndroidToolchain/android-sdk\n' > "$DST/local.properties"

cd "$DST" || exit 1
"D:/AndroidToolchain/gradle-8.10.2/bin/gradle.bat" :app:testDebugUnitTest --console=plain "$@"
exit $?
