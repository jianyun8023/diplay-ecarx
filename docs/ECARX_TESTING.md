# ECARX 软件测试

当前目标：领克 ECARX Android 9 / API 28。测试调用生产入口，以测试源集的 OEM 同名接口、USB shadow 和本地服务替代车机边界；不依赖 OEM SDK。夹具见 [ECARX_FIXTURES.md](ECARX_FIXTURES.md)，实车步骤见 [TESTING.md](TESTING.md)。

## 默认检查

在仓库根目录运行，与 [CI](../.github/workflows/android.yml) 和 [AGENTS.md](../AGENTS.md) 一致：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:lintDebug :mobile:assembleDebug --no-parallel --max-workers=1
```

使用仓库 Gradle wrapper、JDK 25，设置 `ANDROID_HOME` 或本机 `local.properties`。编译 SDK 和构建工具以 CI 配置为准；编译 SDK 不代表测试设备版本。现有 JUnit、Robolectric 4.17 和 Mockito 5.20.0 足够，不新增测试框架。

测试范围直接由两份清单控制，注释按车机功能分组：

- [shared 清单](../shared/src/test/lynk-android9-tests.txt)：仪表与导航、USB 数据链路、热点与授权、音频与 Siri、主屏视频生命周期、协议与认证边界。
- [common 清单](../common/src/test/lynk-android9-tests.txt)：封面与媒体会话、授权和设置入口、连接恢复、方控与 HiCar 诊断、日志脱敏/导出、备份和安装包校验。

Robolectric 默认 SDK 与允许执行的 SDK 均为 28。重点测试的类级 SDK 已改为 28，不依靠 SDK 过滤把原来标记为 29 的关键用例跳掉。类中明确测试其他版本专用 API 的方法保留源码，但不纳入本车机默认执行。

旧版本 USB、Android 12/13 连接 API、电视、MTK、通用外观/语言矩阵以及 home/maphost 示例检查退出默认入口；源码继续保留。认证、协议输入验证、诊断脱敏及数据完整性检查保留。新增本范围测试必须被清单覆盖，并在 XML 报告中确认实际执行。修改范围外功能时先调整清单；`--tests` 只能进一步缩小默认范围，不能绕过它。

仅需定位单个相关功能时，例如：

```sh
./gradlew :shared:testDebugUnitTest --tests '*Usb*Test' \
  :common:testDebugUnitTest --tests '*Usb*Test' \
  --no-parallel --max-workers=1
```

每个 `--tests` 属于前面的测试任务。不要把定向测试结果当作默认全部检查已通过。默认报告位于模块 `build/reports/tests/testDebugUnitTest/`；ECARX 界面截图也在 common 的构建报告目录中，由 CI 收集。

## 本地离线缓存与报告

同一工作区只运行一个 Gradle 进程；测试最多一个 fork。仪表投屏测试使用生产固定端口 `127.0.0.1:1883`，端口被占用时应明确失败，不停止占用进程或连接其服务。

需要离线 Robolectric 缓存、独立报告或 JDK 25 Mockito agent 时，复用 [启动配置](../scripts/testing/ecarx-acceptance.init.gradle)，给上述命令追加：

```text
--offline --no-configuration-cache
--init-script scripts/testing/ecarx-acceptance.init.gradle
-PecarxRobolectricDir=/absolute/path/to/robolectric-jars
-PecarxTestReportDir=/absolute/path/to/run-reports
```

这些是追加参数，路径由本机提供，不写入仓库。缓存只需满足 API 28 对应的准确 `android-all-instrumented` 版本；Gradle 的 `--offline` 不控制 Robolectric 下载，因此需同时指定缓存目录。init 脚本复用 common 现有 Mockito core 作为 agent，不依赖自附加，不引入依赖。

## 结果边界

XML 报告用于确认用例数、失败和跳过；应特别检查 `ClusterArtworkServerTest`、`EcarxMediaSessionOwnershipTest`、`UsbReadQueueCompatibilityTest`、`UsbPermissionSetupTest`、`CarHotspotControlSettingsTest`、`CarHotspotTetheringTest` 和 `VoiceKeyDiagnosticsTest` 均实际执行。实车仍须确认 OEM 能取到图片、系统 USB 授权完成、USB 实际传输、热点广播和车机按键行为。
