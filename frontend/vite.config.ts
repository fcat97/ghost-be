import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    minify: 'terser'
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8878',
      '/ws': {
        target: 'ws://localhost:8878',
        ws: true
      }
    }
  }
})
