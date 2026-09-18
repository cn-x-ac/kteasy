-- V2：物化引擎（schema_change_job 作业账本，M1-03 消费其结构）——落 md 区（PG schema=md）。
-- 每步幂等、可断点续跑：state 四态 + checkpoint_json 存进度游标。
CREATE SCHEMA IF NOT EXISTS md;

CREATE TABLE md.schema_change_job (
    id              bigserial    NOT NULL,
    object_id       varchar(64)  NOT NULL,
    step_kind       varchar(32)  NOT NULL,
    seq             integer      NOT NULL,
    state           varchar(16)  NOT NULL,
    attempts        integer      NOT NULL DEFAULT 0,
    checkpoint_json jsonb,
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_schema_change_job PRIMARY KEY (id),
    CONSTRAINT ck_schema_change_job_state CHECK (state IN ('PENDING', 'RUNNING', 'DONE', 'FAILED'))
);

CREATE INDEX ix_schema_change_job_object_seq ON md.schema_change_job (object_id, seq);
