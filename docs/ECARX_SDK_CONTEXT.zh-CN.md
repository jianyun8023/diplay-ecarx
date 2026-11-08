# ECARX SDK 与麦克风 Policy 上下文

本说明记录 PR #13 的后续核对和麦克风生命周期补充。目标是本项目的领克 01 / 820A / 753、Android 9 车机；接口存在、服务连接、请求被接受和硬件实际可用是不同的证据。

## 当前集成方式

DIM/navigation、方控、日夜跟随和音场读取已恢复为运行时反射与动态代理。仓库不再附带或依赖 `ecarx.adapterapi.jar`；编译和单测均不需要厂商 JAR，运行实现由车机提供。缺少接口或方法时保留现有诊断与回退。

单测使用 `src/test` 内独立编写的最小接口夹具和调用记录器，不打包进 APK。未使用的 GKUI Policy 恢复声明模块已删除；应用继续反射访问车机提供的 Policy。

运行时接口的使用条件及历史提交中的旧 JAR、Policy 恢复声明仍属后续核对范围。移除工作树中的文件不会清除 Git 历史中的副本。

## PR #13 的历史核对

PR #13 曾将上述适配改为 AdaptAPI 编译库直调，依赖方式为 `compileOnly`。以下核对记录保留为运行时接口的依据，不代表当前仍使用编译库。

2026-10-31 的实际 framework dump 中，AdaptAPI 是 **1.1.18.9**。旧应用中附带的 **1.1.17** 声明不能代替这个运行库。实际 `INavigationInfo` 有 15 个 getter，包括 `getDayNightMode`、`getExtensionInfo`。对 PR #13 提交 `d559c506` 的编译产物逐一核对，28 处厂商方法直调和 5 个应用实现的厂商回调/数据接口均匹配实际 DEX；DIM 的单方法显示回调也与运行接口一致。这是二进制接口核对，不是新一轮实车验收。

麦克风部分同时发生了行为变化：

- PR #13 之前：在厂商音频策略对象上反射寻找 `isNeedrequestMic()`，找不到就跳过申请，直接录音。这个方法实际属于 HY 应用封装，不属于本车厂商接口。
- PR #13：改为以音频策略对象非空作为申请条件。但未调用 `PolicyAPI.init()`，所以本车会拿到没有远端连接的包装对象，`requestMic()` 返回 `false`，然后仍直接录音。
- 因此，“未初始化”是原有集成缺口；不能仅凭这个缺口认定 PR #13 将原先成功的仲裁改坏。

## 本次补齐的流程

只对通话（`telephony`）执行以下流程；Siri（`speechrecognition`）保留原来的直接录音入口。

1. 通过 `PolicyAPI.get(applicationContext)` 获取对象，并调用 `init(context, ECarXApiClient.Callback)`。
2. 等待 `onAPIReady(true)`，再调用 `getAudioPolicy()`。不能提前缓存包装对象，因为 SDK 的初始化会替换它。
3. 调用 `requestMic(CALL=2, IMicResourceCallback)`。返回 `true` 只代表请求被接受，**不能据此开始录音**。
4. 收到 `onResume()` 才启用通话模式、HiCar 路由并开始采集。收到 `onAbort(requestType)` 时停止采集、恢复模式/路由并保留申请；后续 `onResume()` 可恢复。
5. 挂断、断连、录音启动失败或关闭 sink 时取消申请，对接受过的请求使用原来的 callback 调用 `releaseMic()`。迟到的初始化/授权回调不得重启已结束的采集。

SDK 缺失时立即保持直接录音；初始化失败、API 不就绪、请求拒绝/异常时也回退。初始化和首次授权合计最多等待 1.5 秒，避免 SDK 自身长时间重连使通话一直没有上行音频；等待首次授权超时会先释放已接受的申请，再进入直接录音。明确的 `onAbort` 表示资源被撤回，不把它当成直接录音的理由，也不再触发超时回退。

Policy 初始化回调按进程只注册一次。SDK 会长期保存初始化回调，所以它不能捕获某次 CarPlay 会话。每次请求的录音监听在释放/回退时清除；没有调用会影响其他 SDK 模块的全局 `ECarXApiClientImpl.release()`。

## 本车服务端限制

继续核对实际 **XSFOpenAPISerivce.apk** 后，确认本车的音频策略服务没有实现麦克风仲裁：

- `com.ecarx.sdk.openapi.af.f()` 创建音频策略 Binder 实现 `com.ecarx.sdk.openapi.d`。
- 其 Binder 接口 `m` 的 descriptor 为 `com.ecarx.sdk.openapi.IAudioPolicy`；transaction 2 是申请、3 是释放。
- 实现 `d.a(int, v)` 和 `d.a(v)` 均抛出 `RemoteException("Method not supported in Android O and above")`。
- GKUI 客户端 `PolicyAPIImpl.RemoteAudioPolicyImpl.requestMic()` 捕获这个异常并返回 `false`。

所以，即便 `onAPIReady(true)`，**本车这份服务端仍预期申请失败、保持直接录音**。本次完成的是正确的客户端生命周期，并未赋予本车缺失的仲裁能力，也不能据此宣称解决了原厂助手与 CarPlay 的硬件麦克风争用。

本车旧的 `com.ecarx.xui.adaptapi.policy.IAudioPolicy` 本身为空接口，不能替代 GKUI 仲裁。HY 的跨车型应用封装及 EAS 4.6.20 也不等同于本车的 GKUI 3.3.9；不混用它们的回调类型。

## 可复核的 dump 依据

以下是本地只读材料的相对路径；不把 APK、framework 或完整反编译代码提交到公共仓库。

| 文件 | SHA-256 |
| --- | --- |
| `jimu-ext/extract/20261031-092437/framework/ecarx.adapterapi.jar` | `f4704d7d90ad24f8100bcc686b1ca0dba005777466d75b8f65242f2785d597bf` |
| `jimu-ext/extract/20261031-092437/framework/ecarx.gkui.openapi.impl.jar` | `e77681de3693910f099e0d70d35e8e7a9875de444a5e2e38258b27994df96921` |
| `jimu-ext/extract/20261031-092357/com.ecarx.sdk.openapi/XSFOpenAPISerivce.apk` | `59c773d735d7b2da75eddb67d1aaf9a54b72273d414f804de531ca5c67ea7f4f` |

关键方法：`PolicyAPI.get/createPolicyAPI`、`PolicyAPIImpl.init/initPolicy/getAudioPolicy`、`RemoteAudioPolicyImpl.requestMic/releaseMic`；服务端为前述 `af`、`d`、`m`。原厂导航的 `NaviDashboardManager` 也直接使用 DIM 接口和 `ILaneInfo[]`，与 PR #13 的调用方式相符。

## 验证边界

`EcarxDimApiTest`、`EcarxLaneGuidanceFlowTest` 验证导航数据 getter、回调、车道数组与空值清理、ETA 单位、启停事件和绕过 SDK 缓存的仪表恢复；`EcarxVehicleDayNightTest`、`EcarxSwKeyInterceptTest` 验证订阅、回调身份、重绑与释放；`AudioDiagnosticSnapshotTest` 验证只读音场查询和缺类回退。

`MicrophoneCaptureLifecycleTest` 使用独立 GKUI ABI fixture，验证初始化、延迟授权、撤回/恢复、挂断时取消、迟到回调、拒绝/不支持、超时和释放。fixture 的策略对象在初始化前也存在；不得把“非空”伪装成“服务已连接”。普通 Android 缺厂商类的回退由 `EcarxCapabilitiesTest` 验证。

实车仍需分别验证 Siri、普通通话、原厂助手工作时来电、连续挂断重拨以及回到媒体后的声音和麦克风路由。记录 `initializing`、`onAPIReady`、`accepted`、`onResume/onAbort`、`fallback` 和 `released`；在当前车机上预期看到初始化后的拒绝回退。模拟授权成功的单测不能作为本车真正获得麦克风的证明。
