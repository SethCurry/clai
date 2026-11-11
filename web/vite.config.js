import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// During `npm run dev`, Vite serves the UI on :5173 and proxies the daemon's
// JSON API and WebSocket. In production `clai web` serves web/dist itself, so
// the browser talks to the same origin and no proxy is involved.
const daemon = process.env.CLAI_DAEMON_URL || 'http://127.0.0.1:8080'
const daemonWs = daemon.replace(/^http/, 'ws')

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: daemon,
        changeOrigin: true,
      },
      '/ws': {
        target: daemonWs,
        changeOrigin: true,
        ws: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
})
