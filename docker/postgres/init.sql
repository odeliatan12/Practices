-- Creates a separate database for each microservice.
-- Runs automatically when the PostgreSQL container first starts.

CREATE DATABASE orders_db;
CREATE DATABASE inventory_db;
CREATE DATABASE payments_db;
CREATE DATABASE fulfillment_db;
CREATE DATABASE users_db;

-- Grant the shared user access to each database
GRANT ALL PRIVILEGES ON DATABASE orders_db TO oms;
GRANT ALL PRIVILEGES ON DATABASE inventory_db TO oms;
GRANT ALL PRIVILEGES ON DATABASE payments_db TO oms;
GRANT ALL PRIVILEGES ON DATABASE fulfillment_db TO oms;
GRANT ALL PRIVILEGES ON DATABASE users_db TO oms;
