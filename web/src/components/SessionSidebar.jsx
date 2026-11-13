function shortId(id) {
  const value = String(id ?? '')
  return value.length > 12 ? `${value.slice(0, 8)}…` : value
}

function folderName(cwd) {
  if (!cwd) return 'unknown directory'
  const parts = String(cwd).split(/[\\/]/).filter(Boolean)
  return parts.at(-1) || cwd
}

/**
 * The session list, connection status, and the button that creates a fresh
 * session.
 */
export function SessionSidebar({
  sessions,
  currentSession,
  agentConfigs,
  selectedConfigId,
  status,
  sessionInfo,
  onNewSession,
  onSelectConfig,
  onManageConfigs,
  onRefresh,
  onLoadSession,
  onDeleteSession,
}) {
  const configNames = new Map(
    agentConfigs.map((config) => [config.configId, config.name]),
  )

  return (
    <aside className="sidebar">
      <div className="sidebar__heading">
        <div>
          <h2 className="sidebar__title">Sessions</h2>
          <p className="sidebar__subtitle">pick a conversation</p>
        </div>
        <span className={`status-dot status-dot--${status}`} title={status} />
      </div>

      <div className="sidebar__config">
        <div className="sidebar__config-head">
          <label htmlFor="agent-config">Agent config</label>
          <button
            type="button"
            className="sidebar__config-manage"
            onClick={onManageConfigs}
          >
            Manage
          </button>
        </div>
        <select
          id="agent-config"
          value={selectedConfigId}
          onChange={(event) => onSelectConfig(event.target.value)}
        >
          <option value="">Daemon defaults</option>
          {agentConfigs.map((config) => (
            <option key={config.configId} value={config.configId}>
              {config.name}
            </option>
          ))}
        </select>
      </div>

      <div className="sidebar__actions">
        <button
          className="button button--primary"
          onClick={() => onNewSession()}
        >
          + New session
        </button>
        <button
          className="button button--quiet"
          onClick={onRefresh}
          title="Refresh sessions"
        >
          Refresh
        </button>
      </div>

      <div className="sidebar__list" aria-label="Sessions">
        {sessions.length === 0 ? (
          <p className="sidebar__empty">
            No sessions yet. Start one to give clai something to do.
          </p>
        ) : (
          sessions.map((session) => {
            const active = session.sessionId === currentSession?.sessionId
            return (
              <div
                className={`session-row${active ? ' session-row--active' : ''}`}
                key={session.sessionId}
              >
                <button
                  className="session-row__select"
                  onClick={() => onLoadSession(session.sessionId)}
                  title={session.cwd}
                >
                  {session.title && (
                    <span className="session-row__title" title={session.title}>
                      {session.title}
                    </span>
                  )}
                  <span className="session-row__id">
                    #{shortId(session.sessionId)}
                  </span>
                  <span className="session-row__cwd">
                    {folderName(session.cwd)}
                  </span>
                  {session.configId && (
                    <span className="session-row__config">
                      {configNames.get(session.configId) ||
                        `config #${shortId(session.configId)}`}
                    </span>
                  )}
                </button>
                <button
                  className="session-row__delete"
                  aria-label={`Delete session ${session.sessionId}`}
                  title="Delete session"
                  onClick={() => {
                    if (
                      window.confirm(
                        `Delete session ${shortId(session.sessionId)}?`,
                      )
                    ) {
                      onDeleteSession(session.sessionId)
                    }
                  }}
                >
                  ×
                </button>
              </div>
            )
          })
        )}
      </div>

      <footer className="sidebar__footer">
        <div className="sidebar__meta">
          <span>model</span>
          <strong>
            {sessionInfo.model
              ? `${sessionInfo.provider || 'default'}:${sessionInfo.model}`
              : 'unknown'}
          </strong>
        </div>
        <div className="sidebar__meta">
          <span>nREPL</span>
          <strong>{sessionInfo.nreplPort || 'offline'}</strong>
        </div>
      </footer>
    </aside>
  )
}
