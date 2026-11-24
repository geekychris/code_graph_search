import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// API_TARGET points the dev server at another backend, e.g. API_TARGET=http://localhost:18190 npm run dev
const target = process.env.API_TARGET || 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  build: { outDir: 'dist' },
  server: { proxy: { '/api': target, '/mcp': target } }
})
