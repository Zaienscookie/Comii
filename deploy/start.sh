#!/usr/bin/env bash
# 一键启动：先建好目录（收藏夹在 data 下，宿主机可写，媒体库可以只读）
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p data data/收藏夹 library
[ -d library ] && mkdir -p "library/收藏夹" 2>/dev/null || true
docker compose up -d "$@"
docker compose logs --tail 20 comii | grep -E "Owner|令牌|收藏夹" || true
