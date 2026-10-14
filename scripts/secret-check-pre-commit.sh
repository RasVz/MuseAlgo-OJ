#!/bin/sh
# 本地密钥防回归 hook：提交前检查暂存区 diff 中是否出现密钥明文。
# 无第三方依赖（grep only）；CI 侧另有 gitleaks workflow 全量兜底。
# 安装：cp scripts/secret-check-pre-commit.sh .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
set -e

# 匹配模式：DeepSeek/OpenAI 风格 sk- 密钥、40+ 位高熵 base64 串（JWT 场景）
# 排除 package-lock.json 的 npm integrity 字段（"sha512-...=" 等标准哈希，非密钥）
PATTERN='sk-[A-Za-z0-9]{20,}|[A-Za-z0-9+/]{44,}='

STAGED=$(git diff --cached -U0 | grep -E "^\+" | grep -v "^+++" | grep -v '"integrity"' || true)

if echo "$STAGED" | grep -qE "$PATTERN"; then
    echo "错误：暂存区 diff 中发现疑似密钥明文，已阻止提交。"
    echo "请将密钥移至环境变量（.env 已 gitignore），而不是写进代码/配置。"
    echo
    echo "$STAGED" | grep -E "$PATTERN" | head -5
    exit 1
fi

exit 0
