\set ON_ERROR_STOP on

DO $$
BEGIN
    IF current_database() <> 'limited_goods_perf' THEN
        RAISE EXCEPTION 'refusing to reset database %', current_database();
    END IF;
END
$$;

TRUNCATE TABLE
    payment_attempts,
    reservations,
    order_items,
    orders,
    inventories,
    sale_items,
    sale_events
CASCADE;
