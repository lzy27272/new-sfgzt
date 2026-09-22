import type { DailyReportDetail } from './types'
import type { UsageCatalogue } from '../storeResources/workUsage'
import { DailyUsageFields } from '../storeResources/DailyUsageFields'
import { DailyReportAutoSummary } from './DailyReportAutoSummary'
import { PlannedHygieneFields } from './PlannedHygieneFields'
import { featureStyles, formatLocalDateTime } from '../shared/FeatureUI'
import styles from './housekeepingReport.module.css'

export function HousekeepingReportForm({ report, values, editable, busy, catalogue, inventoryValue, notice, message, conflict,
  missingRequiredCount, setValue, onRefreshCatalogue, onLegacyDraft, onSave, onSubmit, children }: {
  report: DailyReportDetail; values: Record<string,string>; editable: boolean; busy: boolean; catalogue?: UsageCatalogue;
  inventoryValue: unknown; notice?: string; message?: string; conflict: boolean; missingRequiredCount: number;
  setValue: (key: string, value: string) => void; onRefreshCatalogue: () => void; onLegacyDraft: () => void;
  onSave: () => void; onSubmit: () => void; children?: React.ReactNode
}) {
  const manualItems = report.currentRevision.sections.filter(section => section.applicable).flatMap(section => section.items.filter(item => item.dataSource !== 'SYSTEM'))
  return <div className={styles.layout}><main className={styles.main}>
    <details className={styles.fold}><summary><strong>当日工作自动汇总</strong><span>已提交工作自动带入，无需重复填写。</span></summary><DailyReportAutoSummary report={report} section="work" /></details>
    {(catalogue || report.roomCounts) && <section className={styles.card}><h2>做房数量</h2>{editable && catalogue
      ? <fieldset disabled={busy} className={styles.rooms}><label>退房打扫（间）<input type="number" inputMode="numeric" min="0" step="1" value={values._checkoutRooms ?? ''} onChange={event => setValue('_checkoutRooms', event.target.value)} /></label><label>续住打扫（间）<input type="number" inputMode="numeric" min="0" step="1" value={values._stayoverRooms ?? ''} onChange={event => setValue('_stayoverRooms',event.target.value)} /></label></fieldset>
      : <p>退房打扫 {report.roomCounts?.checkoutRooms ?? '未登记'} 间 · 续住打扫 {report.roomCounts?.stayoverRooms ?? '未登记'} 间</p>}</section>}
    {editable && catalogue ? <section className={styles.card}>
      {report.inventoryContext?.legacyDraft && <div className="inline-warning">原日清入口还有未提交草稿，请先完成原草稿，再返回引用同一份用量。<button className="secondary" onClick={onLegacyDraft}>继续原草稿</button></div>}
      <DailyUsageFields catalogue={catalogue} value={inventoryValue} disabled={busy || report.inventoryContext?.legacyDraft} onChange={value => setValue('_inventory', JSON.stringify(value))} onRefresh={onRefreshCatalogue} />
    </section> : <DailyReportAutoSummary report={report} section="usage" />}
    {(editable ? report.plannedHygieneSupported : report.plannedHygiene != null) && <div className={styles.hygiene}><PlannedHygieneFields value={editable ? { content: values._plannedHygieneContent, roomNumbers: values._plannedHygieneRoomNumbers } : report.plannedHygiene} disabled={busy} onChange={editable ? value => {
      setValue('_plannedHygieneContent', value.content ?? ''); setValue('_plannedHygieneRoomNumbers', value.roomNumbers ?? '')
    } : undefined} /></div>}
    <section className={styles.card} aria-label="其他工作与补充说明"><header><h2>其他工作与补充说明</h2>{!manualItems.some(item => item.required) && <span>选填</span>}</header>
      {!editable && <p className={styles.readonly}>日报已提交或当前任职无编辑权限，原始填报内容只读。需要修改时发起修订，不覆盖历史版本。</p>}
      {manualItems.map((item, index) => <div key={item.id} className={styles.manualItem}><label>
        <span className={manualItems.length === 1 ? styles.srOnly : undefined}>{manualItems.length === 1 ? '其他工作与补充说明' : item.label}{item.required ? '（必填）' : ''}</span>
        {item.systemValue !== undefined && <small>系统预填：{item.systemValue}</small>}
        <textarea rows={4} disabled={!editable || busy} value={values[item.id] ?? ''} placeholder={index === 0 ? '填写尚未记录的工作、异常或交接事项' : item.description} onChange={event => setValue(item.id,event.target.value)} />
      </label>{item.evidenceRequired && <small>需要证据</small>}{item.exception && !editable && <small>历史记录含异常标记</small>}{item.evidence.map(evidence => <p key={evidence.id}>{evidence.fileName} · {evidence.invalidatedAt ? '已失效，历史保留' : evidence.scanStatus || '已上传'}</p>)}</div>)}
      {!manualItems.length && <p>当前模板未配置手工补充字段，请联系管理员核对模板。</p>}<small>已记录的工作无需重复填写</small>
    </section>
    <details className={styles.fold}><summary><strong>AI 汇总与建议</strong><span>依据已提交工作与用量记录汇总<br />仅供参考，不代替人工审核</span></summary><DailyReportAutoSummary report={report} section="ai" /></details>
    {notice && <div className={styles.saved} role="status">{notice}<small>提交后保留原始记录，修改需发起修订。</small></div>}
    {message && <div className={conflict ? featureStyles.conflict : 'inline-error'} role="alert">{message}</div>}
    {editable && <section className={styles.submit}><div><button className="secondary" disabled={busy} onClick={onSave}>{busy ? '处理中…' : '保存草稿'}</button>{report.allowedActions.includes('SUBMIT') && <button className="primary" disabled={busy || missingRequiredCount > 0} onClick={onSubmit}>{busy ? '处理中…' : '提交日报'}</button>}</div>{report.blockedActionReasons.SUBMIT && <p>{report.blockedActionReasons.SUBMIT}</p>}</section>}
  </main><aside className={styles.side}><details className={styles.fold}><summary><strong>日报记录与修订</strong><span>截止：{formatLocalDateTime(report.dueAt)}</span></summary><div className={styles.history}><p>历史模板与已提交证据保留，合并页面不改变历史版本。</p>{report.revisions.map(revision => <p key={revision.id}>修订 {revision.revisionNo} · {revision.status === 'DRAFT' ? '草稿' : revision.status === 'SUBMITTED' ? '已提交' : revision.status === 'APPROVED' ? '已通过' : '已退回'}<small>{formatLocalDateTime(revision.submittedAt)} {revision.correctionReason}</small></p>)}</div></details>{children}</aside></div>
}
