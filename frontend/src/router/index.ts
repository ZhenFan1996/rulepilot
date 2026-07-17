import { createRouter, createWebHistory } from 'vue-router'

import CatalogView from '@/views/CatalogView.vue'
import HomeView from '@/views/HomeView.vue'
import LoginView from '@/views/LoginView.vue'
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
      path: '/login',
      name: 'login',
      component: LoginView,
    },
    {
      path: '/catalog',
      name: 'catalog',
      component: CatalogView,
    },
    {
      path: '/teach',
      name: 'teach',
      component: PlaceholderView,
      meta: { eyebrow: 'IMPORT RULEBOOK', title: '从规则书开始', description: '导入规则书并确认版本、扩展和语言，随后生成从 setup 到计分的完整讲解。' },
    },
    {
      path: '/lesson',
      name: 'lesson',
      component: PlaceholderView,
      meta: { eyebrow: 'GUIDED LESSON', title: '分步骤规则讲解', description: '按组件、setup、回合流程、行动、结束条件和计分逐章学习，并随时回看证据页码。' },
    },
    {
      path: '/questions',
      name: 'questions',
      component: PlaceholderView,
      meta: { eyebrow: 'QUESTIONS AFTER LEARNING', title: '讲解完成后继续答疑', description: '针对讲解步骤或实际对局继续提问，答案沿用已确认的版本、扩展和规则证据。' },
    },
  ],
})

export default router
