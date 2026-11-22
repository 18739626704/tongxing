# 开发与构建

## 运行测试

服务端需要 JDK 21 和 Maven 3.9 或更新版本。集成测试使用内存数据库，无需设置生产环境变量。

```bash
mvn --batch-mode verify
```

管理台时间轴的漂移过滤和分段可以在仓库根目录用 Node.js 运行回归测试。

```bash
node --test src/test/js/admin-route-timeline.test.cjs
```

安卓需要 JDK 21、Android SDK Platform 35 和可用的 Android SDK 工具。首次运行 Gradle Wrapper 时需要网络访问 Maven Central、Google Maven 和 Gradle 分发站点。

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

可在 Android Studio 打开 `android/` 或安装 `app/build/outputs/apk/debug/app-debug.apk`。调试构建才允许访问 `http://10.0.2.2:8080`、`localhost` 或 `127.0.0.1`。本地 HTTP 调试需要在开发实例上设置 `SECURE_COOKIE=false`。真机日常使用时请连接 HTTPS 实例。

## 构建发布 APK

```bash
cd android
./gradlew assembleRelease
zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
apksigner sign --ks /private/path/tongxing.p12 --out tongxing.apk aligned.apk
apksigner verify --verbose --print-certs tongxing.apk
```

`zipalign` 和 `apksigner` 在 Android SDK Build Tools 中。每个分发渠道应妥善保管自己的签名密钥，更新需要与手机已安装的版本使用同一个签名。自行签名的版本通常不能覆盖 GitHub 下载的版本。相同包名下更换签名需要先卸载，手机上的应用本地配置会被清空。请先确认服务器地址和账号凭据。

### 可选构建

```bash
./gradlew assembleRelease -PtongxingAmap=false
./gradlew assembleRelease -PtongxingFirebaseConfig=/private/path/google-services.json
```

标准构建默认包含高德 SDK 和 OpenStreetMap，是否使用高德在运行时由服务端 Android Key 和客户端隐私确认决定。第一条命令构建不含高德 SDK 的轻量包，它仍默认显示 OpenStreetMap。第二条命令增加 Firebase 推送，可以与轻量选项组合。高德依赖从 Maven 仓库下载，Firebase 从指定的 JSON 提取应用配置。APK 仍需签名后才能安装，服务端需同步配置[对应的变量](deploy.md#可选配置)。任何签名私钥、Firebase 服务账号或高德 Web 服务 Key 都应保存在源码目录之外。

## 代码边界

- 安卓 `MainActivity` 负责界面流程，`Api` 统一处理连接地址、会话和请求。
- `LocationSharing` 管理定位和同步。`MainActivity.makeMap()` 依据 SDK、服务端 Android Key 和用户授权选择原生高德，其他情况下由 `LeafletMap` 渲染 OpenStreetMap。时间轴归并由 `RouteTimeline` 负责。
- 服务端 `SharingController` 负责参数校验和 API，`Security` 管理会话与 CSRF。主要授权和事务边界在 `SharingService`，地点提醒由 `SavedPlaces` 处理。
- `store/` 下是 MyBatis-Plus 实体及映射。MySQL 结构通过 `src/main/resources/db/migration/` 的 Flyway 迁移管理。已经发布的迁移不能就地修改，新增变更请增加迁移文件。

接口受配对关系和授权约束。改动绑定、共享、解绑或 SOS 时，需要兼顾旧客户端、重复请求及数据清理。

## 发布版本
同步更新根目录 `pom.xml` 的服务端版本，以及 `android/app/build.gradle` 的 `versionName` 和 `versionCode`。主分支构建通过后，在 GitHub Releases 对应版本上传标准 APK 和 `SHA256SUMS`。签名 APK 和校验文件统一放在 Release 附件中。签名密钥始终保留在发布者自己的环境。
