# 财务业务域 API（开发契约，非完成报告）

前缀 `/api/v1/finance/operations`。金额返回十进制字符串，日期 ISO，字段 camelCase；列表 `{items,total,page,pageSize}`。所有查询按租户及有效组织范围，工资另需 `finance.payroll`。通用权限复用 `finance.read/submit/review/pay/configure`；不按职位自动授权。

## 单据

`GET /context` -> `{types,parties,bankAccounts,hotels,capabilities,limitations}`。
`GET /documents?type=&hotelId=&page=0`；`GET /documents/{id}` -> 单据字段+`lines,settlements,reviews,events,canEdit,canSubmit,canReview,canSettle`。

`POST /documents`、`PUT /documents/{id}` 请求：
```
{requestId,expectedVersion,hotelId,type,title,businessDate,dueDate?,partyId?,sourceType?,sourceId?,sourceReference?,budgetId?,details:{...},lines:[{description,quantity,unitPrice,categoryCode?,departmentId?}]}
```
新建 requestId 为 UUID、expectedVersion=0。金额由 quantity×unitPrice 明细算出，禁止前端总额覆盖。type 固定 CONTRACT/PAYABLE/LOAN/PREPAYMENT/ASSET/PAYROLL/REVENUE/INTERCOMPANY/TAX_TASK。details 各类字段：
- CONTRACT: contractNumber,startDate,endDate,paymentPlan:[{date,amount,label}]，paymentPlan 合计不超合同额。
- PAYABLE: acceptanceReference,invoiceReference,orderReference,treatment=EXPENSE|INVENTORY|ASSET|PREPAID,mismatchNote?（差异说明不自动拒绝）；采购真实批次来源待适配时明确未核验。
- LOAN: borrowerAccountId,purpose,repayBy。
- PREPAYMENT: benefitStart,benefitEnd,contractReference,recognitionMethod=STRAIGHT_LINE 或 MANUAL。
- ASSET: physicalAssetId?,assetCode,inServiceDate,usefulMonths,residualAmount,method=STRAIGHT_LINE；不反向变更实物资产。
- PAYROLL: payrollMonth,confirmedByAccountId,employeeAccountIds（工资权限独立，确认人不得为申请人）。
- REVENUE: externalSystem,externalReference,nightAuditDate,revenueKind=ROOM/OTHER/DEPOSIT/PREPAID，收入和预收分离；退款必须在原已收款单据通过受控 REFUND 动作登记，不能新造退款收入单。
- INTERCOMPANY: counterpartyHotelId,basisReference,counterpartyReviewerId；双方独立确认，内部交易不能冒充合并报表。
- TAX_TASK: taxPeriod,taxType,dueDate,policyReference；手工归档真实外部回执，不执行自动申报。

`POST /documents/{id}/submit` `{requestId,expectedVersion}`。快照现有门店配置四段审批；缺配置/自审不跳过；允许首段既有 skipSelfReview 配置并留痕。`POST /review` `{requestId,expectedVersion,decision:APPROVE|RETURN|REJECT,note}`。`POST /cancel` 同版本请求，只有未支付且非终结可撤回/取消。

`POST /documents/{id}/settle`：
```
{requestId,expectedVersion,action,amount,occurredOn,bankAccountId?,bankTransactionId?,reference,note?,receiptReference?,relatedDocumentId?}
```
action PAYMENT/RECEIPT/REFUND/REPAYMENT/WRITEOFF/DEPRECIATE/AMORTIZE/COUNTERSIGN/ISSUE_INVOICE/RED_INVOICE/TAX_FILE。允许动作按 type 和状态严格验证；资产 DISPOSE 尚缺完整处置分录，当前明确拒绝。PAYMENT/RECEIPT 由快照出纳登记（finance.pay），其他财务动作 finance.review/read 及特定权限。分次结算不超余额；回单缺失显式 MISSING，不伪造已归档。`receiptReference` 为真实回单外部档案引用，与原件上传分开。

POST multipart `/documents/{id}/attachments/{attachmentId}`：kind=CONTRACT|INVOICE|ACCEPTANCE|TRANSACTION|PAYMENT|PAYROLL|OTHER，file，settlementId可选；GET同路径下载。草稿申请人上传原始资料；已批单据仅快照出纳可用kind=PAYMENT且settlementId为真实本单收付款编号补回单。原件20MiB、不可变、哈希校验、原始文件安全扫描，不接受重编码替代扫描。上传使version+1，详情attachments包含actionId关联。

## 主数据、对账与预算

`GET/POST /parties` 写 `{requestId,hotelId,name,kind:SUPPLIER|CUSTOMER|EMPLOYEE,taxNumber?,bankName?,bankAccount?,expectedVersion}`；账号按文本保存，不接受数字自动转换。
`GET/POST /bank-accounts` 写 `{requestId,hotelId,name,accountNumber,bankName,openingDate,openingBalance}`；只有 finance.configure 能增，finance.pay/read 查询脱敏。
`GET/POST /bank-transactions` 写 `{bankAccountId,batchId,rows:[{externalId,occurredOn,direction:IN|OUT,amount,counterparty,reference}]}`；同 externalId 内容冲突拒绝，整批原子。`POST /bank-transactions/{id}/match` `{requestId,allocations:[{settlementId,amount}],note}`，同一流水多笔分配/同一结算多笔流水，双方余额不超额；匹配不自动记收入/费用。
`POST /bank-transactions/{id}/explanation` 写 `{requestId,expectedVersion,reason}`，需 `finance.review` 及组织范围。首次 expectedVersion=-1，已有解释使用返回的 explanationVersion；理由不能为空。流水列表返回 matchedAmount/matchCount 和 explanationStatus=NONE|VALID|STALE、explanationReason、explanationVersion。解释冻结原流水哈希和匹配金额/条数，后续新增匹配自动使旧解释失效；解释不等于匹配、付款或会计凭证。
`GET/POST /budgets` 写 `{requestId,hotelId,month,categoryCode,amount,control:WARN|BLOCK,expectedVersion}`；新增 expectedVersion=0。预算状态使用台账承诺/审批在途/实际转换，不把付款当成本。
`GET /calendar` 返回未完结到期单据（非只当日）。
`GET /reports?hotelId=&month=` 返回 `{month,basis,totals,balances,dataPresent,unpostedSourceDocuments,completeness}`，是业务分类金额及部分余额摘要；目前不提供完整应付/应收账龄及银行余额调节表，不称总账或正式财务报表。`completeness` 为说明字符串数组。

## 尚需专项适配，不伪造成功

真实采购批次退换库存逆向、PMS/OTA 自动连接、自动税务/发票服务、银行转账、正式合并抵销、PDF/Excel 业务导入由后续专用适配实现；业务接口仅接收真实手工确认资料及引用。未经接通不显示已外部验证。当前单据子账与总账取数分别标识。
