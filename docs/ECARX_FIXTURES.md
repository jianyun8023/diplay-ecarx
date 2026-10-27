# ECARX 测试夹具

这些夹具随源码提交，由对应模块的 `src/test` 源集自动编译。测试调用生产入口，夹具只替代车机服务边界；不需要安装 OEM SDK。运行命令见 [ECARX 软件流程测试](ECARX_TESTING.md)。

| 夹具 | 用途 | 主要使用方 |
| --- | --- | --- |
| [DIM 接口与记录器](../shared/src/test/java/com/ecarx/xui/adaptapi/diminteraction/) | OEM 同名接口、getter 载荷读取、调用顺序记录、失败注入；包含媒体、导航、车道和电话服务 | `EcarxDimApiFixtureTest`、`EcarxHudBridgeFlowTest`、`EcarxMediaBridgeFlowTest` |
| [shared 方控接口](../shared/src/test/java/com/ecarx/xui/adaptapi/input/) | 注册/释放、拒绝键、服务不可用、异常与迟到回调 | `EcarxSwKeyInterceptFlowTest` |
| [common 方控接口](../common/src/test/java/com/ecarx/xui/adaptapi/input/Input.kt) | Activity 与真实反射适配器之间的最小 OEM 边界 | `EcarxSwKeyHostFlowTest`（位于 `EcarxSwKeyTest.kt`） |
| [媒体流程夹具](../shared/src/test/java/com/shilapi/xcertplay/hud/EcarxMediaFlowFixture.kt) | 播放状态、生产帧、DIM 调用和内存 MQTT socket，控制调度并在关闭时恢复状态 | `EcarxMediaBridgeFlowTest` |
| [车道协议样本](../shared/src/test/java/com/shilapi/xcertplay/iap2/message/Iap2LaneGuidanceTest.kt) | 三车道、重复角度、畸形 TLV、未知方向/状态；样本直接保留在使用它的测试中 | `Iap2LaneGuidanceTest` |
| [音乐时钟模型](../shared/src/test/java/com/shilapi/xcertplay/airplay/BufferedMusicClockTrimTest.kt) 与 [实时模型](../shared/src/test/java/com/shilapi/xcertplay/airplay/RealtimeMusicClockTrimTest.kt) | 模拟包到达、缓冲消耗和时钟偏差，驱动生产音乐处理 | 对应音乐时钟测试 |
| [测试启动配置](../scripts/testing/ecarx-acceptance.init.gradle) | 单 fork、可选离线 Robolectric 缓存、独立报告目录、common Mockito agent | 本地定向测试和完整 CI 检查 |

## 维护约定

- OEM 类的包名、类名和方法签名是反射契约，保留在 `com.ecarx.xui.adaptapi`，不要为了集中目录而改名。
- shared 与 common 的测试 classpath 独立；两份 `Input` 分别服务适配器测试与 Activity 测试，不直接互相依赖。
- DIM 用例前后调用 `DimInteraction.reset()`；方控用例前后调用 `Input.reset()`；媒体流程夹具使用 `use` 或在 teardown 中 `close()`，防止静态状态、调度和 socket 污染后续用例。
- 单个测试专用的载荷、Binder、窗口和音频模型保留在使用处；有真实复用时再提取。
- 测试成功表示调用与载荷符合夹具约定，不代表实车权限、OEM 固件契约、画面或听感已经验收。车道样本不能代替目标车辆采样。

Android SDK、Gradle/Robolectric 缓存、APK、签名材料和一次性运行日志保留在本机，不作为源码夹具提交。启动配置通过路径参数引用本地依赖，不绑定交接目录或某台机器。
