# Comii 交接文档

> 交接日期：2026-11-14  
> 当前 App：**3.1.7-beta / versionCode 62**  
> 当前服务端镜像：**comii:3.1.0-beta-arm64**  
> 当前项目目录（旧电脑）：`D:\.Desktop\komi_source_delivery`  
> GitHub：<https://github.com/Zaienscookie/Comii>  
> 原则：**NAS 的 R18 原文件只读；不要删除、移动或覆盖原片。**

---

## 1. 现在是什么架构

```
Android App / 浏览器
        │
        ├── 8000  WebUI
        └── 8001  App 数据 API (/api/v1)
                │
        ┌───────▼────────┐
        │ comii 容器      │
        │ 3.1.0-beta-arm64│
        └───┬────────┬───┘
            │        │
       /library    /data
       （媒体）    （用户/审计/缩略图/回收站/更新）
            │
     ┌──────┴─────────────┐
     │                    │
R18 原片（只读）      可写上传目录
/volume1/R18         /volume1/R18/neko 等
```

两层验证：

1. **服务器访问令牌**：保护 8001 数据接口，NAS 的 WebUI 8000 的 API 也校验。
2. **用户账号**：Owner / Admin / Viewer / Custom，控制具体权限。

`neko` 机器已关闭登录验证，也不要求访问令牌；NAS 需要。

---

## 2. NAS 信息

| 项 | 值 |
|---|---|
| 局域网 IP | `192.168.101.17` |
| Tailscale IP | `100.74.140.98` |
| 公网 SSH | `123.138.60.69:11625` |
| SSH 用户 | `debug1` |
| SSH 密码 | `Hzc20051001` |
| 容器名 | `comii` |
| 镜像 | `comii:3.1.0-beta-arm64` |
| WebUI | `http://192.168.101.17:8000` |
| App API | `http://192.168.101.17:8001` |
| 数据目录（宿主机） | `/volume1/docker/comii/data` |
| 数据目录（容器） | `/data` |
| 媒体目录（宿主机） | `/volume1/R18` |
| 媒体目录（容器） | `/library` |

> 交接后建议立即修改 SSH 密码和服务器访问令牌。

### 容器端口

```text
8000 -> WebUI
8001 -> App /api/v1
```

### 容器挂载

| 宿主机 | 容器 | 权限 |
|---|---|---|
| `/volume1/R18` | `/library` | **rw** |
| `/volume1/R18/#recycle` | `/library/#recycle` | ro |
| `/volume1/R18/本子` | `/library/本子` | ro |
| `/volume1/R18/写真` | `/library/写真` | ro |
| `/volume1/R18/图包` | `/library/图包` | ro |
| `/volume1/R18/of` | `/library/of` | ro |
| `/volume1/docker/comii/data` | `/data` | rw |

注意：`#recycle` 是绿联回收站，必须保持只读，不要删除。

### 容器环境变量

```text
COMII_LIBRARY=/library
COMII_DATA=/data
COMII_PORT=8000
COMII_API_PORT=8001
COMII_MAX_UPLOAD_MB=8192
COMII_ACCESS_TOKEN=ComiiNAS_2026_9F4kQ
```

`COMII_AUTH_ENABLED` 未设置，默认是开启。

---

## 3. 服务器访问令牌（所有人共用）

```text
ComiiNAS_2026_9F4kQ
```

请求 8001 时必须带：

```http
X-Comii-Token: ComiiNAS_2026_9F4kQ
```

没有这个头：

```json
HTTP 401
{"detail":"无效的服务器访问令牌"}
```

App 的服务器设置、Login 页、WebUI 登录页都需要这个 Token。

> 这个 Token 不是用户权限 Token，只是服务器白名单。泄露后任何人都能尝试登录，所以不要发到公开群。轮换方法见第 11 节。

---

## 4. 账号

| 用户名 | 密码 | 角色 |
|---|---|---|
| `owner233` | `2778verysafe` | Owner |
| `admin233` | `hhh52778very` | Admin |
| `viewer233` | `123456` | Viewer |

- Owner：全部权限 + 管理用户 + Token
- Admin：内容全部权限，**不能管理用户**
- Viewer：只能看
- Custom：按目录授权，权限包括 `view / download / upload / create / delete / move / manage`

之前的测试用户 `adminlaner`、`zain` 已删除。

### Viewer 的实际限制

服务端已实际测试：

```text
GET  /api/v1/trash        -> 403 没有 delete 权限
POST /api/v1/tags/create  -> 403 没有 manage 权限
POST /api/v1/favorites    -> 403 没有 manage 权限
POST /api/v1/rename       -> 403 没有 manage 权限
GET  /api/v1/export       -> 403 没有 download 权限
GET  /api/v1/browse       -> 200
```

App 长按相册时，Viewer 只显示「打开」。回收站、标签、收藏、封面、重命名、剪切、下载都已隐藏并由服务端拒绝。

> 重要：Viewer 仍然能看到整个媒体库，而整个库是 R18。只想给朋友看部分目录时，不要给 Viewer，给 Custom 并只授权具体目录。

---

## 5. WebUI

登录：

```text
http://192.168.101.17:8000/login.html
```

需要：

```text
服务器访问令牌：ComiiNAS_2026_9F4kQ
用户名 / 密码
```

管理页：

```text
http://192.168.101.17:8000/admin.html
```

- 只有 Owner 能打开
- Admin 打开会显示「仅 Owner 可管理用户」
- 服务端对 Admin 的用户管理接口返回 403
- WebUI 退出登录：右上角更多 → 退出登录
- 页面改动后强制刷新：`Ctrl + F5`

WebUI 登录成功后会在浏览器写入 `comii_access` Cookie，供图片/视频请求使用。

---

## 6. App

登录顺序：

```text
服务器地址
→ 服务器访问令牌
→ 用户名
→ 密码
→ 登录
```

- 没有第二个 API Token 输入框
- 登录成功后会自动进入 App
- 退出登录：设置 → 账号 → 退出登录
- Token / 服务器地址保存在本地，下次自动填
- 图片、视频、更新请求都会带 Token
- 视频播放器 ExoPlayer 已单独加 Token header

当前包：

```text
release/Comii-3.1.7-beta.apk
SHA-256: 471ac18e2824293dbd0309e5e05acb64b7445135b486a23a0aab51c2b4a8f0e4
```

更新接口：

```text
http://192.168.101.17:8001/api/v1/app/update
http://192.168.101.17:8001/api/v1/app/update/download
```

更新接口目前**不需要登录，也不强制 Token**，这是为了旧版本还能升级。媒体数据接口仍然强制 Token。

> 旧版 3.0.7 不支持新鉴权，会 401，必须手动安装 3.1.7。

---

## 7. 用户与权限设计

### 角色固定权限

| 能力 | Owner | Admin | Viewer |
|---|---|---|---|
| 浏览 / 阅读 | ✅ | ✅ | ✅ |
| 下载 / 分享原图 | ✅ | ✅ | ❌ |
| 上传 | ✅ | ✅ | ❌ |
| 新建文件夹 | ✅ | ✅ | ❌ |
| 删除 / 回收站 | ✅ | ✅ | ❌ |
| 移动 / 重命名 | ✅ | ✅ | ❌ |
| 标签 / 封面 | ✅ | ✅ | ❌ |
| 收藏夹 | ✅ | ✅ | ❌ |
| 管理用户 / Token | ✅ | ❌ | ❌ |

### Custom

创建 Custom 用户后，在 WebUI 管理页按目录前缀授权：

```text
path_prefix = neko/zain
view = true
其他 = false
```

能力：

```text
view / download / upload / create / delete / move / manage
```

最长匹配的目录授权生效；没有匹配授权就是拒绝。

---

## 8. 审计日志

- 记录登录成功、登录失败、退出、修改类操作
- 默认保留 **30 天**
- 写入新日志时清理 30 天以前的数据
- 数据库：`/volume1/docker/comii/data/users.db`
- 表：`audit_logs`
- WebUI：`/admin.html` 底部「审计日志」

查看示例：

```bash
sudo docker exec -it comii python - <<'PY'
from comii import auth
for row in auth.list_audit(50):
    print(row)
PY
```

---

## 9. 本机开发环境

旧电脑路径：

```text
D:\.Desktop\komi_source_delivery
```

### Android 构建

需要：

- JDK 17
- Android SDK 35
- Gradle 8.9（项目内已有 `buildtools/gradle/gradle-8.9`）
- `comii.jks`
- `app/local.properties`

当前 `app/local.properties`：

```properties
sdk.dir=D:/.Desktop/komi_source_delivery/buildtools/sdk
COMII_KEYSTORE=D:/.Desktop/komi_source_delivery/comii.jks
COMII_KEYSTORE_PASSWORD=comii2026
COMII_KEY_ALIAS=comii
COMII_KEY_PASSWORD=comii2026
```

新电脑路径变了，必须改这里。

构建命令：

```powershell
cd D:\.Desktop\komi_source_delivery\app
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:GRADLE_USER_HOME='D:\.Desktop\komi_source_delivery\buildtools\gradle-home'
$env:ANDROID_HOME='D:\.Desktop\komi_source_delivery\buildtools\sdk'
$env:ANDROID_USER_HOME='D:\.Desktop\komi_source_delivery\buildtools\android-home'
& 'D:\.Desktop\komi_source_delivery\buildtools\gradle\gradle-8.9\bin\gradle.bat' assembleRelease `
  -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease --console=plain
```

输出：

```text
app/app/build/outputs/apk/release/app-release.apk
```

正式签名证书：

```text
CN=Comii
SHA-256: 8eb8a2d4d2b6062d48c12889f9eff7903b88718fc1bcbc2e1d3fb6229fcf98a8
```

`comii.jks` 密码 `comii2026`，alias `comii`。**不要丢，否则不能覆盖安装。**

### 服务端本地运行

```powershell
cd D:\.Desktop\komi_source_delivery\server
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements-dev.txt
$env:COMII_LIBRARY='D:\.Desktop\komi_source_delivery\test-library'
$env:COMII_DATA='D:\.Desktop\komi_source_delivery\test-data'
python run.py
```

---

## 10. 部署脚本

### NAS 构建镜像

```powershell
cd D:\.Desktop\komi_source_delivery
python buildtools\build_overlay_310.py
```

作用：

1. 打包 `server/ web/ deploy/`
2. SFTP 上传到 NAS `/volume1/docker/comii-src`
3. 在 NAS 上 docker build
4. 生成 `comii:3.1.0-beta-arm64`

### NAS 重建容器

```powershell
python buildtools\deploy_310_nas.py
```

作用：

- 删除并重建 `comii` 容器
- 挂载 `/volume1/R18` + `/volume1/docker/comii/data`
- 设置 `COMII_ACCESS_TOKEN=ComiiNAS_2026_9F4kQ`
- **不会删除 data 目录**
- 会执行 ping / 401 检查

> 重建容器会中断服务约 5-10 秒。

### 发布 App 更新

```powershell
python buildtools\publish_228_nas.py
```

作用：

- 复制 APK 到 `/volume1/docker/comii/data/updates`
- 写 `update.json`
- App 的更新接口读取它

### neko / frp

脚本：

```powershell
python buildtools\deploy_frp_233.py
```

连接：

```text
frp-six.com:45435
user: debug
password: password
```

路径：

```text
/home/debug/service/comii
```

systemd：

```text
comii.service
```

neko 已设置：

```ini
Environment=COMII_AUTH_ENABLED=0
```

因此 neko 不需要登录、不需要访问 Token。不要误删这个环境变量。

---

## 11. 轮换服务器访问令牌

1. 修改 `buildtools/deploy_310_nas.py` 里的 `COMII_ACCESS_TOKEN`
2. 重新执行：

```powershell
python buildtools\deploy_310_nas.py
```

3. 把新 Token 发给使用者
4. App / WebUI 重新填
5. 旧 Token 立即失效

当前 Token：

```text
ComiiNAS_2026_9F4kQ
```

---

## 12. 换电脑检查清单

### 必须带走的文件

- [ ] 整个项目目录（至少 Git 仓库）
- [ ] `comii.jks`（签名证书，最重要）
- [ ] `app/local.properties`
- [ ] `buildtools/`（SDK / Gradle / 构建工具）
- [ ] `release/Comii-3.1.7-beta.apk`
- [ ] 本交接文档

### 新电脑

- [ ] 安装 Git
- [ ] 安装 JDK 17
- [ ] 恢复 Android SDK
- [ ] 修改 `app/local.properties` 里的绝对路径
- [ ] 确认 `comii.jks` 存在
- [ ] 能访问 NAS SSH
- [ ] 能打开 WebUI / App

### 验证

```powershell
cd app
gradle assembleRelease
```

然后：

```bash
ssh -p 11625 debug1@123.138.60.69
sudo docker ps --filter name=comii
curl -s http://127.0.0.1:8001/api/v1/auth/status
```

应返回：

```json
{"initialized":true,"auth_enabled":true,"version":"3.1.0-beta"}
```

---

## 13. 备份

### 用户 / 审计 / 缩略图 / 回收站 / 更新包

```bash
sudo tar czf /volume1/docker/comii/data-backup-$(date +%F).tar.gz \
  -C /volume1/docker/comii data
```

### 只备份用户数据库

```bash
sudo cp /volume1/docker/comii/data/users.db \
  /volume1/docker/comii/users-$(date +%F).db
```

### 不要备份/不要删除

- `/volume1/R18` 原片（另外的备份策略）
- `/volume1/R18/#recycle`（绿联回收站）

---

## 14. 常见故障

### 401 无效的服务器访问令牌

- App/WebUI 的 Token 和 `COMII_ACCESS_TOKEN` 不一致
- 重新输入当前 Token：`ComiiNAS_2026_9F4kQ`
- 检查是否只复制了尾部

### 登录成功但界面不跳转

已修，需要 3.1.3 以上。当前用 3.1.7。

### 退出登录后不能登录

已修，需要 3.1.5 以上。当前用 3.1.7。

### 视频全部不能播

已修 ExoPlayer Token header，需要 3.1.6 以上。  
如果仍失败，可能是视频编码/容器格式，不一定是权限。

### Viewer 长按还有很多菜单

已修，需要 3.1.7；服务端也会 403。

### Admin 能管理用户

服务端已限制 Owner-only；WebUI 也已隐藏。  
如果旧页面还能看到，`Ctrl + F5`，并确认登录的是 admin233 而不是残留的 Owner Cookie。

### WebUI 图片 401

WebUI 登录后会把 Token 写入 `comii_access` Cookie；  
清 Cookie 后重新登录，或强制刷新。

---

## 15. 未完成 / 注意

- Cloudflare Tunnel 尚未正式部署，设计见 `docs/Comii-网络链路设计.md`
- 更新接口目前公开，方便旧版本升级；以后可考虑加 Token
- Viewer 能看全库（含 R18），朋友建议用 Custom
- AI 对话在“正在回复”时退出可能丢失回复，尚未修
- 2.24 调试签名用户不能直接覆盖成正式签名包，需要卸载重装
- 视频修复已编译进 3.1.7，但还需要真机确认各格式

---

## 16. 最快恢复流程

1. 新电脑恢复项目和 `comii.jks`
2. 修改 `app/local.properties`
3. 构建 APK
4. NAS 不需要重装，直接访问：
   - WebUI：`http://192.168.101.17:8000/login.html`
   - App：`http://192.168.101.17:8001`
5. Token：`ComiiNAS_2026_9F4kQ`
6. 账号：`owner233 / 2778verysafe`

如果 NAS 容器损坏：

```powershell
python buildtools\build_overlay_310.py
python buildtools\deploy_310_nas.py
```

数据在 `/volume1/docker/comii/data`，不会因重建容器丢失。
