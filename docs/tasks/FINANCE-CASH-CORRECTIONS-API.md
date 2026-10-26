# 原资金误登记的独立受控更正

这不是执行银行退款。真实退款须保留实际反向资金动作及真实银行流水。这里解决已登记收付款的记录差错，保留原正额事实、原业务日期及原匹配，只追加独立审核的冲回和明确匹配逆向。

API 前缀 `/api/v1/finance/cash-corrections`：

- `GET /facts`：授权范围内报销付款及旧财务业务实际现金动作。工资、税务分别校验独立权限；借款抵报销等非现金核销不在此入口。
- `POST /`：`requestId,sourceKind(CLAIM|OPERATION),sourceId(原finance_payment.id或fo_action.id),expectedSourceVersion,reviewerId,amount,accountingDate,reason,matchingReversals[{matchId,amount}]`。先保存 REVIEW，尚不减少金额。
- `GET /`、`GET /{id}`：冻结来源哈希、原会计事件、来源版本、原日期、更正入账日、申请依据及实际匹配逆向。
- `POST /{id}/review`：`expectedVersion,approve,note`。只能由后台配置财务/财务总监中冻结的审核人处理；不能是申请编制人、原资金登记人或原业务申请人。批准时重新核对来源、版本、净金额、匹配快照、关联核销和锁期。拒绝不改变原金额。

确认后生成 `FX_CASH_CORRECTION` 待核算事件，key=`CLAIM_PAYMENT_CORRECTION` 或 `OPERATION_<原动作>_CORRECTION`。原业务日期保留，记账日使用新更正日；无规则时仍待核算，不假称已制单、已过账或已向银行退款。工资事件携带 `sourceType=FO_PAYROLL` 保护独立敏感读取。

表 `fx_cash_correction` 的来源、金额、原件快照不可改；只允许 REVIEW 一次变为 CONFIRMED/REJECTED。`fx_bank_match_reversal` 只追加，不删除旧匹配。对已匹配金额须提交明确逆向，至少释放冲回后超占的匹配部分，不允许借机反配无关流水。

SQL 净值接口（均 SECURITY INVOKER，forced tenant RLS）：

- `fx_cash_effective_amount(tenant,kind,sourceId,originalAmount,asOf)`
- `fx_bank_match_effective_amount(tenant,kind,matchId,originalAmount,asOf)`
- `fx_bank_action_matched_amount(tenant,actionId,asOf)`
- `fx_bank_instruction_matched_amount(tenant,instructionId,asOf)`
- `fx_bank_allocated_amount(tenant,bankTransactionId,asOf)`：仅旧 FO 与 FC 的匹配净值，FB 和供应链匹配另计。
- `fx_operation_action_amount(tenant,documentId,action,asOf)`：现金动作扣已确认差错，非现金动作保留。
- `fx_claim_paid_amount(tenant,claimId,asOf)`

历史时点以原现金日是否已发生和更正 `accounting_on<=asOf` 取数；旧期不使用当前净额冒充历史现金。匹配发生日按 `created_at AT TIME ZONE Asia/Shanghai`，匹配逆向按更正记账日。

未配置外部银行服务，本接口始终 `externalExecution=NONE`。没有实际原现金事实、唯一原会计来源或来源已独立冲销时阻止确认。借款已被还款/核销、原现金已被退款、预付/资产已被摊销折旧、退款释放额度又被替代资金占用时阻止盲目冲回。
