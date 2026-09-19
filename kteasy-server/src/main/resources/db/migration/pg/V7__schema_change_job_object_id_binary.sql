-- V7（M1-03 设计要点 7 补全）：V6 回补 md 区时漏了作业账本表自身的 object_id——它存的是
-- md_object.id（ULID），属"用到 ID 的地方"。与 md_object.id 现均为 binary 后，凡
-- `md_schema_change_job.object_id` 参与 JOIN/比对（如治理面按对象联查作业）不再撞 Illegal mix。
-- 表名 PG 为 V5 规整后的 md.md_schema_change_job、MySQL 为 md_schema_change_job。
ALTER TABLE md.md_schema_change_job ALTER COLUMN object_id TYPE varchar(64) COLLATE "C";
