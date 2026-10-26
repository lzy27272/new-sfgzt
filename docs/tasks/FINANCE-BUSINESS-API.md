# 收入、逐员工薪酬、双边往来及票税 V2

状态：2026-10-24 编码中。此文档描述真实代码，不代表全部验收或已部署。新表 V92，所有表强制 tenant RLS；只写事实禁止 UPDATE/DELETE，状态变更只锁可变父对象或事务 advisory 锁，不向应用角色放开原件/事实 UPDATE 权限。

## API 与身份

根路径 `/api/v1/finance/business`。所有金额使用 BigDecimal（数值上限 NUMERIC(18,2)），原始票号、员工/来源编号及银行标识按文本保留；前端整数分与 BigInt 计算，禁止浮点金额总计。组织、冻结法人和当前操作权限分别校验。所有 `requestId` 为 UUID，租户内同请求同内容幂等，不同内容返回409。

| API | 真实作用 / 权限 |
|---|---|
| GET `/context` | 授权组织及连接状态；未连接 PMS/OTA 显示 FILE_IMPORT_ONLY，开票/申报 NOT_CONFIGURED |
| GET/POST `/revenue/imports`, POST `/revenue/imports/preview` | 收入来源整批行数/含税总额控制、夜审已关闭、来源/渠道/业务日/连续修订，独立指定确认人；创建还需 finance.submit，读取 finance.read/review |
| POST `/revenue/imports/file?preview=true/false` | 完整规范 JSON 文件服务器预览/导入，导入后扫描归档原字节；不是只输入文件名。明细事务全成全败；原件扫描失败保留 REVIEW 批次待补原件，不能 CONFIRMED |
| GET `/revenue/imports/{id}`, POST `/{id}/confirm` | 真正的原件存在、指定独立确认人、版本一致后激活收入行并生成来源建议；不自动过账 |
| GET `/revenue/accounts`, POST `/revenue/accounts/{id}/movements` | INCOME/AR 渠道净应收、佣金/费用及账龄；DEPOSIT/PREPAYMENT/STORED_VALUE 为实收未消费/未退负债余额。收款/实际退款/核销分别记录，关联相同外部ID历次重结；出纳与范围校验，不能退已消费余额 |
| GET/POST `/payroll/batches`, POST `/payroll/batches/file` | finance.payroll 独立权限，编制还需 finance.submit。逐有效任职员工/本店部门的 earnings/deductions/employerCosts 稳定代码及真实金额；补发输入差异、关联同月原确认批次，不重发全额 |
| GET `/payroll/batches/{id}`, POST `/{id}/confirm`, POST `/{id}/payments` | 独立指定确认人（finance.review+payroll），按组件生成来源；实际每员工分次发放由快照独立出纳（finance.pay+payroll）登记，净额累计封顶、银行标识唯一。普通财务/管理岗位无权读取工资行 |
| GET/POST `/intercompany`, GET `/{id}`, POST `/{id}/confirm` | ADVANCE/SERVICE/ALLOCATION，双方真实组织/法人、独立确认人；分摊基数/原始依据及分值尾差冻结；双方都确认后才产生双边镜像，单侧人员不会取得对方读取范围 |
| POST `/intercompany/{id}/settlements` | 各方配置出纳分别登记真实收付，累计每方不超过确认额；另一方不自动标已收。`side=ORIGIN/COUNTERPARTY`，实际方向由已确认经济性质决定。双方登记差额留待核对 |
| GET/POST `/consolidation/scopes`, POST `/{id}/publish` | 显式门店范围、版本、按交易类型的抵销借贷报表行；独立配置人发布，不能编制人自发布 |
| POST `/consolidation/worksheets` | 真实计算并冻结。损益当月；资产/负债/权益为确认期初加截至月末已过账；旧期内部未清继续计入，各方真实清偿从内部非现金账户余额扣减。仅按该内部来源实际已过账行消除，不借无关凭证凑数；损益及AR/AP均须映射，剩余/双方不齐/未关账/未确认期初/工资无权限则 INCOMPLETE。人民币管理合并底稿，不含复杂权益、多币种 |
| GET `/cash-facts`, GET/POST `/bank-matches` | REVENUE/PAYROLL/OPENING/INTERCOMPANY 实际资金，未配金额单列；与导入银行流水真实多对多分配。共享 tenant `bank-match` seed89 advisory锁，扣 fo_bank_match、fs_settlement、fc_claim_bank_match 及自身配额，业务金额和银行金额均封顶。工资资金仍需独立权限 |
| GET/POST `/cash-corrections` | 四类实际资金的独立差错冲回，不冒充真实退款；只有已配置门店财务/财务总监且非原登记人可办，工资另需独立权限。原事实/原匹配不删；已匹配必须同时提交明确逆向事实，保留实际银行流水未达。金额不能侵入已消费/已退款等依赖；更正记账日须开放，原业务日保留，proof指向真实原会计来源 |
| GET `/opening-items`, POST `/{id}/clear` | V93 ACTIVE OPEN_ITEM真实期初未清，当前组织快照主体及出纳校验、累计封顶；只写清偿事实，不重认原收入/成本。未安装迁移表明确 MIGRATION_NOT_INSTALLED |
| GET/POST `/tax/output-requests`, GET `/{id}` | finance.tax。当前有效同主体收入/应收行分次、合并请求；历次重结共同计算已开/排队额度，不能通过换导入版本重复开票 |
| POST `/tax/output-requests/{id}/register` | 真实外部发票原件+代码/号码/净额/税额/日期/外部回执，不预设税率；全租户实际销项号唯一。红字关联蓝票，净额及税额各自控制未红余额 |
| POST `/{id}/execute` | 外部服务未配置明确503，不伪装已开票/成功 |
| POST `/{id}/cancel` | 未开票或已真实全额红冲才释放排队额度；保留原票/红字及版本，不删除既有事实 |
| GET/POST `/tax/policies`, POST `/{id}/publish` | finance.tax+configure；财务核验官方引用、生效区间、版本、稳定字段、真实取数来源及明示系数；独立发布，不内置税率 |
| GET/POST `/tax/input-decisions` | 同主体已批准报销的已确认真实原票，按实际用途、可抵扣额、当期抵扣和转出记录。可抵扣上限冻结，累计净抵扣封顶；转出不能超过已抵扣，同期可分次，不重写旧事实 |
| GET/POST `/tax/returns`, GET `/{id}`, POST `/{id}/receipts` | 同一已发布政策版本，销项净/税、进项抵扣/转出、真实收入净/税、已过账借贷取数，票账与实际申报差异说明。底稿不是已申报；上传真实回执后独立FILING/PAYMENT事实，分次完税不能超过实际申报应缴 |
| POST/GET `/attachments/{domain}/{id}/{attachment}` | REVENUE/PAYROLL/OUTPUT/TAX 原字节扫描、SHA256、不可变路径、下载重新校验；工资和税务原件分别授权。生产扫描器未配置/超时拒绝，不用经营照片清洗或转向来替代财务原件 |

## 导入契约

`RevenueImport` 见 Java record：requestId/hotelId/source/channel/businessDate/nightAuditDate/revision/expectedCount/controlTotal/sourceReference/reviewerId/nightAuditClosed/rows；每行 externalId/kind/customer/dueDate/amount/netAmount/taxAmount/commission/fee。金额精确到分；原始外部ID保持前导零。重结是完整快照，不得漏掉旧行、变更旧经济性质或低于已收/已核销/已开票及排队额度。负差额形成独立 REVERSAL 来源，不改原确认。

`PayrollBatch`：requestId/hotelId/month/sourceReference/supplementsId/reviewerId/cashierId/lines；每行employeeId/departmentId/earnings/deductions/employerCosts。组件以稳定代码→十进制文本金额表示。外部原始CSV/XLSX格式未有正式样本时，不声称某PMS/银行/工资厂商连接已适配成功；规范文件保留真实来源引用与原件，字段映射需核验实际导出格式。

## 会计来源桥接

`FB_REVENUE_<kind>` 分净额/税额/佣金/费用，修订负差 `_REVERSAL`，实际 RECEIPT/REFUND/REDEEM 分别来源。
`FB_PAYROLL` 分员工 earnings/deductions/employerCosts，工资付款另 PAYMENT。
`FB_INTERCOMPANY` 双边非资金确认；`FB_INTERCOMPANY_CASH` 本方实际资金；`FB_INPUT_TAX` DEDUCTION/TRANSFER_OUT；`FB_OPENING` 仅清偿已核对期初。

metadata 固定 businessTable/businessId/companyId，核算从白名单真实表冻结主体反查，不信任请求声称主体。没有账套/规则/期间则 PENDING，不自动过账，不对银行执行转账。

## 尚需贯通验收及边界

本轮新增21个真实 PostgreSQL+app角色用例的源码；具体运行结果以同提交 Surefire/CI 为准，不能用源码数量冒充全通过。前端模型专项3项本地通过，完整 tsc 已通过（后续修改需重跑）。生产扫描/备份/报表复核仍是发布门禁。

复杂权益、多币种为方案外可选功能。真实外部自动开票/申报/PMS接口需要有效服务、真实格式与业务授权，本版无此配置时通过受控文件/真实结果归档完成，不假造连接。

四类差错已接 `fb_cash_correction` 与 `fb_bank_match_reversal` 不可变事实及独立核对。跨域统一口径使用 `fb_cash_effective_amount(tenant,type,source,original,as_of)`、`fb_bank_matched_amount(tenant,type,source,as_of)`、`fb_bank_allocated_amount(tenant,bankTransaction,as_of)`，均 SECURITY INVOKER、保留RLS。原业务时点过滤 `occurred_on<=as_of`，更正只按 `accounting_on<=as_of` 扣除，不能以当前净值回写旧期历史。服务当前余额与发放/核销/匹配封顶均取净有效值；读旧月份仍保留当时原值。更正本身生成新的待核算来源，仍须映射、制单、独立复核和过账。

未确认来源、工资表与单侧确认的往来允许原编制人或实际指定确认人撤销并保留原件/审计；已确认不能借撤销删除事实。撤销后旧确认节点不能恢复，错误审核人/原件有明确重建路径。
