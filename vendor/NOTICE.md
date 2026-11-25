# 离线依赖

构建使用随仓提供的五个 JAR。文件校验值在 [SHA256SUMS](SHA256SUMS)，APK 的 `assets/licenses/` 包含项目和依赖声明。

| 制品 | 版本 | 来源 |
| --- | --- | --- |
| androidx.wear.tiles:tiles | 1.1.0 | Google Maven，取 AAR 的 classes.jar |
| androidx.wear.tiles:tiles-proto | 1.1.0 | Google Maven，包含重定位的 Protocol Buffers 运行时 |
| androidx.annotation:annotation | 1.2.0 | Google Maven |
| androidx.annotation:annotation-experimental | 1.2.0 | Google Maven，取 AAR 的 classes.jar |
| com.google.guava:listenablefuture | 1.0 | Guava ListenableFuture |

AndroidX 与 Guava 的许可证文本见 [Apache License 2.0](Apache-2.0.txt)。Protocol Buffers 的版权及许可声明见 [Protobuf LICENSE](Protobuf-LICENSE.txt)。
