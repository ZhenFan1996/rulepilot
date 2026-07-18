import { createApp } from 'vue'
import { registerSW } from 'virtual:pwa-register'

import App from './App.vue'
import router from './router'
import './styles/index.css'

createApp(App).use(router).mount('#app')

registerSW({ immediate: true })
