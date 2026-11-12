-- clai storage schema.
--
-- This file is the single source of truth for the storage tables. It is read
-- from the classpath and applied by `clai.daemon.postgres/ensure-schema!`, and
-- can also be applied by hand (or by a container init step) with `psql`.
--
-- Every statement is idempotent, so this file is safe to apply on every
-- process start and against a database whose tables already exist.

-- Agent configurations. One row names a reusable set of model and tool
-- settings that a session can select. A NULL column falls back to the daemon's
-- own configuration.
CREATE TABLE IF NOT EXISTS agent_config (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    model TEXT,
    system_prompt TEXT,
    max_tool_rounds INT,
    think BOOLEAN,
    model_options JSONB NOT NULL DEFAULT '{}'::jsonb,
    response_format JSONB,
    -- NULL means every tool in the agent's registry; [] disables tools.
    tools TEXT[],
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agent_session (
    id BIGSERIAL PRIMARY KEY,
    -- Must be an absolute path
    working_directory TEXT NOT NULL,
    -- The selected agent configuration, when the session has one.
    agent_config_id BIGINT REFERENCES agent_config(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- `CREATE TABLE IF NOT EXISTS` does not add columns to an existing table, so
-- the session/config link is added explicitly for databases created before it
-- existed.
ALTER TABLE agent_session
    ADD COLUMN IF NOT EXISTS agent_config_id BIGINT
        REFERENCES agent_config(id) ON DELETE SET NULL;

-- PostgreSQL has no `CREATE TYPE IF NOT EXISTS`, so guard the enum by hand.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1
                     FROM pg_type t
                     JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE t.typname = 'agent_session_event_type_t'
                      AND n.nspname = current_schema()) THEN
        CREATE TYPE agent_session_event_type_t AS ENUM ('user_prompt',
                                                        'agent_message',
                                                        'agent_thought',
                                                        'tool_call',
                                                        'clojure_form');
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS agent_session_event (
    id SERIAL PRIMARY KEY,
    event_type agent_session_event_type_t NOT NULL,
    content jsonb NOT NULL,
    agent_session_id INT NOT NULL
        REFERENCES agent_session(id) ON DELETE CASCADE
);
