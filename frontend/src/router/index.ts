import { createRouter, createWebHistory } from 'vue-router'

import CatalogView from '@/views/CatalogView.vue'
import DocumentsView from '@/views/DocumentsView.vue'
import HomeView from '@/views/HomeView.vue'
import LessonView from '@/views/LessonView.vue'
import LessonsView from '@/views/LessonsView.vue'
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
      component: DocumentsView,
    },
    {
      path: '/lessons',
      name: 'lessons',
      component: LessonsView,
    },
    {
      path: '/lesson/:planId?',
      name: 'lesson',
      component: LessonView,
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
