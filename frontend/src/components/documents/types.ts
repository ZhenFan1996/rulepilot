export interface GameResponse {
  game: { id: string; name: string }
  editions: Array<{ id: string; name: string; language: string }>
  bggMetadata?: null | { thumbnailUrl: string; bggUrl: string }
}

export interface DocumentResponse {
  document: {
    id: string
    gameEditionId: string | null
    title: string
    officialSourceUrl: string | null
    officialCoverUrl: string | null
  }
  latestVersion: {
    id: string
    originalFilename: string
    size: number
    status: string
  }
}

export interface BggSuggestion {
  bggId: number
  name: string
  publicationYear: number | null
  coverUrl: string
  minPlayers: number | null
  maxPlayers: number | null
  playingTimeMinutes: number | null
  minimumAge: number | null
  normalizedTitleMatch: boolean
  bggUrl: string
}

export interface BggSuggestionState {
  status: 'loading' | 'success' | 'error'
  candidates: BggSuggestion[]
  selectedBggId: number | null
  linkStatus: 'idle' | 'confirming' | 'linked' | 'error'
  linkAlreadyImported: boolean
}

export interface RulebookCandidate {
  title: string
  url: string
  publisher: string
  language: string
  edition: string
  sourceDomain: string
  officialDomainVerified: boolean
  languageVerified?: boolean
  sourceType: 'PUBLISHER' | 'TRUSTED_REPOSITORY' | 'COMMUNITY_PLATFORM' | 'PUBLIC_WEB'
  acquisitionMode: 'DIRECT_PDF' | 'IMAGE_GALLERY' | 'SOURCE_PAGE'
  capability: RulebookSourceCapability
  capabilityEvidence: RulebookCapabilityEvidence[]
  capabilityCheckedAt: string
  nextAction: RulebookSourceAction
}

export type RulebookSourceCapability =
  | 'DIRECT_DOCUMENT'
  | 'CONTIGUOUS_RULE_PAGES'
  | 'DOCUMENT_LISTING'
  | 'GAME_INFO_ONLY'
  | 'UNVERIFIED_PAGE'

export type RulebookCapabilityEvidence =
  | 'DOCUMENT_RESPONSE_CONFIRMED'
  | 'ORDERED_PAGE_SEQUENCE_CONFIRMED'
  | 'DOWNLOADABLE_DOCUMENT_LINKS_OBSERVED'
  | 'EXPLICIT_EMPTY_DOCUMENT_COLLECTION'
  | 'STRUCTURED_GAME_INFORMATION_OBSERVED'
  | 'ACCESS_REQUIRES_LOGIN'
  | 'SOURCE_PROBE_UNAVAILABLE'
  | 'HTML_PAGE_WITHOUT_DOCUMENT_CAPABILITY'
  | 'KNOWN_DOCUMENT_LISTING_ROUTE'
  | 'KNOWN_GAME_INFORMATION_ROUTE'
  | 'CANDIDATE_ONLY'

export type RulebookSourceAction =
  | 'IMPORT_DOCUMENT'
  | 'IMPORT_PAGE_SEQUENCE'
  | 'CONTINUE_ON_SOURCE'
  | 'USE_FOR_IDENTITY_ONLY'
  | 'REVIEW_OR_UPLOAD'

export type RulebookDiscoveryStatus = 'idle' | 'loading' | 'success' | 'unavailable' | 'error'

export interface RulebookDiscoveryCopy {
  action: string
  loading: string
  title: string
  detail: string
  unavailable: string
  empty: string
  error: string
  sources: Record<RulebookCandidate['sourceType'], string>
  capabilities: Record<RulebookSourceCapability, string>
  noImportableTitle: string
  noImportableDetail: string
  identityOnlyTitle: string
  identityOnlyDetail: string
  direct: string
  gallery: string
  page: string
  use: string
  continueListing: string
  reviewUnverified: string
  localUpload: string
  publisher: string
  language: string
  languageVerified: string
  languageReview: string
  edition: string
  searchSteps: string[]
}

export interface SelectedEditionContext {
  game: GameResponse['game']
  edition: GameResponse['editions'][number]
  bggMetadata: NonNullable<GameResponse['bggMetadata']> | null
}

export interface EditionOption {
  id: string
  label: string
}

export interface PhotographedPage {
  id: string
  file: File
  previewUrl: string
}

export type OfficialImportStage =
  | 'QUEUED'
  | 'CONNECTING'
  | 'DOWNLOADING'
  | 'COMPRESSING'
  | 'VERIFYING_FILE'
  | 'SAVING'
  | 'COMPLETED'
  | 'FAILED'

export type TeachingHandoffState =
  | 'NOT_REQUESTED'
  | 'WAITING_FOR_DOCUMENT'
  | 'LAUNCHING'
  | 'LAUNCHED'
  | 'FAILED'

export interface OfficialRulebookImportJob {
  id: string
  title: string
  rulebookTitle?: string
  editionId?: string | null
  editionName?: string | null
  sourceDomain: string
  stage: OfficialImportStage
  downloadedBytes: number
  totalBytes: number | null
  documentVersionId: string | null
  duplicate: boolean
  errorCode: string | null
  teachingHandoffState: TeachingHandoffState
  teachingPreparationRunId: string | null
  teachingErrorCode: string | null
  reused: boolean
}

export interface OfficialImportCopy extends Record<OfficialImportStage, string> {
  title: string
  safe: string
  WAITING_FOR_DOCUMENT: string
  LAUNCHING: string
  LAUNCHED: string
  TEACHING_FAILED: string
  DOCUMENT_FAILED: string
  background: string
}
