# 2026-10-30 导航音量映射复核

基线：`origin/dev` / `88f39fa035e5b7fcd701aaad18762507bde4aa21`。
本次只补证据与无需 ADB 的复测步骤，不更改播放样本、默认通道、设置、权限或媒体后端。
不涉及应用启动唤醒 Siri，不修改原厂或第三方 APK。

## 当前结论

`12/1` 和 `12/20` 已有相同 BUS01 路由的实车证据。
标准 Android 9 的分组逻辑和原厂设置的调用方式，都支持“二者使用相同导航音量控制”的判断方向；**当前报告仍不能确认原厂导航滑块对它们的实际控制关系，也没有实测 groupId**。
相近听感不构成改默认值、增加通道或继续盲试 stream 编号的依据。

## 今天报告能够证明什么

输入：本地 `analysis/test-evidence-20261030/diplay-report-6.txt`、`7.txt`、`8.txt`。
原始报告保留在本地研究目录，不随本文件提交。

| 去重后的完整试听 | 请求属性 | 实际路由 |
| --- | --- | --- |
| 4 次 | usage12 / content20 | BUS01_NAV_GUIDANCE |
| 2 次 | usage12 / content1 | BUS01_NAV_GUIDANCE |
| 4 次 | usage1 / content2 | BUS00_MEDIA |
| 1 次 | 请求属性截断，不归入上面两种导航模式 | BUS01_NAV_GUIDANCE |

计数方法：只扫描 `at=… action=AUDIO_PREVIEW phase=result`，按完整事件行去重；request 数字会复用，不能单独作为事件身份。
18 条 result 记录去重后为 11 条。每条均为 cancelled=false、479462 字节写入、239731 帧播放头；三份报告共享的 7 条重复记录不算独立复现。
同一份报告内将 request 元数据与 result 对应；头部已截断的 request3 不补猜 content。
独立的 uv/Python 标准库检查对上述计数及 `writtenBytes == playbackHeadFrames * 2 == 479462` 做了断言。

便于人工复核：报告6的110–115行为12/20、128–134行为12/1；报告8的153–160、168–174行为12/20。
报告8的request6–10多次快速切换，部分没有完整result，不能说每次试听都完整播放。
三份报告保存 navigationChannel=0；报告6保存 OEM=false，7/8保存 OEM=true。
“当前配置导航”因此会重复固定12/1或固定12/20之一，不是第三条独立硬件通道。
同一约5秒中文PCM、48kHz单声道、0.6音轨增益，排除这次对照中应用有意改变素材或音轨增益。

### 真实导航也已有 BUS01 证据

报告8第1393行（06:56:41）、3967–3976行（07:06:13–15）是实际 `audioType=default` / OPUS 导航，不是固定试听。
记录 channel=NAVIGATION、legacyStream=none、BUS01、播放游标推进及 writeErrors=0；会话配置为 OEM=true。
因此不再把“真实导航是否进入BUS01”当作完全未验证。
但 usage12/content20 仍标记 attributesSource=configured；API28的试听started同样标记属性读回 unavailable。
这证明应用请求/Builder接受20、音频写入及实际设备路由，不证明底层原始属性或DSP保留20。
跨天日志中21点的legacy13/BUS00记录属于昨天，不能算今天自动路由回退，也不能拿保存快照套到旧记录。
本次未在今天早晨段找到真实导航12/1的同场滑块对照，不能把两种固定试听当成已完成真实导航A/B。

## 原厂调用链与 Android 9 的交叉证据

下面本地路径相对研究目录 `analysis/ecarx-app-dump-20261028/`，是已读取的APK反编译材料，不是当前进程运行跟踪。

1. `oem-settings/sources/com/ecarx/settings/audioadjust/AudioAdjustHelper.java:65–80`：遍历音量组，通过组内usage识别媒体、导航等组；`navi_sound=1`只是初始化值，运行时会被查得的groupId覆盖。其`getStreamType()`在此实际返回组ID，不是legacy stream。
2. 同文件174–186、207–238行：导航滑块的写/读最终调用CarAudioManager的setGroupVolume/getGroupVolume。444–457行按组内usage条目的rank选择显示类别；仅凭一个UI标题不能倒推出组的全部成员。
3. `AudioFocusManager.java:112–120`用厂商`NAVIGATION_GUIDANCE`查导航usage；`vehicle/sources/com/ecarx/xui/adaptapi/policy/AudioAttributesImpl.java:25`映射到12。该表的导航content是20，但不等于content参与分组。
4. `oem-navigation/sources/ecarx/naviservice/manager/NaviAudioManager.java:25–28、81–83、102–104`使用`navi_usage`读写导航音量。客户端`CarAudioManager`为抽象接口，`VehicleAPI`反射加载系统`VehicleAPIImpl`；当前资料没有字符串到具体组ID的服务端映射。
5. `DEFAULT_NAVI=13`对应同文件恢复默认时的`setSystemVolume(1,13,false)`，是音量值，不是legacy13或group13的证据。

本轮先通过Context7查询，再读取固定Android9源码：[CarAudioService.java，android-9.0.0_r8](https://android.googlesource.com/platform/packages/services/Car/+/android-9.0.0_r8/service/src/com/android/car/CarAudioService.java)。
它把usage映射为音频上下文；动态路由按usage匹配，组查询按上下文匹配，content不参与这两步。组配置来自`R.xml.car_volume_groups`，总线映射来自HAL；同一组可绑定多个总线，但一个总线不能属于两个组。组查询还受`CAR_CONTROL_AUDIO_VOLUME`保护。
**这是标准实现，不能直接当作本车厂商CarService或具体组编号。**
据此，若本车沿用这段按usage分组的逻辑，12/1和12/20应属于同一导航组；相近听感与此相容，不证明厂商完全忽略content。

未找到本车CarService及其有效资源/overlay、音频策略与HAL映射；原厂设置的`car_volume_items.xml`是显示条目，不是`car_volume_groups.xml`。
不能用默认AOSP组顺序、BUS名称尾号、AudioDeviceInfo.id=10、usage12或SDK默认值填补实际groupId。

## 各字段的证明边界

| 读数 | 能证明 | 不能单独证明 |
| --- | --- | --- |
| BUS01 | 此音轨报告的逻辑输出设备 | 原厂滑块、groupId、扬声器座位、最终增益 |
| usage12 | 请求导航用途，标准实现中选NAVIGATION上下文 | 厂商最终全部策略与物理路由 |
| content1/20 | 请求内容类型，Builder是否保留 | API28底层属性、单独音量组、听感必不同 |
| focus granted | 焦点请求获准 | 音量值、组编号、一定未被厂商压低 |
| 相近听感 | 此样本/滑块/环境下未听出明显区别 | 相同内部增益、相同物理扬声器、content全局无效 |
| 原厂滑块的重复单变量响应 | 对该控制的功能性依赖 | 同groupId、同DSP或同座位 |

## 最小实车复测：只需要媒体和当前导航两项

停车，固定手机录音位置、声场、应用导航衰减，关闭背景音乐；保持当前已可播的导航模式，不再切换12/1与12/20。
用原厂设置中的明确滑块调整，避免用音量键猜当前控制对象。

| 阶段 | 原厂媒体滑块 | 原厂导航滑块 | 操作 |
| --- | --- | --- | --- |
| A | 低但非零 | 中 | 播媒体、当前导航各一次 |
| B | 不变 | 明显降低但非零 | 再播同两项 |
| C | 明显提高 | 恢复A | 再播同两项 |
| D | 恢复A | 恢复A | 重复确认恢复 |

每次等完整句子结束后再点下一项。每阶段拍下两滑块位置、口述阶段名并从应用导出一份报告；不需要电脑或ADB。
B结束先恢复A的滑块位置，再单独提高媒体进入C；各阶段都与A比较，不在播放期间同时调两个滑块。
若现有原厂导航支持“重复播报/语音预览”，可在A/B各播一次相同原厂语句作为共同控制的对照；没有此入口就跳过，不额外开路线或改原厂导航开关。

判定：B仅导航变小、C仅媒体变大、D恢复，且报告路由/模式未变，即可确认当前导航受该原厂导航滑块独立控制。这个功能结论不依赖先取得groupId。
若两项一起变、或均无明显变化，则只报告实际响应，不能认定唯一内部原因；手机录音不换算SPL或精确增益差。

## 为何本轮不增加应用诊断

明确缺口是运行时组映射/音量与滑块因果关系，当前三份报告没有这些读数。
本地SDK有getVolumeGroupIdForUsage/getGroupVolume/min/max接口，但应用尚未接CarService，基线Manifest未声明CAR_CONTROL_AUDIO_VOLUME；原厂settings声明了此权限。
报告的platform signature match和其它权限不能推出该权限已获准，MODIFY_AUDIO_ROUTING=false也不是该权限的同义项。
在未确认运行时入口和权限前，不能把反射失败、SDK桩返回值、Settings缓存、或getStreamVolume(MUSIC)包装成导航组读数。
上述8次播放已经能验证用户关心的滑块控制关系，因此不为获得一个编号引入权限、服务生命周期或隐藏Binder协议。
若后续必须取得确切groupId，应先验证同固件系统库/权限，再只读查询usage1/12对应组、组成员与音量范围；失败明确记录unknown，不假定0或默认1。

## 本次验证与范围

已完成：独立worktree基线检查、报告去重与帧/字节断言、原厂调用点及固定Android9源码复核。
应用和测试代码无改动；未重新引入已删除的启动Siri功能，未修改媒体后端或播放样本。
隔离worktree按AGENTS.md执行shared/common/home全部单元测试，以及mobile/home/maphost的lintDebug与assembleDebug，全部通过（7m21s，241项任务全部执行）。
使用既有JDK25、handoff-support SDK及离线Robolectric依赖，完整构建输出保留在本机`/tmp/lyno-nav-volume-evidence-20261030-check.log`，不随文档上传。
此检查验证基线可构建，不代表修复了实车音量问题；滑块A/B/C/D尚未执行。
