import { mergeConfig } from 'vite'
import { defineConfig } from 'vitest/config'

import viteConfig from './vite.config.ts'

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      include: ['src/**/*.spec.ts'],
      exclude: ['**/node_modules/**', '**/dist/**', '**/._*', '**/**/._*'],
    },
  }),
)
