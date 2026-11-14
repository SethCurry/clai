import { createContext, useContext } from 'react'
import { useClaiSocket } from './useClaiSocket.js'

/**
 * The signed-in console's connection to the daemon.
 *
 * The WebSocket is opened once, above the router's pages, because what it
 * holds is app state rather than page state: the session list, the
 * conversation being streamed, and the REPL entries all have to survive a trip
 * to the git or notes page.
 */
const ClaiContext = createContext(null)

/**
 * Open one daemon connection and share it with every page below. Mount it only
 * for an authenticated user: mounting it is what starts the WebSocket.
 */
export function ClaiProvider({ children }) {
  const clai = useClaiSocket()
  return <ClaiContext.Provider value={clai}>{children}</ClaiContext.Provider>
}

/**
 * The daemon connection from the nearest `ClaiProvider`. Throws outside one,
 * since every such use is a component rendered in the wrong place.
 */
export function useClai() {
  const clai = useContext(ClaiContext)
  if (!clai) {
    throw new Error('useClai must be used inside a ClaiProvider')
  }
  return clai
}
