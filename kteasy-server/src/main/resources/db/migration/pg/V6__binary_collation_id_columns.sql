-- V6（M1-03 设计要点 7）：把 md 区已有表的 id 与全部引用列显式钉 binary 排序规则（列级 COLLATE "C"），
-- 与本卡新建实体表同规则一次到位，消除 JOIN/FK 两端 collation 不一致。先放掉引用列的 3 个外键，
-- 两端改完再重建。PG 改列 collation 用 ALTER COLUMN ... TYPE ... COLLATE。

ALTER TABLE md.md_dict_item DROP CONSTRAINT fk_dict_item_dict;
ALTER TABLE md.md_dict_item DROP CONSTRAINT fk_dict_item_parent;
ALTER TABLE md.md_option DROP CONSTRAINT fk_option_set;

ALTER TABLE md.md_object     ALTER COLUMN id               TYPE varchar(32) COLLATE "C";
ALTER TABLE md.md_object     ALTER COLUMN parent_object_id  TYPE varchar(32) COLLATE "C";
ALTER TABLE md.md_object     ALTER COLUMN created_by       TYPE varchar(32) COLLATE "C";
ALTER TABLE md.md_object     ALTER COLUMN updated_by       TYPE varchar(32) COLLATE "C";

ALTER TABLE md.md_field      ALTER COLUMN id               TYPE varchar(32) COLLATE "C";
ALTER TABLE md.md_field      ALTER COLUMN object_id        TYPE varchar(32) COLLATE "C";
ALTER TABLE md.md_field      ALTER COLUMN ref_object_id    TYPE varchar(32) COLLATE "C";
ALTER TABLE md.md_field      ALTER COLUMN dict_id          TYPE varchar(32) COLLATE "C";
ALTER TABLE md.md_field      ALTER COLUMN option_set_id    TYPE varchar(32) COLLATE "C";

ALTER TABLE md.md_dict       ALTER COLUMN id               TYPE varchar(32) COLLATE "C";
ALTER TABLE md.md_dict_item  ALTER COLUMN id               TYPE varchar(32) COLLATE "C";
ALTER TABLE md.md_dict_item  ALTER COLUMN dict_id          TYPE varchar(32) COLLATE "C";
ALTER TABLE md.md_dict_item  ALTER COLUMN parent_id        TYPE varchar(32) COLLATE "C";

ALTER TABLE md.md_option_set ALTER COLUMN id               TYPE varchar(32) COLLATE "C";
ALTER TABLE md.md_option     ALTER COLUMN id               TYPE varchar(32) COLLATE "C";
ALTER TABLE md.md_option     ALTER COLUMN set_id           TYPE varchar(32) COLLATE "C";

ALTER TABLE md.md_dict_item  ADD CONSTRAINT fk_dict_item_dict   FOREIGN KEY (dict_id)   REFERENCES md.md_dict (id);
ALTER TABLE md.md_dict_item  ADD CONSTRAINT fk_dict_item_parent FOREIGN KEY (parent_id)  REFERENCES md.md_dict_item (id);
ALTER TABLE md.md_option     ADD CONSTRAINT fk_option_set       FOREIGN KEY (set_id)     REFERENCES md.md_option_set (id);
