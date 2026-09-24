-- V4（M1-07 编号规则）：自动编号——规则定义表（md 区元数据）+ 取号热态表（kteasy 引擎区）。
-- 语义来源：步骤卡 M1-07 设计要点 4；模块图纸 01 §1（md_autonum_rule / kteasy_autonum_seq）。
-- binary 规则：仅 id 及引用列（object_id/field_id/rule_id）钉 utf8mb4_bin；period_key/segments_json
--   等非标识符列吃库默认 collation。凡用 ID 皆 binary，与主表同规则防跨库 JOIN Illegal mix（§M1-03 审计）。
-- 无跨表 FK：沿用 md_field.object_id「仅索引不建 FK」先例，且 seq 是每写必改的热点表，与规则定义解耦。
-- period_key 约定：SEQ 段 reset=D|M|Y 时存 UTC 日期串（yyyyMMdd / yyyyMM / yyyy）；不重置（连续流水）存空串 ''
--   （纯数字键永不与 '' 相撞）。PG 版逻辑等价（json↔jsonb、DATETIME(6)↔timestamptz、BIGINT↔bigint）。
-- seq_value 列名：图纸 01 原写 `current`，但裸 `CURRENT` 在两库关键字表里敏感（不确定即不为，赌错＝迁移红），
--   改 `seq_value`（存「最近发放值」，语义等价），已同步回写图纸 01（§A.5 冲突处动文档收敛）。

CREATE TABLE md_autonum_rule (
    id            varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    object_id     varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    field_id      varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    segments_json json         NOT NULL,
    created_at    datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_md_autonum_rule_field UNIQUE (field_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX ix_md_autonum_rule_object ON md_autonum_rule (object_id);

CREATE TABLE kteasy_autonum_seq (
    rule_id    varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    period_key varchar(16)  NOT NULL,
    seq_value  bigint       NOT NULL DEFAULT 0,
    updated_at datetime(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (rule_id, period_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
