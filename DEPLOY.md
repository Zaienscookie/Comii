# Comii 部署手册

> 适用于 `main` 分支（服务端 3.3.5，App 3.6.x）。Docker 与裸机两种方式，任选其一。

---

## 1. 结构与端口

```
浏览器 / App
   │
   ├─ :8000  WebUI（页面 + /api/* 兼容接口）
   └─ :8001  数据接口 /api/v1/*（App 用）
        │
        └─ library/  ← 真实磁盘目录，所有增删改直接落盘
```

| 端口 | 用途 | 认证 |
|---|---|---|
| 8000 | WebUI：登录页、浏览、管理页 | 登录会话（cookie） |
| 8001 | App / API：`/api/v1/*` | 服务器令牌 + 登录会话 |

数据目录（`COMII_DATA`）里会有：`users.db`（账号）、`thumbs/`、`cache/`、`trash/`、`logs/`、`updates/`、`server.conf`。

---

## 2. 方式一：Docker Compose（推荐）

```bash
git clone https://github.com/Zaienscookie/Comii.git
cd Comii

mkdir -p data
# 编辑 docker-compose.yml，把 ./library 换成你的媒体目录
#   只读挂载： - /vol1/1000/media:/library:ro
#   要上传/删除：去掉 :ro

docker compose up -d --build
```

首次启动会自动创建 Owner 并生成令牌，直接看日志拿账号：

```bash
docker compose logs comii | grep -E "Owner|令牌"
```

输出示例：
```
已自动创建 Owner：用户名=admin 密码=Abcd@0000（请尽快修改；配置见 /data/server.conf）
已自动生成服务器访问令牌：Hs6E3KbwYfx5nInSoma1MVUuCx1FBZVy（已写入 /data/server.conf）
```

### 自定义 Owner 用户名/密码

在 `docker-compose.yml` 里改：
```yaml
environment:
  COMII_OWNER_USER: "你的用户名"
  COMII_OWNER_PASSWORD: "你的密码"
  COMII_ACCESS_TOKEN: ""        # 留空 = 自动生成
```

或直接写 `<数据目录>/server.conf`（见第 4 节）。

---

## 3. 方式二：docker run

```bash
docker build -t comii:latest .

docker run -d --name comii --restart unless-stopped \
  -p 8000:8000 -p 8001:8001 \
  -e COMII_LIBRARY=/library \
  -e COMII_DATA=/data \
  -e COMII_AUTH_ENABLED=1 \
  -e COMII_OWNER_USER=admin \
  -e COMII_OWNER_PASSWORD=你的密码 \
  -e COMII_ACCESS_TOKEN=            `# 留空自动生成` \
  -v /你的/媒体目录:/library:ro \
  -v /你的/data目录:/data \
  comii:latest
```

> 想改环境变量**不能** `docker update`，只能删除容器重建；数据在 `/data` 卷里，不会丢。

---

## 4. 配置文件 `server.conf`（安全设置集中在这里）

路径：`<数据目录>/server.conf`（默认 `/data/server.conf`），可用 `COMII_CONFIG` 指向别处。

```json
{
  "access_token": "服务器访问令牌，App 和 WebUI 都要填",
  "auth_enabled": true,
  "owner_user": "admin",
  "owner_password": "首次创建时使用，已有账号后忽略"
}
```

- **优先级**：环境变量 > `server.conf` > 默认值
- 改了之后重启容器 / 服务生效
- 首次启动若没有 `access_token`，服务端会生成一个并写回此文件

---

## 5. 方式三：裸机 + systemd

```bash
cd server
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

`/etc/systemd/system/comii.service`：
```ini
[Unit]
Description=Comii server
After=network-online.target

[Service]
Type=simple
User=comii
WorkingDirectory=/opt/comii/server
Environment=COMII_LIBRARY=/srv/media
Environment=COMII_DATA=/opt/comii/data
Environment=PYTHONUNBUFFERED=1
ExecStart=/opt/comii/server/.venv/bin/python -m comii.serve
Restart=always

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now comii
journalctl -u comii -n 30 | grep -E "Owner|令牌"
```

---

## 6. 首次启动做了什么

服务端启动时会执行 `auth.bootstrap()`：

1. 建表（`users` / `sessions` / `grants` / `tokens` / `audit`）
2. 如果 `users` 表为空且 `auth_enabled=true` → **创建 Owner**
   - 用户名取 `owner_user`（默认 `admin`）
   - 密码取 `owner_password`，为空则随机生成
3. 如果没有 `access_token` → **随机生成**
4. 把结果写入 `server.conf`，并在日志里打印一次

> 已有账号时不会再创建，也不会覆盖密码。

---

## 7. 账号与权限

| 角色 | 能力 | 能否修改 |
|---|---|---|
| Owner | 全部，含用户管理 | 预设，不可改；系统至少保留一个 |
| Admin | 内容管理（浏览/下载/上传/新建/删除/移动/封面标签） | 预设，不可改 |
| Viewer | 只读（浏览 + 下载） | 预设，不可改 |
| Custom | **点用户行勾选 7 项权限** | 可改 |

管理页 `/admin.html`：

- 点一行 = 选中该用户 → 下方出现权限勾选（仅 Custom）
- 「状态」按钮：点一下封禁（同时踢下线），再点一下恢复
- 自己的角色不能改、不能封禁自己、不能把最后一个 Owner 降级
- 登录页的「服务器访问令牌」是**所有人都一样**的那个 token

### 两层验证

```
请求 → ① 服务器令牌（X-Comii-Token / cookie comii_access）
     → ② 登录会话（cookie）
     → ③ 角色 / Custom 权限
```
- 无令牌 → `401 无效的服务器访问令牌`
- 有令牌未登录 → `401 请先登录`
- 权限不足 → `403`

---

## 8. 反向代理 / 隧道

### Cloudflare Tunnel
```bash
cloudflared tunnel route dns comii api.example.com
cloudflared tunnel run --url http://127.0.0.1:8001 comii
```
- 必须透传 `X-Comii-Token`、`Cookie`
- 服务端已读取 `CF-Connecting-IP` 作为真实来源（审计日志用）
- 媒体响应带 `Cache-Control: private`，不会被 CDN 缓存

### frp
```ini
[comii-api]
type = tcp
local_ip = 127.0.0.1
local_port = 8001
remote_port = 18001
```

---

## 9. 升级

```bash
cd Comii
git pull
docker compose up -d --build     # 或 systemctl restart comii
```

- 数据库在 `data/`，升级不动
- 服务端版本号看 `GET /api/v1/ping`
- App 更新：把新的 APK 放进 `<data>/updates/`，并写好 `update.json`

---

## 10. 备份与回滚

```bash
# 备份
tar czf comii-data-$(date +%F).tgz data/
# 回滚
docker compose down
tar xzf comii-data-2026-11-16.tgz
docker compose up -d
```

---

## 11. 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `401 无效的服务器访问令牌` | 没带 token 或 token 不对 | 看 `data/server.conf` 的 `access_token`，App/登录页填一致 |
| `401 请先登录` | token 对了但没登录 | 登录页登录 |
| 新建文件夹报“只读” | 库目录 `:ro` 挂载 | 去掉 `:ro` 重新 `up -d` |
| 端口被占用 | 8000/8001 冲突 | 改 `ports` 左值 |
| 登录页空白 | 静态资源缓存 | Ctrl+F5 强刷 |
| 忘记 Owner 密码 | — | 删 `data/users.db` 重启，会按 `owner_password` 重建（媒体不受影响） |
| 管理页提示“仅 Owner” | 当前账号不是 Owner | 用 Owner 登录，或直接改 `users` 表的 `role` |

---

## 12. 安全检查清单

- [ ] `server.conf` 权限设为 `600`（里面有令牌）
- [ ] 公网暴露时只开 8001（或只经隧道），8000 尽量内网
- [ ] 令牌定期轮换：改 `server.conf` → 重启 → App 和登录页同步更新
- [ ] 不要把含账号/令牌的文档提交到公开仓库
- [ ] 备份 `data/`，但备份文件不要放公网可读目录
