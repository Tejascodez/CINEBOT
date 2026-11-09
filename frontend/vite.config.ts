/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// In dev, /api is proxied to the Spring Boot backend: same origin for the browser, so no CORS needed.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: process.env.VITE_API_TARGET ?? 'http://localhost:8085', changeOrigin: false },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true, // also enables Testing Library's automatic cleanup between tests
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
