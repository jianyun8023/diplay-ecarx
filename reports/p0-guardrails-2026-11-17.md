# P0 工程护栏落地记录（2026-11-17）

基于审计报告 [engineering-governance-audit-2026-11-17.md](engineering-governance-audit-2026-11-17.md)，本次落地 P0 五项护栏。**均为工程护栏，不改变产品行为**；每项均附本地验证证据。

## 变更文件

| 文件 | 变更 |
| --- | --- |
| `.github/workflows/android.yml` | rust_tests 加 clippy 步骤；新增 `kotlin_static`（JDK21 detekt）与 `coverage`（Kover，非阻断）任务；build 增加 `:shared:lintDebug :common:lintDebug`；`check` 汇总纳入 `kotlin_static` |
| `build.gradle.kts` | 声明 `org.jetbrains.kotlinx.kover` 0.9.11（apply false） |
| `common/build.gradle.kts` | 应用 kover；新增 lint baseline 配置 |
| `shared/build.gradle` | 应用 kover；新增 lint baseline 配置 |
| `shared/src/main/rust/diplay-media/src/media/audio/aaudio.rs` | 为 `load!` 宏所在 `api()` 加作用域 `#[allow(clippy::missing_transmute_annotations)]`（附理由） |
| `config/detekt/detekt.yml` | 新增：bug 级规则集（potential-bugs/coroutines/performance 去 Spread），关闭主观 style/complexity/naming |
| `.editorconfig` | 新增：Kotlin/Rust 通用格式契约（IDE/未来 ktlint） |
| `scripts/run_detekt.sh` | 新增：JDK≤22 守卫 + 固定 detekt-cli 1.23.8 运行 |
| `common/lint-baseline.xml`、`shared/lint-baseline.xml` | 新增：冻结历史 lint 问题（394 / 90 条），只拦新增 |
| `docs/BRANCHING.md` | 新增：分支模型与收敛建议 |

## 逐项与验证

### P0-1 Rust clippy（已门禁）
- 门禁：`cargo clippy --locked --all-targets -- -D clippy::correctness -D clippy::suspicious`。
- 处理：仅 `aaudio.rs::api()` 的 `load!` 宏触发 24 条 correctness 级 `missing_transmute_annotations`，该处 `_` 目标类型由 `Api` 字段承载，属有意设计 → 作用域 allow。
- 验证：门禁 **exit 0**；`cargo test --locked --lib` **332 passed / 0 failed**（改动后回归）。
- 剩余：lib 23 条 style 级警告作为 backlog；后续清理后可升级为 `-D warnings`。

### P0-1 Rust fmt（**已门禁**）
- 已按项目锁定工具链 Rust 1.97.1 执行一次性 `cargo fmt` 全仓规范化：**88 个 `.rs` 文件**，12,163 增 / 3,154 删，纯格式化（无语义变化）。
- 验证：`cargo fmt --check` **exit 0**；`cargo test --locked --lib` **332 passed**；clippy 门禁 **exit 0**。
- CI：`rust_tests` 增加 `Rust formatting` 步骤（`cargo fmt --manifest-path shared/src/main/rust/diplay-media/Cargo.toml --check`）；toolchain 组件改为 `clippy, rustfmt`。
- 建议作为独立提交 `style(rust): normalize with rustfmt`，避免与本批其它改动混在一起评审。

### P0-2 Kotlin 静态分析（detekt + .editorconfig，已门禁）
- 直接用 Gradle 插件不可行：Gradle 守护进程锁 JDK 25（`gradle/gradle-daemon-jvm.properties toolchainVersion=25`），detekt 1.23.8（Maven Central 最新）内嵌 Kotlin 编译器拒绝 JDK 25。
- 方案：独立 CLI，CI `kotlin_static` 任务在 **JDK 21** 运行 `scripts/run_detekt.sh`（固定 detekt-cli 1.23.8，缓存 jar）。
- 规则：`config/detekt/detekt.yml` 只保留 bug 级规则；关闭 style/complexity/naming/formatting 等（该代码库大量厂商/反射/特定异常处理，主观规则噪声高）。
- 验证：当前 `shared`+`common` 主源 **0 问题，exit 0**；无需 baseline，直接拦新增 bug 模式。
- ktlint：格式契约见 `.editorconfig`；正式 ktlint/detekt-formatting 门禁与 rustfmt 同理需先做全树规范化，暂不启用。

### P0-3 库模块 lint（已门禁）
- `shared`/`common` 此前**从未在 CI lint**，且当前分别有 49/55 个历史 error。
- 方案：为两模块配置 `lint-baseline.xml`（冻结历史问题），CI 增加 `:shared:lintDebug :common:lintDebug`。
- 验证：`Lint found no new issues (49 errors / 55 errors filtered by baseline)`，**BUILD SUCCESSFUL**。
- 维护：有意变更后运行 `./gradlew :shared:updateLintBaseline :common:updateLintBaseline`。

### P0-4 Kover 覆盖率（已接入，非阻断）
- Kover 0.9.11 在 Gradle 9.5 上可用；`koverXmlReportDebug`/`koverHtmlReportDebug` 产出真实数据。
- 验证：
  - `:common` — BUILD SUCCESSFUL（3m37s），XML/HTML 有 instruction/line 计数。
  - `:shared` — BUILD SUCCESSFUL（2m06s），**1039 类**，行覆盖约 **13752 / 22414 ≈ 61%**。
- CI：新增 `coverage` 任务（`continue-on-error: true`，**不纳入 `check`**），上传 `build/reports/kover/`。非阻断，先看趋势，后续可加 `koverVerify` 阈值。

### P0-5 分支模型（已文档化）
- `docs/BRANCHING.md`：`dev` 为唯一主干；发布用 `v<versionCode>` 标签触发 `release.yml`；`main` 作上游镜像；`release` 线待收敛。
- 现状：`main` 落后 `origin/dev` 915（已被包含）；`release` 落后 915、领先 2（2 个提交见文档）。
- **删除/保护分支需维护者确认**；分支保护在 GitHub 网页端开启。

## 本地验证汇总

| 检查 | 结果 |
| --- | --- |
| Rust `cargo test --locked --lib` | 332 passed / 0 failed |
| Rust `cargo fmt --check` | exit 0（88 文件已规范化） |
| Rust clippy 门禁（correctness/suspicious） | exit 0 |
| detekt（共享+公共主源） | 0 issues, exit 0 |
| Android lint `:shared`/`:common`（baseline） | no new issues, BUILD SUCCESSFUL |
| Kover `:shared`/`:common` XML+HTML | 均 BUILD SUCCESSFUL，含真实覆盖数据 |
| 既有 JVM 单测（kover 运行期） | 全绿（作为报告依赖执行） |

工具链：Rust 1.97.1；Gradle 9.5（守护进程 JDK 25）；detekt 用 JDK 21；Kover 0.9.11；NDK 28.2.13676358。

## 已知限制 / 待办
1. rustfmt 已完成全仓重排并门禁（88 文件），建议单独提交 `style(rust): normalize with rustfmt` 以免混淆评审。
2. clippy 23 条 style 警告：清理后升级 `-D warnings`。
3. ktlint/detekt-formatting 格式门禁：同 rustfmt，需先全树规范化。
4. `coverage` 初始为非阻断，观察若干周后再决定是否加阈值。
5. 分支删除与保护：需维护者执行。
6. detekt CLI 每次冷跑下载约 66MB（CI 已缓存）。
