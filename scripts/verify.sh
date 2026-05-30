#!/usr/bin/env bash
set -euo pipefail

# ─── OC X ALPHA 全量验证脚本 ────────────────────────────────────────────────
# 对标 OpenCodeUI 的 npm run validate: format → lint → typecheck → test → build
#
# 用法:
#   ./scripts/verify.sh              # 全部五层
#   ./scripts/verify.sh --quick      # 跳过 lint（快速回归）
#
# 退出码: 0 = 全部通过, 非 0 = 某层失败

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

FAILED=0

step() {
    echo -e "\n${YELLOW}▸ $1${NC}"
}

ok() {
    echo -e "  ${GREEN}✓ 通过${NC}"
}

fail() {
    echo -e "  ${RED}✗ 失败${NC}"
    FAILED=1
}

# ── 环境准备 ────────────────────────────────────────────────────────────────
export JAVA_HOME="${JAVA_HOME:-/workspace/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/workspace/usr/local/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

GRADLE="./gradlew --no-daemon --console=plain"

QUICK=false
if [[ "${1:-}" == "--quick" ]]; then
    QUICK=true
    echo -e "${YELLOW}快速模式: 跳过 lint${NC}"
fi

# ── 1. 编译检查 (Type Check) ────────────────────────────────────────────────
step "1/5 编译检查 (typecheck)"
if $GRADLE compileDebugKotlin 2>&1 | tail -5; then
    ok
else
    fail
fi

# ── 2. 代码检查 (Lint) ──────────────────────────────────────────────────────
if $QUICK; then
    echo -e "  ${YELLOW}⊘ 跳过 (--quick)${NC}"
else
    step "2/5 代码检查 (lint)"
    if $GRADLE lintDebug 2>&1 | tail -5; then
        ok
    else
        fail
    fi
fi

# ── 3. 单元测试 (Test) ──────────────────────────────────────────────────────
step "3/5 单元测试 (test)"
if $GRADLE testDebugUnitTest 2>&1 | tail -5; then
    ok
else
    fail
fi

# ── 4. APK 构建 (Build) ─────────────────────────────────────────────────────
step "4/5 APK 构建 (assemble)"
if $GRADLE assembleDebug 2>&1 | tail -5; then
    ok
else
    fail
fi

# ── 5. APK 完整性检查 ───────────────────────────────────────────────────────
step "5/5 APK 完整性"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [[ -f "$APK_PATH" ]]; then
    SIZE=$(du -h "$APK_PATH" | cut -f1)
    echo -e "  路径: $APK_PATH"
    echo -e "  大小: $SIZE"
    ok
else
    echo -e "  ${RED}APK 文件不存在${NC}"
    fail
fi

# ── 结果 ─────────────────────────────────────────────────────────────────────
echo ""
if [[ $FAILED -eq 0 ]]; then
    echo -e "${GREEN}══════════════════════════════════════════${NC}"
    echo -e "${GREEN}  全部验证通过${NC}"
    echo -e "${GREEN}══════════════════════════════════════════${NC}"
else
    echo -e "${RED}══════════════════════════════════════════${NC}"
    echo -e "${RED}  验证失败，请修复上方标记的步骤${NC}"
    echo -e "${RED}══════════════════════════════════════════${NC}"
fi

exit $FAILED
