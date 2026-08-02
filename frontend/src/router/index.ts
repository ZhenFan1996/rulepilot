import { createRouter, createWebHistory } from 'vue-router'

import HomeView from '@/views/HomeView.vue'

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
      component: () => import('@/views/LoginView.vue'),
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
    },
    {
      path: '/catalog',
      name: 'catalog',
      component: () => import('@/views/GameShelfView.vue'),
    },
    {
      path: '/catalog/manage',
      name: 'catalog-manage',
      component: () => import('@/views/CatalogView.vue'),
    },
    {
      path: '/teach',
      name: 'teach',
      component: () => import('@/views/DocumentsView.vue'),
    },
    {
      path: '/lessons',
      name: 'lessons',
      component: () => import('@/views/LessonsView.vue'),
    },
    {
      path: '/account',
      name: 'account',
      component: () => import('@/views/AccountView.vue'),
    },
    {
      path: '/settings/models',
      name: 'model-settings',
      component: () => import('@/views/ModelSettingsView.vue'),
    },
    {
      path: '/lesson/:planId?',
      name: 'lesson',
      component: () => import('@/views/LessonView.vue'),
    },
    {
      path: '/lesson/:planId/questions',
      name: 'lesson-questions',
      component: () => import('@/views/LessonQuestionsView.vue'),
    },
    {
      path: '/read/:planId',
      name: 'public-lesson',
      component: () => import('@/views/PublicLessonView.vue'),
    },
    {
      path: '/library',
      name: 'public-library',
      component: () => import('@/views/PublicLibraryView.vue'),
    },
    {
      path: '/table/:planId',
      name: 'table-mode',
      redirect: (to) => ({ name: 'lesson', params: { planId: to.params.planId } }),
    },
  ],
})

export default router
