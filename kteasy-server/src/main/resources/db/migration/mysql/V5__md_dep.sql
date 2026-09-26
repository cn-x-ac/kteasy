-- V5（M1-07 块4）：recalc 依赖图 md_dep（模块图纸 01 §1：target_field ← source_object.field + op + filter）。
-- A4 定稿：rollup 作为一等元数据概念——汇总字段(write_policy=DERIVED)的聚合来源在此声明，元数据保存时静态解析写入、环检测。
-- 列集与 PG 版逻辑等价（json↔jsonb、DATETIME(6)↔timestamptz）；binary 规则同 V3/V4：仅 id 及引用列钉 utf8mb4_bin。
-- op 枚举与 core.meta.DepAggOp 一字不差（SUM/COUNT/AVG/MIN/MAX/FIRST/LAST/COUNT_DISTINCT）。
-- UQ 三元＝同一目标字段对同一来源对象/字段只有一条边（重复声明无意义）。

CREATE TABLE md_dep (
    id               varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    target_field_id  varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    source_object_id varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    source_field_id  varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    op               varchar(16)  NOT NULL,
    filter_json      json,
    created_at       datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_md_dep_edge UNIQUE (target_field_id, source_object_id, source_field_id),
    CONSTRAINT ck_md_dep_op CHECK (op IN ('SUM', 'COUNT', 'AVG', 'MIN', 'MAX', 'FIRST', 'LAST', 'COUNT_DISTINCT'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 环检测/图遍历两向都要快：depOut（本对象字段依赖谁）按 target，depIn（谁依赖本对象）按 source。
CREATE INDEX ix_md_dep_target ON md_dep (target_field_id);
CREATE INDEX ix_md_dep_source ON md_dep (source_object_id);
