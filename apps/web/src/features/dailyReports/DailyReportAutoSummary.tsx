import type { DailyReportDetail } from './types'
import { featureStyles as styles } from '../shared/FeatureUI'
import summaryStyles from './summary.module.css'

const categories: Record<string, string> = { LINEN: '布草', CONSUMABLE: '耗品', CLEANING_SUPPLY: '清洁用品', OPERATING_SUPPLY: '经营物品' }

export function DailyReportAutoSummary({ report, section = 'all' }: { report: DailyReportDetail; section?: 'all' | 'work' | 'usage' | 'ai' }) {
  const summary = report.autoSummary
  const systemDetails = report.currentRevision.sections.filter((section) => section.applicable)
    .flatMap((section) => section.items).filter((item) => item.dataSource === 'SYSTEM' && (item.systemValue !== undefined || item.evidence.length > 0))
  return <>
    {(section === 'all' || section === 'usage') && <section className={styles.section} aria-label="当日物品使用数量汇总">
      <header><h2>当日物品使用数量汇总</h2><span className={styles.badge}>{report.status === 'DRAFT' ? '随审核更新' : '提交时快照'}</span></header>
      <p className={summaryStyles.note}>按本营业日、本人当前任职汇总，同一耗用台账被日报引用时只计算一次。已审核数量与待审核数量分开显示。</p>
      {summary?.usageItems.length ? <div className={summaryStyles.tableWrap}><table>
        <thead><tr><th>物品</th><th>类别</th><th>单位</th><th>已审核用量</th><th>待审核用量</th></tr></thead>
        <tbody>{summary.usageItems.map((item, index) => <tr key={`${item.itemName}-${item.unit}-${index}`}>
          <td>{item.itemName}</td><td>{categories[item.category] || '其他物品'}</td><td>{item.unit}</td>
          <td>{item.approvedQuantity}</td><td>{item.pendingQuantity}</td>
        </tr>)}</tbody>
      </table></div> : <p className={styles.locked}>{!summary ? '该历史日报未保存物品汇总快照，不能以当前台账替代历史记录。' : summary.usageRecordCount > summary.excludedUsageRecordCount ? '已登记本次无耗用。' : '尚无当日已提交的物品耗用记录，请先在工作台完成耗用登记。未登记不代表用量为零。'}</p>}
      {Boolean(summary?.excludedUsageRecordCount) && <p className={summaryStyles.note}>已排除 {summary!.excludedUsageRecordCount} 份退回或冲销台账，不计入有效用量。</p>}
      <p className={summaryStyles.note}>布草数量表示使用量，不等于洗涤结算；待审核耗品、清洁用品尚未扣减库存或计入成本。</p>
    </section>}
    {(section === 'all' || section === 'work') && <section className={styles.section} aria-label="当日工作自动汇总">
      <header><h2>当日工作自动汇总</h2><span className={styles.badge}>事实来源：工作提交记录</span></header>
      {summary?.workItems.length ? <div className={styles.timeline}>{summary.workItems.map((work, index) => <article key={`${work.label}-${index}`}>
        <strong>{work.label} · {work.status === 'APPROVED' ? '已审核完成' : '已提交待审核'}</strong>
        <p className={summaryStyles.text}>{work.completion}</p>
        {work.details && <p className={summaryStyles.text}>{work.details}</p>}
        {work.exception && work.exception !== '无' && <p className={summaryStyles.text}>异常：{work.exception}</p>}
        {work.nextAction && <p className={summaryStyles.text}>后续：{work.nextAction}</p>}
      </article>)}</div> : <p className={styles.locked}>暂无可汇总的当日已提交工作。请核对当前任职、营业日和工作提交情况，系统不会把没有记录视为完成。</p>}
      {systemDetails.length > 0 && <details className={summaryStyles.details}><summary>系统工作明细与证据</summary>{systemDetails.map((item) => <article key={item.id}>
        <strong>{item.label}</strong>
        {item.systemValue !== undefined && <p className={summaryStyles.text}>{item.systemValue}</p>}
        {item.evidence.map((evidence) => <p className={summaryStyles.note} key={evidence.id}>{evidence.fileName} · {evidence.invalidatedAt ? '已失效，历史保留' : '已关联证据'}</p>)}
      </article>)}</details>}
    </section>}
    {(section === 'all' || section === 'ai') && <section className={styles.section} aria-label="AI汇总与建议">
      <header><h2>AI 汇总与建议</h2></header>
      {summary?.mode === 'FACT_ONLY_INTERNAL_RULES' && <p className={summaryStyles.note}>当前为结构化事实与规则分析，未调用大模型；结论供人工确认，不自动审批、扣分或下发任务。</p>}
      {report.aiRecommendations.length ? report.aiRecommendations.map((item) => <dl className={summaryStyles.analysis} key={item.id}>
        <dt>事实</dt><dd>{item.facts}</dd><dt>分析</dt><dd>{item.analysis}</dd><dt>建议</dt><dd>{item.recommendation}</dd>
      </dl>) : <p className={styles.locked}>暂无可核验的分析结果；提交工作后将依据实际记录生成。</p>}
    </section>}
  </>
}
