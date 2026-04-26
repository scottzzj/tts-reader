import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'
import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs'

const backendStaticDir = resolve(__dirname, '../backend/src/main/resources/static')
const runtimeStaticDir = resolve(__dirname, '../backend/target/classes/static')

function syncRuntimeStaticDir() {
  return {
    name: 'sync-runtime-static-dir',
    closeBundle() {
      if (!existsSync(backendStaticDir)) {
        return
      }

      mkdirSync(runtimeStaticDir, { recursive: true })

      const runtimeAssetsDir = resolve(runtimeStaticDir, 'assets')
      const sourceAssetsDir = resolve(backendStaticDir, 'assets')

      if (existsSync(runtimeAssetsDir)) {
        rmSync(runtimeAssetsDir, { recursive: true, force: true })
      }

      cpSync(resolve(backendStaticDir, 'index.html'), resolve(runtimeStaticDir, 'index.html'), { force: true })

      if (existsSync(sourceAssetsDir)) {
        cpSync(sourceAssetsDir, runtimeAssetsDir, { recursive: true, force: true })
      }

      ;['favicon.svg', 'icons.svg'].forEach((fileName) => {
        const sourceFile = resolve(backendStaticDir, fileName)
        if (existsSync(sourceFile)) {
          cpSync(sourceFile, resolve(runtimeStaticDir, fileName), { force: true })
        }
      })
    },
  }
}

function cleanBuildAssets() {
  return {
    name: 'clean-build-assets',
    buildStart() {
      const sourceAssetsDir = resolve(backendStaticDir, 'assets')
      if (existsSync(sourceAssetsDir)) {
        rmSync(sourceAssetsDir, { recursive: true, force: true })
      }
    },
  }
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const server = {
    port: env.VITE_PORT ? Number(env.VITE_PORT) : 5173,
    proxy: {
      '/api': {
        target: env.VITE_API_TARGET || 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  }

  if (env.VITE_HOST) {
    server.host = env.VITE_HOST
  }
  if (env.VITE_STRICT_PORT) {
    server.strictPort = env.VITE_STRICT_PORT !== 'false'
  }

  return {
    plugins: [vue(), cleanBuildAssets(), syncRuntimeStaticDir()],
    server,
    build: {
      outDir: backendStaticDir,
      emptyOutDir: false,
    },
  }
})
