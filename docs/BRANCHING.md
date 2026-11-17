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

## 3. 当前状态（2026-11-17）

| 分支 | 最新提交 | 相对 `origin/dev` |
| --- | --- | --- |
| `origin/dev` | `62f11958`（2026-11-17） | 主干 |
| `main` | `b46ff9be`（2026-10-25） | 落后 915（且已被 `dev` 完全包含） |
| `release` | `3d5e64c4`（2026-10-28） | 落后 915、领先 2 |
| `origin/feat/rust-protocol-media-all` | 2026-11-13 | Rust 核心迁移（已由 PR #97 合入 `dev`）的历史分支 |
| `origin/exp/carplay-ultra-bench` | 2026-11-11 | 实验分支 |
| `origin/main-kt` | 2026-11-12 | 旧 Kotlin 主线 |
| `backup/main-before-upstream-reset` | 2026-10-27 | 本地备份 |

`release` 比 `origin/dev` 多出的 2 个提交：
- `8e4203f6 fix(usb): preserve iPhone trust waits and actionable pairing outcomes`
- `3d5e64c4 docs(release): record verified USB pairing promotion`

它们属于早期发布线；`dev` 已在其后演进 915 个提交。**处置前需确认这些修复是否已在 `dev` 以其它形式存在**（对比 `docs/RUST_CORE_REMAINING.zh-CN.md` 与 USB 相关记录）。

## 4. 建议的收敛动作（需确认后执行）

1. **保护 `dev`**（GitHub → Settings → Branches）：要求 PR、CI `check` 通过、禁止强推。
2. **`main`**：保持为上游镜像即可；如需同步，定期从 `upstream/main` fast-forward，不要在其上直接开发。
3. **`release`**：确认上述 2 个提交已被 `dev` 覆盖后，**以标签取代分支**（`v<versionCode>` 已是发布锚点），可删除该分支。删除前请确认。
4. **清理已合并/过期远程分支**（确认后）：`feat/rust-protocol-media-all`、`main-kt`、`exp/carplay-ultra-bench`。
5. 本地 `backup/main-before-upstream-reset` 可保留或删除（确认后）。

> 本文件只给规则与建议；分支保护是 GitHub 仓库设置，需在网页端开启；分支删除需维护者显式确认，本仓库改动不自动执行删除。
