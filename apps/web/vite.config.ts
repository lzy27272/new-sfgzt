import { fileURLToPath } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ command, mode }) => {
  const envDir = fileURLToPath(new URL('.', import.meta.url))
  const env = loadEnv(mode, envDir)
  // Both the login page and signed-in footer depend on this public build value.
  // Fail before producing a Pilot bundle that would silently hide both links.
  if (command === 'build' && mode === 'pilot' && !env.VITE_ICP_RECORD_NUMBER?.trim()) {
    throw new Error('Pilot build requires a non-empty VITE_ICP_RECORD_NUMBER for the ICP footer.')
  }

  return {
    envDir,
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/api': 'http://localhost:8080',
      },
    },
  }
})
