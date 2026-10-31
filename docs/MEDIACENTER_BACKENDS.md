# 车机媒体接管方

两种模式都发布标准 Android MediaSession，供嘟嘟等安卓桌面读取歌名、歌手、专辑、真实时长、
封面 Bitmap/URI、进度及播放状态，并通过标准播放、暂停、上下曲回调控制 iPhone。
标准会话在音乐音频首次活跃时创建，暂停后保留，断连时释放。创建会话不等于申请音频焦点。

| 设置 | Android MediaSession | 亿咖通媒体源 | HY dashboard 补发 |
| --- | --- | --- | --- |
| DiPlay 直接接管 | 发布 | DiPlay 的 MediaCenterSessionEngine 注册、发布和清理 | 不发送 |
| HY 代理 | 发布 | HY 读取标准会话后代理；DiPlay 不注册、不申请播放归属 | 当前匹配封面就绪后定向发送 |

沿用 `diplay_media_session/ecarx` 偏好：原“亿咖通”（true / ECARX）对应“DiPlay 直接接管”；
原“标准安卓”（false / ANDROID）对应“HY 代理”。没有新增选项或改变旧值。

**默认值是“DiPlay 直接接管”（true）**：`d19750f6` 把 `MediaSessionSettings.mode()` 的缺省从
ANDROID 改成 ECARX，本文早先写的“ANDROID 也是默认值”已不成立。老用户的选择不受影响，
只有从未设置过的机器会拿到新默认。
没有 HY 时，“HY 代理”仍可作为纯标准 Android 会话使用。修改下次连接生效，设置仍提示重新连接。
已有 `EcarxOutputSettings.mediaEnabled` 继续约束 DiPlay 的直接输出。

## 会话、焦点与按键

直接接管模式新增的标准会话不申请音乐焦点，保留原 ECARX/音频渲染器行为。
HY 模式保留现有焦点策略：渲染器已管理焦点时，媒体会话不重复申请；禁用媒体键焦点时也不申请。
否则保留媒体键焦点的丢失、通话临时中断及 iPhone 真实恢复播放时的恢复逻辑。
元数据、暂停和进度来自同一份 iPhone 状态，不伪造暂停、时长或封面尺寸。

ECARX 已取得媒体控制权时，Geely、ECARX 备用广播、前台 raw KeyEvent、标准 MediaSession
硬件媒体键及 Accessibility 的标准媒体键映射不重复转发。自定义非标准媒体键映射仍按原设置处理。
没有 ECARX 控制权时保留已有备用路径。HY 模式不订阅 Geely 媒体键，也不接管 ECARX 媒体键；
标准 transport controls 在两种模式下始终可用。没有添加按时间猜测重复事件的防抖。
标准回调捕获连接归属，断连后的旧回调不能控制新连接，即使复用同一 controller 对象。

**Siri 接管不再独立于媒体接管方**（`c8f087ac` 之后）：两条语音键通道都以“直接接管”为前提——
`GeelyWheelKeys.start()` 走 `shouldHandleSiriKey()`，ECARX 广播通道走 `managesVehicleKeys()`，
两者在 `outputMode == ANDROID` 时都为假。因此在 HY 代理模式下 DiPlay **不接管语音键**，
按键由 HY 处理；HY 要把它转发成 Siri，需要在 HY 的按键选择器里把该键指向 `:siribridge`
启动器（它只发 `com.shihab.diplay.action.SIRI`，不会把 DiPlay 界面带到前台）。
`requestSiriFromAction()` 本身不检查接管方，所以这条转发链在两种模式下都可用。
不恢复“打开应用唤醒 Siri”，也不调整导航、声音路由或声道配置。

> 待修：设置里 `settings_media_output_description` 仍写着“自定义 Siri 按键均可用”，与上述行为不符，
> 且自定义按键本版未适配。改文案前不要以那句为准。

切换到 HY 代理前，仍等待旧 ECARX 注册释放确认，避免 HY 一读取新 active session 就争用媒体源。
迟到的注册 token 必须清理；超时或清理结果不确定时保留阻塞及诊断，拒绝清理需要重启进程。
这只约束接管方交接，不再以标准会话和 ECARX 发布互斥为生命周期前提。

## HY 封面补发

本地 HY 3.6.1 证据：

- `analysis/hywebview-361-20261030/MediaAction-simple.java`：每 800ms 读取首个 active session，
  完整刷新依赖播放状态（受设置控制）、duration 或 Bitmap.rowBytes；metadata/state 回调为空。
- 同目录 `business/sources/com/hyphp/hywebview/Global.java` 的 `HyMediaReceiver/dashboard`：
  接收当前歌曲字段和封面 URI，受 HY 授权、`radioYibiao` 等条件控制。
- `business/sources/z0/i.java`：更新会调用 `updateCurrentSourceType`、
  `updateMusicPlaybackState` 和 `updateMediaList`。它会更新亿咖通媒体信息，不只是 HY 本地 UI。

`CarPlayMediaSession` 拥有发送资格和去重状态。仅 HY 模式且标准会话 active、当前歌曲对应的
封面 URL 已就绪时，`HyMediaDisplay` 向 `com.hyphp.hywebview` 定向发送已有 `dashboard` 协议。
复用 `ClusterArtworkServer` 的回环 HTTP URL；发送 URI 而非复制完整 Bitmap 到广播。
当前元数据、封面 URL 或播放状态改变时补发，仅进度变化不发。
封面先到时缓存至匹配元数据到达；后到时在解码完成后补发。已解码缓存可在会话激活时发布。
异步解码与主线程之间还核对 controller 当前保留快照，快速切歌不发布排队的旧歌曲。
非当前引用、旧连接回调和断连后不补发；损坏图片不清除有效封面。

`HyMediaDisplay` 只封装字段与广播失败隔离，不拥有另一份媒体状态，不发送媒体控制或 Siri 命令。
HY 未运行或接收条件不满足时，广播不会缓存重放；后续封面/元数据/播放态变化会再次尝试。
同一引用内实际图片归属仍依赖 iAP2 传输引用及现有歌曲匹配规则，不能从字节长度推断歌曲。

## 实车配置边界

直接接管时必须确保 HY 不再代理车机媒体。DiPlay 不主动发 HY 消息，但 HY 若仍轮询标准会话，
就可能主动注册或更新自己的亿咖通媒体源。不能假定 HY 的 mode=0 已完全退出，必须结合实际版本、
授权、启动注册与运行日志验证。DiPlay 不修改 HY APK，也不停止第三方服务。
HY 代理模式则需要 HY 的媒体代理、授权和相应仪表功能实际可用。

标准会话消费者需自行具备 Android 所要求的会话读取权限。软件测试不证明嘟嘟/HY 已取得权限，
也不证明真实 HY 已渲染封面。既有报告 6/7/8 属于旧 88f39fa 包，重复日志不能算多次复现；
HTTP 成功次数不能识别请求者或证明仪表已显示。

## 验证与维护

`MediaOutputRoutingTest` 覆盖两模式标准控制、直接模式没有 HY 补发、ECARX 清理交接、
旧会话回调、相同时长/尺寸封面、复用引用、缓存及先后到达、快速切歌、播放态变化和进度去重。
`HyMediaDisplayTest` 验证定向广播字段、缺省值和失败隔离；焦点与 Siri 使用原有专项回归。
设置截图由 `SimplifiedSettingsUiTest.mediaModeHasAnAdvancedEntry` 生成，尺寸 1920×1080。
按 `AGENTS.md` 运行 shared/common/home 测试及 mobile/home/maphost lint、Debug 构建。

实车待验：两模式下嘟嘟读取信息及控制；HY 同尺寸/同时长换曲封面；延迟封面和快速切歌；
后台、暂停、通话结束恢复；每个物理键只执行一次；重连及模式交接没有遗留媒体源。
不得将静态源码或本地 fixture 测试视作真实 HY/车机运行验证。
