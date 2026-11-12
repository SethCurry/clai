import { useState } from 'react'
import { AgentConfigManager } from './components/AgentConfigManager.jsx'
import { Chat } from './components/Chat.jsx'
import { ErrorToasts } from './components/ErrorToasts.jsx'
import { FileExplorer } from './components/FileExplorer.jsx'
import { ReplPanel } from './components/ReplPanel.jsx'
import { SessionSidebar } from './components/SessionSidebar.jsx'
import { useClaiSocket } from './useClaiSocket.js'

const STATUS_LABELS = {
  connected: 'connected',
  connecting: 'connecting',
  disconnected: 'reconnecting',
  error: 'connection error',
}

function EmptyState({ hasSessions, onNewSession }) {
  return (
    <section className="empty">
      <div className="empty__card">
        <p className="empty__eyebrow">clai / web</p>
        <h2>{hasSessions ? 'Choose a session' : 'Start a session'}</h2>
        <p>
          Sessions hold a conversation with the agent. Each one can use the
          repository tools and the live Clojure runtime behind the daemon.
        </p>
        <button
          className="button button--primary"
          onClick={() => onNewSession()}
        >
          + New session
        </button>
      </div>
    </section>
  )
}

/**
 * The web UI shell: connection status, the session sidebar, the chat timeline,
 * and the optional Clojure REPL and file explorer panels.
 */
export default function App() {
  const clai = useClaiSocket()
  const [replOpen, setReplOpen] = useState(true)
  const [filesOpen, setFilesOpen] = useState(true)
  const [configManagerOpen, setConfigManagerOpen] = useState(false)
  const statusLabel = STATUS_LABELS[clai.status] || clai.status
  // The grid gains a column per open panel, so the classes have to agree with
  // what is actually rendered.
  const layoutClass = [
    'layout',
    replOpen && 'layout--with-repl',
    filesOpen && 'layout--with-files',
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <div className="app">
      <header className="topbar">
        <div className="topbar__brand">
          <span className="brand-mark" aria-hidden="true">
            λ
          </span>
          <span className="brand-name">clai</span>
          <span className="brand-tag">agent console</span>
        </div>
        <div className="topbar__meta">
          <span className={`connection connection--${clai.status}`}>
            <span className="connection__dot" />
            {statusLabel}
          </span>
          {clai.sessionInfo.model && (
            <span className="pill">{clai.sessionInfo.model}</span>
          )}
          {clai.sessionInfo.nreplPort && (
            <span className="pill">nREPL :{clai.sessionInfo.nreplPort}</span>
          )}
          <button
            className="button button--quiet"
            onClick={() => setReplOpen((open) => !open)}
          >
            {replOpen ? 'Hide REPL' : 'Show REPL'}
          </button>
          <button
            className="button button--quiet"
            onClick={() => setFilesOpen((open) => !open)}
          >
            {filesOpen ? 'Hide files' : 'Show files'}
          </button>
        </div>
      </header>

      <div className={layoutClass}>
        <SessionSidebar
          sessions={clai.sessions}
          currentSession={clai.currentSession}
          agentConfigs={clai.agentConfigs}
          selectedConfigId={clai.selectedConfigId}
          status={clai.status}
          sessionInfo={clai.sessionInfo}
          onNewSession={clai.newSession}
          onSelectConfig={clai.selectAgentConfig}
          onManageConfigs={() => setConfigManagerOpen(true)}
          onRefresh={clai.refreshSessions}
          onLoadSession={clai.loadSession}
          onDeleteSession={clai.deleteSession}
        />

        {clai.currentSession ? (
          <Chat
            session={clai.currentSession}
            updates={clai.updates}
            promptRunning={clai.promptRunning}
            onPrompt={clai.runPrompt}
            onRename={clai.renameSession}
          />
        ) : (
          <EmptyState
            hasSessions={clai.sessions.length > 0}
            onNewSession={clai.newSession}
          />
        )}

        {replOpen && (
          <ReplPanel
            nreplPort={clai.sessionInfo.nreplPort}
            currentSession={clai.currentSession}
            entries={clai.replEntries}
            running={clai.evalRunning}
            onEval={clai.evalForm}
            onClear={clai.clearRepl}
            onClose={() => setReplOpen(false)}
          />
        )}

        {filesOpen && (
          <FileExplorer
            key={clai.currentSession?.sessionId ?? 'no-session'}
            currentSession={clai.currentSession}
            onClose={() => setFilesOpen(false)}
          />
        )}
      </div>

      <ErrorToasts errors={clai.errors} onDismiss={clai.dismissError} />

      {configManagerOpen && (
        <AgentConfigManager
          configs={clai.agentConfigs}
          plugins={clai.plugins}
          onSave={clai.saveAgentConfig}
          onDelete={clai.deleteAgentConfig}
          onClose={() => setConfigManagerOpen(false)}
        />
      )}
    </div>
  )
}
