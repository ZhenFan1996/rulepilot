import { createRouter, createWebHistory } from 'vue-router'

import HomeView from '@/views/HomeView.vue'
import PlaceholderView from '@/views/PlaceholderView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
    },
    {
      path: '/teach',
      name: 'teach',
      component: PlaceholderView,
      meta: { eyebrow: 'TEACH', title: '规则讲解即将到来', description: '准备好规则书后，这里会引导你按章节理解一局游戏。' },
    },
    {
      path: '/session',
      name: 'session',
      component: PlaceholderView,
      meta: { eyebrow: 'LIVE SESSION', title: '实时桌局即将到来', description: '选择游戏、版本和扩展后，RulePilot 会陪你进行一局桌游。' },
    },
    {
      path: '/search',
      name: 'search',
      component: PlaceholderView,
      meta: { eyebrow: 'QUICK SEARCH', title: '快速查规则即将到来', description: '未来可以直接输入问题，查看带页码和证据的规则答案。' },
    },
  ],
})

export default router
