# diplay-ecarx 工程治理审计报告

- **日期**：2026-11-17
- **分支 / 提交**：`rust-carplay-control-ownership` @ `62f11958`（与 `origin/dev` 0/0，即等同当前 dev）
- **审计范围**：仓库结构与构建、架构归属、代码质量、测试、CI/CD、依赖与供应链、文档、技术债与流程
- **审计方式**：**只读**（未改动任何生产代码）。所有结论均由仓库内文件、命令输出支撑；未运行完整 Gradle/设备测试，构建健康度以 CI 为准。
- **跟踪目标**：本次为"先出报告与路线图，确认后再动代码"。

---

## 1. 总体结论

这是一个**成熟度很高、纪律性罕见**的工程：Conventional Commits、超大测试面（JVM `@Test` ≈ 3453、Rust `#[test]` = 337）、CI 覆盖 Rust 单测/原生 ABI 构建/JVM 测试/lint/R8/签名发布/密钥扫描、AGENTS.md 级别的详尽归属契约、生产代码几乎零 TODO、Kotlin 仅 1 处 `!!`。

风险不在"脏"，而在**治理自动化滞后于手工纪律**：多数质量约束依赖开发者自觉，缺乏可执行的护栏（无 Kotlin/Rust 静态分析门禁、无覆盖率、无依赖自动化/漏洞扫描、无架构规则测试）；同时存在**巨型类集中**、**分支漂移**、**文档膨胀/重复**三类结构性债务。

**综合评级：B（良好，但护栏不足）。**

## 2. 分维度评分卡

| 维度 | 评级 | 一句话结论 |
| --- | --- | --- |
| 架构 | B | 依赖方向干净（mobile→common→shared，无环），但仍有 3 个巨型类且归属规则无机械约束 |
| 代码质量 | C+ | 手工风格好，但无 detekt/ktlint/rustfmt/clippy，质量只靠 Android Lint |
| 测试 | B- | 测试量非常大，但无覆盖率度量，设备测试不进 CI，库模块未 lint |
| CI/CD | B+ | 流水线扎实（密钥扫描/ABI 校验/R8/签名），缺依赖与漏洞扫描 |
| 依赖与供应链 | C | 无 dependabot/renovate、无 cargo audit/deny、Gradle 无依赖锁定 |
| 文档 | B- | 丰富但 AGENTS.md 48KB、验收矩阵混入流水账，存在重复与陈旧 |
| 技术债与流程 | C+ | release/main 分支漂移、缺 CODEOWNERS/CONTRIBUTING/PR 模板、`${user.home}` 缓存污染 |

---

## 3. 分维度发现（按严重度）

严重度：**P0 高** / **P1 中** / **P2 低**。"证据"均可复现。

### 3.1 架构

- **A1【P1】巨型类集中，变更耦合高。**
  - 证据：`common/src/main/java/com/shihab/diplay/CarPlayHostActivity.kt` = 6336 行；`DiPlayActivity.kt` = 5619 行；`shared/.../orchestration/CarPlayController.kt` = 3827 行。
  - 现状：已做过部分拆分（`CarPlaySidePanel.kt`、`CarPlaySafeAreaEditor.kt`、`DiPlayControls.kt`、`DiPlayUpdates.kt` 均存在），但宿主 Activity 仍过大。
  - 建议：以 AGENTS.md 的归属表为依据继续抽取（面板/手势、设置组合、更新编排、诊断），并为每次抽取补回归测试。
- **A2【P1】归属契约无机械约束，存在漂移风险。**
  - 证据：全仓无 Konsist/ArchUnit/边界测试（`grep -rIlE 'konsist|ArchUnit|ModuleBoundary'` 结果为空）。AGENTS.md 用约 48KB 散文描述"Rust 拥有决策、Kotlin 只执行"，但代码可静默违反。
  - 建议：引入 Konsist（Kotlin）做模块依赖方向 + 命名/包规则测试；对"Kotlin 不得复制核心状态"等关键禁令增加 grep 门禁或小范围契约测试。
- **A3【P2】依赖方向干净。** `mobile → common → shared` 单向无环（`common/build.gradle.kts` 用 `api(project(":shared"))`，`mobile` 依赖两者），maphost 独立示例。**这是优点，建议在文档中固化。**
- **A4【P2】构建 DSL 不统一。** `shared/build.gradle`（Groovy）与其余 `*.gradle.kts`（Kotlin DSL）混用；`compileSdk` 存在 `release(37)` 与 `compileSdk = 37` 两种写法。建议统一为 Kotlin DSL。

### 3.2 代码质量

- **Q1【P0】Kotlin 无静态分析门禁。**
  - 证据：无 detekt/ktlint/spotless 配置（grep 全部为空），无 `.editorconfig`；CI 的 lint 只覆盖 `:mobile:lintDebug :maphost:lintDebug`（`.github/workflows/android.yml:200`），**库模块 `common`/`shared` 未被 lint**。
  - 建议：加入 `detekt` + `ktlint` + `.editorconfig`；CI 对 `common`/`shared` 也跑 `lintDebug`。
- **Q2【P0】Rust 无静态分析门禁，且恐慌面集中。**
  - 证据：CI/脚本无 `clippy`/`rustfmt`（grep 为空）；`src/lib.rs:5` 全局 `#![allow(clippy::not_unsafe_ptr_arg_deref, clippy::missing_safety_doc)]`；生产侧 `unwrap/expect/panic` 约 **1437** 处、`unsafe` 约 **1088** 处；`Cargo.toml` release `panic = "abort"`（任一 panic 直接终止进程）。
  - 建议：`cargo fmt --check`；`cargo clippy -- -D warnings`（先在告警级别跑一轮消除，再收紧）；对 JNI/FFI 入口做一次 panic 面审计，关键路径改为返回错误而非 unwrap；补 `missing_safety_doc`。
- **Q3【P1】抑制注解集中在弃用与强转。** `@Suppress` 共 139 处，其中 `DEPRECATION` 77、`UNCHECKED_CAST` 36、`unused` 14——隐含 API 迁移与类型安全债。建议纳入 detekt 规则并逐步清理。
- **Q4【P2】生产代码几乎零 TODO/FIXME。** 仅 11 处 FIXME，全部位于 vendored 的 `shared/src/testCore/java/org/concentus/*`（测试用 Opus 参考实现）。**优点**；债务改由 docs/issues 跟踪。

### 3.3 测试

- **T1【P0】无覆盖率度量。** 无 jacoco/kover（grep 为空）。测试量虽大，但无覆盖率趋势与回归门禁，巨型类的未覆盖面不可见。建议加 Kover 报告（先非阻断，观察基线后再门禁）。
- **T2【P1】设备测试不进 CI。** `shared/src/androidTest` 有 45 个文件（≈190 用例），CI 无 `connectedAndroidTest`/`managedDevice` 任务（grep 为空），仅能本地手工跑。建议加一个 managed-device（或定时/手动的真实设备）作业，纯逻辑部分用 Robolectric 下沉。
- **T3【P1】库模块未在 CI lint。** 见 Q1，`:common`/`:shared` 的 Android Lint 缺失。
- **T4【P2】测试面强。** JVM `@Test` 3453（common 1525 / shared 2110），Rust `#[test]` 337。**优点。**
- **T5【P2】长测试稳定性需关注。** `common/build.gradle.kts` 记录过 Robolectric 全量 OOM（提至 2g heap）；`TEST_FORKS` 分叉缓解。建议引入 flaky 隔离/重试策略与时长趋势监控。

### 3.4 CI / CD

- **C1【P1】无依赖自动化。** 无 `.github/dependabot.yml`（也不见 renovate）。建议加 Dependabot（gradle + cargo + github-actions 三条生态）。
- **C2【P1】无漏洞扫描。** 无 `cargo audit`/`cargo deny`/OWASP dependency-check。项目含加密/TLS/JNI，供应链敏感。建议 CI 增加 `cargo audit`，Gradle 侧加 OWASP 或依赖检查。
- **C3【P2】流水线本身很扎实。** 密钥/私钥扫描（`scripts/check_public_tree.py`）、原生 ABI 与 ELF/JNI 导出校验、R8 `minifyReleaseWithR8`、独立签名恢复与清理、源码打包与校验和。**优点。**
- **C4【P2】JDK 版本偏前沿。** CI 用 `java-version: '25'`，本机为 JDK 21；建议在 README/BUILD 明确所需 JDK，降低本地与 CI 漂移。
- **C5【P2】缓存键含占位词。** 两处 workflow 使用 `key: ndk-28.2.13676358-${{ runner.os }}` 作为 NDK 缓存键；确认其为稳定常量而非轮换值，否则缓存反复失效。

### 3.5 依赖与供应链

- **D1【P1】Gradle 无依赖锁定/校验。** 无 `dependencyLocking`、无 `verification-metadata.xml`。构建可复现性弱于 Rust（Rust 有 `Cargo.lock` 且 CI `--locked`，**优点**）。
- **D2【P1】Rust 依赖混合加锁。** 加密类 `=` 精确锁定（`curve25519-dalek`、`num-bigint`、`aes`、`ctr`、`libopus_sys`），但 `ring`/`libc`/`jni-sys` 用 caret 范围。建议对安全相关依赖统一精确锁定并记录升级流程。
- **D3【P2】无 SBOM / 许可证自动化。** 发布物已有 licenses 与第三方声明（**优点**），但缺机器可读 SBOM；可选 `cargo-cyclonedx` + Gradle SBOM。

### 3.6 文档

- **Doc1【P1】AGENTS.md 体量过大且与架构文档重复。** 约 48KB / 162 行（长行），5 个一级章节，归属细则与 `docs/ARCHITECTURE.zh-CN.md`、`docs/RUST_CORE_REMAINING.zh-CN.md` 存在信息重叠；单一来源难以维护同步。建议：AGENTS 收敛为"索引 + 硬规则"，细则迁至 `docs/owners/*` 并通过链接引用。
- **Doc2【P1】验收矩阵混入按批流水账。** `docs/RUST_CORE_REMAINING.zh-CN.md` 592 行，把"当前验收矩阵"与逐批日期化记录混在一起，历史不断膨胀状态页。建议：拆分为"当前矩阵"（短、稳定）+ `docs/benchmarks/*`（历史证据）。
- **Doc3【P2】文档新鲜度不齐。** 如 `docs/RELEASE-NOTES-NEXT.md` 明言部分用户指南"尚未对照最新 UI 复核"；中英文文档并存（内部 zh-CN、用户侧英文 + zh-TW/zh-CN），需要一次新鲜度核查。
- **Doc4【P2】有价值设计文档未纳入版本管理。** `docs/CONNECTION-TRANSPORT-SWITCH.md`（226 行 USB/Wi‑Fi 切换方案）当前为 **untracked**，存在丢失风险。建议明确：作为设计提案提交，或转为 GitHub Issue 跟踪。

### 3.7 技术债与流程 / 仓库治理

- **G1【P0】分支策略漂移。** 本地 `release` 相对 `origin/dev` 为 **2 ahead / 915 behind**；`main` 为 **0 ahead / 922 behind**（即 main 已被 dev 完全包含）。历史上已出现"release 落后 dev"问题（`.workbuddy/memory/2026-10-29.md`）。建议：明确 `dev → release` 切版模型、保护分支、删除过期本地分支，避免误从陈旧分支发版。
- **G2【P1】缺协作治理文件。** 无 `CODEOWNERS`、`CONTRIBUTING.md`、`.github/PULL_REQUEST_TEMPLATE.md`（`SECURITY.md` 已存在，**优点**）。若走 PR 流，建议补齐。
- **G3【P1】`${user.home}` 缓存污染工作区。** `shared/${user.home}/.m2/...` 与 `common/${user.home}/.m2/...` 共 **72 个** Robolectric/Maven 下载文件（已被 `.gitignore:44` 忽略，但会被反复生成）。根因是测试配置使用了未展开的 `$user.home` 路径。建议在测试 JVM/离线仓库配置中指向规范缓存目录（如 `~/.m2` 或工程 `build/` 下）。
- **G4【P2】`.workbuddy/` 未被忽略。** 属本地工作记忆，`git check-ignore` 显示未忽略。建议加入 `.gitignore` 或纳入受控 docs。
- **G5【P2】minSdk 与目标平台表述张力。** 各模块 `minSdk = 24`，但 AGENTS.md/文档称"生产目标 Android 9 / API 28"，且 Rust `vpipe` 视频路径要求 API 28+（`shared/.../airplay/NativeVideoRoute.kt` 有 `SDK_INT >= 28` 分支）。建议：要么 `minSdk` 提到 28，要么在文档显式说明 24–27 为降级/调试环境（当前 174 处 `SDK_INT` 门禁表明降级是刻意设计）。

---

## 4. 优先级路线图

### P0 — 本周可做（低风险、纯护栏，不改产品逻辑）
1. CI 增加 `cargo fmt --check` 与 `cargo clippy`（先告警，后 `-D warnings`）；对 JNI 入口做 panic 面审计。
2. 引入 `detekt` + `ktlint` + `.editorconfig`；CI 对 `common`/`shared` 增加 `lintDebug`。
3. 增加 Kover 覆盖率报告（先非阻断）。
4. 定义分支模型并清理/保护分支（解决 G1 漂移）。

### P1 — 2–4 周
5. 依赖治理：Dependabot（gradle/cargo/actions）+ `cargo audit`；Gradle 依赖锁定/校验。
6. 增加设备测试 CI 作业（managed device 或定时真机）；纯逻辑下沉 Robolectric。
7. 补 `CODEOWNERS` / `CONTRIBUTING.md` / PR 模板。
8. 修复 `${user.home}` 缓存根因；`.workbuddy/` 决定忽略或入库。
9. 文档治理：拆分 `RUST_CORE_REMAINING` 为"当前矩阵 + 历史"，AGENTS 收敛为索引+硬规则。

### P2 — 结构性、持续
10. 巨型类继续拆分（`CarPlayHostActivity`/`DiPlayActivity`/`CarPlayController`），并配 Konsist 架构测试。
11. 统一构建 DSL（shared 转 Kotlin DSL）；统一 `compileSdk` 写法。
12. 清理 `@Suppress(DEPRECATION/UNCHECKED_CAST)` 债；可选 SBOM 生成。
13. 文档新鲜度核查（用户指南对照最新 UI）。

> 说明：以上 P0/P1 均为**工程护栏**，不触碰产品行为与 Rust/Kotlin 归属，风险低，适合作为"治理第一批"。

---

## 5. 已确认的优势基线（避免过度整改）

- 提交规范：Conventional Commits + 明确 scope（`refactor(bluetooth):`、`fix(media):`、`docs:`…）。
- 测试规模：JVM ≈3453 `@Test`、Rust 337 `#[test]`、androidTest 45 文件。
- CI：Rust 单测 + 原生 ABI 构建 + JVM 分片测试 + lint + R8 + 签名发布 + 密钥/私钥树扫描 + 源码/校验和。
- 安全：`scripts/check_public_tree.py` 阻断凭据；发布 APK 不含 MFi 身份；签名密钥不打包。
- 代码卫生：生产代码 0 TODO、Kotlin 仅 1 处 `!!`；Rust 加密依赖精确锁定。

---

## 6. 建议下一步

本报告为只读结论。建议你从路线图挑选要执行的项（推荐先做 **P0 四项护栏 + P1 的依赖/分支治理**），我再按选定范围逐项落地，每项独立提交、附验证证据。

---

### 附：关键证据复现命令

```bash
# 巨型类
wc -l common/src/main/java/com/shihab/diplay/CarPlayHostActivity.kt \
      common/src/main/java/com/shihab/diplay/DiPlayActivity.kt \
      shared/src/main/java/com/shihab/diplay/orchestration/CarPlayController.kt
# 静态分析缺失
grep -rIlE 'detekt|ktlint|spotless' --include='*.kts' --include='*.gradle' . ; grep -rInE 'clippy|rustfmt' .github scripts
# Rust panic/unsafe
grep -rInE '\.unwrap\(\)|\.expect\(|panic!|unreachable!' shared/src/main/rust --include='*.rs' | wc -l
grep -rInE '\bunsafe\b' shared/src/main/rust --include='*.rs' | wc -l
# 覆盖率/依赖自动化
grep -rIlE 'jacoco|kover' --include='*.kts' --include='*.gradle' . ; ls .github/dependabot.yml 2>/dev/null
# 分支漂移
git rev-list --left-right --count release...origin/dev ; git rev-list --left-right --count main...origin/dev
# 缓存污染
find 'shared/${user.home}' 'common/${user.home}' -type f | wc -l
```
