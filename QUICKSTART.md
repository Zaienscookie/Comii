# Comii 直接启动版（拉仓库就能跑）

## 一、准备
- 已安装 Docker（`docker --version` 有输出即可）
- 媒体目录，例如 `/vol1/1000/demo_images`

## 二、三步启动
```bash
git clone https://github.com/Zaienscookie/Comii.git
cd Comii
mkdir -p data
# 改 docker-compose.yml 里的 ./library 为你的媒体目录
docker compose up -d --build
```

启动后：

| 地址 | 用途 |
|---|---|
| `http://<主机IP>:8000` | WebUI |
| `http://<主机IP>:8001` | App / API |

## 三、首次登录（自动创建 Owner）
首次启动时服务端会：

1. 如果数据库里没有用户 → **自动创建 Owner**
   - 用户名：`COMII_OWNER_USER`（默认 `admin`）
   - 密码：`COMII_OWNER_PASSWORD`；留空则**随机生成**
2. 如果没配 `COMII_ACCESS_TOKEN` → **自动生成**
3. 以上两项都会写进 `./data/server.conf`，**并打印在容器日志里**：

```bash
docker compose logs comii | grep -E "Owner|令牌"
```

看到类似输出即可登录：
```
已自动创建 Owner：用户名=admin 密码=xxxxxxxx（请尽快修改）
已自动生成服务器访问令牌：yyyyyyyy（已写入 /data/server.conf）
```

## 四、登录后要做的事
1. 打开 `http://<IP>:8000/login.html`
2. 用户名/密码填上面日志里的
3. 「服务器访问令牌」填日志里的 token（或 `cat data/server.conf` 查看）
4. 登录后进 `/admin.html` 管理用户：
   - Owner / Admin / Viewer 是预设角色
   - Custom 用户可以点行勾选权限（浏览、下载、上传、新建、删除、移动、封面标签）

## 五、改配置
所有安全配置集中在一个文件：`data/server.conf`
```json
{
  "access_token": "自动生成或你自己填",
  "auth_enabled": true,
  "owner_user": "admin",
  "owner_password": "只在首次创建时使用"
}
```
改完 `docker compose restart` 生效。

环境变量优先级高于该文件，方便临时覆盖。

## 六、常见问题
- **新建文件夹提示“只读”**：`docker-compose.yml` 里 `./library:/library:ro` 的 `:ro` 去掉再 `docker compose up -d`
- **忘记 Owner 密码**：删掉 `data/users.db` 后重启，会按 `server.conf` 里的 `owner_password` 重新建；媒体文件不受影响
- **端口冲突**：改 `ports` 左侧数字
- **想换 token**：改 `data/server.conf` 的 `access_token` 后重启，App 里同步改
