-- Local provider simulation only. Separate table survives Mock PG process restart.
CREATE TABLE mock_pg_receipts (
 attempt_id uuid PRIMARY KEY, amount bigint NOT NULL, scenario varchar(30) NOT NULL,
 accepted_at timestamptz NOT NULL, deadline timestamptz NOT NULL, result varchar(20) NOT NULL
);
