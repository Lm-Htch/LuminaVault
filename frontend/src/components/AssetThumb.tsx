import { useEffect, useState } from 'react'
import { fetchAssetDetail } from '../api/assetDetailCache'

interface AssetThumbProps {
  assetId: number
  alt: string
  className?: string
}

export function AssetThumb({ assetId, alt, className }: AssetThumbProps) {
  const [url, setUrl] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    setUrl(null)
    fetchAssetDetail(assetId)
      .then((detail) => {
        if (alive) {
          setUrl(detail.thumbnailUrl ?? detail.url)
        }
      })
      .catch(() => undefined)
    return () => {
      alive = false
    }
  }, [assetId])

  if (!url) {
    return (
      <div className={`flex items-center justify-center bg-stone-100 text-xs text-stone-400 ${className ?? ''}`}>
        无预览
      </div>
    )
  }
  return <img src={url} alt={alt} loading="lazy" className={className} />
}
