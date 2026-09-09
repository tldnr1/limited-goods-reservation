-- Autocommit gives every watch iteration a fresh activity snapshot. No business table scans.
SET default_transaction_read_only = on;
SET statement_timeout = '1s';
SELECT json_build_object('sampled_at', clock_timestamp(), 'sessions', coalesce(json_agg(
    json_build_object('pid', pid, 'application', application_name, 'client', client_addr,
        'state', state, 'wait_type', wait_event_type, 'wait_event', wait_event,
        'blockers', pg_blocking_pids(pid), 'transaction_started', xact_start,
        'query_started', query_start, 'sql', left(query, 500))), '[]'::json))
FROM pg_stat_activity
WHERE datname = 'limited_goods_perf' AND pid <> pg_backend_pid()
  AND (state <> 'idle' OR xact_start IS NOT NULL)
\watch interval=0.1 count=:sample_count
