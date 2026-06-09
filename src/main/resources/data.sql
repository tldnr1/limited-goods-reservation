INSERT INTO products (id, name, price, created_at, updated_at)
VALUES (1, 'Hot Product', 10000.00, NOW(), NOW());

INSERT INTO product_stock (id, product_id, initial_quantity, sold_quantity, created_at, updated_at)
VALUES (1, 1, 100, 0, NOW(), NOW());
