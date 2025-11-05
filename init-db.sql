-- Initialize Recurra database with required extensions

-- Enable UUID generation
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Enable pgvector for embeddings
CREATE EXTENSION IF NOT EXISTS vector;

-- Enable pgcrypto for HMAC
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Grant necessary permissions
GRANT ALL PRIVILEGES ON DATABASE recurra TO recurra;
GRANT ALL PRIVILEGES ON SCHEMA public TO recurra;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO recurra;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO recurra;

-- Set default schema
ALTER DATABASE recurra SET search_path TO public;
