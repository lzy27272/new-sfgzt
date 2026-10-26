# 真实进项、税费计提及完税清偿（V110）

## 边界

税额不得从含税总额按默认税率反算。原件实际净额、税额、可抵扣用途须分别填写并独立复核，净额加税额精确等于原票金额。确认进项不再生成一笔采购费用。税额依据、抵扣、转出、红字、应纳计提、总账过账、实际外部申报回执和银行完税各有状态，不互相冒充。

无外部税务／银行连接时，这些接口只处理真实结果归档和已发生的银行付款关联，`externalExecution=NONE`。不能显示“系统已自动申报／转账”。

税务取数按真实法人整期汇总，不暗中截成单店零余额。生成新版本前须具备同法人全部实际启用组织、实际来源及所有历史账套（包含停用组织／账套）的权限，选定政策也必须属于明确选择的组织；缺权限明确拒绝，不自动扩大范围。冻结声明保存 `snapshot.inputTaxSources`（当时每笔实际抵扣／转出、typed 发票 ID、原批次和依据关联）及 `snapshot.inputTaxBases`（当时已确认蓝／红依据的完整版本及真实原件哈希），在票据共用锁内取数。后续抵扣、红字或组织变更不改写旧声明；没有真实来源数组的历史版本明确表示未捕获来源链，不能用当前同月票据回填。

新声明另冻结 `companyScopeVersion=FROZEN_COMPANY_HOTELS_V1` 和 `companyScopeHotelIds`。直接申报明细／列表、原件、回执，以及嵌套原申报快照的计提／支付／幂等重试都复用 `FinanceTaxScope`：撤掉任何冻结来源组织权限后拒绝读取，不能因为还可看入口门店就泄露全法人快照。没有完整冻结范围的旧版本保守校验当前该法人全部实际来源与历史账套范围，不把历史报表回填成当前版本。

## 权限及原件

所有接口独立要求 `finance.tax` 和真实组织范围。税额依据和应纳计提的复核还需 `finance.review`，仅冻结的当前复核人可办且仍须属于当前门店财务／财务总监配置，不能自审。税款付款还需 `finance.pay`，仅冻结且当前配置出纳可办，出纳与编制、复核分离。

`GET /api/v1/finance/tax/input-bases/{id}/original` 不授予全店 `finance.read`。仅该依据编制人／原申请人，或当前待复核节点且仍在真实配置中的独立税务复核人，可在授权范围内调阅此张原票。重新核验安全状态、原始大小、SHA256、合法租户路径及冻结哈希；不修改、旋转、重绘原件，失效或撤权后拒绝调阅。

## API

共同前缀：`/api/v1/finance/tax`。金额均以精确十进制文本提交，日期 `YYYY-MM-DD`，月份 `YYYY-MM`，请求编号为实际 UUID 幂等键。

| 方法／路径 | 真实用途 |
|---|---|
| GET `/context` | 授权组织、发布税务政策、实际配置复核人、实际 OUT 银行流水 |
| GET `/input-sources?hotelId=` | 已批准报销原票、已财务确认采购未释放原票，以及明确原采购退货的红票候选；候选不是已确认税额 |
| GET `/input-bases` | 独立原票拆税及用途确认；蓝票含红字后可用上限和净已抵扣 |
| POST `/input-bases` | 提交原件拆税／用途独立确认 |
| POST `/input-bases/{id}/review` | 对当前版本确认／拒绝，复验实际原票和来源生命周期 |
| GET `/input-bases/{id}/original` | 仅当前授权原件，返回原始字节 |
| POST `/input-decisions` | 按真实发布政策记录该原蓝票本期抵扣／转出 |
| GET `/returns` | 真实取数／申报准备版本及实际回执；未有回执不能当成已申报 |
| GET `/accruals` | 实际应纳确认、原计提是否正式过账、净已付／未付、原付款和更正净额 |
| POST `/accruals` | 基于真实取数来源哈希及已明确应纳额提交计提独立复核 |
| POST `/accruals/{id}/review` | 独立确认应纳，生成来源会计事件，不自动过账 |
| POST `/accruals/{id}/payments` | 在原计提正式过账后，关联同法人真实完税 PAYMENT 回执和银行 OUT 流水 |

`POST /input-bases` 字段：`requestId, hotelId, sourceKind, invoiceId, sourceParentId, redOfBasisId, netAmount, taxAmount, eligibleTax, purpose, reviewerId, note`。

- `sourceKind=CLAIM`：`invoiceId` 为真实 `finance_invoice.id`；实际来源为已批准报销、已确认发票、未退役扫描原件，实际法人和购方税号须匹配。
- `sourceKind=PROCUREMENT`：`invoiceId` 为真实 `finance_invoice_intake.id`，原 `fs_invoice_use` 未释放、`fs_recognition` 已 `CONFIRMED`；原件哈希与冻结占票一致。不能要求另造一次报销。
- `sourceKind=PROCUREMENT_RED`：明确 `sourceParentId=fs_return.id` 和 `redOfBasisId=原采购蓝票税额依据`，必须是真实已批准原退货及未被其他蓝票占用的真实红字原件。累计净额／税额／退货含税额不能超原蓝票及原退货余额，已抵扣额须先按原票转出，不能无痕减去旧抵扣。

`POST /input-decisions`：`requestId,basisId,month,policyId,claimedTax,transferOut,purpose,note`。仅已确认蓝票。累计净抵扣不能超过红字后的可用上限，累计转出不能超实际已抵扣。原 `POST /business/tax/input` 兼容，但必须已有真实 CLAIM 的独立税额依据；旧客户端不能以含税金额猜税额。

复核请求：`expectedVersion,approved,note`。

`POST /accruals`：`requestId,returnId,sourceHash,taxKind,amount,accountingOn,reviewerId,note`。来源须有实际税务原件、明确应纳额和发布政策；计提额等于该冻结版本已确认应纳额，不猜率。实际 FILING 回执如已存在须与该版本一致。计提确认不改变对外申报状态。

`POST /accruals/{id}/payments`：`requestId,expectedVersion,taxReceiptId,bankTransactionId,amount,accountingOn,note`。校验真实应纳来源已正式过账、未红冲；PAYMENT 回执不能用 FILING 替代；实际 OUT 银行流水属于同组织同法人且共享跨域可分配余额足够。累计净税款不能超过原应纳额或实际回执。

## 不可变事实及跨域契约

- `ft_input_basis`：独立确认原票实际净额／税额／可抵扣用途。`REVIEW` 只允许独立处理状态及复核元数据，已处理后不可更新。原票唯一有效税务依据按租户和规范票号约束。
- `fb_input_tax`：追加 `source_kind, basis_id, invoice_key, source_snapshot`，原抵扣／转出事实仍只追加，不重复确认费用。
- `ft_tax_accrual`：原 `return_id,source_hash,policy_id,company_id,amount,business_date,accounting_on` 冻结；每真实取数版本最多一个待审／已确认计提。
- `ft_tax_payment`：实际 `accrual_id,tax_receipt_id,bank_account_id,bank_transaction_id,amount,occurred_on,accounting_on,hotel_id,company_id` 不可变，原件和银行快照保留。
- 银行分配直接进入原 `fb_bank_match(source_type='TAX',source_id=ft_tax_payment.id)`，共享租户 `bank-match` advisory 锁种子 89，和 CLAIM／FO／FS／FB 共用余额。`fb_bank_allocated_amount` 已包含税款，不得再叠加税款子集。`ft_tax_bank_allocated_amount` 只供税款专项明细。
- 净已付：`ft_tax_paid_amount(tenant_id,accrual_id,as_of_date)`。单笔净实际现金：`fb_cash_effective_amount(tenant_id,'TAX',payment_id,original_amount,as_of_date)`。
- 真实税款误登记复用 Business 独立 `TAX` 现金纠错及不可变银行匹配逆向，不删原付款或原匹配。更正在开放 `accounting_on` 期间作为负向事实，不回改旧月。
- 实际月资金计划计入 `ft_tax_payment`，不计旧 `fb_tax_receipt PAYMENT`（证明不是第二笔现金），再按真实更正记账日期扣减。

会计来源：

| source_type / event_key | source_id | metadata |
|---|---|---|
| `FB_INPUT_TAX / DEDUCTION` 或 `TRANSFER_OUT` | `fb_input_tax.id` | `businessTable=fb_input_tax,businessId,companyId,invoiceKind,invoiceId,inputBasisId` |
| `FB_TAX_ACCRUAL / ACCRUAL` | `ft_tax_accrual.id` | `businessTable=ft_tax_accrual,businessId,companyId,returnId,policyId,taxKind` |
| `FB_TAX_PAYMENT / PAYMENT` | `ft_tax_payment.id` | `businessTable=ft_tax_payment,businessId,companyId,returnId,accrualId,taxReceiptId` |

通知为真实当前节点 `FINANCE_INPUT_BASIS` 或 `FINANCE_TAX_ACCRUAL`，统一 `FINANCE_REVIEW_PENDING`。计提复核、过账、出纳和真实完税都分开处理；不发送“审批即已外部执行”的消息。

## 验证

`FinanceTaxIntegrationTest` 使用真实 PostgreSQL 强制 RLS 应用角色，真实采购、验收、原票上传、财务确认、已批准员工报销、独立税额和红字转出、受控科目映射、独立过账、真实银行导入及税款差错链。包含未授权／跨门店／自审／撤权原件／超额／旧生命周期等负向。`finance-tax.test.mjs` 校验精确金额和真实 typed 关联。测试执行和实际结果由集中回归日志记录；本文件不把未运行测试标为通过。
