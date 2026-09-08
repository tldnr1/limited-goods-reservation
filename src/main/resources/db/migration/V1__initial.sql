CREATE TABLE sales (
 id uuid PRIMARY KEY, name varchar(200) NOT NULL, opens_at timestamptz NOT NULL
);
CREATE TABLE sale_items (
 id uuid PRIMARY KEY, sale_id uuid NOT NULL REFERENCES sales(id),
 name varchar(200) NOT NULL, price bigint NOT NULL CHECK(price > 0),
 per_user_limit integer NOT NULL CHECK(per_user_limit > 0),
 total integer NOT NULL CHECK(total >= 0), available integer NOT NULL CHECK(available >= 0),
 held integer NOT NULL CHECK(held >= 0), sold integer NOT NULL CHECK(sold >= 0),
 CHECK(total = available + held + sold)
);
CREATE INDEX sale_items_sale ON sale_items(sale_id);
CREATE TABLE orders (
 id uuid PRIMARY KEY, user_id varchar(100) NOT NULL, sale_id uuid NOT NULL REFERENCES sales(id),
 idempotency_key varchar(100) NOT NULL, fingerprint varchar(1000) NOT NULL,
 status varchar(32) NOT NULL CHECK(status IN ('PAYMENT_PENDING','PAYMENT_PROCESSING','CONFIRMED','EXPIRED')),
 total_amount bigint NOT NULL CHECK(total_amount > 0), created_at timestamptz NOT NULL,
 UNIQUE(user_id,idempotency_key)
);
CREATE TABLE order_items (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES orders(id),
 sale_item_id uuid NOT NULL REFERENCES sale_items(id), quantity integer NOT NULL CHECK(quantity > 0),
 unit_price bigint NOT NULL CHECK(unit_price > 0), UNIQUE(order_id,sale_item_id)
);
CREATE INDEX order_items_stock ON order_items(sale_item_id,order_id);
CREATE INDEX orders_user ON orders(user_id);
CREATE TABLE reservations (
 order_id uuid PRIMARY KEY REFERENCES orders(id),
 status varchar(20) NOT NULL CHECK(status IN ('ACTIVE','CONFIRMED','EXPIRED')),
 hold_expires_at timestamptz NOT NULL, confirmation_deadline timestamptz
);
CREATE INDEX reservations_due ON reservations(hold_expires_at) WHERE status = 'ACTIVE';
CREATE TABLE payment_attempts (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES orders(id),
 idempotency_key varchar(100) NOT NULL,
 scenario varchar(30) NOT NULL CHECK(scenario IN ('SUCCESS','FAILURE','DELAYED_SUCCESS','LOST_RESPONSE','UNKNOWN')),
 status varchar(20) NOT NULL CHECK(status IN ('CREATED','PROCESSING','SUCCEEDED','FAILED','UNKNOWN')),
 amount bigint NOT NULL CHECK(amount > 0), created_at timestamptz NOT NULL,
 next_check_at timestamptz NOT NULL, lease_until timestamptz,
 UNIQUE(order_id,idempotency_key)
);
CREATE UNIQUE INDEX payment_one_blocking ON payment_attempts(order_id)
 WHERE status IN ('CREATED','PROCESSING','UNKNOWN','SUCCEEDED');
CREATE INDEX payment_due ON payment_attempts(next_check_at)
 WHERE status IN ('CREATED','PROCESSING','UNKNOWN');
