# ECARX 媒体输出

当前原车媒体卡片只使用 ECARX MediaCenter 发布 CarPlay 的媒体信息和控制，不再提供 UNI、DIM 或 Standard 后端切换。
导航、HUD 和车道继续走独立的 DIM 导航通道；移除的是 DIM 媒体通道。

“仪表媒体卡片”开关控制额外的 MediaCenter 输出。Android 系统媒体会话、封面、播放状态及
播放／暂停／上下曲回调继续保留，HY 等监听器仍可读取系统媒体信息。

HYWebviewDCY 3.5.1 的同尺寸封面刷新兼容：其 `a1.a` 元数据回调为空，800ms 轮询仅在
播放状态（受 HY 设置控制）、时长或位图 `rowBytes` 变化时重新读取。DiPlay 换曲时暂存上一张
封面，新封面到达后尺寸相同，可能导致 HY 一直保留旧图。嘟嘟使用的标准元数据无需修改。

`CarPlayMediaSession` 在 Android 媒体会话活跃且当前封面 URL 已就绪时，通过无状态的
`HyMediaDisplay` 协议适配器向
`com.hyphp.hywebview` 定向发送其既有 `HyMediaReceiver` / `dashboard` 展示消息，附当前歌曲
字段和本机回环封面 URL。复用现有 `ClusterArtworkServer`，不更改图片尺寸、歌曲时长或
MediaSession 元数据。进度轮询不补发；旧会话、非当前封面、解码失败及断连后不补发。
HY 的接收端保留自身的启用条件；HY 未运行时该消息不会缓存等待投递。此入口更新展示，
不调用 `requestPlay`，不发送方控或 Siri 命令。

依据为本地 HY 3.5.1 脱壳代码：`a1.a` 的轮询与元数据回调、`Global.g` 的 `dashboard` 接收器、
`z0.i.a` 的媒体展示更新。`MediaOutputRoutingTest` 覆盖换曲先到歌名后到封面、同尺寸图片、
相同时长、复用封面引用、缓存封面、过期回调和标准元数据不变；仍需按实际 HY 版本实车验收。
2026-10-29：新增两项回归在修复前失败，修复后 28 项定向测试通过；完整检查 2681 项通过、
1 项原有跳过，Lint 和三个 Debug 构建通过。测试验证本机图片内容及广播契约，未运行真实 HY 或嘟嘟。

语音交接尚未修改：HY 与 DiPlay 都接收原车语音有序广播；HY 的“语音”动作启动
`com.ecarx.xiaoka/.XKMainActivity`，`hyCarPlay` 媒体方案则投递给 Autokit，并非 DiPlay。
后续需要明确单一接管方和 Siri 转发契约，不能仅凭媒体控制正常推断语音兼容。

## 功能边界与回归入口

| 功能 | 维护位置 | 边界与验证 |
| --- | --- | --- |
| 标准媒体信息、封面及方控 | `CarPlayMediaSession` | 拥有会话与封面缓存，决定当前控制器及发送时机；`MediaOutputRoutingTest` 验证标准元数据、同尺寸封面与控制不串线。嘟嘟继续使用标准 MediaSession。 |
| HY 展示兼容 | `HyMediaDisplay` | 只把当前曲目与非空封面 URI 转成定向 `dashboard` 广播，封装协议字段和广播失败；无缓存、计时器、偏好读取或控制器引用。`HyMediaDisplayTest` 验证完整字段、空值默认值及失败隔离。 |
| ECARX 媒体输出 | `CarPlayMediaSession` → `MediaCenterSessionEngine` | 协调器提供同源元数据；引擎负责注册、播放归属与清理。HY 展示不能申请 MediaCenter 播放归属。 |
| Siri 声音与音频焦点 | `AndroidMediaSink` 的渲染器及焦点协调器 | Siri 沿媒体输出播放，但保留 ASSISTANT 身份与临时焦点；启用渲染器焦点时，媒体会话不重复申请音乐焦点。对应 `SiriAudioRoutingTest`、`AudioFocusAutoYieldTest`、`CarPlayMediaFocusDisabledTest`。声音路由不决定谁接收语音按钮。 |
| 桌面地图 | `CenterMapLifecycle`、`CenterMapOverlay`、`MapMirrors` | 生命周期对象管理显示条件、桌面监听和回调注册／清理；宿主提供真实 111 地图流状态与当前 sink，Overlay 管窗口，MapMirrors 管镜像 Surface。`CenterMapFollowsDashboardTest` 验证设置、宿主流回调及新 sink 绑定；`CenterMapLifecycleTest` 验证前后台、导航结束和重连时序。 |

桌面地图保留原时序：正常切后台由 Overlay 延迟 600ms 检查进程前台状态；回到 DiPlay
立即隐藏并停止桌面监听。地图流结束延迟 3000ms 隐藏，期间恢复则取消隐藏；超过宽限期
后恢复，重新按 600ms 展示路径处理。宿主的代次检查仍先过滤旧流回调，生命周期对象
不复制流状态。新建或接续 sink 时仍由宿主调用 `MapMirrors.reapply()`，绑定回调读取当前
sink。销毁时取消闲置隐藏、停止监听、隐藏窗口并释放自己注册的回调。

会话资格、当前曲目与异步封面归属仍集中在 `CarPlayMediaSession`，不下放给 HY 适配器，
避免建立第二套媒体状态。广播失败只记录诊断，不影响标准元数据、播放状态及其他媒体输出。
音频与地图已有各自的生命周期；不为此次兼容增加统一的“车机功能管理器”或额外策略层。
新增 HY 协议字段在适配器维护；变更发送时机需跑媒体路由回归；变更焦点或地图条件需跑对应行为回归。
提交前仍执行根目录 `AGENTS.md` 的完整 CI 检查。实车验证继续覆盖 HY、嘟嘟、方控、Siri
声音与桌面地图；语音接管契约独立分析，不能由展示适配器发送语音命令。

独立的 MediaCenter 探测面板已移除。运行所需的权限检查、服务可用性判断和接口契约解析仍保留；
平台签名匹配不能代替实际权限和厂商服务授权。常规诊断报告保留注册、播放接受、会话归属和清理状态。

断连和会话替换必须释放旧注册，旧回调不能控制新会话。调用超时不表示远端已取消，
不能通过反复注册掩盖未确认的清理结果。

实车回归检查歌名、封面、进度、暂停、后台接续和每个方控动作只执行一次，
并确认导航结束与重连正常。软件测试通过不等于车机授权或实际显示已验证。
