import { createApp } from 'vue'
import { registerSW } from 'virtual:pwa-register'

import App from './App.vue'
import { normalizeLocale, setLocale } from './lib/locale'
import { installStaleAssetRecovery } from './lib/staleAssetRecovery'
import router from './router'
import './styles/index.css'

installStaleAssetRecovery(router)

router.afterEach((to, _from, failure) => {
  if (failure) return
  if (typeof to.query.lang === 'string') setLocale(normalizeLocale(to.query.lang))
})

createApp(App).use(router).mount('#app')

registerSW({ immediate: true })
