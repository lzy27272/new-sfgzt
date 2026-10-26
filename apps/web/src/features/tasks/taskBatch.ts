export const MAX_BATCH_TASKS = 50

export type TaskAssignment = {
  id: string; employeeName: string; positionName: string; levelCode?: string
  orgUnitId: string; orgName: string; hotelId?: string; hotelName?: string
}

export type TaskReferenceFile = { id: string; file: File }
export type TaskBatchRow = {
  id: string; assigneeAssignmentId: string; content: string
  referenceNote: string; referenceFiles: TaskReferenceFile[]
}

export function newTaskRow(content = ''): TaskBatchRow {
  return { id: crypto.randomUUID(), assigneeAssignmentId: '', content, referenceNote: '', referenceFiles: [] }
}

export function localDateTimeInput(date: Date): string {
  const part = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${part(date.getMonth() + 1)}-${part(date.getDate())}T${part(date.getHours())}:${part(date.getMinutes())}`
}

export function taskTitle(content: string, prefix = ''): string {
  return Array.from(`${prefix.trim()}${content.trim().split(/\r?\n/)[0].trim()}`).slice(0, 240).join('')
}

export function taskBatchErrors(
  rows: TaskBatchRow[], hotelId: string, assignments: TaskAssignment[], locked = new Set<string>(),
  reviewerAssignmentId = '',
): Record<string, string> {
  const people = new Map(assignments.map(person => [person.id, person]))
  const errors: Record<string, string> = {}
  for (const row of rows) {
    if (locked.has(row.id)) continue
    const person = people.get(row.assigneeAssignmentId)
    if (!person || (person.hotelId ?? person.orgUnitId) !== hotelId) errors[row.id] = '请选择当前门店的有效执行人员。'
    else if (!row.content.trim()) errors[row.id] = '请填写任务内容。'
    else if (row.assigneeAssignmentId === reviewerAssignmentId) errors[row.id] = '执行人员与验收负责人不能相同，请调整统一设置中的验收人。'
  }
  return errors
}

export type TaskDelivery = {
  input: Record<string, unknown>
  createIdempotencyKey: string
  dispatchIdempotencyKey: string
  pendingFiles: TaskReferenceFile[]
  task?: { id: string; version: number }
  dispatched: boolean
  uncertainCreate: boolean
  editable: boolean
}

export type TaskDeliveryStage = 'creating' | 'uploading' | 'dispatching' | 'dispatched'
export type TaskDeliveryPorts = {
  create: (input: Record<string, unknown>, key: string) => Promise<Record<string, unknown>>
  upload: (taskId: string, file: File) => Promise<unknown>
  dispatch: (taskId: string, version: number, key: string) => Promise<unknown>
}

export function newTaskDelivery(row: TaskBatchRow, input: Record<string, unknown>): TaskDelivery {
  return {
    input: structuredClone(input), createIdempotencyKey: `create:${row.id}`, dispatchIdempotencyKey: `dispatch:${row.id}`,
    pendingFiles: [...row.referenceFiles], dispatched: false, uncertainCreate: false, editable: false,
  }
}

// Each row keeps its own immutable request and resume position. A retry skips
// created drafts, uploaded references and dispatched rows independently.
export async function deliverTask(
  delivery: TaskDelivery, ports: TaskDeliveryPorts, onStage: (stage: TaskDeliveryStage) => void,
): Promise<void> {
  if (delivery.dispatched) return
  if (!delivery.task) {
    onStage('creating')
    try {
      const result = await ports.create(delivery.input, delivery.createIdempotencyKey)
      const id = result.id
      const version = Number(result.row_version ?? result.rowVersion ?? result.version ?? 0)
      if (typeof id !== 'string' || !id || !Number.isSafeInteger(version) || version < 0) {
        delivery.uncertainCreate = true
        throw new Error('服务端未返回完整任务信息，请直接重试以确认创建结果。')
      }
      delivery.task = { id, version }
    } catch (reason) {
      const status = reason && typeof reason === 'object' && 'status' in reason ? Number(reason.status) : 0
      const rejected = [400, 403, 404, 422].includes(status)
      delivery.editable = rejected && !delivery.uncertainCreate
      if (!rejected) delivery.uncertainCreate = true
      throw reason
    }
  }
  while (delivery.pendingFiles.length) {
    onStage('uploading')
    await ports.upload(delivery.task.id, delivery.pendingFiles[0].file)
    delivery.pendingFiles.shift()
  }
  onStage('dispatching')
  await ports.dispatch(delivery.task.id, delivery.task.version, delivery.dispatchIdempotencyKey)
  delivery.dispatched = true
  onStage('dispatched')
}
