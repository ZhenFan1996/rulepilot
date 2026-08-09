export type RecommendationProfile = {
  players: number | null
  maxMinutes: number | null
  maxWeight: number | null
  type: string
  interaction: string
}

export type RecommendationClarification = {
  field: 'conversation'
  prompt: string
  options: { value: string; label: string }[]
}

export type RecommendationGame = {
  bggId: number
  name: string
  originalName: string
  nameLocalized: boolean
  publicationYear: number | null
  overallRank: number | null
  geekRating: number
  averageRating: number
  usersRated: number
  thumbnailUrl: string
  minPlayers: number | null
  maxPlayers: number | null
  playingTimeMinutes: number | null
  averageWeight: number | null
  categories: string[]
  mechanics: string[]
  bggUrl: string
}

export type RecommendationReason = {
  kind: 'bgg_fact' | 'preference_inference' | 'web_research'
  text: string
  sourceIndexes: number[]
}

export type RecommendedGame = {
  game: RecommendationGame
  matches: string[]
  tradeoffs: string[]
  reasons?: RecommendationReason[]
}

export type ResearchSource = { index: number; title: string; url: string; domain: string }

export type RecommendationAgentResponse = {
  outcome: 'conversation' | 'needs_clarification' | 'recommendations' | 'no_match' | 'unavailable'
  mode: 'model_assisted'
  assistantMessage: string
  profile: RecommendationProfile
  clarification: RecommendationClarification | null
  sourceCount: number
  candidatesEvaluated: number
  userModel?: {
    summary: string
    hypotheses: { text: string; confidence: 'low' | 'medium' | 'high'; basedOn: string }[]
  }
  researchSources?: ResearchSource[]
  harness?: {
    modelCalls: number
    catalogCalls: number
    webResearchCalls: number
    fallbackUsed: boolean
    actions: string[]
    totalElapsedMs?: number
  }
  games: RecommendedGame[]
}

export type RecommendationMessage = {
  id: number
  role: 'assistant' | 'user'
  text: string
  response?: RecommendationAgentResponse
}

export type RecommendationProgressStage =
  | 'understanding_request'
  | 'selecting_tools'
  | 'searching_bgg_catalog'
  | 'reading_game_details'
  | 'discovering_candidates'
  | 'verifying_bgg_candidates'
  | 'researching_game_fit'
  | 'composing_response'

export type RecommendationProgressUpdate = {
  stage: RecommendationProgressStage
  elapsedMs: number
}
