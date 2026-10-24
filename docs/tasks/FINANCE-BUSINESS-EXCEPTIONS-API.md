# 非现金应收差额与真实税务义务

V101，API 前缀 `/api/v1/finance/business-exceptions`。

## 应收差额

- `GET /adjustments`：当前授权范围的不可删除差额记录。
- `POST /adjustments`：`requestId,entryId,expectedSourceVersion,kind(BAD_DEBT|CHANNEL_DIFFERENCE),amount,accountingDate,proofAttachmentId,reviewerId,reason`。仅独立确认且当前有效的 INCOME/AR，不能对押金、预收或储值直接认损。需同时有财务读取/审核权限及提交权限。保存 REVIEW，尚不减少应收。
- `POST /adjustments/{id}/review`：`expectedVersion,approve,note`。指定的当前后台财务/总监独立核对，版本、冻结未清额、原件及开放期间全部重新验证。

只有 CONFIRMED 才核销原净未收额。保留原收入金额、税额、发票、实收及原业务日期；新开放记账日追加 `FB_RECEIVABLE_ADJUSTMENT` 来源，key 为 BAD_DEBT/CHANNEL_DIFFERENCE。不自动过账，不自动认定可税前扣除。多收须以真实额外收入或真实退款处理，不能在这里静默冲平。

函数 `fb_receivable_adjusted_amount(tenant,entryId,asOf)` 跨同一真实来源 external ID 的历次修订汇总已确认差额；按差额入账日截止，不用当前余额改写历史账龄。应收余额、收款/消费核销上限、来源下修及资金差错冲回检查均扣此净额。未确认的差额不占用已收金额。

## 税务日历

- `GET /tax-calendar`：实际义务、真实官方期限、冻结及当前审核配置、真实申报/完税回执状态和未闭环逾期标记。
- `POST /tax-calendar`：`requestId,hotelId,policyId,taxKind,title,month,dueOn,obligation(FILING|PAYMENT),officialReference,reviewerId`。配置员还需独立税务权限；只可用同主体本期间已独立发布的政策。同主体/税种/所属期/义务不得并存重复未取消版本。不写死全国期限或税率。
- `POST /tax-calendar/{id}/review`：`expectedVersion,approve,note`，配置的独立财务/总监须同时有税务及审核权限。确认激活；拒绝/取消保留历史。激活后日期和政策不可覆盖。
- `POST /tax-calendar/{id}/link`：`requestId,returnId,expectedVersion,note`，独立审核人关联同主体、同门店、同期间、同政策的真实取数记录；链接及快照只追加，不能覆盖。

“已生成取数”“已关联申报记录”均不标记已外部申报。只有原申报记录真实 `fb_tax_receipt` 中的 FILING/PAYMENT 原件决定回执状态，支持分次实际完税。多个申报回执或申报金额不符列差异，不强行冲平。未配置外部服务，`externalExecution=NONE`。

审核配置已变化时显式显示 `REVIEWER_CONFIG_CHANGED`，不把旧任职授权当作仍有效。税务读取和财务读取分别授权，跨组织列表不泄露数据。

## 通知

REVIEW 时通知实际冻结审核账号，统一 `FINANCE_REVIEW_PENDING`，source_type 为 `FINANCE_AR_ADJUSTMENT` / `FINANCE_TAX_CALENDAR`。原始资金差错使用 `FINANCE_CASH_ERROR`。消息只含一般待办提示，不夹带工资、税务或凭证原件；实时状态及当前组织权限由通知跳转与后端再次校验。

## 当前明确边界

本域不是外部税务报送连接器，不自动作出税务可扣除结论。外部系统正式连接、实际政策/期限和真实审核人员须由上线配置确认。

## V106 原误核销恢复

- `GET /recoveries`：实际来源、原损失、恢复额、原业务日及恢复记账日。
- `POST /recoveries`：`requestId,adjustmentId,expectedSourceVersion,amount,accountingDate,proofAttachmentId,reviewerId,reason`。
- `POST /recoveries/{id}/review`：`expectedVersion,approve,note`。编制人不得自审，恢复审核人还须不同于原损失确认人，并属于当前配置的财务/总监。

独立确认的正向恢复事实写 `fb_receivable_recovery`；不修改原 `fb_receivable_adjustment`，不重新确认营业收入或凭空收款。恢复上限只来自同一原损失减已确认恢复额；不能跨来源借用额度。申请及确认均锁真实原损失和当前应收，核对原件、修订版本、实际收款/退款/消费及当前未清快照，发生变化须拒绝旧快照再申请。可分次恢复至原损失全额，不能重复恢复。

账龄共用 `fb_receivable_adjusted_amount` 原签名，V106 后返回“已确认损失减已确认恢复”；各自按记账日截止，旧月损失不被当前恢复改写。来源修订不切断同原损失的链路，但待审恢复不能套用旧修订快照。

确认后生成 `FB_RECEIVABLE_RECOVERY` 待核算事件，key=`BAD_DEBT_RECOVERY` / `CHANNEL_DIFFERENCE_RECOVERY`，须配置原应收/损失恢复映射，不当作新收入或现金。无规则保持待核算，不自动过账。`FINANCE_AR_RECOVERY` 待审通知仅发送实际冻结的恢复审核人。
