# 财务经营分析与迁移 API（V93，2026-10-24）

本说明描述源码契约；不代表已生产发布、金蝶已切换或真实模型已配置。测试结果以本轮最终集中回归为准。

## 权限与事实边界

- 分析读取需 `finance.read` / `finance.accounting.read` / `finance.configure` 任一，仍受当前账号真实组织范围约束。每个冻结报告读取、导出、比较、发布、建议跟进重新验证其全部组织范围，任职切换不沿用旧缓存。
- 指标配置、模型报告生成和编辑、发布及迁移使用 `finance.configure`；独立复核和建议跟进使用 `finance.review`。制稿和最后编辑人不得自审/发布本人稿。
- 账务只来自核算服务正式已过账报告，经营来源另标业务日期/当前已确认修订。缺失指标、历史收入/人数未齐、不存在账套均不是零；集团结果为管理汇总，不是正式合并抵销。
- 金额与数量输出十进制字符串；发票、科目、来源编号保持文本；日期 ISO，月份 YYYY-MM。没有给普通管理岗位自动增加记账、工资或税务权限。

## `/api/v1/finance/insights`

- `GET /context` -> `{hotels,model:{ready,model,originalsTransferred:false,setupPath},metricKinds,consolidationNote}`。
- `GET /dashboard?month=&hotelId=optional` -> `{month,scope,reportVersion,status,currency,amountUnit,dateBasis,facts,books,budgetExecution,aging,channels,cashPlan,revenueReconciliation,missingBooks,missingReasons}`。
  - `facts[]`: `id,hotelId,label,value|null,complete,explanation,unit,sourceReportVersion`。各账套收入、费用、账面净利润和现金净变动的同比/环比含本期与比较期实际三表版本、比较月份、绝对变化和可比性；缺齐或分母0时百分比为null。严格经营利润未完成经营/非经营分类时不以净利润冒充；工时未接入明确缺失。
  - `books[]`保存此次同源利润表、资产负债表与现金流量表汇总。已关账且有close_snapshot的期间读取真实当期冻结版，开放期才用实时快报；页面、fi快照、同版XLSX/PDF直接复用这一次取数，不切换到其他报告版本。
  - 人数、实际工时、电量、水量、间夜来源须显式确认，缺数据保持null；人均收入/每工时收入只有完整收入与已确认正人数/工时同时存在才计算。每间夜电耗/水耗引用两项实际计量修订，任一缺失或间夜为0留空。人工确认来源不等于已自动接通企微考勤或能耗采集。
  - 收入渠道取真实 `fb_revenue_batch/entry` 当前已确认修订；含税收入、不含税、税额、佣金、手续费分列。营业收入调节按账套冻结法人，不将另一法人来源并到当前公司。
  - 账龄按所选月末以前审批与资金动作计算，不使用今日已付余额冒充历史；迁移未清叠加只写清偿流水 `fb_opening_clearing`。PMS应收扣净渠道扣费、跨重结修订真实现金及储值消费抵债，资金差错按更正日期影响，不重认收入。资金需求是未清事项视图，不代表自动付款指令。
  - `budgetExecution` 对新fc已发布策略/稳定code跨版本实际占用与旧fo预算分源列示，不叠加同源，明确 `CURRENT_REGISTERED_STATE_NOT_MONTH_END`：这是当前登记预算控制，随报表冻结，不冒充历史月末或总账成本。`cashPlanControls`是真实已配置资金计划和受控报销/往来资金指令执行额，覆盖范围明确不是全部银行现金；没有执行来源为null。
  - `budgetBusinessSources`按真实冻结法人/门店/月/类别/使用部门/费用资本库存性质/来源类型聚合fbu投影及不可变实际差额，并保留来源版本哈希。`inventoryValue`是累计确认购入库存资金减正式退货，不是现存库存或当期费用；`actual`才是已归类的实际消费/验收/资本/摊销口径，且不是总账过账证明。`budgetSourceIssues`逐项保留OPEN待归类原来源，`budgetSourceCoverage`据此明确不完整；不能把漏归类成本当0。采购inflight/committed是当前未履约投影，不能拿来冒充历史月末，不与旧fo重复相加。
- `POST /metrics` `{requestId,hotelId,month,kind,dimension,value,sourceReference,expectedVersion}`。新记录版本-1，修改用实际版本；CHANNEL_REVENUE需真实渠道dimension。来源及修改留审计。
- `POST /snapshots` `{requestId,month,hotelId:null|UUID,expectedReportVersion}` -> 当前一致事实不可变快照及snapshotId。版本已变409。
- `GET /snapshots`、`GET /snapshots/{id}`；`GET /compare?first=&second=` 仅相同期间和相同范围可比，null保持不适用，不将缺失数值计算成0。
- `GET /export?month=&hotelId=&snapshotId=optional&expectedReportVersion=&format=xlsx|pdf`：真正XLSX/中文多页PDF，屏幕、快照、导出同一取数及版本。响应 `X-Report-Version`。缺中文字体503；Excel超过15位有效精度的金额422，不偷偷截断。文本单元格不执行公式，源编号保留前导零。原冻结版本不会被新凭证覆盖。
- `GET /reports`；`POST /reports` `{requestId,month,hotelId,expectedReportVersion}` -> 模型DRAFT报告。模型未配置/未授权传输503；外部失败不产生已发布报告。
- `GET /reports/{id}` -> `{snapshot,content:{analysis[],recommendations[]},history,followups,...}`。模型及人工编辑内容与冻结事实分开。
- `GET /reports/{id}/export?expectedRevision=&expectedStatus=&format=xlsx|pdf`：同一冻结事实+当前已显示修订的分析/建议完整导出；提供事实版本及分析修订双标识，修订或发布状态变化409，不以事实快照导出冒充含AI内容的月报。
- `PUT /reports/{id}` `{expectedVersion,title,content}`：DRAFT/REVIEWED可新修订，回到DRAFT；PUBLISHED不得原地覆盖。
- `POST /reports/{id}/review`、`/publish` `{expectedVersion}`：DRAFT→REVIEWED→PUBLISHED，编辑人回避，保留原修订内容。
- `POST /reports/{id}/followups` `{requestId,expectedVersion:-1|actual,recommendationIndex,status:ADOPTED|IN_PROGRESS|DECLINED,ownerId,dueDate,note}`：仅已发布建议的明确采纳登记，不能再用一段自报记录DONE。V105实际分配、本人办理、真实已验证依据和指定独立核实闭环见 `FINANCE-INSIGHT-ACTION-API.md`；不自动执行付款/过账/调税/绩效。
- `POST /questions` `{requestId,month,hotelId,expectedReportVersion,snapshotId:optional,kind,question,allowAggregateTransfer:true}`：独立授权问数，允许域OVERVIEW/REVENUE/COST/BUDGET/CASH/AGING/CHANNEL/LABOR/ENERGY/TREND；只用服务端同版授权快照筛选该域的汇总事实，不接受SQL、表名或客户端facts，不执行任何资金/记账/审批/税务/KPI。可对已冻结旧版本提问而不冒充最新版本；实时快报变动则409。模型未配置503，缺指标明确不足，不换另一域或补零。
- `GET /questions`返回本人原查询历史；`GET /questions/{id}`重新检查全部源组织范围和原查询所有人/配置权限，返回同源事实、版本、引用、模型分析/建议及不确定性。问题/来源/最终回答数据库不可变，原问题、请求人、时间和模型结果均可审计。外呼仍在短事务外，只传已选域的汇总指标。

### 模型配置

服务端配置 `hotel.ai.os.finance.ai.endpoint`（可信HTTPS的OpenAI-compatible chat/completions地址）、`api-key`、`model`；管理员审定数据传输后设置 `aggregate-transfer-authorized=true`。默认全未配置，不把规则提示冒充模型结果。

调用只发送授权汇总事实、引用ID、期间/版本、收入调节、按类型聚合账龄；不发送原件、银行账号、员工或客户姓名、个人工资行、原始单据摘要。模型JSON中的每条分析/建议必须引用本快照实际facts[].id，引用其他范围或不存在事实直接拒绝。FORECAST或明确未来预测措辞必须提供1–20条可核对的assumptions和非空uncertainty，建议亦强制明确不确定性；缺少不能只凭提示语放行。页面/导出保留假设和不确定性，不把预测展示为财务事实。

事实先在短事务冻结并提交，模型HTTP在事务外调用，未持账套/审批锁；结果再以短事务、同请求号持久化。失败记录审计但不生成假成功。实际模型配置和真实资料传输必须另经有效授权；本地测试只用隔离HTTP stub与模拟财务事实。

## `/api/v1/finance/migration`

- `GET /templates`：KINGDEE_CONFIGURABLE_EXPORT_MAPPING、支持CSV/XLSX、子账类型、目标字段及各类必填项，明确文件导入可用但外部API未连接。
- `POST /columns` multipart `file`：实际读取UTF-8 CSV或单工作表XLSX列头，不接收改名JSON。文件≤20MiB、10000数据行、100列、单元格≤8000字符。拒绝宏、外链、公式、重复空表头、损坏压缩、超精度数字。
- `POST /batches` multipart `{requestId,bookId,kind,sourceReference,mapping,file}`：mapping为**目标字段到实际源表头**映射JSON；真正资料来自上传文件，不是把JSON当已迁移数据。按行返回错误，不导入。
  - OPENING：accountCode/debit/credit。
  - OPEN_ITEM：sourceKey/accountCode/name/direction/businessDate/dueDate/partyName/originalAmount/remainingAmount。
  - ASSET：原值、累计折旧、净值、残值/剩余寿命；显式原值科目accountCode与accumulatedAccountCode；physicalAssetId必须对应本店既有实物，保留inServiceDate/originalUsefulMonths，不按名字猜建资产。
  - INVENTORY：sourceKey、控制科目、原/剩余价值、真实数量、单位，不自动新建或改写已有实物数量。
  - PREPAID：原/剩余金额、剩余月数、受益结束日。
  - direction为DEBIT/CREDIT，日期YYYY-MM-DD。源业务日早于账套启用日；原额/余额/净值/累计折旧关系强校验。
- `GET /batches?bookId=`、`GET /batches/{id}`：来源文件名、真实SHA256、列映射、逐行值/错误、批次状态和期初控制科目对账。源文件字节独立不可变存储，不随页面批注覆盖。
- `GET /batches/{id}/original`：授权、哈希复核并安全扫描后下载源原件，下载审计。HTTP接收扫描在事务外进行；扫描器未配置/失败均拒绝，不通过修改原件绕过扫描。
- `POST /batches/{id}/commit` `{expectedVersion,expectedFileHash,confirmationNote}`：整批事务，任何错误不半写。OPENING调用真实总账期初导入并ACTIVE；其他子账写结构化 `fm_subledger` 为STAGED，没有声称已增加实物/费用。
- `POST /batches/{id}/activate` 同命令：STAGED且控制科目对账一致方可ACTIVE；资产原值借额与累计折旧贷额分别核对。原值/余额/来源字段数据库防变更；不得将净值简单拿去对原值科目凑平。
- `POST /batches/{id}/cancel` 同命令：仅PREVIEW/STAGED软撤销并保留来源和历史，释放活跃来源键后可修正文件重跑。科目期初及已激活子账不允许“撤销导入”擦账，须走正式调整。
- `GET /subledger?bookId=&kind=`：仅ACTIVE真实期初子账，用于下游未清核销、财务资产和存货价值、剩余待摊衔接。来源唯一键和书籍锁防并发重导；不再确认同一来源收入或购置费用。

## V107 真实项目预算取数

`budgetBusinessSources` 按原有法人/门店/月份/类别/部门/性质/来源，再按实际 `projectKey` 及冻结 `projectSnapshot` 的版本哈希分列。项目只能来自已批准合同或真实在建资产卡的来源引用；报表不另建项目、不用任意 UUID 推定项目。相同项目在不同来源版本形成的真实成本分别保留，避免把历史名称/版本静默改成现在的版本。

每组保留 `projectKind/projectId/projectKey/projectName/projectSnapshot/projectSourceVersion/projectState`：`FROZEN_REAL_PROJECT_REFERENCE` 为真实来源冻结；`EXPLICIT_NO_PROJECT` 为来源明确确认不分项目；历史 `NONE` 加空快照为 `UNASSIGNED_NOT_REVIEWED`，保留其真实金额，不当作零成本、已核项目或已完工项目。预算 `ALL` 是包含项目子集的总预算，不与项目预算重复相加。

页面、冻结事实、XLSX/PDF 及预算问数来自同一版 `facts` 与 `budgetBusinessSources`。模型只获得项目类型/不透明引用/核对状态/版本等汇总维度，不传项目原件、合同自由文本、账户或交易明细。冻结后新成本或新项目版本改变实时报告哈希，不覆盖旧快照或同版导出。

## 部署和财务切换条件

开发完成不等于已经替代金蝶。上线前仍须使用真实金蝶导出文件确认字段映射、逐项余额与子账对账及并行报表核对；启用日期和正式切换经财务确认。模型/第三方连接没有配置不能验收为自动连接。发布须同提交通过CI制品、配套数据库/不可变源文件备份恢复、迁移演练、健康及页面验收；本文不豁免任何发布门禁。

## 2026-10-24 本域交付摘要与外部边界

本节是工作分工及本地验证摘要，不是上线证明，也不替代完整 V2 的跨域验收。

| 范围 | 已有真实源码行为 | 仍须明确的正式使用条件 |
| --- | --- | --- |
| F05 自动服务 | 原件/字段/哈希绑定、逐次外传授权、真实 HTTP OCR/验真适配、候选明确采纳、原请求查询、不可变提供方证明 | 默认未配置；本轮只调用隔离本机 stub 和模拟原件，没有访问真实 OCR、发票提供方或税局。OCR 候选、人工确认、外部验真、抵扣及报销批准相互独立 |
| F11 银行 | 真实文件预览/原子提交、FO/CLAIM/FB/FS 跨域实际现金与净匹配、历史差错时点、未达/未知主体/受限来源说明 | 没有连接真实银行或执行转账；真实账户、完整流水、回单与财务独立核对仍须配置及验收。`complete` 不是外部银行资料完整性的保证 |
| F23 报表 | 当前授权范围的真实三表、历史冻结版、损益/资金/账龄/渠道/预算来源、同比环比及显式经营计量；XLSX/PDF 同版导出 | 缺失留空，不能用零代替；手工来源工时/能耗不是考勤或仪表自动接通。集团为管理汇总，正式抵销底稿由业务域独立处理；严格经营利润未配置经营/非经营分类时不以净利润替代 |
| F24 AI 与行动 | 已授权冻结汇总集、真实可配置模型 HTTP、引用校验、假设/不确定性、可编辑历史、独立复核发布、只读问数、真实责任人及独立核实的建议跟进 | 模型默认未配置或未获传输授权；本轮隔离 stub 不是正式模型接通。建议不会自动付款、过账、申报、调税或改 KPI；跟进完成须真实业务/原件依据及独立核实 |
| F25 档案 | 已支持来源的真实图谱、不可变版本/批注、同源原件、独立限时调阅、权限重查、导出留痕及保存策略 | `manifest.restoreVerified=false`；本轮未执行生产财务数据库与原件卷配套恢复。新增专项业务来源须显式接入图谱和原件权限，不能以通用 JSON 充当已接通 |
| F26 迁移 | CSV/XLSX 实际字节解析、可配置源表头映射、逐行错误、文件哈希/批次版本、原子提交、期初及四类结构化子账对账激活、未生效撤销重跑 | 这是可核对文件导入，不是金蝶/PMS/OTA API 已连接；真实来源导出格式、启用日、控制科目及并行报表需财务确认。迁移不重复生成购置、收入或实物库存 |

此前已核实的集中测试基线为 `.diagnostics/20261024-finance-unified/v2-tests-15.log`：本域 InvoiceAutomation 4 项、BankControl 5 项、Archive 4 项、InsightMigration 9 项、InsightTrend 3 项、InsightAction 8 项、Content 3 项、MigrationParser 3 项均通过。上述是隔离 PostgreSQL/强制 RLS、模拟财务资料及本机 HTTP stub 范围，未使用真实财务原件外传；该轮全仓仍有其他失败，不能称整版回归通过。

集中第 22 轮真实 PG/RLS 报告已确认本域 Archive 4 项、BankControl 5 项、InsightProject 4 项、InsightMigration 9 项、InsightTrend 3 项、InsightAction 8 项、InvoiceAutomation 4 项通过。V112 DailyArchive 新增 7 项首次运行有 5 项通过，另两项为不完整测试夹具（收入批次 CONFIRMED 但当前行未激活、关账报表缺 `completeness.checks`）已修正并保留原金额及导出断言，须待下一轮重跑。不能把前轮结果当作新修改已通过，也不能以这些分域结果声称整版回归通过。前端编译、同提交 CI 制品、备份恢复、部署健康和线上页面验收需由发布负责人分别记录，不由本文静态结论替代。

## V112 每日经营与收款现金日报

独立入口 `/api/v1/finance/insights/daily`，前端在经营分析中按需展开；关闭时不请求整套日报来源。`fi_snapshot` 新增 `period_kind=MONTH|DAY` 与真实 `period_date`，月报历史列表不混入日报，跨日期/日月口径不允许作为同一期间修订比较。

- `GET ?date=YYYY-MM-DD&hotelId=optional`：授权范围单店、集团管理汇总及渠道/现金来源家族。PMS 取实际业务日已确认批次及当前行；INCOME/AR 与押金/储值/预收分开。账务另取实际记账日已过账收入与费用，不与 PMS 收入相加、也不把采购支付当当日费用或用月成本均摊。按真实资金发生日披露已登记收支，独立差错按其记账日列负额；非现金借款抵报销、储值消费不生成新现金。
- FT 税费只取实际 `ft_tax_payment` 银行 OUT，不以 FILING/PAYMENT 回执当现金；已有 FB 更正分支列真实负额，原事实保留且不重复合计。工资不向日报输出个人工资/姓名或明细。现金日报是来源家族汇总，不宣称银行资料齐全。
- 无该日批次、账套或现金来源显示 `null` 与原因，不用零代替。集团同范围任一门店缺来源时相关合计留空；“各门店已登记来源之和”不是正式合并抵销或完整日结保证。
- `POST /snapshots` `{requestId,date,hotelId,expectedReportVersion}`，`GET /snapshots?date=`，`GET /snapshots/{id}`：真实事实哈希/日期/范围绑定的不可变日快照。
- `GET /export?date=&hotelId=&snapshotId=optional&expectedReportVersion=&format=xlsx|pdf`：日经营、集团汇总、现金日报均从同一已授权结果渲染；XLSX/PDF 包含 DAY、实际日期及原版本，冻结后新来源不覆盖旧版。
- `POST /questions` `{requestId,date,hotelId,snapshotId,expectedReportVersion,question,allowAggregateTransfer:true}`：只能针对选定冻结日报，真实日期/范围/版本校验，沿既有只读问数审计与模型适配，不接受客户端事实、SQL 或执行请求。模型默认未配；未配返回 503，不生成模拟答案。外呼不持有数据库写锁；只发送允许的汇总事实，含确切日期/缺数/引用版本。

新增 `FinanceDailyArchiveIntegrationTest` 首稿 7 例及 `finance-daily-archive-render.test.mjs` 2 例。前端新增 2 例 SSR 和 TypeScript 构建已在隔离本机通过；后端新增范围等待集中 PG 日志确认。未调用真实 PMS、银行、税局、生产模型或真实财务原件，不能据此宣称外部系统已连通或生产已发布。
