-- One read-only sample per second, for measured sales only. This has a cost too.
SET default_transaction_read_only = on;
SET statement_timeout = '2s';
WITH order_counts AS (
    SELECT sale_id,count(*) AS orders,count(*) FILTER (WHERE status='CONFIRMED') AS confirmed,
        count(*) FILTER (WHERE status IN ('PAYMENT_PENDING','PAYMENT_PROCESSING')) AS pending_orders,
        count(*) FILTER (WHERE status='EXPIRED') AS expired,
        min(created_at) FILTER (WHERE status IN ('PAYMENT_PENDING','PAYMENT_PROCESSING')) AS oldest_order
    FROM orders WHERE sale_id<>:'exclude_sale_id'::uuid GROUP BY sale_id
), payment_counts AS (
    SELECT o.sale_id,count(*) AS attempts,count(*) FILTER (WHERE p.status='SUCCEEDED') AS succeeded,
        count(*) FILTER (WHERE p.status IN ('CREATED','PROCESSING','UNKNOWN')) AS pending_payments,
        count(*) FILTER (WHERE p.status='FAILED') AS failed,
        min(p.created_at) FILTER (WHERE p.status IN ('CREATED','PROCESSING','UNKNOWN')) AS oldest_payment
    FROM payment_attempts p JOIN orders o ON o.id=p.order_id
    WHERE o.sale_id<>:'exclude_sale_id'::uuid GROUP BY o.sale_id
)
SELECT json_build_object('sampled_at',clock_timestamp(),'sales',coalesce(json_agg(json_build_object(
    'saleId',s.id,'orders',coalesce(o.orders,0),'confirmed',coalesce(o.confirmed,0),
    'pendingOrders',coalesce(o.pending_orders,0),'expired',coalesce(o.expired,0),
    'attempts',coalesce(p.attempts,0),'succeeded',coalesce(p.succeeded,0),
    'pendingPayments',coalesce(p.pending_payments,0),'failedPayments',coalesce(p.failed,0),
    'oldestPendingOrderSeconds',coalesce(extract(epoch FROM clock_timestamp()-o.oldest_order),0),
    'oldestPendingPaymentSeconds',coalesce(extract(epoch FROM clock_timestamp()-p.oldest_payment),0)
)), '[]'::json))
FROM sales s LEFT JOIN order_counts o ON o.sale_id=s.id LEFT JOIN payment_counts p ON p.sale_id=s.id
WHERE s.id<>:'exclude_sale_id'::uuid
\watch interval=1 count=:sample_count
