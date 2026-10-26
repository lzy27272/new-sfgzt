import { useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import type { AppNavigate } from '../../app/routeConfig'
import type { RouteParams } from '../../domain'
import { FinanceFacts, FinanceMutation, FinanceTable } from './FinanceData'
import { useFinanceResource } from './useFinanceResource'
import { financeRequest, financeDownload, errorMessage } from './api'
import { supplyRows, supplyText as txt, type SupplyRow } from './supplyModel'
import s from './finance.module.css'

export default function FinanceAssetGains({ identity, hotelId, gainId, can, go, params }: { identity: ApiIdentity; hotelId: string; gainId: string; can: (p: string) => boolean; go: AppNavigate; params: RouteParams }) {
 const detail = useFinanceResource<SupplyRow>(identity, gainId ? `/supply/asset-gains/${gainId}` : undefined)
 const actualHotel = gainId ? txt(detail.data ?? {}, 'hotelId') : hotelId
 const context = useFinanceResource<SupplyRow>(identity, actualHotel ? `/supply/asset-gains/context?hotelId=${actualHotel}` : undefined)
 const list = useFinanceResource<SupplyRow>(identity, actualHotel ? `/supply/asset-gains?hotelId=${actualHotel}` : undefined)
 const nav = (patch: Record<string, string | undefined>) => go('finance', { ...params, tab: 'supply', screen: 'asset-gains', ...patch })
 return <section className={s.panel}><h3>实物盘盈 · 原件确证与独立财务核实</h3><p className={s.warning}>{txt(context.data ?? {}, 'notice', '不是实物新增后自动认收入，也不是再次采购或重复费用。')}</p>{[detail.error, context.error, list.error].filter(Boolean).map((error, i) => <p key={i} role="alert" className={s.error}>{errorMessage(error)}</p>)}
  <FinanceTable rows={supplyRows(list.data?.items)} label="真实盘盈估值申请" columns={[{ key: 'physicalName', label: '原实物资产' }, { key: 'occurredOn', label: '发现确认日' }, { key: 'accountingOn', label: '记账日' }, { key: 'value', label: '独立待核估值' }, { key: 'status', label: '估值审核' }, { key: 'voucherStatus', label: '实际会计状态' }]} onOpen={row => nav({ gainId: txt(row, 'id'), hotelId: txt(row, 'hotelId') })} />
  {actualHotel && can('finance.accounting.write') && <details><summary>选择真实尚无财务卡的实物，建立盘盈估值草稿</summary><GainDraft key={actualHotel} identity={identity} data={context.data ?? {}} onSaved={row => nav({ gainId: txt(row, 'id'), hotelId: txt(row, 'hotelId') })} /></details>}
  {detail.data && <GainDetail key={`${gainId}:${detail.data.version}`} identity={identity} data={detail.data} context={context.data ?? {}} can={can} go={go} onCard={row => go('finance', { tab: 'supply', screen: 'assets', hotelId: actualHotel, assetId: txt(row, 'id') })} />}
 </section>
}
function GainDraft({ identity, data, onSaved }: { identity: ApiIdentity; data: SupplyRow; onSaved: (row: SupplyRow) => void }) {
 const [bookId, setBook] = useState(''), accounts = supplyRows(data.accounts).filter(a => a.bookId === bookId)
 const choices = (rows: SupplyRow[], label: string) => rows.map(row => ({ value: txt(row, 'id'), label: txt(row, label) }))
 return <><label>真实法人账套<select value={bookId} onChange={e => setBook(e.target.value)}><option value="">明确选择，不默认第一账套</option>{supplyRows(data.books).map(b => <option key={txt(b, 'id')} value={txt(b, 'id')}>{txt(b, 'name')}</option>)}</select></label>{bookId && <FinanceMutation key={bookId} identity={identity} path="/supply/asset-gains" label="保存真实估值草稿，随后上传原件并提交独立核实" fields={[
  { key: 'physicalAssetId', label: '本店真实实物（有财务卡/期初/原资本来源不可重复）', type: 'select', required: true, options: choices(supplyRows(data.assets), 'name') },
  { key: 'departmentId', label: '确证的实际使用/责任部门', type: 'select', required: true, options: choices(supplyRows(data.departments), 'name') },
  { key: 'financialCategoryCode', label: '本店已独立发布资产类别', type: 'select', required: true, options: supplyRows(data.categories).filter(c => c.treatment === 'ASSET').map(c => ({ value: txt(c, 'code'), label: txt(c, 'name') })) },
  { key: 'occurredOn', label: '实际发现及估值确认日', type: 'date', required: true }, { key: 'accountingOn', label: '本次开放期记账日', type: 'date', required: true }, { key: 'value', label: '有原件依据的确认价值（精确到分，不自动估价）', type: 'number', required: true },
  { key: 'assetAccount', label: '真实资产借方科目', type: 'select', required: true, options: accounts.filter(a => a.category === 'ASSET').map(a => ({ value: txt(a, 'code'), label: `${txt(a, 'code')} · ${txt(a, 'name')}` })) },
  { key: 'creditAccount', label: '独立政策确定的权益/收益贷方科目（不自动判断合规）', type: 'select', required: true, options: accounts.filter(a => ['EQUITY', 'REVENUE'].includes(String(a.category))).map(a => ({ value: txt(a, 'code'), label: `${txt(a, 'code')} · ${txt(a, 'name')}` })) },
  { key: 'valuationBasis', label: '实际盘点/估值依据、价值构成和责任确认', type: 'textarea', required: true }, { key: 'policyReference', label: '本主体实际财务处理政策依据（不由系统代选）', type: 'textarea', required: true },
 ]} build={(v, requestId) => ({ ...v, requestId, bookId })} onSuccess={onSaved} />}</>
}
function GainDetail({ identity, data, context, can, go, onCard }: { identity: ApiIdentity; data: SupplyRow; context: SupplyRow; can: (p: string) => boolean; go: AppNavigate; onCard: (row: SupplyRow) => void }) {
 const own = data.applicantId === identity.actorId, review = data.reviewerId === identity.actorId && !own && can('finance.review') && can('finance.accounting.write')
 const accounts = supplyRows(context.accounts).filter(a => a.bookId === data.bookId), options = (category: string) => accounts.filter(a => a.category === category).map(a => ({ value: txt(a, 'code'), label: `${txt(a, 'code')} · ${txt(a, 'name')}` }))
 return <article><h4>原估值快照 · {txt(data, 'id')}</h4><FinanceFacts data={data} labels={{ status: '独立估值审核', occurredOn: '真实发生日', accountingOn: '实际入账日', value: '确证价值', departmentId: '实际部门', financialCategoryCode: '原财务类别', valuationBasis: '原估值依据', policyReference: '原财务政策依据', reviewNote: '独立意见' }} />
  <GainOriginals identity={identity} data={data} writable={own && data.status === 'DRAFT' && can('finance.accounting.write')} />
  {own && data.status === 'DRAFT' && <FinanceMutation identity={identity} path={`/supply/asset-gains/${data.id}/submit`} label="冻结原件快照，提交配置独立财务核实" fields={[{ key: 'note', label: '原件与估值提交说明', required: true }]} build={(v, requestId) => ({ ...v, requestId, expectedVersion: data.version })} />}
  {own && ['DRAFT', 'PENDING'].includes(String(data.status)) && <FinanceMutation identity={identity} path={`/supply/asset-gains/${data.id}/cancel`} label="撤回未确认原申请（保留历史；更正须另建新申请）" fields={[{ key: 'note', label: '撤回原因', required: true }]} build={(v, requestId) => ({ ...v, requestId, expectedVersion: data.version })} />}
  {review && data.status === 'PENDING' && <FinanceMutation identity={identity} path={`/supply/asset-gains/${data.id}/review`} label="独立核实原实物、估值原件和本主体政策" fields={[{ key: 'approved', label: '独立核实决定', type: 'select', required: true, options: [{ value: 'true', label: '通过：仅生成真实会计草稿，不自动过账/现金' }, { value: 'false', label: '拒绝：保留原估值和原件历史' }] }, { key: 'note', label: '实际独立复核依据，不冒充外部估值专家', type: 'textarea', required: true }]} build={(v, requestId) => ({ ...v, requestId, expectedVersion: data.version, approved: v.approved === 'true' })} />}
  <FinanceTable rows={supplyRows(data.source)} label="真实盘盈来源与独立过账" columns={[{ key: 'status', label: '会计来源状态' }, { key: 'voucherStatus', label: '真实凭证状态' }, { key: 'reversedById', label: '红冲依赖' }, { key: 'open', label: '会计核对', render: row => <button type="button" onClick={() => go('finance', { tab: 'accounting', section: 'vouchers', bookId: txt(data, 'bookId'), voucherId: txt(row, 'voucherId') })}>打开真实凭证</button> }]} />
  <FinanceTable rows={supplyRows(data.cards)} label="原实物财务卡（不重复确认收入/购置）" columns={[{ key: 'assetCode', label: '财务卡编号' }, { key: 'status', label: '后续财务状态' }]} onOpen={onCard} />
  {data.status === 'APPROVED' && can('finance.accounting.write') && !supplyRows(data.cards).length && <details><summary>实际独立过账后，承接同一实物财务卡</summary><FinanceMutation identity={identity} path={`/supply/asset-gains/${data.id}/card`} label="核验已过账原值并承接卡片，不新增会计费用或资金" fields={[{ key: 'assetCode', label: '真实财务卡编号', required: true }, { key: 'residualValue', label: '独立确认残值', type: 'number', required: true }, { key: 'usefulMonths', label: '独立确认未来使用月数（整数）', type: 'number', required: true }, { key: 'inServiceDate', label: '本次财务承接投用日（不早于盘盈确认）', type: 'date', required: true }, { key: 'cipAccount', label: '本账套在建科目（后续统一卡生命周期）', type: 'select', required: true, options: options('ASSET') }, { key: 'depreciationAccount', label: '累计折旧科目', type: 'select', required: true, options: options('ASSET') }, { key: 'expenseAccount', label: '折旧费用科目', type: 'select', required: true, options: options('EXPENSE') }]} build={(v, requestId) => ({ ...v, requestId, usefulMonths: Number(v.usefulMonths) })} onSuccess={onCard} /></details>}
 </article>
}
function GainOriginals({ identity, data, writable }: { identity: ApiIdentity; data: SupplyRow; writable: boolean }) {
 const [file, setFile] = useState<File>(), [busy, setBusy] = useState(false), [error, setError] = useState(''), [attachmentId, setAttachmentId] = useState(() => crypto.randomUUID())
 return <section><h4>真实估值/盘点原件 · 字节与指纹不变</h4><FinanceTable rows={supplyRows(data.originals)} label="盘盈原件" columns={[{ key: 'originalName', label: '原件' }, { key: 'sizeBytes', label: '字节' }, { key: 'sha256', label: '不可变 SHA256' }, { key: 'scanStatus', label: '安全扫描' }, { key: 'download', label: '授权调阅', render: row => <button type="button" onClick={() => void financeDownload(identity, `/supply/asset-gains/${data.id}/originals/${row.id}`, txt(row, 'originalName')).catch(e => setError(errorMessage(e)))}>下载原件</button> }]} />{writable && <form onSubmit={async e => { e.preventDefault(); if (!file || busy) return;setBusy(true);setError('');try { const body = new FormData();body.append('file', file);await financeRequest(identity, `/supply/asset-gains/${data.id}/originals?attachmentId=${attachmentId}`, 'POST', body);setFile(undefined) } catch (e) { setError(errorMessage(e)) } finally { setBusy(false) } }}><input type="file" required accept=".pdf,.ofd,.xml,.jpg,.jpeg,.png" onChange={e => { setFile(e.target.files?.[0]);setAttachmentId(crypto.randomUUID()) }} /><button disabled={!file || busy}>{busy ? '正在原文件扫描并归档…' : '上传真实估值原件，不旋转压缩'}</button></form>}{error && <p role="alert" className={s.error}>{error}</p>}</section>
}
