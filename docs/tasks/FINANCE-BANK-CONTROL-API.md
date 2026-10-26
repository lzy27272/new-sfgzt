# F11 银行实际资金日记账与跨域净核对

本说明补充现有银行文件预览、原子提交和受控报销付款匹配；不连接或执行真实银行转账，不修改原银行流水。

## 账户对账

`GET /api/v1/finance/control/bank-statements?bankAccountId=&through=YYYY-MM-DD`

仅 finance.read / finance.pay 且在资金账户组织范围内可读。账户号码仅保留末四位；未知法人不得推断绑定今天的门店公司。

- 银行余额：账户期初额 + 期初日（含）至对账日（含）的真实银行入账 - 出账；期初前流水不重复计入。
- 日记账包括原 FO 实际资金动作（无论是否旧受控指令入口）、有真实 finance_payment 的 CLAIM 执行指令、FS 明确关联银行的退货退款、FB 明确通过银行匹配分配到该账户的真实收付。
- 受控指令状态不是现金事实：缺对应付款/资金动作的 EXECUTED 指令单列待核对，不凭指令造现金。
- FX 资金登记差错按独立确认的 accounting_on 作为负额行，原正额现金行不覆盖；原业务在期初前而更正在期初后的，仍独立列新更正，不重记原资金。不是执行退款。
- FB 收入/工资/迁移未清/内部往来没有可推断的银行账户，只能使用实际 fb_bank_match 分配额。其匹配逆向按 accounting_on 单列负额；未分配的业务资金保留“账户未归属”，不按门店猜账户、不把全额复制到多个银行账户。
- FS 资金分配与退款按 accounting_date 截止，非资金抵货/换货不进现金；不会再生成一笔收款冲复制原资金。
- 银行已分配额统一使用净 FX（FO+CLAIM）+净 FB+FS，截至所选日期的实际匹配及逆向。银行未达、超配/负配、未归属及受限来源分别列示。
- 工资、税务来源分别检查独立权限；缺权限不返回对应现金明细，已识别工资银行摘要及对方名隐藏。缺权、未知主体、跨法人错配、未归属资金或孤儿指令时，不以部分余额冒充全额：controlledCashBalance 和 difference 为 null，complete=false。visibleRegisteredCashBalance 仅为当前有权且能归属的登记部分。

`complete=true` 只表示当前已登记来源、账户归属及银行流水对平，不等于银行对账单资料已经被证明完整。没有登记的外部现金不能由系统推断不存在。

响应保留旧字段，同时增加 `entityKnown`、`restrictedSourceCount`、`unassignedBusinessCashFacts`、`invalidEntityOrAllocationCount`、`orphanInstructionCount`、`basis`、`completenessMeaning`。前端应显示上述完整性说明及 cashJournal，不能只显示银行未达表。

## 报销付款匹配

`POST /api/v1/finance/control/bank-transactions/{id}/claim-match`

`{requestId,instructionId,amount,note}`：finance.pay + 对应银行及付款组织范围；同银行/同冻结法人、已实际发生出账、真实 finance_payment、非借款抵报销。以 fx_cash_effective_amount 净实际付款及 fx_bank_instruction_matched_amount 净历史分配校验可分配额；银行剩额用统一跨域净分配，不再拿名义指令金额或原正额匹配凑平。相同请求/内容幂等，改内容409。未知主体、期初前流水、未发生/非现金指令均明确拒绝。

## 验证边界

`FinanceBankControlIntegrationTest` 五项真实 PostgreSQL / app强制RLS 回归覆盖：FX跨期历史、FB明确账户及逆向、CLAIM净额与幂等、独立工资/主体/范围、FS真实退款及登记时点。测试不访问真实银行或生产原件；同提交CI、迁移/恢复和线上验收仍由发布流程执行。
