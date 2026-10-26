import type { ApiIdentity } from '../../api/client'
import { useFinanceResource } from './useFinanceResource'
import { errorMessage } from './api'
import { supplyRows, supplyText } from './supplyModel'
import type { FinanceRow } from './unifiedModel'
import s from './finance.module.css'

export type FinanceProjectSelection = { projectKind?: string; projectId?: string }
/** Only real authorized approved-contract / CIP identities; no project-name free text and no inferred project. */
export default function FinanceProjectPicker({ identity, hotelId, value, onChange, allowAll = false }: { identity: ApiIdentity; hotelId: string; value: FinanceProjectSelection; onChange: (value: FinanceProjectSelection) => void; allowAll?: boolean }) {
 const projects = useFinanceResource<FinanceRow>(identity, hotelId ? `/projects/choices?hotelId=${encodeURIComponent(hotelId)}` : undefined)
 const choices = supplyRows(projects.data?.items), selected = allowAll && value.projectKind === 'ALL' ? 'ALL' : !value.projectKind || value.projectKind === 'NONE' ? 'NONE' : `${value.projectKind}:${value.projectId ?? ''}`
 const present = choices.some(p => `${p.kind}:${p.id}` === selected)
 return <label>真实项目关联（可不关联，不猜项目）<select value={selected} disabled={!hotelId || projects.loading} onChange={event => { if (allowAll && event.target.value === 'ALL') { onChange({ projectKind: 'ALL', projectId: undefined }); return } const project = choices.find(p => `${p.kind}:${p.id}` === event.target.value); onChange(project ? { projectKind: String(project.kind), projectId: String(project.id) } : { projectKind: 'NONE', projectId: undefined }) }}>{allowAll && <option value="ALL">门店/部门总预算（包含所有真实项目及未关联项）</option>}<option value="NONE">未关联项目（不会动用某个项目专用额度）</option>{selected !== 'NONE' && selected !== 'ALL' && !present && <option value={selected}>原冻结项目：当前不在可选清单，须重新核对</option>}{choices.map(project => <option key={`${project.kind}:${project.id}`} value={`${project.kind}:${project.id}`}>{project.kind === 'CONTRACT' ? '真实已批合同' : '既有在建卡'} · {supplyText(project, 'name')}</option>)}</select>{projects.data?.complete === false && <span role="status" className={s.warning}>项目清单未完整返回，当前仅显示服务端可见部分；不会把未列出的项目视为不存在或按名称新建。</span>}{!!projects.error && <span role="alert" className={s.error}>{errorMessage(projects.error)}</span>}</label>
}
