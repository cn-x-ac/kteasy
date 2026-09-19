-- V7（M1-03 设计要点 7 补全）：V6 漏了 md_schema_change_job.object_id（存 md_object.id，属"用到 ID 的地方"）。
-- 钉 utf8mb4_bin 与 md_object.id 两端一致，凡该列参与 JOIN/比对不再撞 Illegal mix of collations。
ALTER TABLE md_schema_change_job MODIFY object_id varchar(64) COLLATE utf8mb4_bin NOT NULL;
