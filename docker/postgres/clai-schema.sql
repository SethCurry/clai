-- clai storage schema.
--
-- Keep this in sync with `schema-ddl` in src/clai/daemon/postgres.clj; that
-- vector is the source of truth used by `clai.daemon.postgres/ensure-schema!`.
--
-- Every statement is idempotent, so this file is safe to apply on every
-- container start and against a database whose tables already exist.

BEGIN;

CREATE TABLE IF NOT EXISTS clai_sessions (
  session_id text PRIMARY KEY,
  cwd        text NOT NULL,
  model      text,
  params     jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS clai_session_messages (
  id         bigserial PRIMARY KEY,
  session_id text NOT NULL
             REFERENCES clai_sessions(session_id) ON DELETE CASCADE,
  message    jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS clai_session_messages_session_idx
  ON clai_session_messages (session_id, id);

CREATE TABLE IF NOT EXISTS clai_data (
  namespace  text NOT NULL,
  data_key   text NOT NULL,
  value      jsonb NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (namespace, data_key)
);

COMMIT;
