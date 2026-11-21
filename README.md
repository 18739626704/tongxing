# 同行

同行是可以自托管的双人位置与状态共享应用。两个人安装 Android 客户端并在同一台服务端注册后，可以通过邀请码建立绑定，查看对方位置和足迹，发送消息、报平安和 SOS。

[下载 Android APK](downloads/tongxing-0.7.0.apk?raw=true) · [GitHub Releases](../../releases) · [部署教程](docs/deploy.md) · [开发与构建](docs/development.md) · [隐私与数据](docs/privacy.md) · [第三方组件](docs/third-party.md)

## 开始使用

1. 按[部署教程](docs/deploy.md)运行服务端，准备 HTTPS 地址和注册口令。
2. 两台 Android 8.0 或更新的手机安装 APK，填入相同的服务器 HTTPS 地址，各自注册账号。
3. 一方在「设置 → 伴侣绑定」生成邀请码，另一方在同一入口输入。
4. 按应用提示授权。基础安装包用系统定位，地图默认显示本地坐标网格。需要街道底图时，在「设置 → 权限与共享」卡片中选择「开启街道底图」。

APK 已签名，可从 [SHA256SUMS](downloads/SHA256SUMS)核对完整性。安装包可以连接任意兼容的自托管实例。服务端随本仓库提供，不提供公共实例和公共账号。

## 功能

- 绑定双方的位置、带精度提示的地图、足迹和停留时间轴。
- 应用内消息、报平安、SOS 位置与查看回执，以及地点和行程提醒。
- 在双方授权后共享设备状态和应用使用时长。
- 基础安装包支持 OpenStreetMap 街道底图。自定义构建可选接入高德地图或 Firebase Cloud Messaging。

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
