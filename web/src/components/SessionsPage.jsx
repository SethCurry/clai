import { useState } from 'react'
import { useClai } from '../ClaiContext.jsx'
import { AgentConfigManager } from './AgentConfigManager.jsx'
import { Chat } from './Chat.jsx'
import { ExplorerPanel } from './ExplorerPanel.jsx'
import { ReplPanel } from './ReplPanel.jsx'
import { SessionSidebar } from './SessionSidebar.jsx'

/** What the chat column says while no conversation is loaded. */
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
 * The sessions page, which is the console itself: the session sidebar, the
 * chat timeline, and the optional Clojure REPL and explorer panels beside them.
 *
 * The panels and the agent-configuration dialog are page state, so they go
 * back to their defaults when the user visits another page and returns. The
 * conversation, the session list, and the connection are not: they live in
 * `useClai`, which outlives this page.
 */
export function SessionsPage() {
  const clai = useClai()
  const [replOpen, setReplOpen] = useState(true)
  const [explorerOpen, setExplorerOpen] = useState(true)
  const [explorerTab, setExplorerTab] = useState('files')
  const [configManagerOpen, setConfigManagerOpen] = useState(false)

  // The grid gains a column per open panel, so the classes have to agree with
  // what is actually rendered.
  const layoutClass = [
    'layout',
    replOpen && 'layout--with-repl',
    explorerOpen && 'layout--with-explorer',
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <>
      <div className={layoutClass}>
        <SessionSidebar
          sessions={clai.sessions}
          currentSession={clai.currentSession}
          agentConfigs={clai.agentConfigs}
          selectedConfigId={clai.selectedConfigId}
          status={clai.status}
          sessionInfo={clai.sessionInfo}
          replOpen={replOpen}
          explorerOpen={explorerOpen}
          onToggleRepl={() => setReplOpen((open) => !open)}
          onToggleExplorer={() => setExplorerOpen((open) => !open)}
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

        {explorerOpen && (
          <ExplorerPanel
            tab={explorerTab}
            onTab={setExplorerTab}
            currentSession={clai.currentSession}
            onClose={() => setExplorerOpen(false)}
          />
        )}
      </div>

      {configManagerOpen && (
        <AgentConfigManager
          configs={clai.agentConfigs}
          plugins={clai.plugins}
          onSave={clai.saveAgentConfig}
          onDelete={clai.deleteAgentConfig}
          onClose={() => setConfigManagerOpen(false)}
        />
      )}
    </>
  )
}
