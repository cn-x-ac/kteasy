-- V1：引擎自身元信息（ kteasy 区 / PG 默认 schema=kteasy）。单行表，记版本与初始化时间。
-- 语义参照【规格】§1.2；本卡不建租户动态表（S8：Flyway 只管引擎自身）。
CREATE TABLE kteasy_meta (
    id             smallint    NOT NULL,
    engine_version varchar(32) NOT NULL,
    initialized_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_kteasy_meta PRIMARY KEY (id),
    CONSTRAINT ck_kteasy_meta_singleton CHECK (id = 1)
);
