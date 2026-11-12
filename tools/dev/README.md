# Comii 开发辅助脚本

这些脚本是开发本机使用的工具，不参与运行时。

| 脚本 | 用途 |
|---|---|
| `setup_android_sdk.py` | 直接解析 Google SDK 仓库 XML，把 platform-tools / android-35 / build-tools 装到 `<workspace>/buildtools/sdk`（绕过 sdkmanager 的代理问题） |
| `setup_gradle.py` | 下载 Gradle 发行版到 `<workspace>/buildtools/gradle` |
| `deploy_server.py` | 打包 `server/` + `web/`，通过 SSH/SFTP 部署到远端并重启 systemd 服务（密码从 `COMII_SSH_PASSWORD` 读取，不写入仓库） |
| `seed_library.py` | 生成演示用的漫画库（渐变封面 + 内页） |
| `integration_check.py` | 对运行中的服务器做端到端检查（浏览、缩略图、PDF 导入、上传、删除/恢复/彻底删除、旧版 WebUI 接口） |

## 本机构建环境

```powershell
python tools/dev/setup_android_sdk.py .
python tools/dev/setup_gradle.py .
# gradle.properties（放在 <workspace>/buildtools/gradle-home/gradle.properties）
#   systemProp.https.proxyHost=127.0.0.1
#   systemProp.https.proxyPort=7897
#   org.gradle.vfs.watch=false
```

构建（PowerShell 示例）：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:GRADLE_USER_HOME="$pwd\buildtools\gradle-home"
$env:ANDROID_HOME="$pwd\buildtools\sdk"
$env:ANDROID_USER_HOME="$pwd\buildtools\android-home"
cd app
..\buildtools\gradle\gradle-8.9\bin\gradle.bat assembleDebug
```

有网络代理时，Gradle 的代理写在 `GRADLE_USER_HOME/gradle.properties`，不要写进项目仓库。

## 部署

```powershell
$env:COMII_SSH_PASSWORD='******'
python tools/dev/deploy_server.py .
```
