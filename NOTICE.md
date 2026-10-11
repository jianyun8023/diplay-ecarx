# 修改与归属声明（NOTICE）

本文件记录本仓库相对于上游所做的修改，并保留上游与第三方的署名要求。
**再分发本仓库或其修改版时，请一并保留本文件**，以及 [`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md) 中列出的全部声明。

---

## 一、这是什么

本仓库是 **DiPlay** 的修改版；DiPlay 本身是 **xcertplay** 的修改版。

| 项目 | 说明 | 许可 |
| --- | --- | --- |
| [xcertplay](https://github.com/shilapi/xcertplay) | 原始 Android 车机 CarPlay 接收端实现（作者 shilapi） | GNU GPL-3.0 |
| [DiPlay](https://github.com/shihabal3amri/DiPlay) | 在其之上构建、面向车机的发行版（作者 shihabal3amri） | GNU GPL-3.0 |
| **本仓库** | 面向领克 05 / 亿咖通 ECARX 车机的移植版 | GNU GPL-3.0 |

许可原文见 [`LICENSE`](LICENSE)。界面部分另有 AGPL-3.0 约束，见第四节。

## 二、修改声明（GPL-3.0 第 5 条要求）

- **本仓库经过修改**，不是 DiPlay 或 xcertplay 的原始版本。
- **修改者**：本仓库的维护者，见 git 提交者信息（`git log`）。
- **修改日期**：起始于 **2026-10-09**（首个本地提交），其后持续修改；准确日期见 git 提交历史。
- **修改内容**：完整记录见 [`CHANGELOG.md`](CHANGELOG.md) 与 git 历史，概要如下——
  - 移除上游面向 BYD / DiLink 的专用内容（不影响 ECARX 路径）；
  - 新增面向 ECARX 车机的适配与修复，按序标记为 `fix1` … `fix9`，含：ECARX DIM 仪表投送、
    车机权限声明与参数修正、RTP 时钟探针与 Opus 编码器支持、通话回声参考、
    音频焦点与路由、**蓝牙 profile 级让出（保留 iAP2 握手）** 等；
  - 原生库改为预编译 `.so` 随仓库提供，**构建不再需要 NDK**；
  - 平台签名改用 AOSP 默认 platform 密钥（本车机固件为 `test-keys`，厂商未更换该密钥），
    以取得清单中 `signature` 级权限——见 [`BUILD.md`](BUILD.md)。
- ★ 本仓库**不是**上游的官方发布，维护者与上游作者**无隶属、赞助或背书关系**；
  本仓库的改动**不代表上游立场**，也不向上游提交。

## 三、凭据与签名密钥（重要）

- 本仓库（源码）**不包含** CarPlay 认证所需的 MFi accessory 身份（私钥与证书），
  也**不包含**任何 Android 签名密钥。这两者都由构建时从仓库外注入，详见 [`BUILD.md`](BUILD.md)。
- 上游公开说明（原文见 [`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md) 的
  *Experimental authentication data* 一节）：其预览版 APK 内嵌的是一套**从公开 Carlinkit C2Air
  固件中恢复的**证书/密钥对，**并非**为该上游项目新签发的 Apple 凭据，也不作为项目源码再许可；
  其能否持续被接受、是否适合公开分发，**均未定论**。
- ⇒ 由本仓库构建出的 APK 若内嵌了任何身份文件，**其中的私钥可被任何人提取**。
  这是该路径固有的性质，不是疏漏；请据此判断你是否要分发这些产物。

## 四、第三方署名

接收端上游署名（xcertplay / LIVI / Showcase）、界面（DiAuto，**AGPL-3.0**）、CarPlay 图标、
运行时依赖（AndroidX、Kotlin、Bouncy Castle、JmDNS、SLF4J）、原生回声消除（SpeexDSP）等
完整名单、许可类型与原文位置，见：

- [`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md)
- `docs/licenses/`（含 `DiAuto-AGPL-3.0.txt` 与 `docs/licenses/dependencies/`）

## 五、商标与免责

CarPlay 及相关标识是 **Apple Inc.** 的商标。本项目**不是 Apple 认证产品**，与 Apple、
吉利/领克、亿咖通均无关联，也不代表其认可。本软件按 GPL-3.0 第 15、16 条
「**不提供任何担保**」分发；安装到车辆上进行测试的风险由使用者自行承担。

---

## Modifications notice (English summary)

This repository is a **modified version** of [DiPlay](https://github.com/shihabal3amri/DiPlay),
which is itself a modified version of [xcertplay](https://github.com/shilapi/xcertplay).
Both are licensed under GNU GPL-3.0.

- **Modifier**: the maintainer of this repository (see the git commit metadata).
- **Date of modification**: from **2026-10-09** onward; see the git history for exact dates.
- **Nature of the changes**: upstream BYD/DiLink-specific content removed; ECARX head-unit
  adaptation and fixes added (marked `fix1`…`fix9`); native libraries shipped prebuilt so the
  NDK is no longer required; release signing switched to the AOSP default platform key so that
  `signature`-level permissions are granted on this head unit. Details in `CHANGELOG.md`,
  `README.md` and `BUILD.md`.

This is **not** an official release of either upstream project, and it implies **no affiliation
with or endorsement by** their authors. The source repository contains **no MFi accessory
identity and no Android signing key**; the accessory identity bundled in prebuilt APKs is a
certificate/key pair recovered from public Carlinkit C2Air firmware as described upstream, and
**its private key is extractable by anyone**. If you redistribute this repository or a modified
version of it, **keep this file** and the notices listed in `docs/THIRD_PARTY_NOTICES.md`.

CarPlay and its marks belong to Apple Inc. This is not an Apple-certified product.
