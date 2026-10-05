import { api } from './client'
import type { AssetDetailResponse } from './types'

const cache = new Map<number, Promise<AssetDetailResponse>>()

export function fetchAssetDetail(id: number, force = false): Promise<AssetDetailResponse> {
  if (force || !cache.has(id)) {
    cache.set(id, api.get<AssetDetailResponse>(`/api/assets/${id}`))
  }
  const promise = cache.get(id)!
  promise.catch(() => cache.delete(id))
  return promise
}

export function invalidateAssetDetail(id: number): void {
  cache.delete(id)
}
