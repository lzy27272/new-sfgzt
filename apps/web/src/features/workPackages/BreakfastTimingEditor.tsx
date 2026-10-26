import type { WorkPackageItemDraft } from './configuration'

export function BreakfastTimingEditor({ item, onChange }: { item: WorkPackageItemDraft; onChange: (patch: Partial<WorkPackageItemDraft>) => void }) {
  const policy = item.executionExtras.breakfastStages
  if (!policy || typeof policy !== 'object' || Array.isArray(policy)) return null
  const stages = policy as Record<string, unknown>
  const update = (next: Record<string, unknown>) => onChange({ executionExtras: { ...item.executionExtras, breakfastStages: { ...stages, ...next } } })
  return <fieldset className="full-field"><legend>早餐三阶段巡查</legend>
    <p>按本店实际早餐营业和在岗时段确认。三阶段合并为一项工作，可分次保存草稿；收尾开始后才能最终提交。</p>
    <div className="form-grid work-rule-grid">{(['opening', 'peak', 'closing'] as const).map((phase, index) => {
      const current = stages[phase] as Record<string, unknown> | undefined
      return <fieldset key={phase}><legend>{['开餐', '高峰', '收尾'][index]}</legend>{(['start', 'end'] as const).map((edge) => <label key={edge}>{edge === 'start' ? '开始' : '结束'}时间<input type="time" value={String(current?.[edge] ?? '')} onChange={event => update({ [phase]: { ...current, [edge]: event.target.value } })} /></label>)}</fieldset>
    })}<label>未结问题复查时间<input type="time" value={String(stages.unresolvedRecheck ?? '')} onChange={event => update({ unresolvedRecheck: event.target.value })} /><small>仅有未解决问题时复查。</small></label>
    <label className="checkbox-label"><input type="checkbox" checked={stages.scheduleConfirmed === true} onChange={event => update({ scheduleConfirmed: event.target.checked })} />已核对本店早餐营业及执行人实际在岗时段</label></div>
    {stages.scheduleConfirmed !== true && <p className="inline-warning">时段未确认，不会生成早餐巡查工作。</p>}
  </fieldset>
}
