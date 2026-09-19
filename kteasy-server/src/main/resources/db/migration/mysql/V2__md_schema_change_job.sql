-- V2（M1-03 压扁收尾）：物化引擎作业账本（MySQL 前缀 md_）。列集与 PG 版逻辑等价。
-- 原 V6/V7 回补已折进此处：object_id 存 md_object.id，列级钉 COLLATE utf8mb4_bin（与主表同规则防 JOIN Illegal mix）。
CREATE TABLE md_schema_change_job (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    object_id       VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    step_kind       VARCHAR(32) NOT NULL,
    seq             INT         NOT NULL,
    state           VARCHAR(16) NOT NULL,
    attempts        INT         NOT NULL DEFAULT 0,
    checkpoint_json JSON        NULL,
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_schema_change_job_state CHECK (state IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    INDEX ix_schema_change_job_object_seq (object_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
