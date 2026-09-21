import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import type { RoleContext } from '../../domain'
import { loadDataRetentionPolicy, loadDataRetentionPreview, updateDataRetentionPolicy } from './api'
import {
  buildDataRetentionUpdateRequest,
  dataRetentionDraftFrom,
  formatRetentionBytes,
  formatRetentionTimestamp,
  retentionLimits,
  validateDataRetentionDraft,
  type DataRetentionDraft,
  type DataRetentionPolicy,
  type DataRetentionPreview,
  type RetentionNumericField,
} from './policy'
import styles from './dataRetention.module.css'
import { ImageRetentionPanel } from './ImageRetentionPanel'

type RetentionField = Readonly<{
  key: RetentionNumericField
  label: string
  description: string
  unit: '天' | '个月'
}>

const shortTermFields: readonly RetentionField[] = Object.freeze([
  Object.freeze({ key: 'imageRetentionDays', label: '工作与任务图片', description: '从上传成功起计算；重要证据标记后暂停清理。', unit: '天' }),
  Object.freeze({ key: 'documentRetentionDays', label: 'PDF、Word、Excel 文档', description: '用于短期复核和业务排错。', unit: '天' }),
])

const analysisFields: readonly RetentionField[] = Object.freeze([
  Object.freeze({ key: 'employeeMetricRetentionMonths', label: '员工完成率与工作汇总', description: '保留日、月、年度完成情况及员工成长分析。', unit: '个月' }),
  Object.freeze({ key: 'pmsRetentionMonths', label: 'PMS 标准化历史', description: '支持日、月、年度同比及环比分析。', unit: '个月' }),
  Object.freeze({ key: 'otaRetentionMonths', label: 'OTA 标准化历史', description: '支持平台、门店、房型和营业日维度分析。', unit: '个月' }),
  Object.freeze({ key: 'kpiRetentionMonths', label: '员工 KPI 与规则版本', description: '历史结果不可覆盖，关联当期计算规则。', unit: '个月' }),
  Object.freeze({ key: 'rawPayloadRetentionDays', label: 'PMS / OTA 原始响应', description: '仅用于短期追溯、接口排错和重放核验。', unit: '天' }),
])

function errorMessage(reason: unknown, fallback: string): string {
  if (reason instanceof ApiError && reason.status === 409) return '策略已被其他管理员更新。请刷新后重新确认再保存。'
  return reason instanceof Error ? reason.message : fallback
}

function sameDraft(left: DataRetentionDraft, right: DataRetentionDraft): boolean {
  return left.imageCleanupEnabled === right.imageCleanupEnabled
    && Object.keys(retentionLimits).every((key) => left[key as RetentionNumericField] === right[key as RetentionNumericField])
}

function RetentionFields({
  fields,
  draft,
  errors,
  disabled,
  onChange,
}: {
  fields: readonly RetentionField[]
  draft: DataRetentionDraft
  errors: Partial<Record<RetentionNumericField, string>>
  disabled: boolean
  onChange: (key: RetentionNumericField, value: string) => void
}) {
  return <div className={styles.fieldGrid}>{fields.map((field) => {
    const limits = retentionLimits[field.key]
    const inputId = `retention-${field.key}`
    return <label className={styles.field} htmlFor={inputId} key={field.key}>
      <span><strong>{field.label}</strong><small>{field.description}</small></span>
      <span className={styles.numberControl}>
        <input
          id={inputId}
          inputMode="numeric"
          type="number"
          min={limits.min}
          max={limits.max}
          step="1"
          value={draft[field.key]}
          disabled={disabled}
          aria-invalid={Boolean(errors[field.key])}
          aria-describedby={`${inputId}-help`}
          onChange={(event) => onChange(field.key, event.target.value)}
        />
        <b>{field.unit}</b>
      </span>
      <small className={errors[field.key] ? styles.fieldError : styles.fieldRange} id={`${inputId}-help`}>
        {errors[field.key] ?? `允许范围 ${limits.min}–${limits.max}${field.unit}`}
      </small>
    </label>
  })}</div>
}

function PreviewPanel({ preview, loading, onRefresh }: {
  preview?: DataRetentionPreview
  loading: boolean
  onRefresh: () => void
}) {
  return <article className={styles.previewPanel}>
    <header>
      <div><span>DELETION PREVIEW</span><h2>到期图片候选预览</h2><p>统计上传后已到期、未标记重要证据且尚未删除的图片，包括未审核图片。</p></div>
      <button className="secondary" disabled={loading} onClick={onRefresh}>{loading ? '刷新中…' : '刷新预览'}</button>
    </header>
    <div className={styles.previewNotice}><strong>预览不会删除文件</strong><span>自动清理由后台执行；文件位置或指纹异常时停止删除并记录失败。</span></div>
    <div className={styles.metrics} aria-live="polite">
      <div><span>候选图片</span><strong>{preview ? preview.candidateCount.toLocaleString('zh-CN') : '—'}</strong><small>张</small></div>
      <div><span>预计释放空间</span><strong>{preview ? formatRetentionBytes(preview.totalSizeBytes) : '—'}</strong><small>只读估算</small></div>
      <div><span>最早到期时间</span><strong>{preview ? formatRetentionTimestamp(preview.oldestRetentionUntil) : '—'}</strong><small>候选范围</small></div>
      <div><span>最晚到期时间</span><strong>{preview ? formatRetentionTimestamp(preview.newestRetentionUntil) : '—'}</strong><small>候选范围</small></div>
    </div>
    <footer>预览生成于 {preview ? formatRetentionTimestamp(preview.generatedAt) : '—'} · 租户清理开关：{preview?.cleanupEnabled ? '开启' : '关闭'}</footer>
  </article>
}

export function DataRetentionAdministration({ identity }: { identity: RoleContext }) {
  const [policy, setPolicy] = useState<DataRetentionPolicy>()
  const [preview, setPreview] = useState<DataRetentionPreview>()
  const [draft, setDraft] = useState<DataRetentionDraft>()
  const [loading, setLoading] = useState(true)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string>()
  const [notice, setNotice] = useState<string>()
  const requestGeneration = useRef(0)

  const reload = useCallback(async () => {
    const generation = ++requestGeneration.current
    setLoading(true)
    setError(undefined)
    const [policyResult, previewResult] = await Promise.allSettled([
      loadDataRetentionPolicy(identity),
      loadDataRetentionPreview(identity),
    ])
    if (generation !== requestGeneration.current) return
    const failures: string[] = []
    if (policyResult.status === 'fulfilled') {
      setPolicy(policyResult.value)
      setDraft(dataRetentionDraftFrom(policyResult.value))
    } else failures.push(errorMessage(policyResult.reason, '数据留存策略加载失败'))
    if (previewResult.status === 'fulfilled') setPreview(previewResult.value)
    else failures.push(errorMessage(previewResult.reason, '到期图片预览加载失败'))
    setError(failures.length ? failures.join('；') : undefined)
    setLoading(false)
  }, [identity])

  useEffect(() => {
    void reload()
    return () => { requestGeneration.current += 1 }
  }, [reload])

  const refreshPreview = async () => {
    setPreviewLoading(true)
    setError(undefined)
    try {
      setPreview(await loadDataRetentionPreview(identity))
    } catch (reason) {
      setError(errorMessage(reason, '到期图片预览刷新失败'))
    } finally {
      setPreviewLoading(false)
    }
  }

  const validationErrors = useMemo(
    () => draft ? validateDataRetentionDraft(draft) : {},
    [draft],
  )
  const originalDraft = useMemo(() => policy ? dataRetentionDraftFrom(policy) : undefined, [policy])
  const dirty = Boolean(draft && originalDraft && !sameDraft(draft, originalDraft))
  const invalid = Object.keys(validationErrors).length > 0

  const changeNumber = (key: RetentionNumericField, value: string) => {
    setNotice(undefined)
    setDraft((current) => current ? { ...current, [key]: value } : current)
  }

  const save = async () => {
    if (!draft || !policy || invalid) return
    if (draft.imageCleanupEnabled && (!policy.imageCleanupEnabled || Number(draft.imageRetentionDays) < policy.imageRetentionDays)
      && !window.confirm('保存后，超过图片保存期限且未标记为重要证据的图片将自动清理，无法恢复。确认继续？')) return
    setSaving(true)
    setError(undefined)
    setNotice(undefined)
    try {
      const saved = await updateDataRetentionPolicy(identity, buildDataRetentionUpdateRequest(draft, policy.version))
      setPolicy(saved)
      setDraft(dataRetentionDraftFrom(saved))
      setNotice('数据留存策略已保存。自动清理需同时满足部署开关和租户开关；重要证据不参与清理。')
      await refreshPreview()
    } catch (reason) {
      setError(errorMessage(reason, '数据留存策略保存失败'))
    } finally {
      setSaving(false)
    }
  }

  const reset = () => {
    if (!originalDraft) return
    setDraft(originalDraft)
    setError(undefined)
    setNotice(undefined)
  }

  return <section className={`page-section ${styles.page}`}>
    <header className="page-title">
      <div><span className="eyebrow">DATA GOVERNANCE</span><h1>数据留存与清理预览</h1><p>短期保留原始证据，长期保存员工完成率、PMS、OTA 与 KPI 结构化历史。</p></div>
      <div className="page-actions"><span className="source-flag api">管理员专属 · 实时策略</span></div>
    </header>

    <div className={styles.executionGate} role="status">
      <span>安全门禁</span>
      <div><strong>双重开关控制，仅清理到期普通图片</strong><p>部署执行器与租户开关均开启才会删除。图片按上传成功时间计时；重要证据须在清理前标记保留。工作记录、数据和报告不随图片删除。</p></div>
    </div>

    {notice && <div className="inline-success page-error" role="status">{notice}</div>}
    {error && <div className="inline-error page-error" role="alert">{error}</div>}

    {loading && !draft ? <div className="state-card"><div className="spinner" /><strong>正在并行读取留存策略与清理预览</strong></div> : draft && policy ? <>
      <div className={styles.layout}>
        <div className={styles.formStack}>
          <article className={styles.policyPanel}>
            <header><div><span>SHORT-TERM EVIDENCE</span><h2>短期原始证据</h2><p>图片从上传成功起滚动保留，未审核图片同样计时；文档不参与本次自动清理。</p></div></header>
            <RetentionFields fields={shortTermFields} draft={draft} errors={validationErrors} disabled={saving} onChange={changeNumber} />
          </article>

          <article className={styles.policyPanel}>
            <header><div><span>LONG-TERM ANALYTICS</span><h2>长期结构化分析数据</h2><p>员工指标、PMS、OTA 和 KPI 独立配置，历史快照与规则版本长期可追溯。</p></div></header>
            <RetentionFields fields={analysisFields} draft={draft} errors={validationErrors} disabled={saving} onChange={changeNumber} />
          </article>

          <article className={styles.cleanupPanel}>
            <div><span>IMAGE CLEANUP</span><h2>图片自动清理开关</h2><p>执行器已部署时，开启并保存后将自动清理到期普通图片。删除不可恢复。</p></div>
            <label className={styles.cleanupToggle}>
              <input
                type="checkbox"
                checked={draft.imageCleanupEnabled}
                disabled={saving}
                onChange={(event) => {
                  setNotice(undefined)
                  setDraft({ ...draft, imageCleanupEnabled: event.target.checked })
                }}
              />
              <span><strong>{draft.imageCleanupEnabled ? '开启' : '当前关闭'}</strong><small>{draft.imageCleanupEnabled ? '保存后允许执行器清理；重要证据除外' : '不会删除，保持只读预览'}</small></span>
            </label>
          </article>

          <div className={styles.formActions}>
            <div><strong>策略版本 V{policy.version}</strong><small>最近更新：{formatRetentionTimestamp(policy.updatedAt)}{policy.updatedBy ? ` · ${policy.updatedBy}` : ''}</small></div>
            <button className="secondary" disabled={!dirty || saving} onClick={reset}>撤销未保存修改</button>
            <button className="primary" disabled={!dirty || invalid || saving} onClick={() => void save()}>{saving ? '保存中…' : '保存留存策略'}</button>
          </div>
        </div>

        <PreviewPanel preview={preview} loading={previewLoading} onRefresh={() => void refreshPreview()} />
      </div>
      <ImageRetentionPanel identity={identity} revision={policy.version} />
    </> : <div className="state-card error-state"><b>!</b><strong>留存策略暂不可用</strong><span>请检查服务端状态后重试。</span><button className="secondary" onClick={() => void reload()}>重新加载</button></div>}
  </section>
}
