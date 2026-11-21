#!/usr/bin/env bash
# 一键启动：收藏夹放 data 下；媒体库里的 收藏夹 用软链指向容器内 /data/收藏夹
# 这样媒体库可以一直是 :ro（只读），收藏照常可写，部署时自动就绪。
set -euo pipefail
cd "$(dirname "$0")/.."
MEDIA_DIR="${COMII_MEDIA_DIR:-library}"
mkdir -p data data/收藏夹 "$MEDIA_DIR"
if [ ! -e "$MEDIA_DIR/收藏夹" ]; then
  # 容器内路径：/library/收藏夹 -> /data/收藏夹（宿主上该链接可能是悬空的，正常）
  ln -sfn /data/收藏夹 "$MEDIA_DIR/收藏夹" 2>/dev/null || true
fi
docker compose up -d "$@"
docker compose logs --tail 20 comii | grep -E "Owner|令牌|收藏夹" || true
