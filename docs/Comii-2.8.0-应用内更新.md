# Comii 2.8.0：应用内自动更新

> App：2.8.0（versionCode 14） · 服务端：2.8.0
> 更新包位置：服务器 `data/updates/`

## 1. 行为

- App 启动后（6 小时内只查一次）向数据接口请求 `GET /api/v1/app/update`。
- 服务端没有更新、或版本不高于当前 App：接口返回 `available:false`，**界面什么都不显示**。
- 有新版本：弹窗显示版本号、更新说明、安装包大小；点「下载并安装」后
  下载 APK 到 App 缓存目录，再调起系统安装器。
- 设置 → 关于 →「检查更新」可手动检查，最新时提示「已是最新版本」。

## 2. 服务端接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/app/update` | 返回 `available/versionCode/versionName/notes/size/sha256/url` |
| GET | `/api/v1/app/update/download` | 返回 APK（`application/vnd.android.package-archive`） |

读取规则：

```
<data>/updates/update.json     # {"versionCode":14,"versionName":"2.8.0","notes":"...","apk":"comii-2.8.0.apk","sha256":"..."}
<data>/updates/comii-2.8.0.apk
```

没有 `update.json` 或没有 APK → 返回 `available:false`（App 静默）。

## 3. 以后怎么发布更新

```powershell
$env:COMII_SSH_PASSWORD='******'
python tools/dev/publish_update.py release\Comii-2.9.0.apk 15 2.9.0 "更新说明"
```

脚本会：上传 APK 到服务器 `data/updates/`、写 `update.json`（含 sha256）、
再打印 `/api/v1/app/update` 的返回结果。

## 4. Android 注意点

- Manifest 已加 `REQUEST_INSTALL_PACKAGES` 和 `FileProvider`
  （authority：`<applicationId>.fileprovider`，路径 `cache/updates/`）。
- 首次安装会要求允许「安装未知应用」，这是 Android 8+ 的系统行为，App 无法绕过。
- debug 签名与正式签名不能互相覆盖安装；当前发布的都是 debug 包，保持一致即可。
- 下载走 OkHttp，支持进度；断网/服务端错误会在弹窗里显示原因。

## 5. 已验证

- `GET /api/v1/app/update` 返回 2.8.0 / versionCode 14 / 21.6 MB / sha256。
- `/api/v1/app/update/download` 返回 APK，Content-Type 正确。
- App 端逻辑：仅当 `versionCode > BuildConfig.VERSION_CODE` 时弹窗；否则静默。
