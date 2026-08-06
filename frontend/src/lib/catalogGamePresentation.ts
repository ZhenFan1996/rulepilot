export interface CatalogGamePresentation {
  editionId: string
  gameName: string
  editionName: string
  language: string
  publicationYear: number | null
  bggId: number
  thumbnailUrl: string
  minPlayers: number | null
  maxPlayers: number | null
  playingTimeMinutes: number | null
  minimumAge: number | null
  bggUrl: string
}
