# 测试基线（P0 改造前）— 2026-11-17

工具链：Rust 1.97.1（rust-toolchain.toml 锁定）+ rustfmt 1.9.0-stable + clippy 0.1.97；JDK 21（本机，CI 用 25）；Gradle 9.5.0；NDK 28.2.13676358。

| 套件 | 结果 | 备注 |
| --- | --- | --- |
| Rust `cargo test --locked --lib` | **332 passed / 0 failed / 0 ignored**（9.20s） | 日志 reports/baseline/rust-test.log |
| JVM `:shared:testDebugUnitTest` | **2122 tests / 0 fail / 0 error / 2 skipped**（328 类） | Robolectric |
| JVM `:common:testDebugUnitTest` | **1659 tests / 0 fail / 0 error / 0 skipped**（239 类） | Robolectric |
| JVM 合计 | **3781 tests / 0 fail / 2 skipped** | BUILD SUCCESSFUL in 3m12s |
| `cargo clippy --all-targets` | **0 error / 47 生产警告（lib）；测试目标 60（含 47 重复）** | 日志 reports/baseline/rust-clippy.log |
| `cargo fmt --check` | **失败**：约 2.4–2.7 万行、147 文件差异（全仓从未按 rustfmt 格式化） | reports/baseline/rust-fmt-check*.log |

clippy 警告构成：`missing_transmute_annotations` 24、`items_after_test_module` 11、`manual_is_multiple_of` 4、`too_many_arguments` 4、其余 10 类各 1。

结论：测试全绿；clippy 仅告警无错误且量可控；fmt 需要单独一次性重排（大 diff），不宜与功能改动混合。
