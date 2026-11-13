# Comii 签名说明

## 为什么会「签名冲突」

Android 只允许**同包名 + 同签名**的 APK 覆盖安装。2.24.0 当初是在另一台机器上用
那台机器的 debug key 签的；换机器后重新构建，debug key 不同，覆盖安装就会被系统拒绝：

```
App not installed as package conflicts with an existing package.
There is an error with the developer signature of the APK.
```

## 现在的做法：固定一把专用 key

2.25.0 起使用项目自己的 keystore（本机 `C:\Users\Public\comii-keys\comii.jks`，
备份在交接目录，口令单独保存，不进仓库）：

| 项 | 值 |
|---|---|
| alias | `comii` |
| 证书 | `CN=Comii, OU=Personal, O=Zaienscookie, C=CN` |
| SHA-256 | `8eb8a2d4d2b6062d48c12889f9eff7903b88718fc1bcbc2e1d3fb6229fcf98a8` |
| 有效期 | 10000 天 |

`app/app/build.gradle.kts` 从 `app/local.properties`（已在 `.gitignore`）或环境变量读取：

```properties
COMII_KEYSTORE=C:/Users/Public/comii-keys/comii.jks
COMII_KEYSTORE_PASSWORD=******
COMII_KEY_ALIAS=comii
COMII_KEY_PASSWORD=******
```

`debug` 与 `release` 都使用这把 key；仓库 `.gitignore` 已排除 `*.jks` / `*.keystore` / `keystore/`。

## 首次切换

旧版（2.24.0）用的是旧签名，**必须卸载一次**：

1. 卸载手机上的 Comii（会清掉 App 本地设置和离线下载；阅读进度、标签在服务器上，不会丢）
2. 安装 2.25.0（`release/Comii-2.25.0.apk`）
3. 重新填服务器地址：Tailscale `100.74.140.98:8001` 或局域网 `192.168.101.17:8001`

之后用这把 key 出的包都能直接覆盖安装。

## 构建

```bash
cd app
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

`app/local.properties` 里没有 `COMII_KEYSTORE` 时会退回默认 debug 签名——那种包**不要**
发给手机安装，否则又会签名冲突。
