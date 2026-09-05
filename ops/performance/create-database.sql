\set ON_ERROR_STOP on

SELECT 'CREATE DATABASE limited_goods_perf OWNER limited_goods'
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_database
    WHERE datname = 'limited_goods_perf'
)\gexec
