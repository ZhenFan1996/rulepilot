import { createReadStream, readFileSync } from 'node:fs'
import path from 'node:path'

import tailwindcss from '@tailwindcss/vite'
import vue from '@vitejs/plugin-vue'
import { type Plugin } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'
import { defineConfig } from 'vitest/config'

import { pwaNavigationFallbackDenylist } from './src/lib/pwaRouting'

const ocrAssets = [
  {
    fileName: 'ocr-assets/v7/worker.min.js',
    sourcePath: path.resolve(__dirname, 'node_modules/tesseract.js/dist/worker.min.js'),
  },
  {
    fileName: 'ocr-assets/v7/tesseract-core-lstm.wasm.js',
    sourcePath: path.resolve(__dirname, 'node_modules/tesseract.js-core/tesseract-core-lstm.wasm.js'),
  },
  {
    fileName: 'ocr-assets/v7/lang/eng.traineddata.gz',
    sourcePath: path.resolve(
      __dirname,
      'node_modules/@tesseract.js-data/eng/4.0.0_best_int/eng.traineddata.gz',
    ),
  },
  {
    fileName: 'ocr-assets/v7/lang/chi_sim.traineddata.gz',
    sourcePath: path.resolve(
      __dirname,
      'node_modules/@tesseract.js-data/chi_sim/4.0.0_best_int/chi_sim.traineddata.gz',
    ),
  },
]

const apiProxyTarget = process.env.VITE_API_PROXY_TARGET ?? 'http://127.0.0.1:8080'

function localOcrRuntime(): Plugin {
  return {
    name: 'rulepilot-local-ocr-runtime',
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const requestedPath = request.url?.split('?')[0]?.replace(/^\//, '')
        const asset = ocrAssets.find((candidate) => candidate.fileName === requestedPath)
        if (!asset) return next()
        response.setHeader(
          'Content-Type',
          asset.fileName.endsWith('.gz') ? 'application/gzip' : 'application/javascript; charset=utf-8',
        )
        response.setHeader('Cache-Control', 'public, max-age=31536000, immutable')
        createReadStream(asset.sourcePath).pipe(response)
      })
    },
    generateBundle() {
      for (const asset of ocrAssets) {
        this.emitFile({
          type: 'asset',
          fileName: asset.fileName,
          source: readFileSync(asset.sourcePath),
        })
      }
    },
  }
}

export default defineConfig({
  plugins: [
    vue(),
    tailwindcss(),
    localOcrRuntime(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['rulepilot-icon.svg'],
      manifest: {
        name: 'RulePilot 桌游规则讲解助手',
        short_name: 'RulePilot',
        description: '从规则书生成带引用的桌游讲解，并在讲解后提供规则答疑。',
        theme_color: '#f2ead9',
        background_color: '#f2ead9',
        display: 'standalone',
        start_url: '/',
        scope: '/',
        lang: 'zh-CN',
        icons: [
          {
            src: '/rulepilot-icon.svg',
            sizes: 'any',
            type: 'image/svg+xml',
            purpose: 'any maskable',
          },
        ],
      },
      workbox: {
        cleanupOutdatedCaches: true,
        navigateFallback: '/index.html',
        navigateFallbackDenylist: pwaNavigationFallbackDenylist,
        globPatterns: [
          'index.html',
          'manifest.webmanifest',
          'rulepilot-icon.svg',
          'assets/index-*.{js,css}',
          'assets/LessonView-*.js',
          'assets/LessonModeNav-*.js',
          'assets/LessonChapterList-*.js',
          'assets/liveLesson-*.js',
          'assets/offlineKnowledge-*.js',
          'assets/teachingProgress-*.js',
          'assets/NotFoundView-*.js',
        ],
        globIgnores: ['ocr-assets/**'],
        runtimeCaching: [
          {
            urlPattern: /\/assets\/.*\.(?:js|css)$/,
            handler: 'CacheFirst',
            options: {
              cacheName: 'rulepilot-visited-route-assets',
              expiration: {
                maxEntries: 80,
                maxAgeSeconds: 30 * 24 * 60 * 60,
              },
              cacheableResponse: {
                statuses: [0, 200],
              },
            },
          },
        ],
      },
    }),
  ],
  server: {
    proxy: {
      '/api': apiProxyTarget,
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    setupFiles: ['./src/test/setupLocale.ts'],
  },
})
