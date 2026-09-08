-- Zero rows means the inventory counters agree with durable order rows.
SELECT s.id, s.total, s.available, s.held, s.sold
FROM sale_items s
WHERE total <> available + held + sold
 OR held <> COALESCE((SELECT sum(i.quantity) FROM order_items i JOIN orders o ON o.id=i.order_id
                     WHERE i.sale_item_id=s.id AND o.status IN ('PAYMENT_PENDING','PAYMENT_PROCESSING')),0)
 OR sold <> COALESCE((SELECT sum(i.quantity) FROM order_items i JOIN orders o ON o.id=i.order_id
                     WHERE i.sale_item_id=s.id AND o.status='CONFIRMED'),0);
SELECT status,count(*) FROM orders GROUP BY status;
SELECT status,count(*) FROM payment_attempts GROUP BY status;
