import { createRouter, createWebHistory } from 'vue-router'

import CatalogView from '@/views/CatalogView.vue'
import DocumentsView from '@/views/DocumentsView.vue'
import HomeView from '@/views/HomeView.vue'
import LessonView from '@/views/LessonView.vue'
import LessonsView from '@/views/LessonsView.vue'
import LoginView from '@/views/LoginView.vue'
import ModelSettingsView from '@/views/ModelSettingsView.vue'
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
      path: '/settings/models',
      name: 'model-settings',
      component: ModelSettingsView,
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
      meta: { title: '讲解中的问题，都留在原来的位置', description: '打开一份讲解后，可以直接针对当前章节提问。这样不用再次选择游戏和版本。' },
    },
  ],
})

export default router
