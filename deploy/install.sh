#!/usr/bin/env bash
# Install / refresh Comii on a RHEL 8-like host.
#
#   sudo COMII_ROOT=/root/dev bash deploy/install.sh
#
# The script assumes the repository was copied to $COMII_CODE (default
# $COMII_ROOT/comii). It provisions a self-contained CPython 3.11 when the
# system Python is too old, installs the backend dependencies and installs the
# systemd unit.
set -euo pipefail

COMII_ROOT="${COMII_ROOT:-/root/dev}"
COMII_CODE="${COMII_CODE:-$COMII_ROOT/comii}"
COMII_LIBRARY="${COMII_LIBRARY:-$COMII_ROOT/library}"
COMII_DATA="${COMII_DATA:-$COMII_ROOT/data}"
PYTHON="${COMII_PYTHON:-$COMII_ROOT/.python/bin/python3.11}"
PYTHON_VERSION="${COMII_PYTHON_VERSION:-3.11.9}"

log() { printf '[install] %s\n' "$*"; }

if [ ! -d "$COMII_CODE/server" ]; then
  echo "server/ not found under $COMII_CODE" >&2
  exit 1
fi

if [ ! -x "$PYTHON" ]; then
  log "building CPython $PYTHON_VERSION -> $COMII_ROOT/.python"
  if command -v dnf >/dev/null 2>&1; then
    dnf install -y -q gcc make tar openssl-devel bzip2-devel libffi-devel \
      zlib-devel xz-devel sqlite-devel readline-devel libuuid-devel gdbm-devel \
      ncurses-devel >/dev/null
  fi
  mkdir -p "$COMII_ROOT/.src"
  cd "$COMII_ROOT/.src"
  [ -f "Python-$PYTHON_VERSION.tgz" ] || \
    curl -fsSL -o "Python-$PYTHON_VERSION.tgz" \
      "https://www.python.org/ftp/python/$PYTHON_VERSION/Python-$PYTHON_VERSION.tgz"
  tar xzf "Python-$PYTHON_VERSION.tgz"
  cd "Python-$PYTHON_VERSION"
  [ -f Makefile ] || ./configure --prefix="$COMII_ROOT/.python" \
    --with-ensurepip=install --enable-shared \
    LDFLAGS="-Wl,-rpath,$COMII_ROOT/.python/lib" >/dev/null
  make -j"$(nproc)" >/dev/null
  make install >/dev/null
fi

log "installing python dependencies"
"$PYTHON" -m pip install -q --upgrade pip
"$PYTHON" -m pip install -q -r "$COMII_CODE/server/requirements.txt"

mkdir -p "$COMII_LIBRARY" "$COMII_DATA"

log "writing systemd unit"
cat > /etc/systemd/system/comii.service <<UNIT
[Unit]
Description=Comii server (comic library)
After=network.target

[Service]
Type=simple
WorkingDirectory=$COMII_CODE/server
Environment=COMII_LIBRARY=$COMII_LIBRARY
Environment=COMII_DATA=$COMII_DATA
Environment=COMII_PORT=8000
Environment=COMII_LOG_LEVEL=info
ExecStart=$PYTHON -m uvicorn comii.main:app --host 0.0.0.0 --port 8000
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
log "library: $COMII_LIBRARY"
log "web UI:  http://<server-ip>:8000/"
log "api:     http://<server-ip>:8000/api/v1/ping"
