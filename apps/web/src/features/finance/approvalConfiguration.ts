export type ApprovalConfiguration = {
  companyName: string; taxNumber: string; managerAccountId: string | null; financeAccountId: string | null;
  directorAccountId: string | null; leaderAccountId: string | null; cashierAccountId: string | null;
  leaderName: string; payableAccount: string; skipSelfReview: boolean; enabled: boolean; expectedVersion: number;
}
export type FinanceStore = {
  hotel_id: string; hotel_name: string; hotel_code?: string; unit_type?: string; company_id?: string;
  company_name?: string; tax_number?: string; manager_account_id?: string; finance_account_id?: string;
  director_account_id?: string; leader_account_id?: string; cashier_account_id?: string; leader_name?: string;
  payable_account?: string; skip_self_review?: boolean; workflow_version?: number; enabled?: boolean; version?: number;
  draft?: ApprovalConfiguration;
}
export type ConfigurationData = { stores: FinanceStore[]; accounts: { id: string; display_name: string; login_name?: string }[] }
export const reviewerFields = [
  ['managerAccountId', '初审'], ['financeAccountId', '门店财务'],
  ['directorAccountId', '财务总监'], ['leaderAccountId', 'CEO终审'],
] as const
export function configurationFromStore(store: FinanceStore): ApprovalConfiguration {
  return { companyName: store.company_name ?? '', taxNumber: store.tax_number ?? '', managerAccountId: store.manager_account_id ?? null,
    financeAccountId: store.finance_account_id ?? null, directorAccountId: store.director_account_id ?? null,
    leaderAccountId: store.leader_account_id ?? null, cashierAccountId: store.cashier_account_id ?? null,
    leaderName: store.leader_name || '刘照阳', payableAccount: store.payable_account ?? '',
    skipSelfReview: store.skip_self_review ?? true, enabled: false, ...store.draft, expectedVersion: store.version ?? -1 }
}
export function configurationIssues(form: ApprovalConfiguration, accounts: ConfigurationData['accounts'], enabling: boolean): string[] {
  const errors: string[] = []
  if (!form.companyName.trim()) errors.push('请填写公司完整名称')
  if (!/^[A-Z0-9]{15,20}$/.test(form.taxNumber.trim())) errors.push('请填写15至20位纳税人识别号')
  const selected = reviewerFields.map(([key]) => form[key]).filter(Boolean)
  if (new Set(selected).size !== selected.length) errors.push('四段审核必须配置不同账号')
  for (const [key, label] of [...reviewerFields, ['cashierAccountId', '出纳'] as const]) {
    if (enabling && !form[key]) errors.push(`${label}账号待配置`)
    else if (form[key] && !accounts.some(a => a.id === form[key])) errors.push(`${label}账号已停用或不在可选范围，请重新选择`)
  }
  return errors
}
