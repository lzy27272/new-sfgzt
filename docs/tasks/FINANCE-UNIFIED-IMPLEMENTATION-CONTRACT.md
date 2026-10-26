# 财务统一版实施协作契约

用户于2026-10-24明确授权按当前V2方案编码、合并/推送及部署，覆盖此前等待视觉确认的实施门禁。完整范围见工作区design-previews/20261023-finance-integration/UNIFIED-FUNCTIONAL-DESIGN-V2.md；不能将此文件的实现计划当作已完成报告。

基线：本地及已核验远端origin/main 00e4f820cbce1b45aacdace0f120b344cffd0215；分支feat/finance-unified-20261024。GitHub直连失败，但使用本机已有代理可读取远端，发布前仍须重新核验不可变提交及CI制品。

## 分工与不重叠文件

- 核算域：新增FinanceAccountingService/Controller/Models及其测试、V88__finance_accounting.sql。负责账套、科目、期间、凭证审核过账、余额与报表、月结、迁移。
- 业务域：新增FinanceOperationsService/Controller/Models及其测试、V89__finance_operations.sql。负责供应商/账户、合同/应付/借款/预付、分次付款回单、流水对账、预算、资产/工资/收入来源、税务任务/档案。使用真实表和来源引用，不使用演示数据。
- 原报销与通知域：单独分配后负责FinanceService/Models/Evidence及V90、原报销多明细/待配置草稿、岗位待办API、WeCom通知/直达及财务AI衔接。
- 主代理：全部前端页面、岗位工作台组件、前端测试与浏览器验收，整体集成与发布。共享路径改动先协调；不改他人正在编辑的文件。

## HTTP契约

统一前缀/api/v1/finance；现有接口保留。使用现有TenantPrincipal、AccessPolicy、TenantDatabaseContext及版本/幂等约定。

核算模块前缀/accounting：
- GET /context -> {books,accounts,periods}。
- GET/POST /books；GET/POST /accounts；GET /vouchers?bookId=&month=；GET /vouchers/{id}；POST /vouchers；POST /vouchers/{id}/review、/post、/reverse。
- GET /reports?bookId=&month= -> 账簿、余额/试算、损益/资产负债/现金流及数据完整性信息；导出与页面共用结果。
- GET /periods/check?bookId=&month=；POST /periods/close；POST /imports（试导入/正式导入以显式模式区分）。
- 日期ISO、金额使用十进制字符串，JSON字段camelCase，数据库使用snake_case。

业务模块前缀/operations：
- GET /context -> {types,parties,bankAccounts,...}；GET /documents?type=&hotelId=；GET /documents/{id}；POST /documents；PUT /documents/{id}；POST /documents/{id}/submit、/review、/settle、/cancel。
- GET/POST /parties；GET/POST /bank-accounts；GET/POST /bank-transactions（批次导入及单行）；POST /bank-transactions/{id}/match。
- GET/POST /budgets；GET /calendar；GET /reports?hotelId=&month=。
- 业务类型CONTRACT/PAYABLE/LOAN/PREPAYMENT/ASSET/PAYROLL/REVENUE/INTERCOMPANY/TAX_TASK可共用列表/详情，但每类必须实现实际数据校验与具体状态流转，不得只有空按钮或伪造完成结果。

新增接口可用Map作为返回包络，但写请求应校验字段、长度、金额和状态。列表返回{items,total,...}，不能拿分页长度当总数。

## 集成约束

核算表使用fa_前缀；业务表使用fo_前缀；两者独立迁移，不改旧迁移。业务调用核算服务通过明确方法或持久化事件建立来源映射；先协商签名，不假定另一个代理的方法已存在。

全部新表强制租户RLS，明确应用角色授权；保存租户+来源事件唯一键。真实报表只读已过账分录，缺失映射/来源显示待处理。金额不能用float/double；付款/库存/费用/凭证各按对应事件确认，不能重复记费用。

已确认审批默认链保持初审→门店财务→财务总监→CEO；出纳单独登记。不硬编码真实人、税率、门槛或纳税人身份，不默认授予普通管理岗位付款/全集团明细权限。

当前不是要上线接口壳。发现不能在本次完整实现/验证的项目应立即向主代理报告，最终按实际证据披露，不能将未实现项宣称完成。
