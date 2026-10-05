import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api } from '../api/client'
import { useConfirm } from '../components/ConfirmProvider'
import { useToast } from '../components/ToastProvider'
import type { CharacterResponse } from '../api/types'
import { formatDate } from '../utils/format'

export function CharactersPage() {
  const confirm = useConfirm()
  const toast = useToast()
  const [characters, setCharacters] = useState<CharacterResponse[]>([])
  const [form, setForm] = useState({ name: '', aliases: '', series: '', description: '' })
  const [busy, setBusy] = useState(false)
  const [bootstrapping, setBootstrapping] = useState(false)
  const [autoCreate, setAutoCreate] = useState(false)

  const load = useCallback(async () => {
    try {
      const [list, settings] = await Promise.all([
        api.get<CharacterResponse[]>('/api/characters'),
        api.get<{ autoCreate: boolean }>('/api/characters/settings'),
      ])
      setCharacters(list)
      setAutoCreate(settings.autoCreate)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '加载失败')
    }
  }, [toast])

  useEffect(() => {
    void load()
  }, [load])

  const create = async (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    try {
      await api.post('/api/characters', {
        name: form.name.trim(),
        aliases: form.aliases
          .split(/[,，]/)
          .map((alias) => alias.trim())
          .filter(Boolean),
        series: form.series.trim() || undefined,
        description: form.description.trim() || undefined,
      })
      setForm({ name: '', aliases: '', series: '', description: '' })
      toast.success('角色已创建，可在图片详情页标记并添加参考')
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '创建失败')
    } finally {
      setBusy(false)
    }
  }

  const toggleAutoCreate = async (value: boolean) => {
    setAutoCreate(value)
    try {
      const settings = await api.put<{ autoCreate: boolean }>('/api/characters/settings', { autoCreate: value })
      setAutoCreate(settings.autoCreate)
      toast.success(value ? '已开启：分析图片时自动创建并关联角色' : '已关闭自动创建（仅关联已有角色）')
    } catch (err) {
      setAutoCreate(!value)
      toast.error(err instanceof Error ? err.message : '设置失败')
    }
  }

  const bootstrap = async () => {
    setBootstrapping(true)
    try {
      const created = await api.post<number>('/api/characters/bootstrap')
      toast.success(created > 0 ? `已从模型标签创建 ${created} 个角色，可重命名或删除` : '没有新的角色标签需要创建')
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '批量创建失败')
    } finally {
      setBootstrapping(false)
    }
  }

  const remove = async (character: CharacterResponse) => {
    const confirmed = await confirm({
      title: '删除角色',
      message: `删除角色「${character.name}」？相关图片的标记会被清除，参考向量一并删除。`,
      confirmText: '删除',
      danger: true,
    })
    if (!confirmed) {
      return
    }
    try {
      await api.del(`/api/characters/${character.id}`)
      toast.success('已删除')
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '删除失败')
    }
  }

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)]">
      <form onSubmit={create} className="card h-fit p-5">
        <h2 className="font-medium text-stone-800">新建角色</h2>
        <label className="mt-4 block">
          <span className="label">角色名</span>
          <input
            value={form.name}
            onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
            placeholder="如：初音未来"
            className="input"
          />
        </label>
        <label className="mt-4 block">
          <span className="label">别名（逗号分隔）</span>
          <input
            value={form.aliases}
            onChange={(event) => setForm((current) => ({ ...current, aliases: event.target.value }))}
            placeholder="miku, hatsune miku"
            className="input"
          />
        </label>
        <label className="mt-4 block">
          <span className="label">作品 / 系列</span>
          <input
            value={form.series}
            onChange={(event) => setForm((current) => ({ ...current, series: event.target.value }))}
            placeholder="如：VOCALOID"
            className="input"
          />
        </label>
        <label className="mt-4 block">
          <span className="label">描述（外貌特征，可选）</span>
          <textarea
            value={form.description}
            onChange={(event) => setForm((current) => ({ ...current, description: event.target.value }))}
            rows={3}
            className="input resize-none"
            placeholder="如：蓝绿色双马尾、蓝色眼睛、领带制服"
          />
        </label>
        <button type="submit" disabled={busy || !form.name.trim()} className="btn-primary mt-4">
          {busy ? '创建中…' : '创建角色'}
        </button>
        <p className="mt-3 text-xs text-stone-400">
          创建后在图片详情页「标记角色」即可添加参考向量，之后上传相似图片会自动匹配
        </p>
      </form>

      <div>
        <div className="mb-3 flex items-start justify-between gap-3">
          <p className="text-sm text-stone-500">
            自动创建角色时，模型标签会转换为角色名并标记为「自动」，可随时改名或删除
          </p>
          <div className="flex shrink-0 items-center gap-3">
            <label className="flex cursor-pointer items-center gap-2 text-sm text-stone-600">
              <input
                type="checkbox"
                className="h-4 w-4 accent-teal-700"
                checked={autoCreate}
                onChange={(event) => void toggleAutoCreate(event.target.checked)}
              />
              自动创建角色
            </label>
            <button
              type="button"
              className="btn-secondary btn-sm"
              disabled={bootstrapping}
              onClick={() => void bootstrap()}
            >
              {bootstrapping ? '创建中…' : '从标签批量创建'}
            </button>
          </div>
        </div>
        <div className="card overflow-hidden">
          <table className="w-full text-left text-sm">
            <thead className="bg-stone-50 text-xs uppercase tracking-wide text-stone-400">
              <tr>
                <th className="px-5 py-3 font-medium">角色</th>
                <th className="px-5 py-3 font-medium">别名</th>
                <th className="px-5 py-3 font-medium">作品</th>
                <th className="px-5 py-3 font-medium">参考</th>
                <th className="px-5 py-3 font-medium">创建时间</th>
                <th className="px-5 py-3 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-stone-100">
              {characters.map((character) => (
                <tr key={character.id} className="transition hover:bg-stone-50/70">
                  <td className="px-5 py-3.5 font-medium text-stone-800">
                    {character.name}
                    {character.autoCreated && <span className="chip ml-2">自动</span>}
                  </td>
                  <td className="px-5 py-3.5 text-stone-500">
                    {character.aliases.length > 0 ? character.aliases.join(' / ') : '-'}
                  </td>
                  <td className="px-5 py-3.5 text-stone-500">{character.series || '-'}</td>
                  <td className="px-5 py-3.5 text-stone-500">{character.referenceCount}</td>
                  <td className="px-5 py-3.5 text-stone-500">{formatDate(character.createdAt)}</td>
                  <td className="px-5 py-3.5 text-right">
                    <button type="button" onClick={() => void remove(character)} className="btn-danger btn-sm">
                      删除
                    </button>
                  </td>
                </tr>
              ))}
              {characters.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-5 py-16 text-center text-stone-400">
                    还没有角色，先在左侧创建一个
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
