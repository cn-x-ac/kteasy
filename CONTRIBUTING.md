# 贡献指南

先说结论：**引擎的正确性优先于功能数量，也优先于发布节奏。** 如果你的改动会让某条收口变形，先开 Issue 讨论，别直接用 PR 倒逼。

## 环境

- JDK 21（其余由 Gradle toolchain 处理，不需要本机另装）
- `./gradlew build` 即全量校验：编译 + 单测 + 版权头/ktlint
- 本地若通过 `~/.gradle/init.d` 全局脚本注入镜像源：可以正常工作，构建会打若干行
  "prefer settings repositories" 告警——这是刻意的（`repositoriesMode = PREFER_SETTINGS`），
  告警不是错误，也别把它改成 `FAIL_ON_PROJECT_REPOS`（会直接拒掉这类环境）
- 若 `services.gradle.org` 拉取超时：请改用你所在网络的 Gradle 分发镜像**预置本机 wrapper 缓存**，
  不要修改 `gradle/wrapper/gradle-wrapper.properties` 里的官方 URL（CI 与海外贡献者依赖它）

## 三条收口（改动碰到就得写进 PR 描述）

1. 一切实体写走 `data` 的通用写入通道，一切实体读走 `query`（**禁止旁路 SQL**，SQL 只出自 `query`/`schema`）；
2. 一切物理结构变更走 `schema` 的变更作业（幂等、可断点续跑）；
3. 方言差异只经 `SchemaProvider` + capability 标志表达，**业务层不写 `if (isMySQL)`**，也不许"两边假装等价"。

聚合/回填按元数据依赖图由通用 recalc 完成，禁止按对象手写级联；N2N 与子项一律差量更新，禁止全删重插或只插不删。

## 提交与 Pull Request

- **按功能块拆分小提交**，一个提交做一件事；不要把格式化与行为改动混在同一个提交里。
- 提交署名由仓库维护者统一注入（`GIT_AUTHOR_*` / `GIT_COMMITTER_*` + `--no-gpg-sign`），贡献者不必配置。
- 新建源码文件必须带版权头（`/*` + `Copyright 2026 阿杰很厉害 <506907958@qq.com>. SPDX-License-Identifier: Apache-2.0` + Apache 短段落）；
  跑 `./gradlew spotlessApply` 会自动补齐。版权头模板全仓唯一：`config/spotless/license-header.txt`。
- 依赖只接受宽松许可（Apache-2.0 / BSD / MIT）。**不接受复制、改写或以"看过原文之后重写"方式引入的任何
  受 copyleft 传染的上游产品源码与文档文字**——本项目以 Apache-2.0 分发，这条没有商量余地，CI 会做词表与许可扫描。
- 新端点先在 API 契约总表登记再写实现（契约先行）。
- 涉及双库的改动：同一测试类跑 PG 与 MySQL 两个 Profile（Testcontainers 真容器，禁 H2、禁 mock SQL）；
  合理降级必须**显式**标注 capability 并写理由，静默通过＝违规。

## 门禁链

`spotless（版权头/风格）` → `防污染词表扫描` → `L1 纯函数单测` → `L2 双库集成 ×[pg,mysql]` →
`L3 引擎端到端` → `L4 对抗与故障（沙箱逃逸、kill -9 断点续跑、并发）` → `L5 性能基线（夜间/里程碑末）` →
`ArchUnit（出口唯一 + 方言隔离）` → `覆盖率地板`。任一红＝不合入。

## 版本与变更日志

改动影响发布内容时，更新 `CHANGELOG.md` 的 `[未发布]` 段。兼容面分层承诺（物理／协议／宿主 API）见该文件抬头。
