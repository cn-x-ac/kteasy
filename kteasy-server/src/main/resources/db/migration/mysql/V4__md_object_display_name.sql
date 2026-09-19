-- V4：md_object 主显从 name_field_id 单字段 → display_name 显示名称模板（【清单】S10，M1-02 收编）。
-- 存量数据以原名称字段回填为"仅一个占位符"的特例；name_field_id 无外键，直接删除。
ALTER TABLE md_object ADD COLUMN display_name varchar(255);

UPDATE md_object o
   JOIN md_field f ON f.id = o.name_field_id
   SET o.display_name = CONCAT('{', f.api_name, '}');

ALTER TABLE md_object DROP COLUMN name_field_id;
