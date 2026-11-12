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
    -- Plugin names (or ids) active for sessions using this configuration.
    -- NULL means every enabled plugin; [] disables plugins.
    plugins TEXT[],
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- Installed plugins. A row names a Clojure jar, the namespace in that jar
-- that exposes the plugin descriptor, and the plugin's own JSON configuration.
-- `clai.plugins` loads enabled rows at daemon/REPL startup and composes their
-- hooks; see `doc/plugins.md`.
CREATE TABLE IF NOT EXISTS plugin (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    -- Absolute path to the plugin's jar file.
    jar_path TEXT NOT NULL,
    -- Namespace that defines the `plugin` Var in the jar.
    entry_namespace TEXT NOT NULL,
    -- Plugin-specific configuration, passed to the plugin's constructor.
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    -- Lower priorities run first; equal priorities run by name.
    priority INT NOT NULL DEFAULT 100,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agent_session (
    id BIGSERIAL PRIMARY KEY,
    -- Optional human-readable label; the UI falls back to the directory name.
    title TEXT,
    -- Must be an absolute path
    working_directory TEXT NOT NULL,
    -- The selected agent configuration, when the session has one.
    agent_config_id BIGINT REFERENCES agent_config(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- `CREATE TABLE IF NOT EXISTS` does not add columns to an existing table, so
-- these columns are added explicitly for databases created before they
-- existed.
ALTER TABLE agent_session
    ADD COLUMN IF NOT EXISTS agent_config_id BIGINT
        REFERENCES agent_config(id) ON DELETE SET NULL;

ALTER TABLE agent_session
    ADD COLUMN IF NOT EXISTS title TEXT;

ALTER TABLE agent_config
    ADD COLUMN IF NOT EXISTS plugins TEXT[];

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

-- Git repositories registered with clai, for example so the daemon can create
-- worktrees from them. `url` is the remote (or local) repository URL, and
-- `name` is the short, human-readable label users and the UI address it by.
CREATE TABLE IF NOT EXISTS git_repo (
    id BIGSERIAL PRIMARY KEY,
    -- Human-readable name; unique so a repository can be addressed by name.
    name TEXT NOT NULL UNIQUE,
    -- Repository URL (https, ssh, or a local path), passed to git as-is.
    url TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- Git worktrees checked out from a registered repository. A worktree belongs
-- to exactly one `git_repo` row and is removed with it.
CREATE TABLE IF NOT EXISTS git_worktree (
    id BIGSERIAL PRIMARY KEY,
    git_repo_id BIGINT NOT NULL
        REFERENCES git_repo(id) ON DELETE CASCADE,
    -- Branch checked out in the worktree.
    branch TEXT NOT NULL,
    -- Absolute path of the worktree's directory.
    directory TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    -- git checks a branch out in at most one worktree per repository, and a
    -- directory holds at most one worktree.
    UNIQUE (git_repo_id, branch),
    UNIQUE (directory)
);
