#!/usr/bin/env bash
# Install / refresh Comii on a RHEL 8-like host.
#
#   sudo bash deploy/install.sh
#
# Expected layout after installation:
#
#   /root/dev/comii/              source (this repository)
#   /root/dev/runtime/python/     self-contained CPython 3.11
#   /root/dev/storage/library/    comic library
#   /root/dev/storage/data/       thumbnails / trash / progress / logs
#   /root/dev/scripts/            helper scripts
set -euo pipefail

COMII_ROOT="${COMII_ROOT:-/root/dev}"
COMII_CODE="${COMII_CODE:-$COMII_ROOT/comii}"
COMII_LIBRARY="${COMII_LIBRARY:-$COMII_ROOT/storage/library}"
COMII_DATA="${COMII_DATA:-$COMII_ROOT/storage/data}"
PYTHON_VERSION="${COMII_PYTHON_VERSION:-3.11.9}"
PYTHON="${COMII_PYTHON:-$COMII_ROOT/runtime/python/bin/python3.11}"

log() { printf '[install] %s\n' "$*"; }

if [ ! -d "$COMII_CODE/server" ]; then
  echo "server/ not found under $COMII_CODE" >&2
  exit 1
fi

if [ ! -x "$PYTHON" ]; then
  log "building CPython $PYTHON_VERSION -> $COMII_ROOT/runtime/python"
  if command -v dnf >/dev/null 2>&1; then
    dnf install -y -q gcc make tar openssl-devel bzip2-devel libffi-devel \
      zlib-devel xz-devel sqlite-devel readline-devel libuuid-devel gdbm-devel \
      ncurses-devel >/dev/null
  fi
  mkdir -p "$COMII_ROOT/runtime" "$COMII_ROOT/.src"
  cd "$COMII_ROOT/.src"
  [ -f "Python-$PYTHON_VERSION.tgz" ] || \
    curl -fsSL -o "Python-$PYTHON_VERSION.tgz" \
      "https://www.python.org/ftp/python/$PYTHON_VERSION/Python-$PYTHON_VERSION.tgz"
  tar xzf "Python-$PYTHON_VERSION.tgz"
  cd "Python-$PYTHON_VERSION"
  ./configure --prefix="$COMII_ROOT/runtime/python" --with-ensurepip=install \
    --enable-shared LDFLAGS="-Wl,-rpath,$COMII_ROOT/runtime/python/lib" >/dev/null
  make -j"$(nproc)" >/dev/null
  make install >/dev/null
  cd /
  rm -rf "$COMII_ROOT/.src"
fi

log "installing python dependencies"
"$PYTHON" -m pip install -q --upgrade pip
"$PYTHON" -m pip install -q -r "$COMII_CODE/server/requirements.txt"

mkdir -p "$COMII_LIBRARY" "$COMII_DATA" "$COMII_ROOT/scripts"

cat > "$COMII_ROOT/README.md" <<DOC
# /root/dev 目录布局

    comii/                  源码（server/、web/、app/、deploy/、tools/、docs/）
    runtime/python/         自带 CPython 3.11 与后端依赖
    storage/library/        漫画库（真实文件夹）
    storage/data/           缩略图缓存、回收站、阅读进度、日志
    scripts/                辅助脚本

服务：    systemctl status comii
WebUI：   http://<host>:8000/
数据接口：http://<host>:8001/api/v1/ping
日志：    journalctl -u comii -f  或  storage/data/logs/comii.log
DOC

log "writing systemd unit"
cat > /etc/systemd/system/comii.service <<UNIT
[Unit]
Description=Comii server (Comic library: WebUI + data API)
After=network.target

[Service]
Type=simple
WorkingDirectory=$COMII_CODE/server
Environment=COMII_LIBRARY=$COMII_LIBRARY
Environment=COMII_DATA=$COMII_DATA
Environment=COMII_PORT=8000
Environment=COMII_API_PORT=8001
Environment=COMII_LOG_LEVEL=info
ExecStart=$PYTHON -m comii.serve
Restart=always
RestartSec=3
User=root

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable comii --quiet
systemctl restart comii
sleep 2
systemctl is-active comii

log "library:  $COMII_LIBRARY"
log "web UI:   http://<server-ip>:8000/"
log "data API: http://<server-ip>:8001/api/v1/ping"
