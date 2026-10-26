# 财务档案与审计联查 V2 / F25

入口 `/api/v1/finance/archive`，前端独立 `FinanceArchive`（identity、permissions、go、params），建议 `tab=archive`。本模块不替换任何原单、原件、工资、税务或总账授权。

## 授权与不可变边界

- `finance.archive.read`：当前来源权限仍有效时，可预览、冻结、检索元数据；受限档案除创建人、调阅管理员外，须独立批准且未过期才能读取内容、批注和导出。
- `finance.archive.grant`：处理他人的调阅申请、提出保存策略及由不同授权账号独立发布。不能自批，不能越租户/全部源组织范围，不能授予工资、税务、原件或总账权限。
- 每次目录候选过滤、查看、申请、批准、批注、导出、原件下载都重新调用真实来源 detail 的现有授权。源权限撤回、组织变动或工资权限撤回后，历史调阅批准也不能继续读取。
- 快照保存实际源版本、法人/组织集合、实际来源关联、SHA256，不接收客户端传入业务事实或对象存储地址。JSONB键序使用规范化散列，不因字段顺序导致假失配。
- 档案快照、批注、访问日志均 append-only；受限标记不能原地改为公开，批注不改变原件及会计分录。无自动清理/删除财务原件端点。

## 真实来源与图谱

来源类型 CLAIM、OPERATION、REVENUE、PAYROLL、INTERCOMPANY、VOUCHER、INSIGHT、TAX_RETURN、MIGRATION；均逐类复用现有业务服务授权及真实资料，不用泛型JSON代替来源实现。

图谱包括真实原单、已登记会计来源事件、实际关联凭证、不可变附件hash、同一分析报告冻结账套报表版本、申报/完税回执、迁移原导出文件。无法证明历史版本的凭证不冒充该历史报表的来源；未获凭证权限时不向图谱填入其明细。内部往来归档须具有双方的全部组织范围。

## 接口

- `GET /context`：类型、当前可见法人、调阅最长72小时、保存/恢复边界；未启用自动删除。
- `GET /preview?sourceType=&sourceId=`：从服务端真实来源读取当前版本、资料、关联图谱。
- `POST /` `{requestId,sourceType,sourceId,expectedSourceVersion,title,restricted:true}`：先重新读取并核对当前版本再冻结。非调阅管理员不能创建范围内公开档案。相同来源同hash不重复生成快照；请求编号复用不同内容409。
- `GET /?sourceType=optional&sourceId=optional`：授权范围/独立工资权限及原来源权限过滤的最近200条候选窗口；不把窗口长度冒充全局总数。
- `GET /{id}`：重新授权、核对冻结hash、检查限时调阅，返回冻结内容、图谱、同源历史版本及独立批注。
- `POST /{id}/notes` `{requestId,body}`：追加不可变批注，不覆盖前版本。
- `POST /{id}/requests` `{requestId,reason,hours:1..72}`：有真实源权限但无受限内容调阅权的账号可申请，待审重复申请拒绝。
- `GET /requests`：本人申请或调阅管理范围可办申请，仍逐项核验来源权限。
- `POST /requests/{id}/decide` `{expectedVersion,approve,note}`：非申请人独立批准/拒绝，批准时从服务端时钟计限时；每次读取核对 `expires_at>now()`。
- `POST /requests/{id}/revoke` 同命令：撤销现有批准，不删除原申请/原审核历史。
- `GET /{id}/export`：当前获准的完整冻结版本JSON，含来源版本、内容hash、原件hash及批注；调阅与导出独立留痕。不是声称含原文件的伪ZIP包。
- `GET /{id}/originals/{attachment}`：先检查档案及冻结图谱关联，再调用已有 FinanceEvidence/OperationsEvidence/BusinessEvidence 或 Migration原件下载，重做原权限、文件完整性/安全状态核验，不接受对象路径。迁移原文件重新通过安全扫描。禁止基于调阅批准绕过原件下载服务。
- `GET /policies`、`POST /policies` `{requestId,companyId,years,legalHold,reason}`、`POST /policies/{id}/publish`：真实法人、全部配置组织范围、不同账号独立发布保存制度版本。年限由财务确认，不内置未经确认的法律结论；发布不会启动删除。
- `GET /manifest`：授权档案元数据及hash核对清单，明确 `restoreVerified:false`。不含未获受限内容，不把导出清单当数据库/附件备份或恢复证明。

## 备份、恢复和验收

V97资料在PostgreSQL内，须与财务原件卷（finance-evidence及finance-business-evidence，实际部署以配置为准）配套备份。恢复验收仍须在隔离环境实际恢复同批数据库与原文件，核对不可变快照/原件hash及授权；此API不伪造“恢复成功”状态，也不执行生产恢复。

`FinanceArchiveIntegrationTest` 使用实际PG/app强制RLS、实际Accounting来源授权：旧版保留及JSONB散列、不可变批注、过期/撤销调阅、作者自审、移除源权限/范围、工资独立授权、跨租户、保存策略独立发布、manifest不冒充恢复证明。正式原件下载仍应沿现有Evidence集成验收。

## V112 专项原单与独立已关账三表

新增真实来源 `SUPPLY_RECOGNITION`、`ASSET_CARD`、`ACCRUAL`、`ASSET_GAIN`、`BOOK_REPORT`，原有来源保持。原单仍由原业务服务授权，调阅权限不替代 `finance.read`、总账、采购、工资、税务或原件权限。

- `GET /sources?sourceType=&hotelId=optional`：真实编号候选及名称、冻结法人和组织；逐项经过实际服务读取。采购确认仅索引本次实际使用的收票原件，不复制门店其他候选发票；原采购申请/验收原件继续使用采购原权限。资产卡沿实际采购确认、原批准单、盘盈估值或核对期初承接，不按实物名称猜关联。租赁/待摊沿真实批准原单或已生效 PREPAID 批次，不拿其计提凭证代替原合同。
- `GET /report-sources`：当前账号实际可读的已关账账套及月份，版本由 Accounting 真实授权快照返回（工资受限版本不冒充未遮蔽版本）。
- `POST /report-sources` `{requestId,bookId,month,expectedReportVersion}`：复核真实已关账三表后写不可变 `fac_report_source`，不依赖模型配置、AI 报告生成或审批。随后仍通过正式 `/archive` 冻结档案版本。
- `GET /{id}/reports/export?expectedReportVersion=&reportType=INCOME_STATEMENT|BALANCE_SHEET|CASH_FLOW&format=xlsx|pdf`：先核验实际档案及原来源权限，再从该档案内的冻结三表渲染，返回 `X-Report-Version`；原账套后续快照不能替换此版内容。失败不改用其他表或其他版本。

`INTAKE`、`PROCUREMENT` 是图谱内部所需来源，不是让客户端提交任意源表的新归档类型。原件下载经真实 Intake/Operations/Procurement/AssetGain 受保护下载，逐份检查原件安全状态、大小、SHA256。盘盈原件真实表为 `fs_asset_gain_original`，不存在通用 `fs_original`。税务凭证经真实 `returnId` 链回申报版本及原件/外部回执；完税回执不等于已发生的银行支付。

`FinanceDailyArchiveIntegrationTest` 的新增验收覆盖真实 PG/RLS、实际原业务服务、采购已用票据与别单隔离、FAR 原件、盘盈原件完整性、无 AI 的独立三表、同版导出及跨来源待办授权。此处描述测试范围；是否通过以本次集中测试日志为准，不把源码或旧轮通过记录作为新代码上线证明。

税务链按 V110 在制表时冻结的 `snapshot.inputTaxSources` 与 `inputTaxBases` 取数，`inputLineage=CAPTURED_AT_PREPARATION` 才表示逐票来源已捕获。图谱区分每笔实际抵扣/转出事实、独立蓝/红税额依据及其原件哈希、真实采购红字退货引用。税额依据中 `sourceKind=PROCUREMENT|PROCUREMENT_RED` 的 `invoiceId` 是收票资料编号，必须走 INTAKE；`CLAIM` 则由真实 `finance_invoice` 查回原报销及附件，不能将两类 UUID 混用。

逐票链包含的所有实际组织都重新检查并纳入档案范围。无原报销/收票读取权时，只保留 `SOURCE_ACCESS_REQUIRED/ORIGINAL_PERMISSION_NOT_GRANTED` 说明，不借税务菜单或调阅批准授予永久原票权。已取得原读取权后，原件仍须与冻结 SHA256 精确一致。旧历史申报没有逐票链时明确 `SOURCE_LINEAGE_NOT_CAPTURED`，不按现在同月所有票、现在的红字状态或现在余额回填旧申报。
