# 同行

同行是可以自托管的双人位置与状态共享应用。两个人安装 Android 客户端并在同一台服务端注册后，可以通过邀请码建立绑定，查看对方位置和足迹，发送消息、报平安和 SOS。

[下载最新标准 APK](../../releases/latest) · [历史发布](../../releases) · [部署教程](docs/deploy.md) · [开发与构建](docs/development.md) · [隐私与数据](docs/privacy.md) · [第三方组件](docs/third-party.md)

## 开始使用

1. 按[部署教程](docs/deploy.md)运行服务端，准备可通过 HTTPS 访问的服务器地址。
2. 两台 Android 8.0 或更新的手机安装 APK，填入相同的服务器域名或 IP，各自设置用户名和密码。
3. 一方在「设置 → 伴侣绑定」生成二维码，另一方在同一入口扫一扫或手动输入邀请码。
4. 按应用提示授权。标准 APK 打开地图即显示街道底图。服务器配置有效的高德 Android Key 且用户已同意高德隐私说明时，自动使用高德。其余情况下显示 OpenStreetMap。

APK 已签名，可使用 Releases 同一版本下的 `SHA256SUMS` 核对完整性。安装包可以连接任意兼容的自托管实例。服务端随本仓库提供，不提供公共实例和公共账号。自托管管理员可以按部署教程启用 Web 控制台。

## 功能

- 绑定双方的位置、带精度提示的地图、足迹和停留时间轴。
- 应用内消息、报平安、SOS 位置与查看回执，以及地点和行程提醒。
- 在双方授权后共享设备状态和应用使用时长。
- 标准构建同时支持高德与 OpenStreetMap。Firebase Cloud Messaging 和不带高德 SDK 的轻量包可按需构建。

## 管理控制台

自托管管理员可通过 `/admin/` 搜索和分页浏览账号、查看设备概况和绑定对象、按日期查看平滑后的足迹与移动／停留时间轴、读取当前绑定双方保留的文字消息，并通过操作日志筛选管理操作。部署与访问设置见[管理控制台说明](docs/deploy.md#web-管理控制台)。

## 源码目录

| 路径 | 内容 |
| --- | --- |
| `android/` | Android Studio 项目、客户端和单元测试 |
| `src/main/` | Spring Boot API、MyBatis-Plus 数据访问和 Flyway 迁移 |
| `src/test/` | 服务端集成测试 |
| `compose.yaml` | 服务端、MySQL 和 Caddy 的自托管配置 |
| `docs/` | 部署、开发、构建和数据说明 |

欢迎提交 Issue 和 Pull Request。提交前请阅读[贡献指南](CONTRIBUTING.md)，安全问题请参阅[安全报告方式](SECURITY.md)。

## 许可证

同行原创代码采用 [MIT 许可证](LICENSE)。第三方组件继续按[各自的许可证](docs/third-party.md)分发。
