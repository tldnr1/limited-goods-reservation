-- This experiment uses SUCCESS payments. All counts are scoped to its warmup sale.
WITH sale_orders AS (
    SELECT * FROM orders WHERE sale_id = :'sale_id'::uuid
), sale_attempts AS (
    SELECT p.* FROM payment_attempts p JOIN sale_orders o ON o.id=p.order_id
), inventory AS (
    SELECT s.*, coalesce(sum(i.quantity) FILTER (WHERE o.status IN ('PAYMENT_PENDING','PAYMENT_PROCESSING')),0) AS expected_held,
        coalesce(sum(i.quantity) FILTER (WHERE o.status='CONFIRMED'),0) AS expected_sold
    FROM sale_items s LEFT JOIN order_items i ON i.sale_item_id=s.id LEFT JOIN orders o ON o.id=i.order_id
    WHERE s.sale_id=:'sale_id'::uuid GROUP BY s.id
), over_limit AS (
    SELECT o.user_id,i.sale_item_id FROM sale_orders o JOIN order_items i ON i.order_id=o.id
    JOIN sale_items s ON s.id=i.sale_item_id WHERE o.status<>'EXPIRED'
    GROUP BY o.user_id,i.sale_item_id,s.per_user_limit HAVING sum(i.quantity)>s.per_user_limit
)
SELECT json_build_object(
    'orders',(SELECT count(*) FROM sale_orders),
    'confirmedOrders',(SELECT count(*) FROM sale_orders WHERE status='CONFIRMED'),
    'attempts',(SELECT count(*) FROM sale_attempts),
    'succeededAttempts',(SELECT count(*) FROM sale_attempts WHERE status='SUCCEEDED'),
    'confirmedReservations',(SELECT count(*) FROM reservations r JOIN sale_orders o ON o.id=r.order_id WHERE r.status='CONFIRMED'),
    'inventoryViolations',(SELECT count(*) FROM inventory WHERE total<>available+held+sold
        OR available<0 OR held<0 OR sold<0 OR held<>expected_held OR sold<>expected_sold),
    'userLimitViolations',(SELECT count(*) FROM over_limit)
);
