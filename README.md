# Kteasy

> 元数据驱动的低代码引擎：对象 / 字段 / 自动化 / 审批 / 记录转换 / 单据模板。
> 一次建模，得到表、表单、列表、查询、权限与流程——引擎在运行时把它们物化出来。

| 项 | 值 |
|---|---|
| 作者 / 版权 | 阿杰很厉害 &lt;506907958@qq.com&gt;（`Copyright 2026 阿杰很厉害`） |
| 许可 | [Apache-2.0](LICENSE) · 署名见 [NOTICE](NOTICE) 与每个源码文件头 |
| 身份四件套 | 域名 `x-ac.cn` ↔ Java 包根 `cn.x.ac` ↔ GitHub 组织 [`cn-x-ac`](https://github.com/cn-x-ac) ↔ npm scope `@x-ac` |
| 仓库 | `cn-x-ac/kteasy`（monorepo） |
| 技术栈 | Kotlin · Spring Boot 4.x（JDK 21）· PostgreSQL（参考实现）/ MySQL 8（对应支持）· Vue3 |
| 查询语言 | EQL（Entity Query Language） |
| 交流与安全披露 | 邮箱 506907958@qq.com（`SECURITY.md`：72h 内响应） |

## 我能用它做什么

给业务对象建模（订单、工单、合同……），引擎负责：动态存储与加字段（不锁表）、表单与列表布局、
EQL 查询与权限过滤、字段级校验、when/then 自动化、审批流、记录转换、单据模板与导出、数据导入与可追溯、
附件（对象存储）、通知总线与审计。引擎与业务分离——`kteasy-core` 是纯库，示例业务只是它的第一个用户。

## 模块结构

```
kteasy-core               引擎库（元数据/物化/EQL/写入通道/自动化/审批/转换/权限/导入/文件/通知/审计/内核）
kteasy-server             唯一常驻进程：Spring Boot 装配 + REST 层（薄壳，零业务逻辑）
kteasy-demo-app           示例业务（M6：管理端与用户端 SPA、CRM 验收剧本）
kteasy-automation-graaljs 可选脚本层（GraalJS），不进入 server 默认依赖
build-logic               Gradle 约定插件（构建配置单一来源）
```

三条不可让步的收口：一切实体写 `data.write()`；一切实体读走 `query`；一切物理结构变更走 `schema` 作业。
方言差异只经 `SchemaProvider` + capability 标志表达，绝不在业务层写 `if (isMySQL)`。

## 快速开始

```bash
./gradlew build                 # 编译 + 单测 + 合规校验（版权头 / ktlint）
./gradlew spotlessApply         # 自动补齐版权头与格式
./gradlew -p build-logic build  # 约定插件自身也参与校验
```

要求：JDK 21（其余由 Gradle toolchain 处理）。开发态双库与对象存储、起服与 `/api/health` 见 M0-02 交付的
`docker-compose.dev.yml` 与本文档「部署」章节。

## 数据与兼容口径

- **PostgreSQL（recommended）**：参考实现，`ext` 走 JSONB + 表达式索引；
- **MySQL 8.0.17+（supported）**：`ext` 走 JSON 列，能力差异以 capability 标志显式降级（不假装两边等价）；
- 自定义字段默认进 `ext`，加字段 = 元数据插一行 + 业务行多一个 key：**零 DDL、零锁表**；
- 一切 blob 进对象存储（S3/MinIO），数据库只存引用。

## 零外呼（诚实条款）

引擎不向作者控制的任何服务器回连：无遥测、无许可证校验、无静默版本检查。
出站调用点清单化并由 CI 扫描，白名单仅限：对象存储、SMTP、三方推送、PDF 引擎、以及你配置的回调。

## 文档族

公开仓库只携带规格的可发布导出版（`docs/` 目录，由文档站卡生成）；内部论证、决策记录与运维细节不在公开仓库内。

| 文档 | 作用 |
|---|---|
| `docs/specs/` | 技术规格与模块图纸（对象模型、方言 SPI、EQL 文法、写入管道、权限、布局、自动化目录、审批状态机、转换、模板、导入、开放接口） |
| `docs/guide/` | 快速上手、概念五词（对象·字段·自动化·审批·转换）、管理员手册、EQL 文法页、FAQ |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 提交规范（按功能块小提交）、门禁与测试矩阵 |

## 版本

SemVer：`0.x` 直至 M6 验收剧本稳定后进入 `1.0.0`。兼容面分层承诺（物理结构 / 协议 / 宿主 API）见文档。

## License

Copyright 2026 阿杰很厉害 &lt;506907958@qq.com&gt;

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is
distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and limitations under the License.
