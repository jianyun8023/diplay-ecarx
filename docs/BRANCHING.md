# 分支模型（Branching model）

本文定义 DiPlay 的分支与发布规则，并记录当前分支状态与建议。**删除/保护任何分支前需先确认。**

## 1. 分支角色

| 分支 | 角色 | 说明 |
| --- | --- | --- |
| `dev` | **主干（trunk）** | 唯一活跃集成分支。功能与修复分支合入这里，CI（`.github/workflows/android.yml`）在 `push`/`pull_request` 上校验。 |
| `main` | **上游基线镜像** | 对应上游 DiPlay 基线，只作参考，不承载本项目的持续开发。 |
| `release` | **历史发布线（待收敛）** | 早期发布分支；当前已与 `dev` 分叉，保留仅为历史。 |
| 功能分支 | 短期 | 从 `dev` 切出，命名 `fix/<topic>`、`feat/<topic>`、`refactor/<topic>`、`docs/<topic>`；合并后删除。 |
| 标签 `v<versionCode>` | 发布锚点 | 从 `dev` 打标签；GitHub Release 以 `v<versionCode>` 触发 `.github/workflows/release.yml`，版本号即 APK `versionCode`。 |

## 2. 发布流程

1. 在 `dev` 上完成并验证改动，CI 全绿。
2. 从 `dev` 打 `v<versionCode>` 标签（`versionCode` 来自 `mobile/build.gradle.kts` 的 `android.defaultConfig.versionCode`）。
3. 发布 GitHub Release（`release.yml` 校验 tag 与 APK `versionCode` 一致，产出签名 APK、licenses、源码、mapping、`update.json`）。
4. **不要**从陈旧的 `release`/`main` 分支发版——它们可能落后主干数百个提交。

## 3. 当前状态（2026-11-18 复核）

| 分支 | 最新提交 | 相对 `origin/dev` |
| --- | --- | --- |
| `origin/dev` | `f3e7f852`（2026-11-17） | 主干 |
| `origin/feat/ecarx-carplay-duck-handshake` | `895ca625` | PR #126 源分支（开放） |
| `origin/feat/boot-ready-gate` | `e0fd87a7` | 领先 2 个提交，**无开放 PR**，在途工作（开机自启等热点就绪） |
| `origin/exp/carplay-ultra-bench` | 2026-11-11 | 实验分支 |
| `main`（仅本地） | `c7b60eda`（2026-10-27） | 上游基线存档；远端已删 |
| `release/v121`（仅本地） | `3d143eb5` | 仅多一个 versionCode=121 提交，发布锚点由 `v121` 标签承担 |
| `release-v150-armv8`、`build/dev-release-v130`（仅本地，含 worktree） | — | 已完全包含于 dev，无独有内容 |

早期 `release` 分支（曾领先 dev 两个提交）**本地与远端均已删除**，删除前其内容核实如下：

- `fix(usb): preserve iPhone trust waits and actionable pairing outcomes`——同标题修复已在 dev 祖先中（`98f8657a`、`2fda17bd`，2026-10-28），dev 版本范围更大；
- `docs(release): record verified USB pairing promotion`——纯文档，已被 [native-usb-2026-11-13 验收记录](benchmarks/native-usb-2026-11-13.md) 和 2026-11-14 的 USB 收尾确认覆盖。

## 4. 建议的收敛动作（需确认后执行）

1. **保护 `dev`**（GitHub → Settings → Branches）：要求 PR、CI `check` 通过、禁止强推。
2. **`main`**：保持本地存档即可；如需同步上游，定期从 `upstream/main` fast-forward，不要在其上直接开发。
3. **删除本地残余发布分支**（确认后）：`release-v150-armv8`、`build/dev-release-v130`（内容均在 dev）、`release/v121`（发布锚点由 `v121` 标签承担）。删除 `build/dev-release-v130` 前先移除其 worktree。
4. **处理在途分支**：`feat/boot-ready-gate` 领先 dev 两个提交且无 PR，应补 PR 或明确去留；`exp/carplay-ultra-bench` 与已合并的 `feat/rust-protocol-media-all` 等可清理（确认后）。
5. **不要**从陈旧的 `main`/残余发布分支发版——发版只从 dev 打 `v<versionCode>` 标签。

> 本文件只给规则与建议；分支保护是 GitHub 仓库设置，需在网页端开启；分支删除需维护者显式确认，本仓库改动不自动执行删除。
