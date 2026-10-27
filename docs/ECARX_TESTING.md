# ECARX 软件流程测试

测试调用真实生产入口，以测试源集中的 OEM 同名接口替代车机系统服务。通过只表示生产代码按预期发起调用或处理回调；`EcarxResult.ok` 不证明仪表实际显示、权限生效或车机兼容。

## 运行入口

在仓库根目录执行。使用 wrapper 固定的 Gradle 9.5.0；`gradle/gradle-daemon-jvm.properties` 指定 JDK 25。设置 `ANDROID_HOME` 或本地 `local.properties`，SDK 版本见 [CI 配置](../.github/workflows/android.yml)。现有依赖为 JUnit、Robolectric 4.17，common 另有 Mockito 5.20.0；不需要新增测试框架或 OEM SDK 依赖。

先运行以下 targeted 测试；每个 `--tests` 只属于它前面的测试任务。新增 ECARX 测试应进入对应选择范围，最终验收须核对新增类确实出现在 XML 报告中。

```sh
./gradlew :shared:testDebugUnitTest \
  --tests 'com.shilapi.xcertplay.hud.*' \
  --tests '*Ecarx*' \
  --tests 'com.shilapi.xcertplay.transport.Iap2RouteGuidanceSubscriptionTest' \
  --tests 'com.shilapi.xcertplay.glance.CarPlayGlanceTest' \
  :common:testDebugUnitTest --tests '*Ecarx*' \
  --no-parallel --max-workers=1 --rerun-tasks --stacktrace
```

再完整执行 [AGENTS.md](../AGENTS.md) 要求的 CI 任务；完整检查不带 `--tests`：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug \
  --no-parallel --max-workers=1 --rerun-tasks --stacktrace
```

同一共享工作区只安排一个 Gradle 运行者，targeted 和完整检查串行执行。`--no-parallel` 约束一次构建内的项目任务，并不能阻止另一个进程改写同一 `build/`。投屏连接测试使用生产固定端口 `127.0.0.1:1883`，测试任务与测试进程也须串行：命令限制 worker 为 1，外部 init 限制 `maxParallelForks=1`。端口占用时由测试 bind 明确失败，不停止占用进程或连接其服务。不要为释放旧 Robolectric 下载锁而杀掉既有测试进程。

## 离线缓存与 JDK 25 Mockito

普通网络环境可以使用默认 Robolectric 下载流程。已有下载锁占用或需复用缓存时，通过可选的外部 init 配置测试 JVM，不修改全局 Gradle 配置。Gradle 的 `--offline` 只控制 Gradle 依赖解析，不会替代 Robolectric 的离线设置；命令行 `-D` 也不会自动转发到 fork 出的测试 JVM。

下列内容可保存到工作区外的 `ecarx-test.init.gradle`。它从 `-P` 接收本地缓存和可选报告目录；Mockito agent 从 common 已有测试 classpath 找到，不增加或下载另一份依赖。此本地 init 使用任务闭包，运行时配合 `--no-configuration-cache`。

```groovy
import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

gradle.projectsEvaluated {
    allprojects { p ->
        def deps = p.findProperty('ecarxRobolectricDir')
        def results = p.findProperty('ecarxTestReportDir')
        p.tasks.withType(Test).configureEach { t ->
            t.maxParallelForks = 1
            if (deps) {
                def directory = p.file(deps)
                if (!directory.isDirectory()) {
                    throw new GradleException("Robolectric directory missing: ${directory}")
                }
                t.systemProperty 'robolectric.offline', 'true'
                t.systemProperty 'robolectric.dependency.dir', directory.absolutePath
            }
            if (results) {
                def base = p.file("${results}/${p.name}/${t.name}")
                t.reports.html.outputLocation = new File(base, 'html')
                t.reports.junitXml.outputLocation = new File(base, 'xml')
                t.binaryResultsDirectory = new File(base, 'binary')
            }
            if (p.path == ':common') {
                t.jvmArgumentProviders.add({
                    def jars = t.classpath.files.findAll {
                        it.name ==~ /mockito-core-[0-9].*\.jar/
                    }
                    if (jars.size() != 1) {
                        throw new GradleException("Expected one Mockito core jar, found ${jars.size()}")
                    }
                    ["-javaagent:${jars.first().absolutePath}".toString()]
                } as CommandLineArgumentProvider)
            }
        }
    }
}
```

设置以下变量为自己机器上的路径，给前述两条命令各追加这些参数（无需修改仓库文件）：

```sh
# ECARX_TEST_INIT：上述外部 init 文件
# ECARX_ROBOLECTRIC_DIR：已备齐的 android-all-instrumented jar 目录
# ECARX_TEST_REPORT_DIR：本次运行独立的绝对报告目录
--init-script "$ECARX_TEST_INIT" --no-configuration-cache \
  -PecarxRobolectricDir="$ECARX_ROBOLECTRIC_DIR" \
  -PecarxTestReportDir="$ECARX_TEST_REPORT_DIR"
```

这些是追加参数，不能单独作为 shell 命令运行。缓存必须包含所有 `@Config(sdk = …)` 所需的准确版本和 instrumented 修订号；缺 jar 时补齐外部缓存，不缩窄 SDK 范围掩盖缺失。已存在的缓存及其符号链接须保留。离线目录中存在 jar 不能替代实际执行结果。

Mockito 在较新 JDK 上需要 instrumentation；这里显式传入 `mockito-core` 的 `-javaagent`，不依赖自附加。若以后需要仓库级支持，可仅在 common 使用非传递的 agent configuration 和声明输入的 `CommandLineArgumentProvider`，复用现有 Mockito 版本；本轮不改 shared/common 构建文件。

配置依据：[Robolectric 配置](https://robolectric.org/configuring/)、[Mockito instrumentation](https://github.com/mockito/mockito/blob/v5.20.0/mockito-core/src/main/java/org/mockito/Mockito.java)、[Gradle 命令行](https://docs.gradle.org/current/userguide/command_line_interface.html)。

## 覆盖与验收边界

补齐前已有以下相关测试；它们不等同于 ECARX OEM 桥接完整流程已覆盖：

| 位置（测试源集相对路径） | 已有范围 |
| --- | --- |
| `common/src/test/java/com/shilapi/xcertplay/EcarxSwKeyTest.kt` | 八类动作、按键学习冲突、Siri/电话/媒体映射与开关、未知键 |
| `shared/src/test/java/com/shilapi/xcertplay/transport/Iap2RouteGuidanceSubscriptionTest.kt` | 识别中的导航显示组件与 `0x5200` 订阅内容 |
| `shared/src/test/java/com/shilapi/xcertplay/glance/CarPlayGlanceTest.kt` | 真实帧驱动的下一转向与歌曲、到达/断连、过期和空列表宽限、未知消息 |
| `shared/src/test/java/com/shilapi/xcertplay/hud/NavigationOutputWorkerTest.kt` | 输出隔离、clear 丢弃排队/迟到帧、重启与溢出清理 |

本轮补齐的预期落点如下；具体新增类和通过数须在实现就绪后的验收记录中确认，不能把计划当作已通过：

| 流程 | 覆盖落点 | 必须观察的生产输出 |
| --- | --- | --- |
| 仪表/DIM | `shared/src/test/java/com/shilapi/xcertplay/hud/`；OEM 夹具在 `shared/src/test/java/com/ecarx/xui/adaptapi/diminteraction/` | `EcarxHudBridge` 接收真实 iAP2 帧后，经 `EcarxDimApi` 发出的导航状态、箭头、距离、路名、结束清理及 presentation 回调 |
| 方控 | shared 的 hud/airplay 测试、common 的 ECARX 方控测试 | `EcarxSwKeyIntercept` 注册/拦截、OEM callback 或广播输入后，真实 listener/业务动作的次序和消费结果 |
| 多媒体 | `shared/src/test/java/com/shilapi/xcertplay/hud/` 与同一 OEM 夹具目录 | `EcarxMediaBridge` 接收 now-playing 后，经真实反射适配器发出的 source、播放/列表代理 getter 内容、进度和清空调用 |

当前实现代理提供的类名为：DIM 的 `EcarxDimApiFixtureTest`、`EcarxHudBridgeFlowTest`、`EcarxProjectionChannelTest`；方控的 `EcarxSwKeyInterceptFlowTest`、`EcarxKeyBroadcastsFlowTest` 及已有 `EcarxSwKeyTest` 扩展（含 `EcarxSwKeyHostFlowTest`、`EcarxSwKeyBluetoothFlowTest`）；多媒体的 `EcarxMediaBridgeFlowTest` 和辅助夹具 `EcarxMediaFlowFixture`，另在 common 补 `EcarxMediaSessionOwnershipTest`。这些类属于上述选择范围，仍待文件齐备后审查与执行。

验收时逐项检查：

- 从真实 bridge/intercept 的入口输入，不以 fixture setter 自己存入的值作为唯一断言。OEM fixture 负责记录生产代码调用和读取真实代理 getter；业务 callback 要断言实际次数、参数及顺序。
- 正向序列之外覆盖 stop/clear、重连、旧 callback 或迟到输入，以及可注入的 OEM 抛错/拒绝和后续恢复。失败断言要检查输出或状态，不能仅证明“没有抛异常”。
- 每个用例恢复单例、callback、偏好、时钟和 ticker；测试顺序不应改变结果。避免固定等待，用受控时钟/Looper 驱动超时。
- 同名 OEM fixture 全局存在时不能声称覆盖了 `ClassNotFoundException`；空 handle、缺失/错误方法签名、OEM 调用异常是不同边界，分别记录。缺类需要独立 classloader/独立测试进程时单列，不能伪造覆盖结论。
- targeted 报告和完整检查报告分目录保留，按 XML 分别统计 tests、failures、errors、skipped 与通过数。`UP-TO-DATE`/缓存命中不能报告为本次重新执行；失败后修复重跑要保留首次失败原因。
- 对关键缺陷保留反证：同一正确行为回归须在修复前生产源码或可复现故障注入下失败，再在修复版通过。临时切换仅在所有实现文件停止改写后进行，保存修复版并核对哈希、始终恢复；修复前后报告分开，不把编译错误当作成功复现。旧媒体回调必须捕获生产 listener，不以自建转发 lambda 代替。

关键反证分开运行：只还原 common 的 now-playing owner gate 时，保留 shared teardown 修复，检验真实旧 listener 的替换/脱离用例；只还原 shared 的 teardown 分支时，保留 common gate，检验 `noProjection` 和导航来源优先级用例。方控按缺方法、已释放 callback、Geely 迟到断开逐项定位。广播注销 API 是本轮新增接口，直接还原旧文件会造成编译失败，因此保留 API，仅注入“不再检查 receiver active 状态”的原故障，再运行迟到回调正确行为回归。每次保存准确源码差异、失败 testcase 与恢复哈希。

### 修复进度与保留问题

以下列出 targeted 执行结果与保留问题；修复前反证及完整 CI 的实际结果见末节，软件测试通过不是实车验收：

| 问题 | 当前状态 | 验收要求 |
| --- | --- | --- |
| 缺 `requestKeysInterception` 方法仍返回 accepted | 已修复；第二轮 shared targeted 对应回归通过 | 错误 handle/签名不注册且不返回成功 handle |
| release 后迟到按键仍触发业务 | 本地停用与 release 幂等已修复；第二轮 shared targeted 对应回归通过 | 保留真实旧 OEM callback，release 后 press/release 不到业务；OEM release 抛错仍本地停用 |
| 注销广播后迟到回调仍触发业务 | stopped gate 与 Activity 两处注销接线均已完成；shared/common 第二轮 targeted 对应回归通过 | 真实 Activity teardown 走新入口；保留的旧 voice/call receiver 在注销后不再触发 controller |
| Geely stop 后迟到 disconnect 重新绑定 | owner、stop 清理、主 Handler 转发与统一锁序已完成；common 的 12 项 Geely 回归通过 | 重启后旧 connection/listener 不冒充新 owner，停止后的延迟重试无效；统一 `CarPlayMediaKeys → Geely` 锁序，使身份检查与业务转发不被 stop/替换插入 |
| 旧 now-playing listener 覆盖新会话媒体 | common owner gate 已修复；3 项真实 listener 回放测试通过 | 捕获 attach 安装的实际 listener，替换/脱离 controller 后回放旧 callback，观察真实 bridge/OEM 输出 |
| 媒体断连清空卡但未释放来源 | teardown 帧复用 `clearLocked()` 已修复；shared 23 项媒体测试通过 | 先发布 `carPlayMedia` 再断连，真实 flush 得到 `noProjection`，后续 tick 不重声明；正常暂停/空标题且仍有媒体内容时保留来源 |
| ECARX 与 Geely 的蓝牙偏好行为不同 | 独立保留问题，本轮不改契约 | Geely AIDL 路径只注册语音的行为与 Activity ECARX 路径分别报告，不把前者的双触发抑制扩展为全部路径已验证 |

语音键不是本轮发现的生产缺陷：首轮测试与 Android 29 jar 常量核对确认 `KEYCODE_VOICE_ASSIST=231`、`KEYCODE_ASSIST=219`，生产默认正确识别 `200231`。原“200231 与 200219 不一致”的判断及失败用例属于测试假设错误，已撤回并修正通过；未改生产键码。重复广播用例原先要求同次投递两份，现已改为核验独立注册/注销及有序广播的中止语义并通过。真实车机发送的物理键码不由这些 JVM 结果证明。

为可重复性关闭真实 ticker/worker、手动驱动生产 tick 的用例，不能宣称验证了后台线程竞争。投屏 7 项测试已通过，包含已建立 socket 的帧验证、生产 CONNECT/CONNACK、断连新连接重发、错误 PUBACK 耗尽后恢复与拒绝 CONNACK。失败路径 socket 清理、构造 bind 失败关闭和 worker drain 补强已完成并通过第二轮 targeted；未 shutdown 后续 case 还要复用的单例 executor。空白/暂停卡、释放媒体来源和实际窗口生命周期分别报告。

### 仪表窗口软件流程

shared 测试验证导航帧→DIM 调用、面板 owner 请求与公开 show/hide API。common 已新增 `EcarxClusterPresentationLifecycleTest`，11 项 targeted 测试通过，使用真实 Presentation/`ClusterVideoTexture`，仅替代 OEM 服务、显示环境及解码器边界：

1. 从 Activity 实际 `onClusterPanelRequested()` 进入，驱动主 Looper，创建第二显示器窗口；驱动真实 TextureView listener，断言 Surface 仅传给 type 111，窗口存在后才请求 OEM 投屏。
2. 关闭/重复关闭时撤回对应 Surface 并释放 wrapper，不重复业务清理；快速 show→hide 合并后不能留下窗口或打开投屏。
3. 显示器缺失或窗口被拒绝时不请求 OEM 开启投屏；重开使用新窗口/Surface。
4. 保留已关闭 texture/窗口的实际 callback，再在关闭和重开后投递，不能使旧 Surface 回流到当前解码目标。本轮已在 Presentation 层补 closed gate，拒绝并释放迟到 Surface；对应 2 项在修复前均行为断言失败，修复版通过。

以上为已执行的 JVM 软件窗口测试，仍不证明真实 ECARX 显示器权限、解码性能、屏幕画面或仪表形状适配。

## 导航车道独立缺口

2026-10-27 上游补证：**已找到 Apple 车道样本、解析和接收能力声明，当前 fork 尚未移植；ECARX 目标映射与实车效果仍待验证。** 原始 `shihabal3amri/DiPlay` 的 [057ca284](https://github.com/shihabal3amri/DiPlay/commit/057ca2843807b2b41a9687d407fabdb810accb77)（PR #1028，已合入当前 main `9d89633035d1add5b044ba3de5e57dd078bf2511`）增加了 [Iap2LaneGuidance 解析](https://github.com/shihabal3amri/DiPlay/blob/9d89633035d1add5b044ba3de5e57dd078bf2511/shared/src/main/java/com/shilapi/xcertplay/iap2/message/Iap2LaneGuidance.kt#L13)、三车道原始载荷测试、识别容量字段7/8、接收列表5204以及Glance回调。样本“实车采集”是上游声明，本次只独立复核字节解码，未独立实车验证。详见[DiPlay 上游协议与三层归属报告](../../analysis/DIPLAY_UPSTREAM_PROTOCOL_ANALYSIS_2026-10-27.md)。

该报告还区分主线与 PR 引用的实验 `lynk` 分支：后者另有重复角度、吉利图标映射和DIM推送，部分图标记录验证于CS11_A4，不能直接视作01/753合同。当前项目继承链为原始DiPlay → jiopia/DiPlay-ECARX → 当前fork；应局部移植协议层，保留中间作者的ECARX设备逻辑及本轮生命周期修复，不整仓合并原始BYD实现。

当前代码事实：

- `shared/src/main/java/com/shilapi/xcertplay/iap2/catalog/Iap2Endpoint.kt` 注册了 `0x5204 / LaneGuidanceInfoUpdate` 名称；当前 `Iap2IdentificationClient.MESSAGES_RECEIVED_FROM_PHONE` 只声明 `0x5201/0x5202` 导航更新，没有 `0x5204`。
- `hud/RouteGuidanceState.accept()` 只处理 `0x5201/0x5202`，`AppleManeuver` 没有结构化车道字段。
- `hud/EcarxDimApi.updateLaneInfoEmpty()` 只构造空的强类型 `ILaneInfo[]`；`NaviInfoHandler` 的 `getLaneInfo()` 返回 null。`EcarxHudBridge.endLocked()` 在结束时调用空数组清理，尚无非空车道映射/推送。
- `common/src/main/java/com/shilapi/xcertplay/EcarxClusterPresentation.kt` 承载的是 CarPlay type 111 仪表视频流。视频像素里的车道由手机绘制，和 DIM 的结构化 `ILaneInfo` 是不同链路；视频显示车道不能作为 DIM 车道验收证据，空数组清理测试也不能算车道功能完成。

本地 7 APK 的只读分析已提供插件侧线索，详见工作区外层的[高德投屏分析报告](../../analysis/AMAP_PROJECTION_ANALYSIS_2026-10-27.md)：`KEY_TYPE=13012 / EXTRA_DRIVE_WAY` 经模型、排序和 `lane_Back_icon` 数字解析后发送 `ILaneInfo[]`，样本 getter 为 `getLaneNumber/getLaneBackIconId/getLaneBackIcon`，同号 PNG 也有方向/颜色线索。这些是发送端及插件位图证据，仍缺 Apple `0x5204` 到该模型的映射，以及目标 DIM 接收端/实车的编码确认；不能直接据此改生产语义或夹具。

报告另记样本 ETA 传剩余时间，而当前 DiPlay 传到达时间戳；生产代码已有目标车需要 epoch 的历史说明，差异需由目标固件核对。投屏包名、ready/首帧时序同样待实车确认，不据此改动本轮实现。

补齐结构化车道前需完成以下工作；上游已提供部分 Apple TLV/样本证据，目标 OEM 契约和完整会话语义仍未验证：

1. **样本复用与目标补采**：先复用上游三车道载荷及实验支复合角度用例，再取得目标设备的脱敏 `0x5204` 及相邻 `5201/5202`，记录 iPhone/iOS、导航 App、连接方式和画面。补采改道、到达、断连及车道消失；接收容量和5204声明尚未进入当前fork。
2. **解析契约**：上游已给出重复outer ID2、lane内0=index(u16 BE)、1=status(u8)、2=angle(s16 BE)、3=highlight(s16 BE)及status2为推荐的样本。主线只取首个angle，实验支保留重复angle；完整status枚举、lane与maneuver关联、增量和清空语义仍须核对。不能把解析失败返回空列表当作已证实的清空事件。
3. **OEM 映射**：取得目标固件实际 `ILaneInfo` 接口 getter/返回类型及车道编码证据，明确 lane 顺序、方向组合与推荐状态映射。确认独立 `updateLaneInfo()` 和 `INavigationInfo.getLaneInfo()` 的使用契约，再确定数据模型与推送策略。
4. **自动化回归**：脱敏真实样本驱动生产 parser→state→bridge→OEM getter/调用断言；覆盖多车道、未知字段/编码、畸形 TLV、重复/乱序、过期、改道、断连/重连、空车道和结束清理，以及 OEM 不支持/失败。另保留视频 surface/流生命周期测试，分别报告。
5. **实车验收**：记录精确 APK、车型/固件、权限、手机/iOS/App 与连接方式；分别核对视频车道和 DIM lane strip 的位置/顺序/推荐方向、改道更新、到达/断连清空和重连。需要真实车机与道路场景的项目单列未验，不以 JVM 测试代替。

## 本轮执行状态

历史基线 XML 核对为 2076 tests：2075 passed、1 skipped、0 failures、0 errors；该数字不是本轮重新验证结果。

首轮仅执行 shared targeted（可移植 init、离线 Robolectric、单 worker、单 fork）：84 tests，82 passed、2 failed、0 skipped、0 errors。DIM 6、HUD 10、MQTT 7、多媒体 23、glance 6、输出 worker 3、导航订阅 2 项均通过；方控拦截 18 项中 1 失败，广播 9 项中 1 失败。失败分别为语音键数字的错误预期（默认已识别 `200231`），以及重复广播注册的投递预期（实际 `abortBroadcast()` 后同次仅到第一个 receiver）。已分派所属代理核对测试，不能为满足错误假设改业务语义。初次 wrapper 被沙箱缓存锁写权限阻止的日志另存；授权使用现有缓存后完成了上述运行，未改全局 Gradle 配置。

首轮 common targeted 已执行：45 tests，32 passed、13 failed、0 skipped、0 errors。窗口生命周期 11/11、真实媒体 listener owner 3/3、原方控映射 7/7 通过；host 方控 12 项中 2 失败（语音键测试假设、Activity 未接入新注销入口），Geely 12 项中 11 失败，表现为服务未绑定、keys 为空或 connection/listener 为 null，初步需检查静态设备检测值的初始化/夹具顺序，尚不能将这些结果判作锁修复失效。编译、可移植 init 的离线设置及 Mockito javaagent 均已实际运行成功。

所属方控代理已更正测试、补 Activity 接线，并为 Geely 夹具隔离沙箱、先设置 Build 再断言真实静态检测结果，避免跨 suite 初始化缓存导致未进入生产路径；未替换设备检测生产逻辑。第二轮 common targeted 45/45、shared targeted 84/84 通过，均为 0 skipped、0 failures、0 errors。精确日志及 XML 分轮保留在本次验收的工作区外独立目录。

关键修复前反证已完成，均使用当前正确行为测试，独立切换单个生产文件；除广播保留新注销 API 并仅移除 active gate 外，其余使用修复前 Git 基线源码：

| 反证 | 用例数 | 修复前实际结果 |
| --- | ---: | --- |
| 方控拦截缺方法、迟到 callback、OEM release 失败与重复释放 | 4 | 4 项行为断言失败 |
| 广播停用 gate 故障注入 | 2 | 2 项行为断言失败 |
| 媒体 teardown 释放来源与导航优先级 | 2 | 2 项行为断言失败，缺少正确 `noProjection` |
| 捕获的真实旧媒体 listener 在替换/脱离 controller 后回放 | 2 | 2 项行为断言失败，OEM 实际收到旧歌曲 |
| Geely 迟到 disconnect、旧 owner、直接 stop 与 Binder 回调锁等待 | 4 | 4 项行为断言失败 |
| 已关闭窗口的真实 Texture listener 迟到 available | 2 | 2 项行为断言失败，旧 Surface 复活/替换当前 Surface |
| Activity 旧注销接线下的真实 voice/call receiver 回放 | 1 | 业务回调校验失败，实际仍触发 Siri/接听 |

合计 17 tests、17 个预期行为失败、0 errors、0 skipped；没有以编译失败或测试夹具异常冒充反证。每次切换通过 EXIT trap 恢复冻结修复版，并保存源码差异、测试日志、XML 和恢复哈希；完整 CI 前，7 个生产文件全部 SHA-256 与冻结快照一致。targeted 及各组反证保留独立报告，反证中的预期失败不计入正常修复版通过率。

完整 CI 首轮停止在 common：shared 1201 tests（1200 passed、1 skipped），common 980 tests（979 passed、1 failed）；两模块均 0 errors。失败为未改动的 `SettingsBackupServerTest.theCodeIsMadeOfTheDigitsAKeyboardTypesInEveryLocale`，真实 HTTP 请求抛 `IOException: Invalid Http response`，ECARX 用例在整套中全部通过。该类独立复跑 29/29 通过，未修改备份生产/测试代码，首轮失败日志与 XML 保留；尚未确认这次间歇 HTTP 失败的根因。

完整 CI 第二轮已完成：`BUILD SUCCESSFUL`，241 actionable tasks 全部 executed，三模块合计 2187 tests：2186 passed、1 skipped、0 failures、0 errors。

| 模块 | tests | passed | skipped | failures / errors |
| --- | ---: | ---: | ---: | ---: |
| shared | 1201 | 1200 | 1 | 0 / 0 |
| common | 980 | 980 | 0 | 0 / 0 |
| home | 6 | 6 | 0 | 0 / 0 |

`mobile/home/maphost` 的 `lintDebug` 和 `assembleDebug` 均通过；lint XML 均无 Error/Fatal，Warning 数分别为 4/7/1。唯一跳过是既有 `AirPlayPortSelectorTest.fallsBackWhenAnotherListenerOwnsTheWildcardPort`，主机允许 wildcard 与特定地址监听共存，触发 `AssumptionViolatedException: Host allows wildcard and specific-address listeners to overlap`；不是 ECARX 用例跳过。第二轮备份 HTTP 用例通过，但首轮失败根因仍未确认，不能以重跑通过掩盖这一限制，未扩大备份服务修复范围。

本次机器的日志和独立报告保留于 `/private/tmp/lyno-ecarx-acceptance/`：`shared-targeted-r2`、`common-targeted-r2`、各反证目录、`full-ci`、`backup-server-repro` 与 `full-ci-r2`；JUnit XML 位于各轮的 `<module>/testDebugUnitTest/xml/`。最终再次核对 7 个生产文件均与冻结快照 SHA-256 相同。未修改全局 Gradle 配置、未新增仓库脚本或构建依赖、未提交或推送；真实车机、结构化车道映射以及上述固件契约均未验收。
