-- V4（M1-07 编号规则）：自动编号——规则定义表（md 区元数据）+ 取号热态表（kteasy 引擎区）。
-- 语义来源：步骤卡 M1-07 设计要点 4；模块图纸 01 §1（md_autonum_rule / kteasy_autonum_seq）。
-- binary 规则：仅 id 及引用列（object_id/field_id/rule_id）钉 COLLATE "C"；period_key/segments_json
--   等非标识符列保持库默认。凡用 ID 皆 binary，与主表同规则防跨库 JOIN Illegal mix（§M1-03 审计）。
-- 落位：md_autonum_rule 显式建在 md schema（V2 已建，此处 IF NOT EXISTS 兜底）；
--   kteasy_autonum_seq 非限定 → 落 Flyway default-schema=kteasy（同 V1 的 kteasy_meta，引擎运行态区）。
-- 无跨表 FK：沿用 md_field.object_id「仅索引不建 FK」先例，且 seq 是每写必改的热点表，与规则定义解耦。
-- period_key 约定：SEQ 段 reset=D|M|Y 时存 UTC 日期串（yyyyMMdd / yyyyMM / yyyy）；不重置（连续流水）存空串 ''。
-- seq_value 列名：图纸 01 原写 `current`，裸 `CURRENT` 在两库关键字表里敏感（不为速度牺牲正确性），
--   改 `seq_value`（存「最近发放值」，语义等价），已同步回写图纸 01。列集与 MySQL 版逻辑等价。

CREATE SCHEMA IF NOT EXISTS md;

CREATE TABLE md.md_autonum_rule (
    id            varchar(32)  COLLATE "C" NOT NULL,
    object_id     varchar(32)  COLLATE "C" NOT NULL,
    field_id      varchar(32)  COLLATE "C" NOT NULL,
    segments_json jsonb        NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_md_autonum_rule PRIMARY KEY (id),
    CONSTRAINT uk_md_autonum_rule_field UNIQUE (field_id)
);

CREATE INDEX ix_md_autonum_rule_object ON md.md_autonum_rule (object_id);

CREATE TABLE kteasy_autonum_seq (
    rule_id    varchar(32)  COLLATE "C" NOT NULL,
    period_key varchar(16)  NOT NULL,
    seq_value  bigint       NOT NULL DEFAULT 0,
    updated_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_kteasy_autonum_seq PRIMARY KEY (rule_id, period_key)
);
