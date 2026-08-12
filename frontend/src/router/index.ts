import { createRouter, createWebHistory } from 'vue-router'

import { appScrollBehavior } from '@/lib/routeExperience'
import HomeView from '@/views/HomeView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior: appScrollBehavior,
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
      meta: { titleKey: 'route.title.home' },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { titleKey: 'route.title.login' },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { titleKey: 'route.title.register' },
    },
    {
      path: '/catalog',
      name: 'catalog',
      component: () => import('@/views/GameShelfView.vue'),
      meta: { titleKey: 'route.title.games' },
    },
    {
      path: '/catalog/manage',
      name: 'catalog-manage',
      component: () => import('@/views/CatalogView.vue'),
      meta: { titleKey: 'route.title.manageGames' },
    },
    {
      path: '/games/:gameId',
      name: 'game-workspace',
      component: () => import('@/views/GameWorkspaceView.vue'),
      meta: { titleKey: 'route.title.game' },
    },
    {
      path: '/discover',
      name: 'game-recommendations',
      component: () => import('@/views/GameRecommendationChatView.vue'),
      meta: { titleKey: 'route.title.discover' },
    },
    {
      path: '/discover/catalog',
      name: 'game-catalog-browse',
      component: () => import('@/views/GameRecommendationsView.vue'),
      meta: { titleKey: 'route.title.browseGames' },
    },
    {
      path: '/discover/:bggId',
      name: 'game-discovery',
      component: () => import('@/views/GameDiscoveryView.vue'),
      meta: { titleKey: 'route.title.gameDetails' },
    },
    {
      path: '/teach',
      name: 'teach',
      component: () => import('@/views/DocumentsView.vue'),
      meta: { titleKey: 'route.title.addRulebook' },
    },
    {
      path: '/lessons',
      name: 'lessons',
      component: () => import('@/views/LessonsView.vue'),
      meta: { titleKey: 'route.title.guides' },
    },
    {
      path: '/account',
      name: 'account',
      component: () => import('@/views/AccountView.vue'),
      meta: { titleKey: 'route.title.account' },
    },
    {
      path: '/settings/models',
      name: 'model-settings',
      component: () => import('@/views/ModelSettingsView.vue'),
      meta: { titleKey: 'route.title.models' },
    },
    {
      path: '/admin/agent-audit',
      name: 'agent-audit',
      component: () => import('@/views/AgentAuditView.vue'),
      meta: { titleKey: 'route.title.agentAudit' },
    },
    {
      path: '/lesson/:planId?',
      name: 'lesson',
      component: () => import('@/views/LessonView.vue'),
      meta: { titleKey: 'route.title.guide' },
    },
    {
      path: '/lesson/:planId/questions',
      name: 'lesson-questions',
      component: () => import('@/views/LessonQuestionsView.vue'),
      meta: { titleKey: 'route.title.questions' },
    },
    {
      path: '/rulebooks/:versionId',
      name: 'rulebook-reader',
      component: () => import('@/views/RulebookReaderView.vue'),
      meta: { titleKey: 'route.title.rulebook' },
    },
    {
      path: '/read/:planId',
      name: 'public-lesson',
      component: () => import('@/views/PublicLessonView.vue'),
      meta: { titleKey: 'route.title.publicGuide' },
    },
    {
      path: '/read/:planId/questions',
      name: 'public-lesson-questions',
      component: () => import('@/views/PublicLessonView.vue'),
      meta: { titleKey: 'route.title.questions' },
    },
    {
      path: '/library',
      name: 'public-library',
      component: () => import('@/views/PublicLibraryView.vue'),
      meta: { titleKey: 'route.title.library' },
    },
    {
      path: '/table/:planId',
      name: 'table-mode',
      redirect: (to) => ({ name: 'lesson', params: { planId: to.params.planId } }),
      meta: { titleKey: 'route.title.questions' },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
      meta: { titleKey: 'route.title.notFound' },
    },
  ],
})

export default router
