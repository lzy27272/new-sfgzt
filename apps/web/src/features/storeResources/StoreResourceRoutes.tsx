import { type FormEvent, useEffect, useMemo, useState } from 'react'
import { hasPermission, permissions } from '../../app/permissions'
import type { AppNavigate } from '../../app/routeConfig'
import type { RoleContext, RouteParams } from '../../domain'
import { AsyncState } from '../../shared/AsyncState'
import { useScopedResource } from '../../shared/useScopedResource'
import { useStableCommand } from '../../shared/useStableCommand'
import { FeatureHeader, formatLocalDateTime } from '../shared/FeatureUI'
import {
  applyStoreResourceImport, approveStoreResourceHandover, confirmStoreResourceHandover, createFixedAsset, createInventoryMovement,
  createStoreResourceHandover, createStoreResourceItem, decideStoreResourceHandoverDispute,
  downloadStoreResourceImportErrors, downloadStoreResourceImportTemplate,
  loadFixedAssets, loadHandoverParticipants, loadInventoryMovements, loadStoreInventoryBalances, loadItemUsageScopeOptions,
  loadStoreResourceHandover, loadStoreResourceHandovers, loadStoreResourceHotels, loadStoreResourceImportHistory, loadStoreResourceItems,
  previewStoreResourceImport,
  retireFixedAsset, setStoreResourceItemEnabled, submitStoreResourcePhysicalCount, updateFixedAsset,
  updateStoreResourceItem,
} from './api'
import styles from './storeResources.module.css'
import { createImportRequestGuard, resolveStoreResourceHotelId } from './interactionState'
import { baseQuantityPreview, entryUnits, unitConfigurationError } from './unitConversion'
import { WorkUsagePanel } from './WorkUsagePanel'
import { DepartmentPrefixPanel } from './DepartmentPrefixPanel'
import { LaundryPanel } from './LaundryPanel'
import { CostPanel } from './CostPanel'
import { ItemUsageScopeEditor, emptyUsageOptions } from './ItemUsageScopeEditor'
import { inferUsageScope, usageScopeSummary, usageScopeValidation, type ItemUsageScope, type UsageScopeOptions } from './usageScope'
import type {
  FixedAsset, FixedAssetInput, HandoverParticipant, InventoryAdjustmentInput, StoreInventoryBalance,
  StoreResourceHandover, StoreResourceHandoverDetail, StoreResourceHandoverInput, StoreResourceItem,
  StoreResourceImportJob, StoreResourceItemCategory, StoreResourceItemInput, StoreResourceTab,
} from './types'

const tabs: Array<{ id: StoreResourceTab; label: string; description: string }> = [
  { id: 'items', label: '物品配置', description: '布草、耗品、清洁用品及门店物品' },
  { id: 'department-prefix', label: '编号与使用部门', description: '按QT、KF、WX识别并预览补全部门' },
  { id: 'inventory', label: '库存流水与调整', description: '收发、消耗、退回与调整全程留痕' },
  { id: 'usage', label: '日清耗用审核', description: '门店配置审核人，审核通过才扣库' },
  { id: 'laundry', label: '布草洗涤台账', description: '送洗、回收、差异及洗涤费单独记账' },
  { id: 'costs', label: '成本分析与月结', description: '分类成本、来源明细及历史月结快照' },
  { id: 'assets', label: '固定资产', description: '资产卡片、位置、状态和责任人' },
  { id: 'handovers', label: '异动移交', description: '账面快照、异议盘点与三方确认' },
]
const categoryLabels: Record<StoreResourceItemCategory, string> = {
  LINEN: '布草', CONSUMABLE: '耗品', CLEANING_SUPPLY: '清洁用品', OPERATING_ITEM: '经营物品',
}
const movementLabels: Record<string, string> = {
  OPENING: '期初入账', RECEIPT: '入库', ISSUE: '领用', CONSUMPTION: '消耗', CONSUME: '消耗',
  RETURN: '退回', DAMAGE: '损坏', ADJUSTMENT: '库存调整', TRANSFER_IN: '调入', TRANSFER_OUT: '调出',
}
const movementSourceLabels: Record<string, string> = {
  MANUAL: '手工登记', WORK_RECORD: '工作记录联动', HANDOVER: '异动移交', IMPORT: '表格导入', LAUNDRY: '洗涤报损及冲销', PROCUREMENT: '采购收货复核入库',
}
const terminalHandoverStatuses = new Set(['COMPLETED', 'REJECTED', 'CANCELLED'])

function validTab(value?: string): StoreResourceTab {
  return tabs.some((tab) => tab.id === value) ? value as StoreResourceTab : 'items'
}
function money(value: number) { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 2 }).format(value) }
function numeric(value: number) { return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 6 }).format(value) }
function statusLabel(value: string): string {
  return ({
    ACTIVE: '在用', INACTIVE: '停用', MAINTENANCE: '维修中', DAMAGED: '损坏', MISSING: '盘亏', RETIRED: '已退役',
    AWAITING_DISPUTE_DECISION: '待确认是否有异议', PHYSICAL_COUNT_REQUIRED: '有异议，待实物盘点',
    AWAITING_TRANSFEROR_CONFIRMATION: '待移交人确认', AWAITING_RECIPIENT_CONFIRMATION: '待签收人确认',
    AWAITING_APPROVAL: '待审批人审批', COMPLETED: '已完成', REJECTED: '已拒绝', CANCELLED: '已取消',
    INVALID: '校验未通过', READY: '待确认导入', APPLIED: '已导入',
  } as Record<string, string>)[value] ?? value
}
type ResourceState<T> = ReturnType<typeof useScopedResource<T[]>>

export function StoreResourceRoutes({ identity, grantedPermissions, params, go }: {
  identity: RoleContext; grantedPermissions: string[]; params: RouteParams; go: AppNavigate
}) {
  const selectedTab = validTab(params.tab)
  const [message, setMessage] = useState<string>()
  const [commandError, setCommandError] = useState<string>()
  const [showImport, setShowImport] = useState(false)
  const hotels = useScopedResource(`${identity.key}:store-resource-hotels`, (signal) => loadStoreResourceHotels(identity, signal), [])
  const selectedHotelId = resolveStoreResourceHotelId(hotels.data, params.hotelId)
  useEffect(() => {
    setMessage(undefined); setCommandError(undefined); setShowImport(false)
  }, [identity.key, selectedHotelId])
  const items = useScopedResource(`${identity.key}:store-resource-items:${selectedHotelId || 'none'}`, (signal) => selectedHotelId ? loadStoreResourceItems(identity, selectedHotelId, signal) : Promise.resolve([]), [])
  const balances = useScopedResource(`${identity.key}:store-resource-balances:${selectedHotelId || 'none'}`, (signal) => selectedHotelId ? loadStoreInventoryBalances(identity, selectedHotelId, signal) : Promise.resolve([]), [])
  const assets = useScopedResource(`${identity.key}:store-resource-assets:${selectedHotelId || 'none'}`, (signal) => selectedHotelId ? loadFixedAssets(identity, selectedHotelId, signal) : Promise.resolve([]), [])
  const handovers = useScopedResource(`${identity.key}:store-resource-handovers:${selectedHotelId || 'none'}`, (signal) => selectedHotelId ? loadStoreResourceHandovers(identity, selectedHotelId, signal) : Promise.resolve([]), [])
  const movements = useScopedResource(`${identity.key}:store-resource-movements:${selectedHotelId || 'none'}:${selectedTab === 'inventory'}`, (signal) => selectedHotelId && selectedTab === 'inventory' ? loadInventoryMovements(identity, selectedHotelId, signal) : Promise.resolve([]), [])
  const participants = useScopedResource(`${identity.key}:store-resource-participants:${selectedHotelId || 'none'}`, (signal) => selectedHotelId ? loadHandoverParticipants(identity, selectedHotelId, signal) : Promise.resolve([]), [])
  const summary = useMemo(() => {
    const activeItems = items.data.filter((item) => item.status === 'ACTIVE')
    const activeItemIds = new Set(activeItems.map((item) => item.id))
    return {
      itemCount: activeItems.length,
      lowStockCount: balances.data.filter((balance) => activeItemIds.has(balance.itemId) && balance.quantity <= balance.minimumQuantity).length,
      assetCount: assets.data.filter((asset) => asset.status !== 'RETIRED').length,
      pendingHandoverCount: handovers.data.filter((handover) => !terminalHandoverStatuses.has(handover.status)).length,
      inventoryValue: balances.data.reduce((total, balance) => total + balance.inventoryValue, 0),
    }
  }, [assets.data, balances.data, handovers.data, items.data])
  const selectedHotel = hotels.data.find((hotel) => hotel.id === selectedHotelId)
  const success = async (value: string, reloads: Array<() => Promise<unknown>>) => {
    setMessage(value); setCommandError(undefined); await Promise.all(reloads.map((reload) => reload()))
  }
  const switchHotel = (hotelId: string) => {
    setMessage(undefined); setCommandError(undefined); go('store-resources', { hotelId, tab: selectedTab })
  }
  return <section className={styles.page}>
    <FeatureHeader eyebrow="STORE RESOURCES" title="门店库存与固定资产" description="集中维护门店布草、耗品、经营物品、库存流水、固定资产和异动移交，所有变更保留操作记录。" actions={<>{hasPermission(grantedPermissions, permissions.storeResource.importPreview) && <button type="button" className="primary" disabled={!selectedHotelId} onClick={() => setShowImport((value) => !value)}>{showImport ? '收起批量导入' : '表格批量导入'}</button>}<button type="button" className="secondary" onClick={() => void Promise.all([hotels.reload(), items.reload(), balances.reload(), movements.reload(), assets.reload(), handovers.reload(), participants.reload()])}>刷新</button></>} />
    <section className={styles.scopeBar}><label>当前门店<select aria-label="当前门店" value={selectedHotelId} onChange={(event) => switchHotel(event.target.value)} disabled={hotels.loading || !hotels.data.length}>
      {!selectedHotelId && <option value="">{hotels.data.length ? '请选择可访问门店' : '暂无可管理门店'}</option>}{hotels.data.map((hotel) => <option key={hotel.id} value={hotel.id}>{hotel.name}{hotel.city ? ` · ${hotel.city}` : ''}</option>)}
    </select></label><span>{selectedHotel?.code ?? '—'}</span></section>
    <AsyncState loading={hotels.loading} error={hotels.error} empty={!hotels.loading && !hotels.error && !hotels.data.length} onRetry={hotels.reload} emptyTitle="当前权限范围没有门店" emptyDescription="请由管理员为当前任职授权门店范围和门店资源读取权限。" />
    {!hotels.loading && !hotels.error && params.hotelId && !selectedHotelId && <div className={styles.error} role="alert">该门店不存在、已停用或不在当前任职的授权范围内，请选择可访问门店。</div>}
    {!!selectedHotelId && <>
      {(grantedPermissions.includes('*') || grantedPermissions.some(p => ['procurement.read', 'procurement.request', 'procurement.configure'].includes(p))) && <div className={styles.scopeBar}><div><strong>采购与收货</strong><p>低库存补货、主动采购、收货复核与库存联动</p></div><button className="primary" onClick={() => go('procurement', { hotelId: selectedHotelId })}>进入采购与收货</button></div>}
      {showImport && hasPermission(grantedPermissions, permissions.storeResource.importPreview) && <StoreResourceImportPanel key={`${identity.key}:${selectedHotelId}`} identity={identity} hotelId={selectedHotelId} canApply={hasPermission(grantedPermissions, permissions.storeResource.importApply)} onApplied={(scopeOnly) => success(scopeOnly ? '物品与资产使用范围已更新，未新增库存或成本流水。' : '新店物品、期初库存和固定资产已一次性导入', [items.reload, balances.reload, movements.reload, assets.reload])} onError={setCommandError} />}
      <section className={styles.metrics}><Metric label="物品种类" value={summary.itemCount} note="当前启用配置" /><Metric label="低库存" value={summary.lowStockCount} note="达到或低于最低库存" warning={summary.lowStockCount > 0} /><Metric label="固定资产" value={summary.assetCount} note="当前门店资产" /><Metric label="待办移交" value={summary.pendingHandoverCount} note={`库存金额 ${money(summary.inventoryValue)}`} warning={summary.pendingHandoverCount > 0} /></section>
      <nav className={styles.tabs} aria-label="门店资源功能">{tabs.map((tab) => <button type="button" key={tab.id} className={selectedTab === tab.id ? styles.active : ''} onClick={() => go('store-resources', { hotelId: selectedHotelId, tab: tab.id })}><strong>{tab.label}</strong><small>{tab.description}</small></button>)}</nav>
      {message && <div className={styles.success} role="status">{message}</div>}{commandError && <div className={styles.error} role="alert">{commandError}</div>}
      {selectedTab === 'department-prefix' && <DepartmentPrefixPanel key={`${identity.key}:${selectedHotelId}`} identity={identity} hotelId={selectedHotelId} canConfigure={hasPermission(grantedPermissions, permissions.storeResource.itemManage) && hasPermission(grantedPermissions, permissions.storeResource.assetManage)} onApplied={async () => { await Promise.all([items.reload(), assets.reload()]) }} />}
      {selectedTab === 'usage' && <WorkUsagePanel key={`${identity.key}:${selectedHotelId}`} identity={identity} hotelId={selectedHotelId} canConfigure={hasPermission(grantedPermissions, permissions.storeResource.itemManage)} canReverse={hasPermission(grantedPermissions, permissions.storeResource.movementCreate)} go={go} onStockChanged={async () => { await Promise.all([items.reload(), balances.reload(), movements.reload()]) }} />}
      {selectedTab === 'laundry' && <LaundryPanel key={`${identity.key}:${selectedHotelId}`} identity={identity} hotelId={selectedHotelId} canConfigure={hasPermission(grantedPermissions, permissions.storeResource.itemManage)} canManage={hasPermission(grantedPermissions, permissions.storeResource.movementCreate)} onStockChanged={async () => { await Promise.all([items.reload(), balances.reload(), movements.reload()]) }} />}
      {selectedTab === 'costs' && <CostPanel key={`${identity.key}:${selectedHotelId}`} identity={identity} hotelId={selectedHotelId} canClose={hasPermission(grantedPermissions, permissions.storeResource.costClose)} go={go} />}
      {selectedTab === 'items' && <ItemConfigurationTab key={`${identity.key}:items:${selectedHotelId}`} identity={identity} hotelId={selectedHotelId} items={items} balances={balances.data} canManage={hasPermission(grantedPermissions, permissions.storeResource.itemManage)} onSuccess={(value) => success(value, [items.reload, balances.reload])} onError={setCommandError} />}
      {selectedTab === 'inventory' && <InventoryTab key={`inventory:${selectedHotelId}`} identity={identity} hotelId={selectedHotelId} items={items.data} balances={balances.data} movements={movements} canCreate={hasPermission(grantedPermissions, permissions.storeResource.movementCreate)} onSuccess={(value) => success(value, [movements.reload, balances.reload, items.reload])} onError={setCommandError} />}
      {selectedTab === 'assets' && <AssetTab key={`assets:${selectedHotelId}`} identity={identity} hotelId={selectedHotelId} assets={assets} participants={participants.data} canManage={hasPermission(grantedPermissions, permissions.storeResource.assetManage)} onSuccess={(value) => success(value, [assets.reload])} onError={setCommandError} />}
      {selectedTab === 'handovers' && <HandoverTab key={`handovers:${selectedHotelId}`} identity={identity} hotelId={selectedHotelId} handovers={handovers} participants={participants.data} canCreate={hasPermission(grantedPermissions, permissions.storeResource.handoverCreate)} canConfirm={hasPermission(grantedPermissions, permissions.storeResource.handoverConfirm)} canApprove={hasPermission(grantedPermissions, permissions.storeResource.handoverApprove)} onSuccess={(value) => success(value, [handovers.reload, balances.reload, assets.reload])} onError={setCommandError} />}
    </>}
  </section>
}

function StoreResourceImportPanel({ identity, hotelId, canApply, onApplied, onError }: {
  identity: RoleContext; hotelId: string; canApply: boolean; onApplied: (scopeOnly: boolean) => Promise<void>; onError: (message?: string) => void
}) {
  const [scopeOnly, setScopeOnly] = useState(false)
  const [file, setFile] = useState<File>()
  const [job, setJob] = useState<StoreResourceImportJob>()
  const [notice, setNotice] = useState<string>()
  const command = useStableCommand(`store-resource-import-${hotelId}`)
  const [requestGuard] = useState(createImportRequestGuard)
  useEffect(() => () => requestGuard.invalidate(), [requestGuard])
  const history = useScopedResource(`${identity.key}:store-resource-import-history:${hotelId}`, (signal) => loadStoreResourceImportHistory(identity, hotelId, signal), [])
  const preview = async () => {
    if (!file || command.busy) return
    const request = requestGuard.start()
    onError(undefined); setJob(undefined); setNotice(undefined)
    let result: StoreResourceImportJob | undefined
    try {
      result = await command.run(() => previewStoreResourceImport(identity, hotelId, file, scopeOnly))
      if (!result || !requestGuard.isCurrent(request)) return
      setJob(result)
      setNotice(result.duplicateUpload
        ? result.status === 'APPLIED' ? '该文件已导入，系统不会重复入账。' : '该文件已重新校验，请核对本次预检结果后操作。'
        : result.canApply ? '整表校验通过，请核对汇总后确认导入。' : `发现${result.errorCount}项问题，正式数据尚未写入。`)
    } catch (reason) {
      if (requestGuard.isCurrent(request)) onError(reason instanceof Error ? reason.message : '导入文件预检失败')
      return
    }
    try {
      await history.reload()
    } catch {
      if (requestGuard.isCurrent(request)) onError('文件预检已完成，但导入历史刷新失败；可稍后点击刷新，不影响本次预检结果。')
    }
  }
  const apply = async () => {
    if (!job || !canApply || !job.canApply || command.busy) return
    const request = requestGuard.start()
    onError(undefined)
    let result: StoreResourceImportJob | undefined
    try {
      result = await command.run((key) => applyStoreResourceImport(identity, hotelId, job.id, key))
      if (!result || !requestGuard.isCurrent(request)) return
      setJob(result); setNotice(result.scopeOnly ? '使用范围已更新，库存数量、成本及责任人均未改变。' : '导入已完成，期初数量已形成不可修改的期初导入流水。')
    } catch (reason) {
      if (requestGuard.isCurrent(request)) onError(reason instanceof Error ? reason.message : '确认导入失败')
      return
    }
    const refreshes = await Promise.allSettled([history.reload(), onApplied(result.scopeOnly === true)])
    if (requestGuard.isCurrent(request) && refreshes.some((refresh) => refresh.status === 'rejected')) {
      onError(result.scopeOnly ? '使用范围已更新，但页面数据刷新失败；请点击刷新重新加载最新数据。' : '导入已经成功入账，但页面数据刷新失败；请点击刷新重新加载最新数据。')
    }
  }
  const download = async (action: () => Promise<void>, fallback: string) => {
    if (command.busy) return
    const request = requestGuard.start()
    try {
      await command.run(() => action())
    } catch (reason) {
      if (requestGuard.isCurrent(request)) onError(reason instanceof Error ? reason.message : fallback)
    }
  }
  return <section className={`${styles.panel} ${styles.importPanel}`}>
    <header><div><h2>库存与固定资产批量导入</h2><p>先选择模式，再下载模板填写。上传只做预检；确认后才整批写入，任何错误均不写正式数据。新建导入部门留空时可按QT、KF、WX规则识别；明确填写的部门优先。</p></div><button type="button" className="secondary" disabled={command.busy} onClick={() => void download(() => downloadStoreResourceImportTemplate(identity, hotelId, scopeOnly), '标准模板下载失败')}>下载标准模板 V5</button></header>
    <fieldset disabled={command.busy} className={styles.importModes}><legend>导入模式</legend>{[false,true].map(mode => <label key={String(mode)}><input type="radio" name="import-mode" checked={scopeOnly === mode} onChange={() => { requestGuard.invalidate(); setScopeOnly(mode); setJob(undefined); setNotice(undefined) }} />{mode ? '仅更新使用范围' : '新增物品与期初库存'}</label>)}</fieldset>
    {scopeOnly && <div className={styles.scopeImportNotice}>按已有物品编码或资产编号更新部门、岗位，不改变库存数量、成本、流水及责任人。下载模板已带入当前配置；请补齐需要更新的行，删除无需更新的行。</div>}
    <div className={styles.scopeImportNotice}>物品与固定资产均包含「使用部门」必填列和「适用岗位」选填列。模板附部门岗位参考表，多项用分号分隔，岗位留空表示所选部门内全部岗位。使用范围与保管责任人分别配置，不会自动创建部门或岗位。</div>
    <p className={styles.unitNote}>兼容旧 V1/V2 模板：缺少使用范围时标记待配置，补齐后员工才能领用及填报；不拆分库存，不改变历史成本或已保存的常用品配置。</p>
    <div className={styles.importUpload}><label>选择已填写的 .xlsx 文件<input type="file" disabled={command.busy} accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" onChange={(event) => { requestGuard.invalidate(); setFile(event.target.files?.[0]); setJob(undefined); setNotice(undefined); onError(undefined) }} /></label><button type="button" className="primary" disabled={!file || command.busy} onClick={() => void preview()}>{command.busy ? '处理中…' : '上传并预检'}</button></div>
    {notice && <div className={job?.canApply || job?.status === 'APPLIED' ? styles.success : styles.error} role="status">{notice}</div>}
    {job && <><section className={styles.importMetrics}><Metric label="物品" value={job.summary.itemCount} note={job.scopeOnly ? '仅更新部门及岗位' : `期初数量 ${numeric(job.summary.openingQuantity)}`} />{!job.scopeOnly && <Metric label="期初库存金额" value={money(job.summary.openingInventoryValue)} note="按单位成本计算" />}<Metric label="固定资产" value={job.summary.assetCount} note={job.scopeOnly ? '数量、原值、责任人保持不变' : `原值 ${money(job.summary.assetOriginalValue)}`} /><Metric label="校验结果" value={job.errorCount ? `${job.errorCount}项错误` : '全部通过'} note={`${job.validRowCount}/${job.rowCount} 行有效`} warning={job.errorCount > 0} /></section>
      {job.errorCount === 0 && job.summary.unconfiguredUsageCount > 0 && <div className={styles.scopeImportWarning}>有 {job.summary.unconfiguredUsageCount} 个物品未配置使用范围。旧模板仍可导入，但员工暂不能选择，请在物品配置中补齐；不会自动设置为全店通用。</div>}
      {job.errors.length > 0 && <section><div className={styles.importSectionTitle}><h3>需要修正</h3><button type="button" className="secondary" disabled={command.busy} onClick={() => void download(() => downloadStoreResourceImportErrors(identity, hotelId, job.id), '错误表下载失败')}>下载错误表</button></div><div className={styles.tableWrap}><table className={styles.importTable}><thead><tr><th>工作表</th><th>行号</th><th>字段</th><th>问题</th><th>原值</th></tr></thead><tbody>{job.errors.map((error, index) => <tr key={`${error.sheetName}:${error.rowNumber}:${error.field}:${index}`}><td>{error.sheetName}</td><td>{error.rowNumber}</td><td>{error.field}</td><td>{error.message}</td><td>{error.value || '—'}</td></tr>)}</tbody></table></div></section>}
      {job.errorCount === 0 && <details className={styles.importPreview}><summary>查看前30行数据预览</summary><div className={styles.previewColumns}><div><h3>{job.scopeOnly ? '已有物品使用范围' : '物品与期初库存（按基础单位）'}</h3>{job.preview.items.map((item) => <p key={`item:${item.rowNumber}`}><strong>{item.sku} · {item.name}{item.specification ? ` · ${item.specification}` : ''}</strong>{!job.scopeOnly && <><span>{categoryLabels[item.category]} · {numeric(item.openingQuantity)} {item.unit} · ¥{numeric(item.unitCost)}/{item.unit}</span><span>采购：1{item.purchaseUnit}＝{numeric(item.purchaseFactor)}{item.unit}；领用/耗用：1{item.issueUnit}＝{numeric(item.issueFactor)}{item.unit}</span></>}<span>使用部门：{item.usageDepartmentNames || '使用范围待配置'} · 适用岗位：{item.usageScope ? item.usagePositionNames || '所选部门内全部岗位' : '补齐范围后员工可选择'}</span></p>)}</div><div><h3>固定资产</h3>{job.preview.assets.map((asset) => <p key={`asset:${asset.rowNumber}`}><strong>{asset.assetCode} · {asset.name}</strong>{!job.scopeOnly && <span>{asset.category} · {asset.location || '未填位置'} · {money(asset.originalValue)}</span>}<span>使用部门：{asset.usageDepartmentNames || '使用范围待配置'} · 适用岗位：{asset.usagePositionNames || '所选部门内全部岗位'}</span></p>)}</div></div></details>}
      <div className={styles.actions}>{job.status === 'APPLIED' && <span className={styles.goodPill}>{job.scopeOnly ? '使用范围已更新' : '已导入，不会重复入账'}</span>}{job.status !== 'APPLIED' && <button type="button" className="primary" disabled={!canApply || !job.canApply || job.status !== 'READY' || command.busy} title={canApply ? undefined : '当前岗位没有确认导入权限'} onClick={() => void apply()}>{command.busy ? '处理中…' : job.scopeOnly ? '确认更新使用范围' : '确认整批导入'}</button>}</div></>}
    {Boolean(history.error) && <div className={styles.error} role="alert">导入历史暂时无法加载。<button type="button" className="secondary" onClick={() => void history.reload()}>重新加载历史</button></div>}
    {history.data.length > 0 && <details className={styles.importHistory}><summary>最近导入记录（{history.data.length}）</summary>{history.data.map((entry) => <p key={entry.id}><span>{entry.originalName}</span><strong>{statusLabel(entry.status)} · {entry.rowCount}行 · {formatLocalDateTime(entry.appliedAt || entry.createdAt)}</strong></p>)}</details>}
  </section>
}

function Metric({ label, value, note, warning = false }: { label: string; value: string | number; note: string; warning?: boolean }) {
  return <article className={warning ? styles.metricWarning : ''}><span>{label}</span><strong>{value}</strong><small>{note}</small></article>
}

function ItemConfigurationTab({ identity, hotelId, items, balances, canManage, onSuccess, onError }: {
  identity: RoleContext; hotelId: string; items: ResourceState<StoreResourceItem>; balances: StoreInventoryBalance[]; canManage: boolean
  onSuccess: (message: string) => Promise<void>; onError: (message: string) => void
}) {
  const [editing, setEditing] = useState<StoreResourceItem>(); const [showForm, setShowForm] = useState(false)
  const command = useStableCommand(`store-resource-item-${hotelId}`)
  const usageOptions = useScopedResource(`${identity.key}:${hotelId}:usage-scope-options:${canManage}:${showForm}`, signal => canManage && showForm ? loadItemUsageScopeOptions(identity, hotelId, signal) : Promise.resolve(emptyUsageOptions), emptyUsageOptions)
  const balanceByItem = useMemo(() => new Map(balances.map((balance) => [balance.itemId, balance])), [balances])
  return <section className={styles.panel}><header><div><h2>门店物品配置</h2><p>统一配置员工填报可选择的布草、耗品、清洁用品和经营物品。</p></div>{canManage && <button type="button" className="primary" onClick={() => { setEditing(undefined); setShowForm(true) }}>新增物品</button>}</header>
    {showForm && <ItemForm key={editing?.id ?? 'new-item'} initial={editing} busy={command.busy} usageOptions={usageOptions.data} usageOptionsLoading={usageOptions.loading} usageOptionsError={usageOptions.error} onReloadUsageOptions={() => void usageOptions.reload()} onCancel={() => { setShowForm(false); setEditing(undefined) }} onSubmit={async (input) => { try { await command.run((key) => editing ? updateStoreResourceItem(identity, hotelId, editing.id, input, editing.rowVersion, key) : createStoreResourceItem(identity, hotelId, input, key)); setShowForm(false); setEditing(undefined); await onSuccess(editing ? '物品配置已更新' : '物品已创建') } catch (reason) { throw reason } }} />}
    <AsyncState loading={items.loading} error={items.error} empty={!items.data.length} onRetry={items.reload} emptyTitle="暂无门店物品配置" />
    <div className={styles.resourceList}>{items.data.map((item) => { const current = balanceByItem.get(item.id)?.quantity ?? 0; return <article key={item.id}><div><span className={styles.code}>{item.sku}</span><h3>{item.name}</h3><small>{categoryLabels[item.category]} · 基础单位 {item.unit}{item.specification ? ` · ${item.specification}` : ''}</small><p className={styles.unitNote}>采购：1{item.purchaseUnit}＝{numeric(item.purchaseFactor)}{item.unit}<br />领用/耗用：1{item.issueUnit}＝{numeric(item.issueFactor)}{item.unit}</p><p className={styles.itemScopeLabel}>{usageScopeSummary(item.usageScope).departments} · {usageScopeSummary(item.usageScope).positions}</p></div><dl><div><dt>现存</dt><dd className={current < item.minimumQuantity ? styles.dangerText : ''}>{numeric(current)} {item.unit}</dd></div><div><dt>最低库存</dt><dd>{numeric(item.minimumQuantity)} {item.unit}</dd></div><div><dt>参考成本 / {item.unit}</dt><dd>¥{numeric(item.unitCost)}</dd></div></dl><footer><span className={item.status === 'ACTIVE' ? styles.goodPill : styles.mutedPill}>{statusLabel(item.status)}</span>{canManage && <><button type="button" className="secondary" disabled={command.busy} onClick={() => { setEditing(item); setShowForm(true) }}>编辑</button><button type="button" className="secondary" disabled={command.busy} onClick={() => void (async () => { try { await command.run((key) => setStoreResourceItemEnabled(identity, hotelId, item.id, item.status !== 'ACTIVE', item.rowVersion, key)); await onSuccess(item.status === 'ACTIVE' ? '物品已停用' : '物品已启用') } catch (reason) { onError(reason instanceof Error ? reason.message : '物品状态更新失败') } })()}>{item.status === 'ACTIVE' ? '停用' : '启用'}</button></>}</footer></article> })}</div>
  </section>
}

export function ItemForm({ initial, busy, onCancel, onSubmit, usageOptions = emptyUsageOptions, usageOptionsLoading = false, usageOptionsError, onReloadUsageOptions }: {
  initial?: StoreResourceItem; busy: boolean; onCancel: () => void; onSubmit: (input: StoreResourceItemInput) => Promise<void>
  usageOptions?: UsageScopeOptions; usageOptionsLoading?: boolean; usageOptionsError?: unknown; onReloadUsageOptions?: () => void
}) {
  const [form, setForm] = useState({ employeeUsageEnabled: initial?.employeeUsageEnabled ?? true, sku: initial?.sku ?? '', name: initial?.name ?? '', category: initial?.category ?? 'CONSUMABLE' as StoreResourceItemCategory, unit: initial?.unit ?? '个', unitCost: String(initial?.unitCost ?? 0), minimumQuantity: String(initial?.minimumQuantity ?? 0), specification: initial?.specification ?? '', purchaseUnit: initial?.purchaseUnit ?? '', purchaseFactor: String(initial?.purchaseFactor ?? 1), issueUnit: initial?.issueUnit ?? '', issueFactor: String(initial?.issueFactor ?? 1) })
  const conversionError = unitConfigurationError(form.unit, form.purchaseUnit, form.purchaseFactor, form.issueUnit, form.issueFactor)
  const [manualScope, setScope] = useState<ItemUsageScope>(initial?.usageScope ?? { mode: initial ? 'LEGACY' : 'DEPARTMENTS', departmentIds: [], positionIds: [] })
  const [scopeChanged, setScopeChanged] = useState(false)
  const inferred = !initial && !scopeChanged ? inferUsageScope(form.sku, usageOptions) : undefined
  const scope = inferred ?? manualScope
  const scopeError = !initial || scopeChanged ? usageScopeValidation(scope, usageOptions) : undefined
  const scopePending = (!initial || scopeChanged) && (usageOptionsLoading || Boolean(usageOptionsError))
  const [submitError, setSubmitError] = useState<string>()
  return <form className={styles.form} onSubmit={(event) => {
    event.preventDefault()
    if (busy || conversionError || scopeError || scopePending) return
    setSubmitError(undefined)
    void onSubmit({ employeeUsageEnabled: form.employeeUsageEnabled, sku: form.sku.trim(), name: form.name.trim(), category: form.category, unit: form.unit.trim(), unitCost: Number(form.unitCost), minimumQuantity: Number(form.minimumQuantity), specification: form.specification.trim(), purchaseUnit: form.purchaseUnit.trim() || form.unit.trim(), purchaseFactor: form.purchaseFactor || '1', issueUnit: form.issueUnit.trim() || form.unit.trim(), issueFactor: form.issueFactor || '1',
      ...((!initial || scopeChanged) && scope.mode !== 'LEGACY' ? { usageScope: { mode: scope.mode, departmentIds: scope.departmentIds, positionIds: scope.positionIds } } : {}),
    }).catch(reason => setSubmitError(reason instanceof Error ? reason.message : '保存失败，已填内容保留，请重试'))
  }}><h3>{initial ? '编辑物品' : '新增物品'}</h3><fieldset disabled={busy} className={styles.formGrid}>
    <label>物品编码<input required maxLength={80} value={form.sku} onChange={(event) => setForm({ ...form, sku: event.target.value })} /></label><label>物品名称<input required maxLength={160} value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} /></label>
    <label>分类<select value={form.category} onChange={(event) => setForm({ ...form, category: event.target.value as StoreResourceItemCategory })}>{Object.entries(categoryLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label><label>库存基础单位<input required maxLength={32} readOnly={initial?.baseUnitLocked} value={form.unit} onChange={(event) => setForm({ ...form, unit: event.target.value })} /><small>{initial?.baseUnitLocked ? '已有库存流水，基础单位已锁定' : '例如：支、条、毫升；所有库存统一按此单位保存'}</small></label>
    <label className={styles.full}>规格型号<input maxLength={240} value={form.specification} onChange={(event) => setForm({ ...form, specification: event.target.value })} placeholder="例如：30g/支，100支/箱；5升/桶" /></label>
    <label>采购单位<input maxLength={32} value={form.purchaseUnit} onChange={(event) => setForm({ ...form, purchaseUnit: event.target.value })} placeholder={`留空使用${form.unit}`} /></label><label>1 {form.purchaseUnit.trim() || form.unit} 等于多少 {form.unit}<input required type="number" min="0.000001" step="0.000001" value={form.purchaseFactor} onChange={(event) => setForm({ ...form, purchaseFactor: event.target.value })} /></label>
    <label>领用/耗用单位<input maxLength={32} value={form.issueUnit} onChange={(event) => setForm({ ...form, issueUnit: event.target.value })} placeholder={`留空使用${form.unit}`} /></label><label>1 {form.issueUnit.trim() || form.unit} 等于多少 {form.unit}<input required type="number" min="0.000001" step="0.000001" value={form.issueFactor} onChange={(event) => setForm({ ...form, issueFactor: event.target.value })} /></label>
    <label>基础单位参考成本（元/{form.unit}）<input required type="number" min="0" step="0.0001" value={form.unitCost} onChange={(event) => setForm({ ...form, unitCost: event.target.value })} /></label><label>最低库存（{form.unit}）<input required type="number" min="0" step="0.0001" value={form.minimumQuantity} onChange={(event) => setForm({ ...form, minimumQuantity: event.target.value })} /></label>
  </fieldset><fieldset disabled={busy}>
    <label className={styles.checkbox}><input type="checkbox" checked={form.employeeUsageEnabled} onChange={event => setForm({ ...form, employeeUsageEnabled: event.target.checked })} />允许员工领用及填报实际使用数量</label>
    {inferred && <p role="status">已按编号识别：{usageScopeSummary(inferred, usageOptions).departments}。可手工调整；手工配置优先。</p>}
    <ItemUsageScopeEditor value={scope} onChange={next => { setScope(next); setScopeChanged(true) }} options={usageOptions} loading={usageOptionsLoading} error={usageOptionsError} retry={onReloadUsageOptions} allowLegacy={Boolean(initial && (!initial.usageScope || initial.usageScope.mode === 'LEGACY'))} itemName={form.name} employeeUsageEnabled={form.employeeUsageEnabled} />
  </fieldset><p className={styles.unitNote}>比例按该物品配置，不同包装规格建议使用不同物品编码。修改配置仅影响后续流水，不会重算已有数量或成本。</p>
  {conversionError && <p className={styles.error} role="alert">{conversionError}</p>}{scopeError && !usageOptionsLoading && !usageOptionsError && <p className={styles.error} role="alert">{scopeError}</p>}{submitError && <p className={styles.error} role="alert">{submitError}</p>}
  <div className={styles.actions}><button type="button" className="secondary" disabled={busy} onClick={onCancel}>取消</button><button type="submit" className="primary" disabled={busy || Boolean(conversionError) || Boolean(scopeError) || scopePending}>{busy ? '保存中…' : '保存物品'}</button></div></form>
}

function InventoryTab({ identity, hotelId, items, balances, movements, canCreate, onSuccess, onError }: {
  identity: RoleContext; hotelId: string; items: StoreResourceItem[]; balances: StoreInventoryBalance[]
  movements: ResourceState<Awaited<ReturnType<typeof loadInventoryMovements>>[number]>; canCreate: boolean
  onSuccess: (message: string) => Promise<void>; onError: (message: string) => void
}) {
  const [showForm, setShowForm] = useState(false); const command = useStableCommand(`store-resource-movement-${hotelId}`)
  return <section className={styles.panel}><header><div><h2>库存流水与调整</h2><p>任何增加或减少均生成不可覆盖的流水，并记录操作人、时间和原因。</p></div>{canCreate && <button type="button" className="primary" onClick={() => setShowForm((value) => !value)}>新增库存流水</button>}</header>
    {showForm && <AdjustmentForm items={items.filter((item) => item.status === 'ACTIVE')} balances={balances} busy={command.busy} onCancel={() => setShowForm(false)} onSubmit={async (input) => { try { await command.run((key) => createInventoryMovement(identity, hotelId, input, key)); setShowForm(false); await onSuccess('库存变动已记入流水') } catch (reason) { onError(reason instanceof Error ? reason.message : '库存变动登记失败') } }} />}
    <AsyncState loading={movements.loading} error={movements.error} empty={!movements.data.length} onRetry={movements.reload} emptyTitle="暂无库存流水" />
    <div className={styles.timeline}>{movements.data.map((movement) => <article key={movement.id}><div><strong>{movement.itemName}</strong><span>{movementLabels[movement.movementType] ?? movement.movementType}</span></div><b className={movement.quantityDelta < 0 ? styles.dangerText : styles.goodText}>{movement.quantityDelta > 0 ? '+' : ''}{numeric(movement.quantityDelta)} {movement.unit}</b><p>录入 {numeric(movement.inputQuantity)} {movement.inputUnit}（1{movement.inputUnit}＝{numeric(movement.conversionFactor)}{movement.unit}） · 库存金额变动 ¥{numeric(movement.valueDelta)}</p><p>{numeric(movement.quantityBefore)} → {numeric(movement.quantityAfter)} {movement.unit} · {movement.reason}</p><small>{movement.operatorName} · {formatLocalDateTime(movement.occurredAt)}{movement.sourceType ? ` · ${movementSourceLabels[movement.sourceType] ?? movement.sourceType}` : ''}</small></article>)}</div>
  </section>
}

export function AdjustmentForm({ items, balances, busy, onCancel, onSubmit }: { items: StoreResourceItem[]; balances: StoreInventoryBalance[]; busy: boolean; onCancel: () => void; onSubmit: (input: InventoryAdjustmentInput) => Promise<void> }) {
  const [item, setItem] = useState<StoreResourceItem | undefined>(items[0]); const [movementType, setMovementType] = useState<InventoryAdjustmentInput['movementType']>('RECEIPT'); const [quantity, setQuantity] = useState(''); const [unitCost, setUnitCost] = useState(''); const [reason, setReason] = useState(''); const [selectedUnit, setSelectedUnit] = useState('')
  const balanceByItem = new Map(balances.map((balance) => [balance.itemId, balance]))
  const options = item ? entryUnits(item) : []
  const requestedUnit = selectedUnit || (movementType === 'RECEIPT' ? item?.purchaseUnit : item?.issueUnit) || item?.unit || ''
  const quantityUnit = options.find((option) => option.unit.toLowerCase() === requestedUnit.toLowerCase())?.unit ?? requestedUnit
  const factor = options.find((option) => option.unit === quantityUnit)?.factor ?? 1
  const preview = baseQuantityPreview(quantity, factor)
  const invalidQuantity = !preview.quantity || Boolean(preview.error) || (movementType !== 'ADJUSTMENT' && Number(quantity) < 0)
  const outgoing = movementType === 'CONSUMPTION' || movementType === 'DAMAGE' || Number(quantity) < 0
  const stale = !!item && !items.some((current) => current.id === item.id && current.rowVersion === item.rowVersion)
  const resetAmount = () => { setQuantity(''); setUnitCost('') }
  return <form className={styles.form} onSubmit={(event) => {
    event.preventDefault()
    if (busy || !item || invalidQuantity || stale) return
    void onSubmit({ itemId: item.id, movementType, quantity, quantityUnit, expectedItemVersion: item.rowVersion, unitCost: outgoing || !unitCost ? undefined : unitCost, reason: reason.trim() })
  }}><h3>新增库存流水</h3><fieldset disabled={busy} className={styles.formGrid}>
    <label>物品<select required value={item?.id ?? ''} onChange={(event) => { setItem(items.find((current) => current.id === event.target.value)); setSelectedUnit(''); resetAmount() }}><option value="">请选择</option>{items.map((current) => <option key={current.id} value={current.id}>{current.name}（现存 {numeric(balanceByItem.get(current.id)?.quantity ?? 0)} {current.unit}）</option>)}</select></label><label>变动类型<select value={movementType} onChange={(event) => { setMovementType(event.target.value as InventoryAdjustmentInput['movementType']); setSelectedUnit(''); resetAmount() }}><option value="RECEIPT">入库</option><option value="CONSUMPTION">消耗</option><option value="RETURN">退回</option><option value="DAMAGE">损坏</option><option value="ADJUSTMENT">盘点调整</option></select></label>
    <label>录入单位<select required value={quantityUnit} onChange={(event) => { setSelectedUnit(event.target.value); resetAmount() }}>{options.map((option) => <option key={option.unit} value={option.unit}>{option.unit}（1{option.unit}＝{numeric(option.factor)}{item?.unit}）</option>)}</select></label>
    <label>数量（{quantityUnit}）<input required type="number" step="0.0001" value={quantity} onChange={(event) => setQuantity(event.target.value)} placeholder={movementType === 'ADJUSTMENT' ? '增填正数，减填负数' : '请输入正数'} /></label>
    {!outgoing && <label>本次单价（元/{quantityUnit}，选填）<input type="number" min="0" step="0.0001" value={unitCost} onChange={(event) => setUnitCost(event.target.value)} placeholder="留空按当前库存平均成本" /></label>}<label className={styles.full}>变动原因<textarea required maxLength={1000} rows={3} value={reason} onChange={(event) => setReason(event.target.value)} /></label>
  </fieldset>
    {item && <div className={styles.conversionPreview} role="status">{preview.quantity ? `本次${outgoing ? '减少' : '增加'}库存 ${preview.quantity.replace('-', '')} ${item.unit}；1 ${quantityUnit}＝${numeric(factor)} ${item.unit}` : `库存以${item.unit}记账；1 ${quantityUnit}＝${numeric(factor)} ${item.unit}`}<small>{outgoing ? '消耗/损坏按库存加权平均成本计价，不重复按采购包装计数。' : `填写的是每${quantityUnit}的单价，不是每${item.unit}的价格（两单位相同时一致）。`}</small></div>}
    {preview.error && <p className={styles.error} role="alert">{preview.error}</p>}{stale && <p className={styles.error} role="alert">物品配置已变化，请重新选择物品并核对换算比例。</p>}
    <div className={styles.actions}><button type="button" className="secondary" disabled={busy} onClick={onCancel}>取消</button><button type="submit" className="primary" disabled={busy || !item || invalidQuantity || stale || !reason.trim()}>{busy ? '登记中…' : '确认并留痕'}</button></div></form>
}

function AssetTab({ identity, hotelId, assets, participants, canManage, onSuccess, onError }: { identity: RoleContext; hotelId: string; assets: ResourceState<FixedAsset>; participants: HandoverParticipant[]; canManage: boolean; onSuccess: (message: string) => Promise<void>; onError: (message: string) => void }) {
  const [showForm, setShowForm] = useState(false); const [editing, setEditing] = useState<FixedAsset>(); const command = useStableCommand(`store-resource-asset-${hotelId}`)
  const scopeOptions = useScopedResource(`${identity.key}:asset-scope:${hotelId}:${canManage}`, signal => canManage ? loadItemUsageScopeOptions(identity, hotelId, signal) : Promise.resolve(emptyUsageOptions), emptyUsageOptions)
  return <section className={styles.panel}><header><div><h2>固定资产</h2><p>登记资产编号、状态、位置、原值和当前责任人。</p></div>{canManage && <button type="button" className="primary" onClick={() => { setEditing(undefined); setShowForm(true) }}>登记资产</button>}</header>
    {showForm && <AssetForm key={editing?.id ?? 'new-asset'} initial={editing} participants={participants} usageOptions={scopeOptions.data} optionsLoading={scopeOptions.loading} optionsError={scopeOptions.error} reloadOptions={scopeOptions.reload} busy={command.busy} onCancel={() => { setShowForm(false); setEditing(undefined) }} onSubmit={async (input) => { try { await command.run((key) => editing ? updateFixedAsset(identity, hotelId, editing.id, input, editing.rowVersion, key) : createFixedAsset(identity, hotelId, input, key)); setShowForm(false); setEditing(undefined); await onSuccess(editing ? '固定资产已更新' : '固定资产已登记') } catch (reason) { onError(reason instanceof Error ? reason.message : '资产保存失败') } }} />}
    <AsyncState loading={assets.loading} error={assets.error} empty={!assets.data.length} onRetry={assets.reload} emptyTitle="暂无固定资产" /><div className={styles.resourceList}>{assets.data.map((asset) => <article key={asset.id}><div><span className={styles.code}>{asset.assetCode}</span><h3>{asset.name}</h3><small>{asset.category}{asset.specification ? ` · ${asset.specification}` : ''}{asset.location ? ` · ${asset.location}` : ''}</small><p className={styles.itemScopeLabel}>使用部门：{usageScopeSummary(asset.usageScope).departments} · 适用岗位：{usageScopeSummary(asset.usageScope).positions}</p></div><dl><div><dt>状态</dt><dd>{statusLabel(asset.status)}</dd></div><div><dt>责任人</dt><dd>{asset.responsibleName ?? '未指定'}</dd></div><div><dt>资产原值</dt><dd>{money(asset.originalValue)}</dd></div></dl><footer>{asset.acquiredOn && <small>购置日期 {asset.acquiredOn}</small>}{canManage && asset.status !== 'RETIRED' && <><button type="button" className="secondary" onClick={() => { setEditing(asset); setShowForm(true) }}>编辑</button><button type="button" className="secondary" disabled={command.busy} onClick={() => { if (!window.confirm(`确认将资产“${asset.name}”退役？退役记录将永久保留。`)) return; void (async () => { try { await command.run((key) => retireFixedAsset(identity, hotelId, asset.id, key)); await onSuccess('固定资产已退役') } catch (reason) { onError(reason instanceof Error ? reason.message : '资产退役失败') } })() }}>退役</button></>}</footer></article>)}</div>
  </section>
}

export function AssetForm({ initial, participants, busy, onCancel, onSubmit, usageOptions = emptyUsageOptions, optionsLoading = false, optionsError, reloadOptions }: { initial?: FixedAsset; participants: HandoverParticipant[]; busy: boolean; onCancel: () => void; onSubmit: (input: FixedAssetInput) => Promise<void>; usageOptions?: UsageScopeOptions; optionsLoading?: boolean; optionsError?: unknown; reloadOptions?: () => void }) {
  const [form, setForm] = useState({ assetCode: initial?.assetCode ?? '', name: initial?.name ?? '', category: initial?.category ?? '', specification: initial?.specification ?? '', serialNumber: initial?.serialNumber ?? '', unit: initial?.unit ?? '台', originalValue: String(initial?.originalValue ?? ''), acquiredOn: initial?.acquiredOn ?? '', location: initial?.location ?? '', responsibleAssignmentId: initial?.responsibleAssignmentId ?? '', status: initial?.status ?? 'ACTIVE' })
  const [manualScope, setScope] = useState<ItemUsageScope>(initial?.usageScope ?? { mode: initial ? 'LEGACY' : 'DEPARTMENTS', departmentIds: [], positionIds: [] })
  const [scopeChanged, setScopeChanged] = useState(false)
  const inferred = !initial && !scopeChanged ? inferUsageScope(form.assetCode, usageOptions) : undefined
  const scope = inferred ?? manualScope
  const scopeError = (!initial || scopeChanged) ? usageScopeValidation(scope, usageOptions) : undefined
  const invalid = busy || ((!initial || scopeChanged) && (optionsLoading || !!optionsError || !!scopeError))
  return <form className={styles.form} onSubmit={(event) => { event.preventDefault(); if (invalid) return; void onSubmit({ assetCode: form.assetCode.trim(), name: form.name.trim(), category: form.category.trim(), specification: form.specification.trim() || undefined, serialNumber: form.serialNumber.trim() || undefined, unit: form.unit.trim(), originalValue: Number(form.originalValue), acquiredOn: form.acquiredOn || undefined, location: form.location.trim() || undefined, responsibleAssignmentId: form.responsibleAssignmentId || undefined, status: form.status, ...((!initial || scopeChanged) && scope.mode !== 'LEGACY' ? { usageScope: scope } : {}) }) }}><h3>{initial ? '编辑固定资产' : '固定资产登记'}</h3><div className={styles.formGrid}>
    <label>资产编号<input required maxLength={80} value={form.assetCode} onChange={(event) => setForm({ ...form, assetCode: event.target.value })} /></label><label>资产名称<input required maxLength={160} value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} /></label><label>资产分类<input required maxLength={80} value={form.category} onChange={(event) => setForm({ ...form, category: event.target.value })} /></label><label>规格型号<input maxLength={240} value={form.specification} onChange={(event) => setForm({ ...form, specification: event.target.value })} /></label><label>序列号<input maxLength={160} value={form.serialNumber} onChange={(event) => setForm({ ...form, serialNumber: event.target.value })} /></label><label>计量单位<input required maxLength={32} value={form.unit} onChange={(event) => setForm({ ...form, unit: event.target.value })} /></label><label>资产原值<input required type="number" min="0" step="0.01" value={form.originalValue} onChange={(event) => setForm({ ...form, originalValue: event.target.value })} /></label><label>购置日期<input type="date" value={form.acquiredOn} onChange={(event) => setForm({ ...form, acquiredOn: event.target.value })} /></label><label>存放位置<input maxLength={160} value={form.location} onChange={(event) => setForm({ ...form, location: event.target.value })} /></label><label>责任人<select value={form.responsibleAssignmentId} onChange={(event) => setForm({ ...form, responsibleAssignmentId: event.target.value })}><option value="">暂不指定</option>{participants.map((person) => <option key={person.assignmentId} value={person.assignmentId}>{person.employeeName} · {person.positionName}</option>)}</select></label><label>资产状态<select value={form.status} onChange={(event) => setForm({ ...form, status: event.target.value })}><option value="ACTIVE">在用</option><option value="MAINTENANCE">维修中</option><option value="DAMAGED">损坏</option><option value="MISSING">盘亏</option><option value="RETIRED">已退役</option></select></label>
  </div>{inferred && <p role="status">已按编号识别：{usageScopeSummary(inferred, usageOptions).departments}。手工配置优先。</p>}
  <fieldset disabled={busy}><ItemUsageScopeEditor value={scope} onChange={next => { setScope(next); setScopeChanged(true) }} options={usageOptions} loading={optionsLoading} error={optionsError} retry={reloadOptions} allowLegacy={!!initial && scope.mode === 'LEGACY'} itemName={form.name} employeeUsageEnabled={false} /></fieldset><p>资产使用部门与责任人分别配置，不改变原值或移交责任。</p>{scopeError && <p role="alert">{scopeError}</p>}
  <div className={styles.actions}><button type="button" className="secondary" onClick={onCancel}>取消</button><button type="submit" className="primary" disabled={invalid || !form.originalValue}>{busy ? '登记中…' : '登记资产'}</button></div></form>
}

function HandoverTab({ identity, hotelId, handovers, participants, canCreate, canConfirm, canApprove, onSuccess, onError }: { identity: RoleContext; hotelId: string; handovers: ResourceState<StoreResourceHandover>; participants: HandoverParticipant[]; canCreate: boolean; canConfirm: boolean; canApprove: boolean; onSuccess: (message: string) => Promise<void>; onError: (message: string) => void }) {
  const [showForm, setShowForm] = useState(false); const [countingDetail, setCountingDetail] = useState<StoreResourceHandoverDetail>(); const [loadingCountId, setLoadingCountId] = useState<string>(); const command = useStableCommand(`store-resource-handover-${hotelId}`); const currentAssignmentId = identity.businessActorAssignmentId
  const runAction = async (action: (key: string) => Promise<unknown>, successMessage: string) => { try { await command.run(action); setCountingDetail(undefined); await onSuccess(successMessage) } catch (reason) { onError(reason instanceof Error ? reason.message : '移交操作失败') } }
  const openCount = async (handover: StoreResourceHandover) => { setLoadingCountId(handover.id); try { setCountingDetail(await loadStoreResourceHandover(identity, handover.id)) } catch (reason) { onError(reason instanceof Error ? reason.message : '读取盘点明细失败') } finally { setLoadingCountId(undefined) } }
  return <section className={styles.panel}><header><div><h2>资产与库存异动移交</h2><p>自动生成账面快照；有异议才进入实物盘点，随后由移交人、签收人、审批人依次确认并永久留痕。</p></div>{canCreate && <button type="button" className="primary" onClick={() => setShowForm((value) => !value)}>发起移交</button>}</header>
    {showForm && <HandoverForm participants={participants} busy={command.busy} onCancel={() => setShowForm(false)} onSubmit={async (input) => { try { await command.run((key) => createStoreResourceHandover(identity, hotelId, input, key)); setShowForm(false); await onSuccess('移交已发起并生成账面快照') } catch (reason) { onError(reason instanceof Error ? reason.message : '发起移交失败') } }} />}
    <AsyncState loading={handovers.loading} error={handovers.error} empty={!handovers.data.length} onRetry={handovers.reload} emptyTitle="暂无异动移交记录" /><div className={styles.handoverList}>{handovers.data.map((handover) => { const isTransferor = currentAssignmentId === handover.transferorAssignmentId; const isRecipient = currentAssignmentId === handover.recipientAssignmentId; const isApprover = currentAssignmentId === handover.approverAssignmentId; return <article key={handover.id}><header><div><span>{handover.handoverNo ?? handover.id.slice(0, 8).toUpperCase()}</span><h3>{statusLabel(handover.status)}</h3></div><b>{handover.disputeDecision === 'OBJECTION' ? '有异议' : handover.disputeDecision === 'NO_OBJECTION' ? '无异议' : '待确认'}</b></header><div className={styles.handoverPeople}><span>移交人<strong>{handover.transferorName}</strong></span><i>→</i><span>签收人<strong>{handover.recipientName}</strong></span><i>→</i><span>审批人<strong>{handover.approverName}</strong></span></div><p>账面快照共 {handover.lineCount} 项{handover.disputeReason ? ` · 异议说明：${handover.disputeReason}` : ''}</p><small>发起 {formatLocalDateTime(handover.createdAt)}{handover.snapshotAt ? ` · 快照 ${formatLocalDateTime(handover.snapshotAt)}` : ''}</small><footer>
      {canConfirm && (isTransferor || isRecipient) && handover.status === 'AWAITING_DISPUTE_DECISION' && <><button type="button" className="primary" disabled={command.busy} onClick={() => void runAction((key) => decideStoreResourceHandoverDispute(identity, handover.id, false, undefined, handover.rowVersion, key), '已确认账面快照无异议')}>无异议</button><button type="button" className="secondary" disabled={command.busy} onClick={() => { const reason = window.prompt('请填写异议原因'); if (reason?.trim()) void runAction((key) => decideStoreResourceHandoverDispute(identity, handover.id, true, reason.trim(), handover.rowVersion, key), '异议已登记，请完成实物盘点') }}>有异议</button></>}
      {canConfirm && isTransferor && handover.status === 'PHYSICAL_COUNT_REQUIRED' && <button type="button" className="primary" disabled={loadingCountId === handover.id || command.busy} onClick={() => void openCount(handover)}>{loadingCountId === handover.id ? '读取中…' : '填写实物盘点'}</button>}
      {canConfirm && isTransferor && handover.status === 'AWAITING_TRANSFEROR_CONFIRMATION' && <button type="button" className="primary" disabled={command.busy} onClick={() => void runAction((key) => confirmStoreResourceHandover(identity, handover.id, 'transferor', handover.rowVersion, key), '移交人已确认')}>移交人确认</button>}
      {canConfirm && isRecipient && handover.status === 'AWAITING_RECIPIENT_CONFIRMATION' && <button type="button" className="primary" disabled={command.busy} onClick={() => void runAction((key) => confirmStoreResourceHandover(identity, handover.id, 'recipient', handover.rowVersion, key), '签收人已确认')}>签收确认</button>}
      {canApprove && isApprover && handover.status === 'AWAITING_APPROVAL' && <><button type="button" className="primary" disabled={command.busy} onClick={() => void runAction((key) => approveStoreResourceHandover(identity, handover.id, true, undefined, handover.rowVersion, key), '审批通过，责任已正式转移')}>审批通过</button><button type="button" className="secondary" disabled={command.busy} onClick={() => { const reason = window.prompt('请填写拒绝原因'); if (reason?.trim()) void runAction((key) => approveStoreResourceHandover(identity, handover.id, false, reason.trim(), handover.rowVersion, key), '移交已拒绝') }}>拒绝</button></>}
    </footer>{countingDetail?.id === handover.id && <PhysicalCountForm key={countingDetail.id} detail={countingDetail} busy={command.busy} onCancel={() => setCountingDetail(undefined)} onSubmit={(lines, note) => runAction((key) => submitStoreResourcePhysicalCount(identity, handover.id, lines, note, countingDetail.rowVersion, key), '实物盘点已提交')} />}</article> })}</div>
  </section>
}

function PhysicalCountForm({ detail, busy, onCancel, onSubmit }: { detail: StoreResourceHandoverDetail; busy: boolean; onCancel: () => void; onSubmit: (lines: Array<{ lineId: string; physicalQuantity?: number; physicalState?: string; differenceReason?: string }>, note?: string) => Promise<void> }) {
  const [rows, setRows] = useState(() => detail.lines.map((line) => ({ lineId: line.id, physicalQuantity: line.physicalQuantity === undefined ? (line.bookQuantity === undefined ? '' : String(line.bookQuantity)) : String(line.physicalQuantity), physicalState: line.physicalState ?? line.bookState ?? 'ACTIVE', differenceReason: line.differenceReason ?? '' }))); const [note, setNote] = useState(''); const lineById = new Map(detail.lines.map((line) => [line.id, line])); const change = (lineId: string, patch: Partial<(typeof rows)[number]>) => setRows((current) => current.map((row) => row.lineId === lineId ? { ...row, ...patch } : row)); const invalid = rows.some((row) => lineById.get(row.lineId)?.resourceType === 'INVENTORY' && (row.physicalQuantity === '' || Number(row.physicalQuantity) < 0))
  return <form className={styles.form} onSubmit={(event) => { event.preventDefault(); void onSubmit(rows.map((row) => lineById.get(row.lineId)?.resourceType === 'INVENTORY' ? { lineId: row.lineId, physicalQuantity: Number(row.physicalQuantity), differenceReason: row.differenceReason.trim() || undefined } : { lineId: row.lineId, physicalState: row.physicalState, differenceReason: row.differenceReason.trim() || undefined }), note.trim() || undefined) }}><h3>实物盘点</h3><p>逐项核对快照；数量或状态不一致时填写差异原因。</p>{rows.map((row) => { const line = lineById.get(row.lineId)!; return <div className={styles.formGrid} key={row.lineId}><label>{line.resourceName}<small>账面：{line.resourceType === 'INVENTORY' ? `${numeric(line.bookQuantity ?? 0)} ${line.unit ?? ''}` : statusLabel(line.bookState ?? 'ACTIVE')}</small>{line.resourceType === 'INVENTORY' ? <input required type="number" min="0" step="0.01" value={row.physicalQuantity} onChange={(event) => change(row.lineId, { physicalQuantity: event.target.value })} /> : <select value={row.physicalState} onChange={(event) => change(row.lineId, { physicalState: event.target.value })}><option value="ACTIVE">在用</option><option value="MAINTENANCE">维修中</option><option value="DAMAGED">损坏</option><option value="MISSING">盘亏</option><option value="RETIRED">已退役</option></select>}</label><label>差异原因（如有）<input maxLength={2000} value={row.differenceReason} onChange={(event) => change(row.lineId, { differenceReason: event.target.value })} /></label></div> })}<label>盘点说明<textarea rows={3} maxLength={2000} value={note} onChange={(event) => setNote(event.target.value)} /></label><div className={styles.actions}><button type="button" className="secondary" onClick={onCancel}>取消</button><button type="submit" className="primary" disabled={busy || invalid}>{busy ? '提交中…' : '提交实物盘点'}</button></div></form>
}

function HandoverForm({ participants, busy, onCancel, onSubmit }: { participants: HandoverParticipant[]; busy: boolean; onCancel: () => void; onSubmit: (input: StoreResourceHandoverInput) => Promise<void> }) {
  const [transferorAssignmentId, setTransferorAssignmentId] = useState('')
  const [recipientAssignmentId, setRecipientAssignmentId] = useState('')
  const [approverAssignmentId, setApproverAssignmentId] = useState('')
  const [note, setNote] = useState('')
  const confirmingParticipants = participants.filter((person) => person.canConfirmHandover)
  const approvingParticipants = participants.filter((person) => person.canApproveHandover)
  const participantByAssignment = new Map(participants.map((person) => [person.assignmentId, person]))
  const transferorEmployeeId = participantByAssignment.get(transferorAssignmentId)?.employeeId
  const recipientEmployeeId = participantByAssignment.get(recipientAssignmentId)?.employeeId
  const selectedEmployeeIds = [transferorAssignmentId, recipientAssignmentId, approverAssignmentId]
    .map((assignmentId) => participantByAssignment.get(assignmentId)?.employeeId)
    .filter((employeeId): employeeId is string => Boolean(employeeId))
  const distinctPeople = selectedEmployeeIds.length === 3 && new Set(selectedEmployeeIds).size === 3
  const confirmingIds = new Set(confirmingParticipants.map((person) => person.assignmentId))
  const approvingIds = new Set(approvingParticipants.map((person) => person.assignmentId))
  const eligible = confirmingIds.has(transferorAssignmentId)
    && confirmingIds.has(recipientAssignmentId)
    && approvingIds.has(approverAssignmentId)
  const option = (person: HandoverParticipant) => <option key={person.assignmentId} value={person.assignmentId}>{person.employeeName} · {person.positionName}</option>
  return <form className={styles.form} onSubmit={(event) => { event.preventDefault(); void onSubmit({ transferorAssignmentId, recipientAssignmentId, approverAssignmentId, note: note.trim() || undefined }) }}><h3>发起门店资产与库存移交</h3><div className={styles.formGrid}><label>移交人<select required value={transferorAssignmentId} onChange={(event) => setTransferorAssignmentId(event.target.value)}><option value="">请选择</option>{confirmingParticipants.map(option)}</select></label><label>签收人<select required value={recipientAssignmentId} onChange={(event) => setRecipientAssignmentId(event.target.value)}><option value="">请选择</option>{confirmingParticipants.filter((person) => person.employeeId !== transferorEmployeeId).map(option)}</select></label><label>审批人<select required value={approverAssignmentId} onChange={(event) => setApproverAssignmentId(event.target.value)}><option value="">请选择</option>{approvingParticipants.filter((person) => person.employeeId !== transferorEmployeeId && person.employeeId !== recipientEmployeeId).map(option)}</select></label><label className={styles.full}>移交说明<textarea rows={3} maxLength={1000} value={note} onChange={(event) => setNote(event.target.value)} /></label></div><div className="inline-warning">移交人、签收人需要移交确认权限，审批人需要移交审批权限，且三方必须为不同人员；发起后由服务端冻结当时的账面快照。</div><div className={styles.actions}><button type="button" className="secondary" onClick={onCancel}>取消</button><button type="submit" className="primary" disabled={busy || !transferorAssignmentId || !recipientAssignmentId || !approverAssignmentId || !distinctPeople || !eligible}>{busy ? '发起中…' : '生成快照并发起'}</button></div></form>
}
