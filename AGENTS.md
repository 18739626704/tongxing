# 工程约定

- 服务端位于仓库根目录，使用 JDK 21。安卓位于 `android/`，使用 Gradle Wrapper。
- 分别运行 `mvn verify` 和 `cd android && ./gradlew testDebugUnitTest lintDebug` 验证改动。
- 保持已发布 Flyway 迁移文件原样。新增数据结构时添加迁移，涉及共享的变更同时核对客户端和服务端。
- 示例只能使用占位值。源码、提交与构建日志中不得包含真实环境的凭据、设备快照或位置记录。
