# 部署服务端

## Docker Compose

准备一台安装 Docker Engine 和 Compose v2 的服务器，一个指向该服务器的域名，以及可从公网访问的 TCP 80 和 443 端口。数据库由同一套 Compose 启动并存储在本机命名卷中。

在 GitHub 仓库页面通过「Code → Download ZIP」下载源码并解压，或克隆仓库。在包含 `compose.yaml` 的目录执行：

```bash
cp .env.example .env
openssl rand -hex 24
openssl rand -hex 24
```

编辑 `.env`，将两次生成的随机值分别填入两个数据库密码字段。

| 变量 | 填什么 |
| --- | --- |
| `DOMAIN` | 服务域名，例如 `share.example.com`，不要加 `https://` |
| `DB_PASSWORD` | 同行专用 MySQL 用户的密码 |
| `DB_ROOT_PASSWORD` | MySQL 管理员密码，与应用密码不同 |
| `REGISTRATION_ENABLED` | 默认 `true`，设为 `false` 可关闭新增注册 |
| `AMAP_ANDROID_KEY` | 可选，标准 APK 使用的 Android 平台地图 SDK Key |
| `AMAP_WEB_SERVICE_KEY` | 可选，高德 Web 服务 Key，用于到达或离开事件的地点名称 |
| `ADMIN_USERNAME` | 可选，独立的控制台管理员登录名 |
| `ADMIN_PASSWORD_HASH` | 可选，管理员密码的 BCrypt 哈希，见下文 |
| `ADMIN_INITIAL_PASSWORD` | 可选，临时管理员密码，10–64 个字符且最多 72 字节 |
| `FCM_SERVICE_ACCOUNT_JSON` | 可选，开启 FCM 的 Firebase 服务账号 JSON，保存为一行 |

```bash
docker compose up -d --build
docker compose ps
curl -fsS https://share.example.com/api/health
```

把例子里的域名换成自己的。健康检查应返回 `{"status":"ok"}`。Caddy 会自动申请 HTTPS 证书。若申请失败，确认域名的 DNS 记录、公网端口和已有反向代理情况。若应用启动失败，用 `docker compose logs app --tail=100` 查看日志。更新前备份 MySQL，更新后再次检查健康接口。保留数据库卷，避免用 `docker compose down -v` 删除数据。

## Railway 或已有反向代理

可以把 fork 后的仓库根目录作为 Railway 服务源，使用根目录 `Dockerfile`。添加 MySQL 服务并配置下表，再给应用服务生成 HTTPS 域名并设置 `/api/health` 为健康检查路径。应用监听容器的 `8080` 端口。建议用 MySQL 内网地址。

| 环境变量 | 示例或说明 |
| --- | --- |
| `DB_URL` | `jdbc:mysql://<mysql-host>:3306/<database>` |
| `DB_USER` | MySQL 用户 |
| `DB_PASSWORD` | MySQL 密码 |
| `REGISTRATION_ENABLED` | 可选，默认允许注册 |
| 地图、推送与管理员 | 参照上表的可选变量 |

已有 MySQL 和 HTTPS 反向代理时，也可用 JDK 21 执行 `mvn verify`，设置同一组环境变量后用 `java -jar target/tongxing-server-0.7.7.jar` 启动，再由反向代理把 HTTPS 转发到 8080。正式部署保持 `SECURE_COOKIE=true`。

## 手机注册

0.7.2 起，输入服务器域名或 IP、用户名和密码即可注册。省略协议时客户端会补上 `https://`。正式服务器需要可信 HTTPS 证书。若使用 IP，证书也必须覆盖该 IP。新账号默认以用户名作为显示名。服务器默认允许新账号注册。仅供私用的实例在双方注册后，可将 `REGISTRATION_ENABLED` 设为 `false` 并重启服务，已有账号仍可登录。

从 0.7.1 或更早版本升级时，可以从环境变量中移除旧的 `ENROLLMENT_KEY`。新版服务端会忽略旧客户端仍然发送的该字段。

## 可选配置

### 高德

从 0.7.1 起，标准 APK 同时包含高德 Android SDK 和 OpenStreetMap。无 Android Key 或用户尚未同意高德隐私说明时，自动显示 OpenStreetMap 街道底图，无需到设置中勾选。配置 Android Key 后，标准 APK 登录并同步配置时会提示首次隐私确认。同意后自动切换，之后无需再次选地图。旧版轻量 APK 或通过 `-PtongxingAmap=false` 构建的 APK 需升级到含 SDK 的标准 APK 才能使用高德。

1. 在[高德开放平台控制台](https://console.amap.com/)进入「应用管理 → 我的应用」，创建一个应用。
2. 添加 Key，服务平台选「Android 平台 SDK」。包名填 `app.tongxing.mobile`，SHA1 填该 APK 的实际发布证书指纹。可用 `apksigner verify --print-certs <APK>` 查看。把这一项填到服务端的 `AMAP_ANDROID_KEY`。
3. 若需要地点名，再添加一个「Web 服务」Key，填到服务端的 `AMAP_WEB_SERVICE_KEY`。这一项也可单独配置，无需重打默认 APK。
4. 重启或重新部署服务让变量生效。标准 APK 登录后读取 Android Key，无需在手机填写或因换 Key 重新打包。Web 服务 Key 只由服务端使用，与底图选择独立。Android Key 必须与实际 APK 的包名和签名 SHA-1 匹配。

参考 [Android 地图 SDK 申请指引](https://lbs.amap.com/api/android-sdk/guide/create-project/get-key) 和 [Web 服务 Key 申请指引](https://lbs.amap.com/api/webservice/guide/create-project/get-key)。

### Web 管理控制台

设置 `ADMIN_USERNAME` 和 `ADMIN_PASSWORD_HASH` 后重启服务，通过服务域名的 `/admin/` 登录。该管理员账号由环境变量管理，与手机端注册的账号独立。可以用 `htpasswd -nBC 12 admin` 交互式生成 BCrypt 哈希，将输出中用户名后面的哈希填入 `ADMIN_PASSWORD_HASH`。Docker Compose 的 `.env` 里请用单引号包住哈希，以便按字面读取其中的美元符号。通过部署平台变量界面设置时，直接填哈希值。

首次初始化可以设置 `ADMIN_INITIAL_PASSWORD`。生成独立随机密码并在部署环境中配置，登录名默认为 `admin`，也可通过 `ADMIN_USERNAME` 自定义。第一次用临时密码登录后会直接进入改密页。修改后的密码哈希保存在数据库，旧密码和旧会话随即失效。原 `ADMIN_PASSWORD_HASH` 配置仍可使用。

管理员可以查询用户账号、禁用或恢复账号，也可以查看服务器保留的位置、足迹、设备状态、应用时长、动态和地点记录。禁用会使账号的已有会话失效并停止共享。控制台每 15 秒刷新当前用户的位置及状态，实际位置更新时间取决于手机上报。审计记录保留 30 天。

### Firebase 系统推送

此配置适用于需要在应用退到后台时提高消息提醒及时性的部署。基础 APK 的消息依靠同步获取，后台受 Android 限制时可能延迟。兼容手机、通知权限以及系统的锁屏通知设置也会影响显示。

在 Firebase 控制台创建项目并注册 Android 应用 `app.tongxing.mobile`。下载 Android 应用的 `google-services.json` 用于[客户端构建](development.md#可选构建)。为同一个 Firebase 项目创建有发送 FCM 消息权限的服务账号，生成凭据并将完整 JSON 以单行写入服务器上的 `FCM_SERVICE_ACCOUNT_JSON`。请通过部署平台的私有变量界面配置生产凭据。不要上传 JSON 或在日志中打印它。
