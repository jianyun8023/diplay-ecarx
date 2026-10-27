# 领克 ECARX 测试夹具

这些夹具随源码提交，由对应模块的 `src/test` 源集自动编译。测试调用生产入口，夹具只替代车机服务边界；不需要安装 OEM SDK。运行命令见 [ECARX 软件流程测试](ECARX_TESTING.md)。

| 夹具 | 用途 | 主要使用方 |
| --- | --- | --- |
| [DIM 接口与记录器](../shared/src/test/java/com/ecarx/xui/adaptapi/diminteraction/) | OEM 同名接口、getter 载荷读取、调用顺序记录、失败注入；包含媒体、导航、车道和电话服务 | `EcarxDimApiFixtureTest`、`EcarxHudBridgeFlowTest`、`EcarxMediaBridgeFlowTest` |
| [shared 方控接口](../shared/src/test/java/com/ecarx/xui/adaptapi/input/) | 注册/释放、拒绝键、服务不可用、异常与迟到回调 | `EcarxSwKeyInterceptFlowTest` |
| [common 方控接口](../common/src/test/java/com/ecarx/xui/adaptapi/input/Input.kt) | Activity 与真实反射适配器之间的最小 OEM 边界 | `EcarxSwKeyHostFlowTest`（位于 `EcarxSwKeyTest.kt`） |
| [媒体流程夹具](../shared/src/test/java/com/shilapi/xcertplay/hud/EcarxMediaFlowFixture.kt) | 播放状态、生产帧、DIM 调用和内存 MQTT socket，控制调度并在关闭时恢复状态 | `EcarxMediaBridgeFlowTest` |
| [车道协议样本](../shared/src/test/java/com/shilapi/xcertplay/iap2/message/Iap2LaneGuidanceTest.kt) | 三车道、重复角度、畸形 TLV、未知方向/状态；样本直接保留在使用它的测试中 | `Iap2LaneGuidanceTest` |
| [音乐时钟模型](../shared/src/test/java/com/shilapi/xcertplay/airplay/BufferedMusicClockTrimTest.kt) 与 [实时模型](../shared/src/test/java/com/shilapi/xcertplay/airplay/RealtimeMusicClockTrimTest.kt) | 模拟包到达、缓冲消耗和时钟偏差，驱动生产音乐处理 | 对应音乐时钟测试 |
| [仪表封面 HTTP 测试](../common/src/test/java/com/shilapi/xcertplay/ClusterArtworkServerTest.kt) | 本机取图、HEAD、过期图片、非法请求、连接关闭；真实封面 listener→DIM 链路见 `EcarxMediaSessionOwnershipTest` | `ClusterArtworkServerTest`、`EcarxMediaSessionOwnershipTest` |
| [USB 回放夹具](../shared/src/test/java/com/shilapi/xcertplay/transport/UsbQueueReplay.kt) | 统一载荷、queue 接受/拒绝、分段完成、超时、取消和关闭；独立于测试类 | `UsbReadQueueCompatibilityTest`，历史 USB 路径用例复用 |
| [系统 USB 授权 shadow](../shared/src/test/java/com/shilapi/xcertplay/transport/UsbPermissionActivityProbeTest.kt) | 组件可用性、PendingIntent 与系统授权请求计数；只供本测试使用 | `UsbPermissionActivityProbeTest` |
| [热点 ADB 会话](../common/src/test/java/com/shilapi/xcertplay/CarHotspotAdbGrantTest.kt) | 本机 FakeAdbd 模拟确认、拒绝、延迟批准与权限写入；保留在使用处 | `CarHotspotAdbGrantTest` |
| [热点 Binder 替身](../shared/src/test/java/com/shilapi/xcertplay/network/fakebinder/FakeConnectivityBinder.kt) | 回调与系统连接服务边界 | `PlatformTetheringStartTest` |
| [HiCar 包与按键样本](../common/src/test/java/com/shilapi/xcertplay/VoiceKeyDiagnosticsTest.kt) | 包可见性、平台签名与权限；验证只读采集 | `VoiceKeyDiagnosticsTest` |
| [测试启动配置](../scripts/testing/ecarx-acceptance.init.gradle) | 可选离线 Robolectric 缓存、独立报告目录、common Mockito agent | 本地定向测试与默认车机检查 |

## 维护约定

- OEM 类的包名、类名和方法签名是反射契约，保留在 `com.ecarx.xui.adaptapi`，不要为了集中目录而改名。
- shared 与 common 的测试 classpath 独立；两份 `Input` 分别服务适配器测试与 Activity 测试，不直接互相依赖。
- DIM 用例前后调用 `DimInteraction.reset()`；方控用例前后调用 `Input.reset()`；媒体流程夹具使用 `use` 或在 teardown 中 `close()`，防止静态状态、调度和 socket 污染后续用例。
- USB 回放用例开始时调用 `UsbQueueReplay.reset()`，结束时关闭生产 pipe/bridge；FakeAdbd 用 `use` 释放服务并等待会话退出。
- 单个测试专用的载荷、Binder、窗口和音频模型保留在使用处；有真实复用时再提取。
- 测试成功表示调用与载荷符合夹具约定，不代表实车权限、OEM 固件契约、画面或听感已经验收。车道样本不能代替目标车辆采样。

Android SDK、Gradle/Robolectric 缓存、APK、签名材料和一次性运行日志保留在本机，不作为源码夹具提交。启动配置通过路径参数引用本地依赖，不绑定交接目录或某台机器。

## 封面、USB 和热点回归

- 仪表封面沿用 HY 的本机 HTTP 交接方式：`CarPlayMediaKeys` 在现有 worker 解码并缩放封面，`ClusterArtworkServer` 仅监听 `127.0.0.1` 的动态端口，最多保留四张 PNG；DIM 的歌曲与播放列表返回对应 URI。换歌撤下旧封面，断连关闭服务器，无新增存储权限或第三方依赖。
- `UsbSetupDiagnosticsTest` 断言已经生效的配置不再执行 `SET_CONFIGURATION`，避免重置端点；USBMUX claim 复用 NCM 已有的有限重试。`UsbReadQueueCompatibilityTest` 在 API 28 覆盖新 queue 明确返回 false 后使用旧 queue 的兼容尝试、成功后的复用和关闭检查。Android 7/8 路径用例保留源码，退出默认清单。两种 queue 均拒绝时仍返回真实传输失败，不冒充授权失败或继续读取。
- `CarHotspotControlSettingsTest` 检查车载热点控制可从“连接”分类到达，且仅用于手动车载热点模式；沿用原来的系统设置权限/ADB 路径与启动条件。`EcarxSettingsScreenshotsTest` 输出热点控制的紧凑中文、全尺寸英文和紧凑阿拉伯语大字体截图。

实车复验时分别确认封面能加载、换歌不残留、USB 拔插/重连能进入数据通信，以及热点关闭后打开 DiPlay 能恢复。软件测试不证明 OEM HTTP 图片加载策略、USB 驱动兼容或实际热点广播已经通过。

导出报告还会包含封面接收与解码结果、HTTP 发布/请求/成功取图次数、USB 自动确认次数与最后确认时间、热点直接启动能力，以及 HiCar 包名/启用状态/平台签名与控制权限的只读诊断。USB 自有日志标明每次请求与结果对应的设备节点、枚举阶段、接口 claim 次数及旧 queue 是否启用。日志不包含图片内容或热点密码；HiCar 诊断不执行停用操作。
