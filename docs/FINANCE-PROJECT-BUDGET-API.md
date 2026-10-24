# F17 真实项目与预算维度（V107）

项目复用同组织、同实际法人且当前可见的已批准合同（`CONTRACT`），或真实在建工程卡（`CIP`）。不新增项目模块，不从名称、任意 UUID 或来源编号猜测项目。

## 实际选择与冻结

`GET /api/v1/finance/projects/choices?hotelId={真实组织UUID}` 返回 `items`（`kind/id/key/name/version`）与 `complete`。仅返回实际可见来源；法人未配置返回待配置错误，超过500项明确标记不完整，不以截断列表认定来源完整。

- 报销费用行传 `projectKind/projectId`；业务单据放在 `details` 同名字段。
- 采购明细、独立历史财务归类、领用分类及摊销分配使用同一服务端校验器。
- `NONE` 必须不带项目 ID，`CONTRACT/CIP` 必须带实际来源 ID。源业务不能传预算用的 `ALL`。
- 服务端按实际来源重建 `project_key/project_snapshot`（名称、组织、法人、来源版本）；不相信前端传入的快照。
- 原验收、退货、成本分摊使用原来源冻结项目；后续在建卡转固不能把历史项目默认为 `NONE`。
- 旧客户端省略项目字段时不静默清除已有费用行或业务单据的选择；明确改为 `NONE` 才清除草稿关联。正式审批快照、费用事实及来源凭证不被草稿修改覆盖。

`NONE` 加空快照的历史来源是“历史未核项目”，不代表来源完整、没有项目或项目金额为零。

## 实际类别绑定

`finance_category.budget_code` 是财务明确配置的组织内唯一稳定编码。类别 API 的可选 `budgetCode` 字段用于配置；省略字段保留旧值。编码不能从随机类别 ID 或名称推导。

新预算方案发布时同时指定实际 `categoryId/categoryCode`，后台校验同组织启用类别及精确稳定编码，并冻结 `categorySnapshot`。无映射、错 ID、跨组织类别不能发布。旧 code-only 方案没有真实唯一映射时明确阻断待配置，不能按零占用放行。已有预算或原费用引用的稳定编码不能直接改名；需新增类别／独立修订。

## 预算方案与占用

`POST /api/v1/finance/control/policies` 的 `kind=BUDGET`，`spec` 包含月份、精确金额、真实类别 ID/编码、可选实际部门，以及：

- `projectKind=ALL`（默认）：部门／类别全部项目的总预算，包含未指定项目。
- `projectKind=NONE`：仅未指定项目。
- `projectKind=CONTRACT|CIP` 加实际 `projectId`：只约束对应真实项目。
- `budgetKind=EXPENSE|CAPITAL|INVENTORY`：费用／资本／库存采购资金三种性质，不互相冒充费用。
- `control=WARN|BLOCK`：预警留痕或超额阻断；独立发布、原单版本限额例外及原有自审回避仍适用。

发布冻结 `projectKey/projectSnapshot`。总预算与项目专用预算分别校验，不把两条预算占用相加成二次成本。`fc_budget_usage.project_key` 保留该方案的匹配范围；`fbu_flow` 唯一源键包括 `project_key`，支持同部门不同项目的实际分摊。

跨入口占用统一为在途＋承诺＋实际；仅库存资金预算另加真实累计购入 `inventory_value`，不是当前存量。库存收货不是当期费用，领用费用仍用原实际成本来源。快照标记 `BUDGET_FUNDS_USAGE_NOT_EXPENSE`。已承接采购的付款单据不再次占用同一预算，预算方案本身也不生成会计事实。

## 验证边界

新 `FinanceProjectBudgetIntegrationTest` 使用完整V107、真实 PostgreSQL 应用角色与实际四段合同审批；报销随机类别 BLOCK/WARN 与复核快照在 `FinanceIntegrationTest`。结果以统一回归日志为准，本说明不声明已经部署或外部支付成功。
