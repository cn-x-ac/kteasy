-- V5（M1-03）：规整作业账本表名为元数据命名约定「逻辑名自带 md_，PG 再包 md schema」。
-- 历史：V2 曾把 PG 侧建成 md.schema_change_job（缺 md_ 前缀），而 MySQL 侧为 md_schema_change_job，
-- 两库逻辑名不一致，令 NamespaceMapper 单一名映射在 MySQL 侧少一个 md_（M1-03 物化执行器真库跑暴露）。
-- 此迁移仅 PG 侧重命名以消除差异；重命名后 PG=md.md_schema_change_job、MySQL=md_schema_change_job，
-- 与 md_object 等元数据表同一约定。索引 ix_schema_change_job_object_seq 随表更名保留原名，不影响使用。
ALTER TABLE md.schema_change_job RENAME TO md_schema_change_job;
