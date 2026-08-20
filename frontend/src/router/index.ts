import { createRouter, createWebHistory } from 'vue-router'

import type { TranslationKey } from '@/lib/locale'
import { appScrollBehavior } from '@/lib/routeExperience'
import HomeView from '@/views/HomeView.vue'

const routeMeta = (titleKey: TranslationKey) => ({ titleKey })

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior: appScrollBehavior,
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
      meta: routeMeta('route.title.home'),
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: routeMeta('route.title.login'),
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: routeMeta('route.title.register'),
    },
    {
      path: '/catalog',
      name: 'catalog',
      component: () => import('@/views/GameShelfView.vue'),
      meta: routeMeta('route.title.games'),
    },
    {
      path: '/catalog/manage',
      name: 'catalog-manage',
      component: () => import('@/views/CatalogView.vue'),
      meta: routeMeta('route.title.manageGames'),
    },
    {
      path: '/games/:gameId',
      name: 'game-workspace',
      component: () => import('@/views/GameWorkspaceView.vue'),
      meta: routeMeta('route.title.game'),
    },
    {
      path: '/discover',
      name: 'game-recommendations',
      component: () => import('@/views/GameRecommendationChatView.vue'),
      meta: routeMeta('route.title.discover'),
    },
    {
      path: '/discover/catalog',
      name: 'game-catalog-browse',
      component: () => import('@/views/GameRecommendationsView.vue'),
      meta: routeMeta('route.title.browseGames'),
    },
    {
      path: '/discover/:bggId',
      name: 'game-discovery',
      component: () => import('@/views/GameDiscoveryView.vue'),
      meta: routeMeta('route.title.gameDetails'),
    },
    {
      path: '/teach',
      name: 'teach',
      component: () => import('@/views/DocumentsView.vue'),
      meta: routeMeta('route.title.addRulebook'),
    },
    {
      path: '/rulebooks',
      name: 'rulebooks',
      component: () => import('@/views/RulebookLibraryView.vue'),
      meta: routeMeta('route.title.rulebook'),
    },
    {
      path: '/lessons',
      name: 'lessons',
      component: () => import('@/views/LessonsView.vue'),
      meta: routeMeta('route.title.guides'),
    },
    {
      path: '/work',
      name: 'work-status',
      component: () => import('@/views/LessonsView.vue'),
      meta: routeMeta('route.title.workStatus'),
    },
    {
      path: '/account',
      name: 'account',
      component: () => import('@/views/AccountView.vue'),
      meta: routeMeta('route.title.account'),
    },
    {
      path: '/settings/models',
      name: 'model-settings',
      component: () => import('@/views/ModelSettingsView.vue'),
      meta: routeMeta('route.title.models'),
    },
    {
      path: '/admin/agent-audit',
      name: 'agent-audit',
      component: () => import('@/views/AgentAuditView.vue'),
      meta: routeMeta('route.title.agentAudit'),
    },
    {
      path: '/admin/models',
      name: 'admin-models',
      component: () => import('@/views/AdminModelManagementView.vue'),
      meta: routeMeta('route.title.adminModels'),
    },
    {
      path: '/lesson/:planId?',
      name: 'lesson',
      component: () => import('@/views/LessonView.vue'),
      meta: routeMeta('route.title.guide'),
    },
    {
      path: '/lesson/:planId/questions',
      name: 'lesson-questions',
      component: () => import('@/views/LessonQuestionsView.vue'),
      meta: routeMeta('route.title.questions'),
    },
    {
      path: '/rulebooks/:versionId',
      name: 'rulebook-reader',
      component: () => import('@/views/RulebookReaderView.vue'),
      meta: routeMeta('route.title.rulebook'),
    },
    {
      path: '/read/:planId',
      name: 'public-lesson',
      component: () => import('@/views/PublicLessonView.vue'),
      meta: routeMeta('route.title.publicGuide'),
    },
    {
      path: '/read/:planId/questions',
      name: 'public-lesson-questions',
      component: () => import('@/views/PublicLessonView.vue'),
      meta: routeMeta('route.title.questions'),
    },
    {
      path: '/library',
      name: 'public-library',
      component: () => import('@/views/PublicLibraryView.vue'),
      meta: routeMeta('route.title.library'),
    },
    {
      path: '/table/:planId',
      name: 'table-mode',
      redirect: (to) => ({ name: 'lesson', params: { planId: to.params.planId } }),
      meta: routeMeta('route.title.questions'),
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
      meta: routeMeta('route.title.notFound'),
    },
  ],
})

export default router
