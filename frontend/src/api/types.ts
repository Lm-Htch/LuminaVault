export interface Result<T> {
  code: number
  message: string
  data: T
}

export interface TokenResponse {
  tokenType: string
  accessToken: string
  refreshToken: string
  accessExpiresIn: number
  refreshExpiresIn: number
}

export interface UserResponse {
  id: number
  username: string
  nickname: string
  createdAt: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
  pages: number
}

export interface AssetMetadata {
  author?: string | null
  copyright?: string | null
  cameraMake?: string | null
  cameraModel?: string | null
  lensModel?: string | null
  software?: string | null
  dateTaken?: string | null
  orientation?: number | null
  gpsLatitude?: number | null
  gpsLongitude?: number | null
  attributes?: Record<string, string>
}

export interface AssetResponse {
  id: number
  originalFilename: string
  author?: string | null
  contentType: string
  sizeBytes: number
  width?: number | null
  height?: number | null
  sha256: string
  description?: string | null
  characterId?: number | null
  folderId?: number | null
  createdAt: string
  updatedAt: string
}

export interface AssetDetailResponse extends AssetResponse {
  metadata?: AssetMetadata | null
  url: string
  thumbnailUrl?: string | null
}

export interface TrashAssetResponse {
  id: number
  originalFilename: string
  contentType: string
  sizeBytes: number
  width?: number | null
  height?: number | null
  description?: string | null
  createdAt: string
  deletedAt?: string | null
  purgeAt?: string | null
}

export interface AssetUrlResponse {
  url: string
  expiresAt: string
}

export interface AssetSettingsResponse {
  retentionDays: number
}

export interface AgentSearchResult {
  assetId: number
  sha256: string
  originalFilename: string
  confidence: number
  description?: string | null
  tags: string[]
  characterId?: number | null
  characterName?: string | null
}

export interface AgentSearchResponse {
  query: string
  results: AgentSearchResult[]
  comment?: string | null
  aiEnabled: boolean
}

export interface AssetAnalysisResponse {
  assetId: number
  description?: string | null
  tags: string[]
  status: string
  model?: string | null
  error?: string | null
}

export interface CharacterResponse {
  id: number
  name: string
  aliases: string[]
  series?: string | null
  description?: string | null
  autoCreated: boolean
  referenceCount: number
  createdAt: string
}

export interface FolderResponse {
  id: number
  name: string
  description?: string | null
  autoCreated: boolean
  assetCount: number
  createdAt: string
}
