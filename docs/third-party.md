# 第三方组件

- [Leaflet](https://leafletjs.com/) 随 Android 应用分发，见 [BSD-2-Clause 许可证](../android/app/src/main/assets/leaflet-LICENSE.txt)。
- Android 客户端使用 [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded) 和 [ZXing](https://github.com/zxing/zxing) 处理二维码，采用 [Apache 2.0 许可证](Apache-2.0.txt)。
- Gradle Wrapper 采用 [Apache 2.0 许可证](Apache-2.0.txt)。
- 标准 Android 构建从 Maven 下载高德 SDK，受高德的条款约束，轻量构建可关闭此依赖。Firebase 依赖及应用配置在启用 Firebase 构建选项时加入，适用 Firebase 的条款。
