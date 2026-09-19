-- V6（M1-03 设计要点 7）：把 md 区已有表的 id 与全部引用列显式钉 binary 排序规则（utf8mb4_bin），
-- 与本卡新建实体表同规则一次到位，消除 `md_object.id = md_field.object_id` 等 JOIN/FK 的
-- `Illegal mix of collations`（实体表 id 已 binary，若 md 端仍吃表默认 ai_ci 即混）。
-- 先放掉引用这些列的 3 个外键，改完两端再重建（两端 collation 一致方可建）。

ALTER TABLE md_dict_item DROP FOREIGN KEY fk_dict_item_dict;
ALTER TABLE md_dict_item DROP FOREIGN KEY fk_dict_item_parent;
ALTER TABLE md_option DROP FOREIGN KEY fk_option_set;

ALTER TABLE md_object      MODIFY id               varchar(32) COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE md_object      MODIFY parent_object_id varchar(32) COLLATE utf8mb4_bin NULL;
ALTER TABLE md_object      MODIFY created_by       varchar(32) COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE md_object      MODIFY updated_by       varchar(32) COLLATE utf8mb4_bin NOT NULL;

ALTER TABLE md_field       MODIFY id              varchar(32) COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE md_field       MODIFY object_id       varchar(32) COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE md_field       MODIFY ref_object_id   varchar(32) COLLATE utf8mb4_bin NULL;
ALTER TABLE md_field       MODIFY dict_id         varchar(32) COLLATE utf8mb4_bin NULL;
ALTER TABLE md_field       MODIFY option_set_id   varchar(32) COLLATE utf8mb4_bin NULL;

ALTER TABLE md_dict        MODIFY id       varchar(32) COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE md_dict_item   MODIFY id       varchar(32) COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE md_dict_item   MODIFY dict_id  varchar(32) COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE md_dict_item   MODIFY parent_id varchar(32) COLLATE utf8mb4_bin NULL;

ALTER TABLE md_option_set  MODIFY id      varchar(32) COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE md_option      MODIFY id      varchar(32) COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE md_option      MODIFY set_id  varchar(32) COLLATE utf8mb4_bin NOT NULL;

ALTER TABLE md_dict_item   ADD CONSTRAINT fk_dict_item_dict   FOREIGN KEY (dict_id)  REFERENCES md_dict (id);
ALTER TABLE md_dict_item   ADD CONSTRAINT fk_dict_item_parent FOREIGN KEY (parent_id) REFERENCES md_dict_item (id);
ALTER TABLE md_option      ADD CONSTRAINT fk_option_set       FOREIGN KEY (set_id)   REFERENCES md_option_set (id);
