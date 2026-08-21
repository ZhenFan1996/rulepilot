export type RecommendationConstraintRange<T extends number = number> = {
  minimum: T | null
  maximum: T | null
  strength: 'hard' | 'soft'
  sourceText: string
  confirmedTurn: number
}

export type RecommendationProfile = {
  /** Rolling-deploy read compatibility only; canonical client writes omit these projections. */
  players?: number | null
  maxMinutes?: number | null
  maxWeight?: number | null
  type: string
  interaction: string
  playerCount: RecommendationConstraintRange | null
  durationMinutes: RecommendationConstraintRange | null
  complexity: RecommendationConstraintRange | null
}

export type RecommendationClarification = {
  field: 'conversation'
  prompt: string
  options: { value: string; label: string }[]
}

export type RecommendationShortfall = {
  requestedCount: number
  availableCount: number
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
  minimumPlayTimeMinutes?: number | null
  maximumPlayTimeMinutes?: number | null
  minimumAge?: number | null
  suggestedMinimumAge?: number | null
  bestWith?: string
  recommendedWith?: string
  languageDependenceLevel?: number | null
  averageWeight: number | null
  weightVotes?: number | null
  categories: string[]
  mechanics: string[]
  families?: string[]
  designers?: string[]
  publishers?: string[]
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
  fitClaims?: CandidateFitClaim[]
}

export type CandidateFitClaim = {
  subject: string
  strength: 'hard' | 'soft'
  relation: 'satisfied' | 'conflict' | 'unknown'
  text: string
}

export type CandidateComparison = {
  candidates: {
    game: RecommendationGame
    fitClaims: CandidateFitClaim[]
  }[]
  axes: {
    subject: string
    label: string
    capability: 'structured_metadata' | 'taxonomy' | 'attributed_report' | 'rulebook_fact'
    cells: {
      bggId: number
      status: 'observed' | 'unknown'
      observationKind: string
      value: string
    }[]
  }[]
}

export type ResearchSource = { index: number; title: string; url: string; domain: string }

export type RecommendationAgentResponse = {
  conversationId?: string | null
  revision?: number | null
  clientTurnId?: string | null
  replayed?: boolean
  outcome: 'conversation' | 'needs_clarification' | 'recommendations' | 'no_match' | 'unavailable'
  mode: 'model_assisted' | 'model_fast_path' | 'local_fast_path'
  responseLocale?: 'zh-CN' | 'en'
  assistantMessage: string
  profile: RecommendationProfile
  clarification: RecommendationClarification | null
  shortfall?: RecommendationShortfall | null
  sourceCount: number
  candidatesEvaluated: number
  userModel?: {
    summary: string
    hypotheses: {
      field?: string
      value?: string
      text: string
      confidence: 'low' | 'medium' | 'high'
      basedOn: string
    }[]
  }
  researchSources?: ResearchSource[]
  comparison?: CandidateComparison | null
  harness?: {
    modelCalls: number
    catalogCalls: number
    webResearchCalls: number
    fallbackUsed: boolean
    actions: string[]
    totalElapsedMs?: number
  } | null
  games: RecommendedGame[]
}

export type RecommendationServerSession = {
  conversationId: string
  revision: number
  profile: RecommendationProfile
  transcript: { role: 'assistant' | 'user'; text: string }[]
  knownGames: { bggId: number; name: string; originalName: string }[]
  shownBggIds: number[]
  processing: boolean
  processingSince: string | null
  latestResponse: RecommendationAgentResponse | null
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
  phase: 'started' | 'completed' | 'retrying' | 'failed'
  action: RecommendationProgressAction | null
  elapsedMs: number
  decisionCycle: number
  modelCalls: number
  actionCalls: number
  catalogCalls: number
  webResearchCalls: number
  observedCandidates: number
  verifiedCandidates: number
  hardRejectedCandidates: number
  sourceCount: number
}

export type RecommendationProgressAction =
  | 'understand_request'
  | 'direct_reply_fast_path'
  | 'choose_next_action'
  | 'reply_to_user'
  | 'ask_user'
  | 'resolve_bgg_game'
  | 'inspect_candidate_titles'
  | 'browse_bgg_catalog'
  | 'discover_public_candidates'
  | 'lookup_bgg_games'
  | 'research_game_fit'
  | 'compare_candidates'
  | 'report_no_match'
  | 'recommend_games'
