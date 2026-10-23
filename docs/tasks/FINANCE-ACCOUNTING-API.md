# 核算API实时契约

统一前缀 `/api/v1/finance/accounting`。金额写入可用十进制字符串；响应所有金额均十进制字符串。日期ISO；month为YYYY-MM。响应字段统一camelCase。列表`{items,total}`。

权限：`finance.accounting.read`（兼容finance.read）、`finance.accounting.write`、`finance.accounting.review`、`finance.accounting.post`、`finance.accounting.close`；配置账套/科目/规则及导入使用既有`finance.configure`。后台须显式授权，不自动给普通管理岗位记账权；全部检查组织范围。

工资来源明细另需`finance.payroll`。无此权限时工资来源的凭证列表/详情、来源事件、周期计划、明细账/资金日记账及关账快照均不返回个人工资明细；三表和科目汇总仍使用真实已记账总额，响应`detailAccess=PAYROLL_DETAILS_RESTRICTED_AGGREGATES_ONLY`，导出一致。

- GET `/context` -> `{books,accounts,periods,permissions,companies,hotels}`。
- GET `/books` -> items；POST `/books` `{requestId,hotelId,companyId,name,startMonth:"2026-10-01"}`。companyId必须与该组织enabled=true、workflowVersion=2的已发布财务主体一致，不能任意挑选其他法人。账套创建不自动生成虚构科目或期初。
- GET `/accounts?bookId=`；POST `/accounts` `{bookId,code,name,category:"ASSET|LIABILITY|EQUITY|REVENUE|EXPENSE",normalSide:"DEBIT|CREDIT",reportLine,cashAccount:false,enabled:true,expectedVersion:-1}`。新建expectedVersion=-1，修改用现版本。reportLine是财务确认的报表行名，不自动猜测税额。
- GET `/vouchers?bookId=&month=2026-10` -> `{items,total}`；GET `/vouchers/{id}` -> 凭证及`lines`、`source`。POST `/vouchers` `{requestId,bookId,businessDate,accountingDate,summary,lines:[{accountCode,debit:"100.00",credit:"0.00",summary:"",cashFlowCategory:null},{accountCode,debit:"0.00",credit:"100.00",summary:"",cashFlowCategory:null}]}`。
- POST `/vouchers/{id}/review`、`/post` `{expectedVersion:0}`。DRAFT→REVIEWED→POSTED，审核人必须不同于制单人，过账读取真实分录。凭证无原地编辑接口；以新增调整/红冲保留历史。
- 现金/银行分录必须填写现金流分类，创建、审核、过账及数据库过账防线均检查；非资金科目不能填写现金流分类。启用的资金映射缺分类会被拒绝；既有缺分类映射的来源保留PENDING_MAPPING，不生成会永久堵住月结的未分类凭证。
- POST `/vouchers/{id}/reverse` `{requestId,expectedVersion,accountingDate,reason}` -> 新红冲DRAFT，仍须独立审核后过账。
- YEAR_CLOSE不允许通用红冲。当前专用年结调整尚未开放，接口明确拒绝，不能将年结回冲算作新营业收入/费用或通过普通红冲假装完成重作结转。
- GET `/rules?bookId=`；POST `/rules` `{bookId,sourceType,eventKey,debitAccountCode,creditAccountCode,cashFlowCategory:null,enabled:true,expectedVersion:-1}`。
- GET `/source-events?bookId=&hotelId=&month=`；POST `/source-events/{id}/retry` `{}`。缺账套、映射或开放期间的来源事件保留待办；`PENDING_ENTITY_MISMATCH`表示来源冻结法人缺失/与账套不一致。报销从真实claim冻结主体核实，FO事件从真实document.companyId核实；旧事件无冻结主体依据时保留待核对，不根据当前门店配置猜历史主体。重复且内容不同冲突。规则只生成两行等额凭证，不拆分或猜测税额；复杂分录走明确手工凭证并保留来源，不宣称自动税务核算。
- GET `/reports?bookId=&month=` -> `{book,month,currency,amountUnit,dateBasis,generatedAt,reportVersion,status,completeness,trialBalance,generalLedger,detailLedger,cashJournal,incomeStatement,balanceSheet,cashFlowStatement}`。财务报表只读POSTED，三表及账簿共用同一次结果。完整性不足明确显示`INCOMPLETE`。
- GET `/reports/snapshot?bookId=&month=` -> 最近一次关账冻结报表（含frozenAt）；尚未关账返回404。关账保存完整原始报表，读取时再按读取人的工资权限脱敏；无工资权限的合法关账人不会造成快照永久缺明细。实时报告仍检测关账后出现的迟到来源，不静默改写旧快照。
- GET `/reports/export?bookId=&month=&format=xlsx|pdf&reportType=ALL&expectedReportVersion=` -> 真正XLSX/PDF二进制及`X-Report-Version`。reportType支持ALL/TRIAL_BALANCE/GENERAL_LEDGER/DETAIL_LEDGER/CASH_JOURNAL/INCOME_STATEMENT/BALANCE_SHEET/CASH_FLOW；其它报400。expectedReportVersion可传屏幕已取版本，数据变化则409；取数与屏幕完全同源。XLSX金额为精确十进制数值单元格，编号/来源/账号为文本且不执行公式。PDF加载系统中文字体，缺字体明确503；可配置JVM属性hotel.ai.os.pdf.font.sans.path。
- GET `/periods/check?bookId=&month=` -> `{bookId,month,status,version,canClose,checks}`；POST `/periods/close`或`/periods/reopen` `{bookId,month,expectedVersion,reason}`。月结检查来源覆盖、待处理事件、未过账凭证、试算、现金流分类、期初和前期。已批未付应付不是阻断条件。
- BANK_RECONCILIATION逐次读取本账套组织当月occurredOn银行流水。未全额匹配且无有效逐笔解释时阻断关账；解释须同时匹配原行sourceHash、当前匹配金额合计及匹配条数，新增匹配会令旧解释失效。解释由业务接口POST `/operations/bank-transactions/{id}/explanation` `{requestId,expectedVersion,reason}`（finance.review及组织范围）确认；不能用一条笼统来源覆盖记录代替逐笔核对。
- GET `/source-checks?bookId=&month=`；POST `/source-checks` `{bookId,month,sourceType,complete:true,expectedCount:12,actualCount:12,note,expectedVersion:-1}`。是财务确认的覆盖/对账记录，不是由空列表推断完整。
- POST `/periods/year-close` `{requestId,bookId,year:2026,retainedEarningsAccountCode,summary}` -> 损益结转DRAFT，独立审核过账并完成12月关账后衔接下一年。
- GET `/recurring-plans?bookId=` -> items；POST `/recurring-plans` `{requestId,bookId,kind:"DEPRECIATION|AMORTIZATION",sourceType:"FO_ASSET|FO_PREPAYMENT",sourceId,sourceReference,startMonth:"2026-10",endMonth:"2027-09",monthlyAmount:"100.00",totalAmount:"1200.00",debitAccountCode,creditAccountCode,summary}`。实际支持FO_ASSET→DEPRECIATION、FO_PREPAYMENT→AMORTIZATION；sourceId必须是真实业务单据ID，四段已批、同租户/组织/冻结法人。任意UUID、MANUAL_ACCRUAL或仅获批的合同承诺不构成已发生费用依据，ACCRUAL/ALLOCATION暂不开放来源适配。配置权限finance.configure；计划不使用现金科目，不自动假设税率或折旧政策。
- 来源金额依据：资产扣已确认残值并核对投用/使用期和直线政策；独立预付仅以本期以前净实付为基础；承接购买须为同法人同金额同性质的真实PAYABLE，且其RECOGNITION已正式POSTED。计划承诺与既有业务折旧/摊销累计不能超额，执行再次校验两域余额及同月重复。暂停计划不自动释放来源额度。来源快照独立保存真实主体和基准，不将用户填的来源说明当作已核实主体。
- 稳定直线政策：资产按批准金额扣残值/批准寿命，预付按批准总额/批准受益月数，后续付款不重算月标准。直线计划须覆盖来源剩余有效月份并分配精确剩余价值；末月取剩余额度补舍入差，例如1200.01元/12个月为前11月100.00、末月100.01，不开放第13月补差。总额和有效月数不符的计划会被拒绝。
- 独立PREPAYMENT未足额净实付时明确拒绝创建周期计划，避免先按部分实付冻结不可扩展计划；仍可在业务域按批准稳定政策及实际可用余额办理受控分次摊销。承接已正式记账购买的预付卡片按已核实承接价值处理，不重复要求其自身资金动作。
- 周期执行与业务域手工计提共享预算转换函数：沿真实卡片或recognizedSourceDocumentId原购买来源，将同一份fo_budget_usage承诺转为实际，不新增第二次占额。来源行→预算行→账套锁顺序一致；同requestId重复执行直接返回原凭证，不重复转换。预算承诺不足时事务整体拒绝，不生成半份凭证或假成功。
- POST `/recurring-plans/{id}/run` `{requestId,month:"2026-10",expectedVersion:0}` -> 真实DRAFT凭证。按月顺序幂等执行、尾月自动以剩余额度截断，不能累计超过totalAmount；执行使用finance.accounting.write，生成后仍须非本人审核和正式过账。存在本期未执行/未过账计划会阻断月结。
- POST `/recurring-plans/{id}/active` `{active:false,expectedVersion:0,reason}` -> 经配置权限暂停/恢复计划，留审计及版本，不修改既有计提事实。
- POST `/imports` `{requestId,bookId,mode:"PREVIEW|COMMIT",sourceReference,balances:[{accountCode,debit:"0.00",credit:"0.00"}],reconciliation:{"reference":"真实源文件及核对说明"}}`。PREVIEW不写入；COMMIT一次事务、期初试算必须平衡，同一requestId内容不同拒绝。正式期初必须在账套未有已过账凭证时导入；不会把资产/库存/未清往来JSON当业务明细已导入。

Java业务接入：`recordSourceEvent(UUID hotelId,String sourceType,UUID sourceId,String eventKey,LocalDate businessDate,LocalDate accountingDate,BigDecimal amount,String summary,Map<String,String> metadata)`。metadata应带冻结companyId；FO必须带真实documentId，报销必须带claimId。幂等键tenant+sourceType+sourceId+eventKey；待处理状态`PENDING_BOOK/PENDING_ENTITY_MISMATCH/PENDING_MAPPING/PENDING_PERIOD`，凭证生成后`DRAFT`，过账后`POSTED`。

验证命令（repo根目录PowerShell）：`$env:JAVA_HOME=(Resolve-Path '.tooling/jdk/jdk-21.0.11+10').Path; & '.tooling/maven/apache-maven-3.9.9/bin/mvn.cmd' -f apps/core-api/pom.xml '-Dmaven.repo.local=C:/Users/lzy27/Documents/Codex/四方馆AI中台/codex/.tooling/m2' '-Dtest=FinanceAccountingIntegrationTest' test -q`。基线V87。

最新本地验证（2026-10-24 01:53）：FinanceAccountingIntegrationTest 16项全部通过，真实PostgreSQL14.22、迁移目标V89。包含应用角色RLS/组织权限、来源并发幂等、独立审核/不可变过账、期初导入、锁期/红冲/年结、真实XLSX数值文本及中文多页PDF、工资与冻结快照隔离，并覆盖新增预算同源转换、1200.01/12尾差及第13月拒绝、部分实付预付的稳定政策、现金流分类全链、银行未达解释动态门禁和YEAR_CLOSE禁止通用红冲。

同轮FinanceIntegrationTest 26项、FinanceOperationsIntegrationTest 16项（均迁移V90）及规则/企微/AI-data测试合计72项，0失败/错误/跳过。此前一次Core全量741项为0失败/错误、5条件跳过；其后修正点另以本轮72项专项补测。证据为本机surefire-reports及工作区隔离诊断日志；不替代最终提交CI全量、真实资料迁移、恢复演练和生产部署验证。

明确边界：当前期初接口正式导入科目余额；没有将资产/库存/未清往来JSON冒充已写入业务子账，也没有声称完成金蝶真实文件格式解析。周期计划由授权人按月执行生成待审凭证，未接定时调度。来源完整性必须由财务显式核对/配置外部来源，不能从空数据推断完整。合并抵销/复杂税务政策未配置或未开发时不生成模拟结果。
