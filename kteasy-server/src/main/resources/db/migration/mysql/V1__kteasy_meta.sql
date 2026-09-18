-- V1：引擎自身元信息（MySQL 用表前缀 kteasy_ 代替独立 schema）。单行表。
CREATE TABLE kteasy_meta (
    id             SMALLINT    NOT NULL,
    engine_version VARCHAR(32) NOT NULL,
    initialized_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_kteasy_meta_singleton CHECK (id = 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
