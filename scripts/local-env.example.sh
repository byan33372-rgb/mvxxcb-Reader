#!/usr/bin/env bash
# 机器级个性化配置模板。
#
# 用法：复制为 scripts/local-env.sh（已被 .gitignore 忽略，不会入库），
#       取消注释并按本机环境填写。所有项均可选：
#       不填写时脚本从 PATH / 环境变量自动探测，标准开发环境无需任何配置。
#
# export GRADLE_CMD="/path/to/gradle/bin/gradle"          # 无 gradlew 且 gradle 不在 PATH 时
# export JAVA_HOME="/path/to/jdk-17"                      # 未全局设置 JAVA_HOME 时
# export ANDROID_HOME="/path/to/android-sdk"               # 未全局设置时
# export JIANYUE_BUILD_DIR="/ascii/path/jianyue_build"     # 仅源码路径含非 ASCII 字符时需要