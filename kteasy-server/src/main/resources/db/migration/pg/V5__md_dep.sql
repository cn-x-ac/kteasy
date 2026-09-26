-- V5（M1-07 块4）：recalc 依赖图 md_dep（模块图纸 01 §1：target_field ← source_object.field + op + filter）。
-- A4 定稿：rollup 作为一等元数据概念——汇总字段(write_policy=DERIVED)的聚合来源在此声明，元数据保存时静态解析写入、环检测。
-- 落 md schema（V2 已建，IF NOT EXISTS 兜底）；id 及引用列钉 COLLATE "C"；op 枚举与 core.meta.DepAggOp 一字不差。
-- 列集与 MySQL 版逻辑等价（jsonb↔json、timestamptz↔DATETIME(6)）。UQ 三元＝同目标字段对同来源只一条边。

CREATE SCHEMA IF NOT EXISTS md;

CREATE TABLE md.md_dep (
    id               varchar(32)  COLLATE "C" NOT NULL,
    target_field_id  varchar(32)  COLLATE "C" NOT NULL,
    source_object_id varchar(32)  COLLATE "C" NOT NULL,
    source_field_id  varchar(32)  COLLATE "C" NOT NULL,
    op               varchar(16)  NOT NULL,
    filter_json      jsonb,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_md_dep PRIMARY KEY (id),
    CONSTRAINT uk_md_dep_edge UNIQUE (target_field_id, source_object_id, source_field_id),
    CONSTRAINT ck_md_dep_op CHECK (op IN ('SUM', 'COUNT', 'AVG', 'MIN', 'MAX', 'FIRST', 'LAST', 'COUNT_DISTINCT'))
);

CREATE INDEX ix_md_dep_target ON md.md_dep (target_field_id);
CREATE INDEX ix_md_dep_source ON md.md_dep (source_object_id);
