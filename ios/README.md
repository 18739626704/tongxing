# 同行 iOS

原生 SwiftUI 客户端，最低 iOS 17。连接安卓端使用的同一个 HTTPS 服务地址，沿用 Spring Boot 的会话、CSRF、配对邀请码、位置、足迹与动态接口。两部手机需要各自的账号。

## 安装到自己的 iPhone

1. 在有 Xcode 16 或更新版的 Mac 上打开 `ios/Tongxing.xcodeproj`，在 Xcode 的 Settings → Accounts 中添加自己的 Apple 账号。
2. 选择 Tongxing target，打开 Signing & Capabilities，启用 Automatically manage signing，选择自己的 Personal Team。若默认 Bundle Identifier 被占用，换成自己的唯一标识。
3. 连接 iPhone，按系统提示信任这台 Mac 并开启开发者模式。选中 iPhone 后点击 Run。
4. 打开同行，填入安卓端使用的 HTTPS 地址。两个账号可按原方式输入或分享 16 位邀请码。
5. 到地图右上角的「权限」开启定位，再点「同意与伴侣共享位置」。需要锁屏或切换应用时继续上传，可在 iPhone 设置中将定位权限改为「始终」。

Personal Team 的设备安装签名有效期为 7 天，到期后用 Xcode 重新安装。[Apple 开发者账号说明](https://developer.apple.com/help/account/basics/about-your-developer-account/)

无签名模拟器构建：

```bash
xcodebuild -project ios/Tongxing.xcodeproj -scheme Tongxing \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

## 实现范围

- 「地图」使用系统 MapKit，自动显示底图，无需高德 Android Key。位置上传沿用服务端现有 GPS 坐标字段。境内地图标点与两端同点叠合需要真机检查。
- 「足迹」支持查看双方按日轨迹和移动、停留及定位中断时间轴。「动态」支持消息、快捷报备、休息提醒、SOS 位置及收到／已查看回执。
- 「设置」支持邀请和解除绑定、位置与设备状态共享、行程到离提醒、常去地点和到达提醒、紧急联系人、清空轨迹及注销。输入邀请码可跨端配对。
- 退出时清除钥匙串内的本机登录状态。断网时立即停止本机上传，恢复同账号会话后同步待处理的服务器暂停。定位按绑定关系单独取得本机同意。
- iOS 采用 Core Location 回调，25/150 秒是收到有效定位后的上传节流，实际后台间隔取决于系统及定位条件。系统提供的 Android 伴侣屏幕和应用使用状态可读取，本机暂不采集对应数据。锁屏通知、安卓桌面组件和高德 iOS SDK 未接入。

## 双端验收

使用测试账号及非生产位置，依次检查注册和登录、安卓生成邀请码与 iPhone 接受、双向位置和足迹、消息、SOS 及回执、暂停共享后停止上传、断网退出以及重新登录后的服务端暂停同步。iPhone 实体设备还应验证定位权限变更、锁屏实测、蜂窝切换、长时间静止恢复和境内地图点位一致性。
