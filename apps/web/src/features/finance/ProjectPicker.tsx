import type { ApiIdentity } from '../../api/client'
import FinanceProjectPicker from './FinanceProjectPicker'

export function ProjectPicker({ identity, hotelId, kind, projectId, allowAll = false, onChange }: { identity: ApiIdentity; hotelId: string; kind?: string; projectId?: string | null; allowAll?: boolean; onChange: (kind: string, id: string | null) => void }) {
 // Prop adapter only: all consumers share the same actual source fetch, permission and NONE/ALL semantics.
 return <FinanceProjectPicker identity={identity} hotelId={hotelId} value={{ projectKind: kind, projectId: projectId ?? undefined }} allowAll={allowAll} onChange={value => onChange(value.projectKind ?? 'NONE', value.projectId ?? null)} />
}
