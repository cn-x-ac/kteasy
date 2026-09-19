-- V2（M1-03 压扁收尾）：物化引擎作业账本，直接按 md_ 约定建表（PG schema=md），
-- 不再靠 V5 改名迁移；object_id 存 md_object.id，列级钉 binary COLLATE "C"
-- （设计要点 7：凡用到 ID 皆 binary，与主表同规则防 JOIN Illegal mix）——原 V6/V7 回补已折进此处。
CREATE SCHEMA IF NOT EXISTS md;

CREATE TABLE md.md_schema_change_job (
    id              bigserial    NOT NULL,
    object_id       varchar(64)  COLLATE "C" NOT NULL,
    step_kind       varchar(32)  NOT NULL,
    seq             integer      NOT NULL,
    state           varchar(16)  NOT NULL,
    attempts        integer      NOT NULL DEFAULT 0,
    checkpoint_json jsonb,
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_md_schema_change_job PRIMARY KEY (id),
    CONSTRAINT ck_md_schema_change_job_state CHECK (state IN ('PENDING', 'RUNNING', 'DONE', 'FAILED'))
);

CREATE INDEX ix_md_schema_change_job_object_seq ON md.md_schema_change_job (object_id, seq);
