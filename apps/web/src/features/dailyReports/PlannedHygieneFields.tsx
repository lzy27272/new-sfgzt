import { useId } from 'react'
import type { PlannedHygiene } from './types'
import { featureStyles as styles } from '../shared/FeatureUI'
import editorStyles from './editor.module.css'

export function PlannedHygieneFields({ value, onChange, disabled = false }: {
  value?: PlannedHygiene
  onChange?: (value: PlannedHygiene) => void
  disabled?: boolean
}) {
  const labelId = useId()
  return <section className={styles.section} aria-label="计划卫生">
    <header><h2>计划卫生</h2></header>
    {onChange ? <>
      <p className={styles.meta}>选填：记录当日计划卫生内容及对应房间号；当天没有安排可留空。</p>
      <fieldset className={styles.formGrid} disabled={disabled}>
        <label><span id={`${labelId}-content`}>计划卫生内容（选填）</span><textarea aria-labelledby={`${labelId}-content`} rows={3} maxLength={2000} value={value?.content ?? ''}
          placeholder="例如：清洗空调滤网、清洁窗帘及床底"
          onChange={event => onChange({ ...value, content: event.target.value })} /></label>
        <label><span id={`${labelId}-rooms`}>计划卫生房间号（选填）</span><textarea aria-labelledby={`${labelId}-rooms`} rows={3} maxLength={1000} value={value?.roomNumbers ?? ''}
          placeholder="可填多个房号，例如：1201、1202、1203"
          onChange={event => onChange({ ...value, roomNumbers: event.target.value })} /></label>
      </fieldset>
    </> : <div className={styles.formGrid}>
      <div><strong>计划卫生内容</strong><p className={editorStyles.plannedHygieneText}>{value?.content?.trim() || '未填写'}</p></div>
      <div><strong>计划卫生房间号</strong><p className={editorStyles.plannedHygieneText}>{value?.roomNumbers?.trim() || '未填写'}</p></div>
    </div>}
  </section>
}
