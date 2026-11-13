# Comii 部署到绿联 NAS（ARM）

> 当前 ARM 镜像：`comii:2.24.0-arm64`，已在 x86 构建机上用 QEMU 构建并启动自检通过。
> 镜像包：`comii-2.24.0-arm64-image.tar.gz`（约 246MB）。

## 1. 下载镜像包

构建机临时 HTTP：

```
http://192.168.89.153:8099/comii-2.24.0-arm64-image.tar.gz
```

或：

```bash
scp debug@192.168.89.153:/home/debug/comii-2.24.0-arm64-image.tar.gz .
```

下载完可关闭临时服务：

```bash
ssh debug@192.168.89.153 "pkill -f 'http.server 8099'"
```

## 2. 传到绿联并导入

把 `comii-2.24.0-arm64-image.tar.gz` 放到 NAS 的 `/volume1/docker/`，然后：

```bash
gunzip -c /volume1/docker/comii-2.24.0-arm64-image.tar.gz | sudo docker load
```

确认：

```bash
sudo docker images | grep comii
# comii  2.24.0-arm64  ...
```

## 3. 准备媒体目录

```bash
/volume1/comic     -> /library/comic
/volume1/video     -> /library/video
/volume1/docker/comii/data -> /data
```

如果漫画/视频在同一个总目录：

```
/volume1/media（里面直接有 comic、video）
```

则挂载 `/volume1/media:/library`。

## 4. 启动

使用 [docker-compose.arm64.yml](D:\.Desktop\komi_source_delivery\deploy\docker-compose.arm64.yml)，改好 volumes：

```yaml
volumes:
  - /volume1/docker/comii/data:/data
  - /volume1/comic:/library/comic
  - /volume1/video:/library/video
```

```bash
sudo docker compose -f deploy/docker-compose.arm64.yml up -d
```

没有 compose：

```bash
sudo docker run -d --name comii --restart unless-stopped \
  -p 8000:8000 -p 8001:8001 \
  -v /volume1/docker/comii/data:/data \
  -v /volume1/comic:/library/comic \
  -v /volume1/video:/library/video \
  comii:2.24.0-arm64
```

## 5. 验证

```bash
curl http://NAS_IP:8001/api/v1/ping
```

应返回 `"version":"2.24.0"`。

App 填 `NAS_IP:8001`，WebUI 打开 `http://NAS_IP:8000`。

## 6. Tailscale（远程访问）

绿联官方推荐用 **Docker 运行 Tailscale**（不是所有型号/系统版本都有原生应用）。ARM 上使用 `tailscale/tailscale` 的 arm64 镜像即可。

先生成 auth key：Tailscale 控制台 → Settings → Keys → Generate auth key。

`docker-compose.tailscale.yml`：

```yaml
services:
  tailscale:
    image: tailscale/tailscale:latest
    container_name: tailscale
    hostname: ugreen-nas
    restart: unless-stopped
    network_mode: host
    cap_add:
      - NET_ADMIN
    devices:
      - /dev/net/tun:/dev/net/tun
    volumes:
      - /volume1/docker/tailscale/lib:/var/lib/tailscale
    environment:
      - TS_AUTHKEY=tskey-auth-替换成你的key
      - TS_STATE_DIR=/var/lib/tailscale
      - TS_USERSPACE=false
```

启动：

```bash
sudo docker compose -f docker-compose.tailscale.yml up -d
sudo docker exec tailscale tailscale status
sudo docker exec tailscale tailscale ip -4
```

得到 `100.x.y.z` 后：

- 手机/电脑装 Tailscale 客户端，登录同一账号；
- Comii App 服务器地址填 `100.x.y.z:8001`；
- WebUI 打开 `http://100.x.y.z:8000`。

注意：

- 需要 NAS 有 `/dev/net/tun` 且容器有 `NET_ADMIN`；没有 TUN 时 Tailscale 可能只能用 userspace，无法直接暴露 NAS 端口。
- 不要为了远程访问把 8000/8001 直接映射到公网；Tailscale 已经加密组网。
- Tailscale 是第三方服务，登录/密钥由你自己的账号管理。

参考：
- 绿联官方远程访问（含 Tailscale Docker）：https://nas.ugreen.com/blogs/how-to/ugreen-nas-remote-access
- 绿联支持中心 Tailscale 文档：https://support.ugnas.com/knowledgecenter/detail/article/ja-JP/622
- 绿联 NAS Tailscale Compose 示例：https://insectmk.cn/posts/e2958935/
