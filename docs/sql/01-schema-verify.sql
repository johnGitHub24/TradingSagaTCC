-- 01-schema-verify.sql
-- 訂單庫 JDBC: jdbc:h2:mem:orderdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
-- 帳戶庫 JDBC: jdbc:h2:mem:accountdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
-- 帳號 sa、密碼空白

-- SHOW TABLES;

-- 訂單庫預期：trade_orders, saga_instances, saga_steps, outbox_events
-- 帳戶庫預期：accounts, tcc_reservations

-- SELECT * FROM accounts;
-- SELECT * FROM tcc_reservations;
-- SELECT * FROM trade_orders;
-- SELECT * FROM saga_instances;
-- SELECT * FROM outbox_events;
