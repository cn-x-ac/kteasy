# 更新日志

本文件遵循 [Keep a Changelog 1.1.0](https://keepachangelog.com/zh-CN/1.1.0/) 与
[语义化版本 2.0.0](https://semver.org/lang/zh-CN/)。

引擎版本口径：`0.x` 直至 M6 验收剧本稳定，随后进入 `1.0.0`。兼容面分层承诺——
**物理兼容**（对象数据表结构，仅 MAJOR 允许破坏并提供迁移器）／**协议兼容**（布局 JSON、EQL 文法、
事件载荷、开放接口形状：MINOR 只加不改删，破坏性变更＝MAJOR + 迁移公告）／**扩展点宿主 API**
（MAJOR 冻结窗口，提前一年预告）。已发布版本的 Flyway 迁移脚本永不改写，回滚＝备份点 + 镜像 tag。

变更类型：`新增` `变更` `修复` `移除` `废弃` `安全`。

## [未发布]

### 新增

- **M0-01 仓库脚手架与合规骨架**（2026-09-18）
  - monorepo 四模块：`kteasy-core`（引擎库，运行期零第三方依赖）／`kteasy-server`（装配 + REST）／
    `kteasy-demo-app`（示例业务空壳）／`kteasy-automation-graaljs`（可选脚本层占位，不进默认依赖图）。
  - 构建工具链：Gradle wrapper 8.14.5 + JDK 21 toolchain + Kotlin DSL + 版本目录
    `gradle/libs.versions.toml`（版本单一来源，模块脚本内不出现版本号）；`build-logic` 约定插件
    `kteasy.kotlin.jvm` / `kteasy.spring.boot` / `kteasy.compliance`。
  - 合规骨架：Apache-2.0 `LICENSE`、`NOTICE` 署名、每文件版权头由 spotless 8.10.2 + ktlint 1.8.0
    挂在 `check` 上强制；`.editorconfig` / `.gitattributes`（LF 统一）；CI 最小门禁
    （合规 × 2 个构建 + 防污染扫描 + build）。
  - 引擎包骨架 14 个（`meta/schema/query/data/automation/approval/transform/report/privilege/importx/file/notify/audit/kernel`），
    本卡刻意零业务码。

### 变更

- **后端框架大版本：Spring Boot 3.x → 4.x**（2026-09-18 决策）。落地基线 Boot 4.1.1 / Kotlin 2.3.21
  （取 BOM 锁定版）/ Gradle 8.14.5（官方支持面内）。版本单一来源使该项一行可回退。

### 说明

- 尚未产生任何公开 API 承诺：`0.x` 阶段所有表面（含 `kteasy.*` 配置键、REST 形状）均可变。
