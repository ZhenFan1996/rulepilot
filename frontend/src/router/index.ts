import { createRouter, createWebHistory } from 'vue-router'

import CatalogView from '@/views/CatalogView.vue'
import GameShelfView from '@/views/GameShelfView.vue'
import DocumentsView from '@/views/DocumentsView.vue'
import HomeView from '@/views/HomeView.vue'
import LessonView from '@/views/LessonView.vue'
import LessonQuestionsView from '@/views/LessonQuestionsView.vue'
import PublicLessonView from '@/views/PublicLessonView.vue'
import PublicLibraryView from '@/views/PublicLibraryView.vue'
import LessonsView from '@/views/LessonsView.vue'
import LoginView from '@/views/LoginView.vue'
import RegisterView from '@/views/RegisterView.vue'
import ModelSettingsView from '@/views/ModelSettingsView.vue'
import AccountView from '@/views/AccountView.vue'

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
      path: '/register',
      name: 'register',
      component: RegisterView,
    },
    {
      path: '/catalog',
      name: 'catalog',
      component: GameShelfView,
    },
    {
      path: '/catalog/manage',
      name: 'catalog-manage',
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
      path: '/account',
      name: 'account',
      component: AccountView,
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
      path: '/lesson/:planId/questions',
      name: 'lesson-questions',
      component: LessonQuestionsView,
    },
    {
      path: '/read/:planId',
      name: 'public-lesson',
      component: PublicLessonView,
    },
    {
      path: '/library',
      name: 'public-library',
      component: PublicLibraryView,
    },
    {
      path: '/table/:planId',
      name: 'table-mode',
      redirect: (to) => ({ name: 'lesson', params: { planId: to.params.planId } }),
    },
  ],
})

export default router
