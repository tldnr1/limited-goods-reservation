\set ON_ERROR_STOP on

\if :{?sample_count}
\else
\set sample_count 700
\endif

\if :{?sample_interval_seconds}
\else
\set sample_interval_seconds 0.1
\endif

CREATE TEMP TABLE performance_sample_config (
    sample_count integer NOT NULL,
    sample_interval_seconds double precision NOT NULL
);

INSERT INTO performance_sample_config
VALUES (:sample_count, :sample_interval_seconds);

CREATE TEMP TABLE performance_wait_samples (
    sampled_at timestamptz NOT NULL,
    active_sessions integer NOT NULL,
    lock_waiters integer NOT NULL,
    blocked_sessions integer NOT NULL,
    ungranted_locks integer NOT NULL,
    lock_wait_events text,
    waiting_queries text
);

DO $$
DECLARE
    configured_sample_count integer;
    configured_sample_interval double precision;
BEGIN
    SELECT sample_count, sample_interval_seconds
    INTO configured_sample_count, configured_sample_interval
    FROM performance_sample_config;

    FOR sample_number IN 1..configured_sample_count LOOP
        PERFORM pg_stat_clear_snapshot();

        INSERT INTO performance_wait_samples
        SELECT
            clock_timestamp(),
            count(*) FILTER (WHERE activity.state = 'active'),
            count(*) FILTER (
                WHERE activity.state = 'active'
                  AND activity.wait_event_type = 'Lock'
            ),
            count(*) FILTER (
                WHERE activity.state = 'active'
                  AND cardinality(pg_blocking_pids(activity.pid)) > 0
            ),
            (
                SELECT count(*)
                FROM pg_locks AS lock
                JOIN pg_stat_activity AS locked_activity
                  ON locked_activity.pid = lock.pid
                WHERE NOT lock.granted
                  AND locked_activity.state = 'active'
                  AND lock.pid <> pg_backend_pid()
            ),
            string_agg(
                DISTINCT activity.wait_event_type || ':' || activity.wait_event,
                ' | '
            ) FILTER (
                WHERE activity.state = 'active'
                  AND activity.wait_event_type = 'Lock'
            ),
            string_agg(
                DISTINCT left(regexp_replace(activity.query, E'[\\n\\r]+', ' ', 'g'), 240),
                ' | '
            ) FILTER (
                WHERE activity.state = 'active'
                  AND activity.wait_event_type = 'Lock'
            )
        FROM pg_stat_activity AS activity
        WHERE activity.datname = current_database()
          AND activity.pid <> pg_backend_pid()
          AND activity.application_name <> 'limited-goods-wait-sampler';

        PERFORM pg_sleep(configured_sample_interval);
    END LOOP;
END
$$;

\copy performance_wait_samples TO STDOUT WITH (FORMAT csv, HEADER true)
