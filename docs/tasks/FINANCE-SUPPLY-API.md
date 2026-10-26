# 采购、存货与资产衔接 V2

代码日期：2026-10-24。此文是接口和会计边界，不是生产上线或替代金蝶验收声明。

## 真实来源与权限

统一前缀 `/api/v1/finance/supply`。每次请求使用服务端有效任职，强制租户 RLS，财务读权限与组织范围共同校验。`finance.read` / `finance.accounting.read` 才能读取组织范围内财务汇总；只有 `finance.submit` 的原采购申请/收货人只读本人参与批次，未指派 `finance.review` 不获得同店他人采购或全部资产。原票候选在非全量读身份下仅返回本人票。原始发票下载继续走原安全原件服务，不在新域复制文件。

采购数量、收货复核、库存移动及加权平均余额复用 `ProcurementService` / `StoreResourceService`；不新建实物库存。新增 `fs_*` 是财务确认、价值调整、实物调拨衔接及历史事实。

## 接口

| 路径 | 行为 |
|---|---|
| `GET /context` | 当前授权门店、真实权限、计价说明 |
| `GET /receipts?hotelId=` | 按可见性返回已主管复核收货批次及独立财务状态 |
| `GET /receipts/{id}` | 原申请数量/单价、实收数量/价值、票据、差异及退换联查 |
| `POST /matches` | `requestId,receiptId,expectedVersion,estimate,invoiceIds,lines[{lineId,quantity,unitPrice}],accountingDate,note`；逐行匹配，不接受客户端自行声明“匹配成功” |
| `POST /recognitions/{id}/confirm` | `requestId,expectedVersion,note`；必须快照中的独立财务确认人，确认差异或暂估，不能申请人自审 |
| `POST /recognitions/{id}/payable` | `requestId,partyId,dueDate,note`；实际发票确认后按净应付生成唯一原 FO 付款申请草稿，再走四段审批，不另造付款事实 |
| `POST /returns` | `requestId,recognitionId,lineId,quantity,kind RETURN/EXCHANGE,accountingDate,note`；按原批次、累计可退量约束，服务/工程以实际验收扣款而非伪造扣库存 |
| `POST /returns/{id}/review` | `requestId,expectedVersion,approved,note`；原采购配置主管独立复核。存货真实扣减既有库存，保留原采购/复核历史 |
| `POST /returns/{id}/settlement` | `requestId,kind REFUND/CREDIT/REPLACEMENT,amount,bankTransactionId?,targetRecognitionId?,replacementReceiptId?,note`；快照独立出纳、真实 IN 银行流水/同法人同供应商后续货款/真实替换复核批次 |
| `POST /inventory/sync` | `hotelId,throughDate,limit(1..500)`；不可变库存/耗用/盘点/冲销来源幂等接入，既有 4 位价值累计到分保留尾差，采购入库及采购退回不再次当耗用成本 |
| `GET /inventory/reconciliation?hotelId=&throughDate=` | 实物数量/价值、移动合计、价值调整、旧成本逐笔核对、原业务日/实际入账日、未过账及供应商净额联查 |
| `POST /inventory/opening` | `requestId,subledgerId,expectedVersion,note`；承接已生效库存期初到现有计价余额，不动实物数量、不重复总账购置。存在切换后流水则拒绝覆盖 |
| `GET /assets?hotelId=` / `GET /assets/{id}` | 原实物资产与财务卡片/在建项目、原值、累计折旧、净值、凭证状态、双方一致性 |
| `POST /assets` | `requestId,sourceDocumentId? 或 openingSubledgerId?,physicalAssetId,bookId,assetCode,construction,residualValue,usefulMonths,inServiceDate,depreciationStart?,assetAccount,cipAccount,depreciationAccount,expenseAccount`；购置须原批准事项对应科目足额过账；迁移须 ACTIVE 原值/累折双科目、固定实物映射/原投用日期/残值/剩余月数一致，不再确认购置 |
| `POST /assets/{id}/events` | `requestId,expectedVersion,action CAPITALIZE/DEPRECIATE/TRANSFER/LOSS/DISPOSE,occurredOn,targetHotelId?,targetBookId?,proceeds,disposalCost,receivableAccount?,gainAccount?,lossAccount?,payableAccount?,note`；产生真实、平衡、多行待复核凭证，不自动过账或编造到账 |
| `GET /physical-transfers` | 范围内实物调拨及当前请求/接收状态，非全量读须本人或实物管理权限 |
| `POST /physical-transfers` | `requestId,physicalAssetId,targetHotelId,expectedVersion,note`；调出方资产管理权限、快照当前实物，目标配置负责人独立接收前不改归属 |
| `POST /physical-transfers/{id}/confirm` | `requestId,expectedVersion,note`；目标负责人 + 资产管理权限 + 目标范围；同一实物 ID 移交，清除旧店适用部门/岗位并保存原快照，未完成门店交接期间阻止调拨 |
| `POST /physical-transfers/{id}/cancel` | 原调出人撤回或目标负责人拒收未完成请求，保留历史，不改实物 |

所有金额使用 `BigDecimal` 和十进制文本；数量/单价最多 4 位。请求幂等绑定完整内容，换内容复用 ID 拒绝。并发确认、退货和资金分配采用事务锁；跨银行分配复用现有 `tenant:bank-match` advisory lock（seed 89）并扣除 FO、FB、FC、FS 的已分配金额。

## 会计与库存口径

1. `line_kind` 扩展原采购清单：INVENTORY 仍必选原品目；SERVICE / ASSET / PROJECT 必须真实名称、规格/验收单位，且 `item_id` 必须为空。旧三参数 `Line` 与旧 JSON 仍兼容。原主管验收非库存事项仅冻结 `accepted_value`，不生成库存移动。
2. 发票必须实际归集原件、确认购方税号及本主体、未被报销占用；一组票金额与逐行含税合计严格相等。数量/原价差异留原值和票值，由独立财务判断，不因平台补贴等差异直接拒绝。
3. 暂估从原复核真实价值取数；发票后到生成独立调整，不改旧采购/成本。价差按原批次保守剩余量拆分库存与已耗用调整；`fs_stock_valuation` 留前值/后值/原行/差额，未来 WAC 从原余额继续。
4. 退回的库存价值采用当前移动加权平均；原票应付冲减与实际库存价值不同的部分分别产生价差会计来源，不能只记一条平不了的分录。原批次已耗用部分不会因后续补货恢复可退权。
5. 采购退款是实际银行入账的分配，并非又一次新增收入。抵款只用于同法人同供应商后续已确认货款。生成 FO 草稿、四段资金审批与总账确认是不同状态。共享 Guard 在实际付款时重查净应付上限，不能通过旧入口付超。
6. 服务验收扣款不写实物扣库；工程/资产的实物/价值后续变更应结合原资产及在建核算，不把验收扣款当作删除历史采购。
7. 资产来源与实物标识唯一。原值、累折、净值、处置收益/损失、应付处置费用在真实分录中分别计算。处置收益是应收而非已收现金，处置费用是应付而非已付现金；独立出纳另行执行。前一事项未过账不允许后续核算；锁期失败整笔事务回滚。
8. 同法人调拨先完成原实物交接，再生成调出/调入两个账套的原值、累折和内部清算对应凭证；跨法人不得冒充同法人调拨，须双方往来交易。
9. `FS_PROCUREMENT` / `FS_PROCUREMENT_RETURN` / `FS_RETURN_SETTLEMENT` / `FS_INVENTORY` 的来源保存真实业务表、业务 ID、冻结公司和稳定 `ruleEventKey`。未发布科目映射显示待配置，不猜科目；自动生成来源/草稿不等于已复核过账。

## 验收状态

最初 8 项 PostgreSQL app 角色测试经 root 运行通过。此后增加采购非库存扩展、完整资产处置/锁期回滚、实物双确认、同店越权否定、4 位尾差等测试，最新 14 项必须由 root 统一构建重新执行；本文不将旧 8 项通过扩大为最新全量通过。前端 `finance-supply.test.mjs` 两项十进制/结构测试已通过，最新 TypeScript 构建已通过。尚需根任务完整联调、现有采购回归、迁移演练、同提交 CI、原件/数据库恢复及生产页面验证。

## V102 原票占用错误的独立释放

`GET/POST /invoice-releases`、`POST /invoice-releases/{id}/review` 保留每次占用的票号、真实原件 SHA、主体和原匹配快照。仅未实际确认票成本的 PENDING 匹配可以申请；独立财务确认后释放 active 占用。若此前存在已确认暂估，只释放错误新票而保留原暂估；否则回到 UNMATCHED 等待重新匹配。拒绝保留票占用。确认后成本、应付、退货依赖存在时不允许只释放票让另一报销再次列成本。

原 `fs_invoice_use` 从不删除。释放时间/独立释放 ID 有完整不可变事实；部分唯一索引只约束 active 使用。正反向报销/采购仍共用 tenant `:invoice-registry` seed69 事务锁。尚不提供已确认成本的完整作废/逆向释放入口：需正式反向经济事实与新凭证闭环，不能把错误占用释放等同于红冲成本。

## V103/V104 真实预算承接和明确成本维度

原采购行扩展 `financialCategoryCode,departmentId,budgetUnitPrice`。成本类别必须来自本门店独立发布的 CATEGORY 政策，不解释物品的物理分类；预估单价按申请输入单位，实际下单仍按真实 `unit_price/factor` 重新核对。草稿可以缺项；已有预算时未知价格不能以零通过采购预算。

`GET /financial-dimensions?hotelId=` 返回授权品目、真实部门、历史未归类采购、实际领用待归类、fbu 真实预算流及未完成问题。原配置权限本身不赋予全部财务读取权限。

- `POST /financial-dimensions/items`：配置未来品目的明确耗用类别和可选建议部门，不能改写旧流水。
- `POST /financial-dimensions/movements`：原来源没有实际部门时，由非原登记人的独立财务明确本次真实耗用部门。实际工作任职能够证明部门则在原流水首次写入时冻结；从不把品目建议部门当作实际使用证据。
- `POST /financial-dimensions/procurement-lines`：已批准历史原行没有财务维度时，由非原申请/采购经办人按原件追加不可变归类。原审批、原行和原金额不改；已关账期不静默补写。

`fbu_flow` 跟踪在途、承诺、费用/资本实际及库存购入资金。只有 FS 财务确认/暂估后的接受数量按原采购价释放承诺；实物收货复核本身不会提前释放。真实成本按原财务来源的两位金额和累计尾差承接，版本重放不重复。供应商退货使用原退货冻结会计日期，不用今天或原采购日期替代。

特别说明：`inventory_value` 在预算流中表示**累计已确认购入资金基数（减正式退货）**，不是现存库存价值，也不是费用实际。现存价值应看实际库存余额/财务计价核对；真正领用、盘点、服务或资本化才属于相应成本事实。未归类列待核对，不填零隐藏。

专项测试 `FinanceSupplyDimensionsIntegrationTest` 已补真实 PG app 角色场景：单位换算、物理收货不释放承诺、财务确认后部分释放、同版重放、分币尾差、冻结退货月、历史归类及实际部门归集。最新是否全部通过，以根任务统一测试报告为准。

已耗用价差的实际部门不能从采购默认部门猜测。`POST /financial-dimensions/price-costs` 以真实 `sourceEventId` 和逐实际部门 `financialCategoryCode,departmentId,amount` 分配，正数合计必须精确等于原价差绝对值，增减方向由原来源冻结。独立财务追加不可变归集，不重复会计费用；未分配保留问题并挡住月结。

## 实际采购资产/工程原行直接承接财务卡

`POST /assets` 增加第三种互斥真实来源：`procurementRecognitionId,procurementLineId,procurementAllocatedAmount`。必须原票/差异已实际确认，逐行对应资产/在建科目已独立过账且未红冲；残值、原实物 ID、账套主体必须一致。同原行卡片分配原值合计不得超过真实净资本金额，不要求伪造旧 FO 资产单或先付款。原采购资本金额与已分配卡片在来源列表并列保留，未分配金额不称作已经建卡。

明确“台/部/辆/张/把/个/件”的独立设备单位限制卡片数不超过原完整验收数量。其他套/包/项等组合单位当前只支持原整体一张实物卡，不提供组合部件拆卡功能；工程 PROJECT 按在建价值归集，不把进度数量当设备台数。已经承接卡片的资本原行不能再用采购退货入口重复冲减资产，需通过原卡片处置及真实应收/供应商结算流程。

最新测试边界：根任务第14轮确认 Supply18、F102 release7、FAR11、F20 7 通过；其后增补的采购多实物建卡、数量上限、预算16+场景及FAR新版预算桥接仍待最新全量报告。不得把旧通过结果扩大成新全部通过或生产已部署。

## V107 明确项目预算维度

原采购行、独立金融归类、已耗用价差分配使用可选 `projectKind=NONE|CONTRACT|CIP,projectId`。只能关联同门店、同实际法人、权限内的已批准真实合同或既有在建卡；服务端冻结真实来源版本与主体快照，不接受手填项目名称或按科目/物品类别推断项目。`GET /api/v1/finance/projects/choices?hotelId=` 是共用选择器来源，清单不完整与当前不可选的原冻结项目均明确提示，不静默清空。

项目随原采购行继承到财务验收及正式退货，领用按原工作证明的实际部门及独立财务确认的项目承接。已经冻结真实实际的成本不允许通过追加归类搬到另一个项目。FBU 逐源、原行、月份、项目唯一；部门总预算 `ALL` 包含未关联及各项目，专用预算精确匹配实际 `CONTRACT:id` / `CIP:id`，不会把其它项目的占用挪来或把未关联项冒充指定项目。

库存采购资金预算单独使用在途 + 承诺 + 累计已确认购入资金减正式退货；不会因收货/财务验收释放承诺后重新开放同一笔资金限额。此口径不是费用实际，不是现存库存。费用预算仍仅使用真实消耗/服务及当期摊销，资本预算使用实际验收资本来源。

同月、类别、实际部门存在未确证项目的历史占用时，项目专用预算不能过滤该来源后按零放行。新源明确选择 NONE 会标 `EXPLICIT_NONE`，历史空快照不冒充已核实。已冻结且没有受控归类路径的原占用，只能先核原件后独立改用总预算或从下一期启用专用预算；本界面不能修改已承接的历史项目。真实已发生成本仍保留并列问题，停用原专用政策后旧问题仅保留审计，不永久阻断新总预算。

当前专项源码：Supply 21、Dimensions 24（V107）、FAR 14（V111）。根任务第17轮确认 Supply 21、F102 release 7 通过；该轮发现的真实分类 fixture 和不可变银行流水读锁问题已修复，尚待新快照回归。项目、设备数量和库存资金占用用例须以最新统一 PostgreSQL 报告为准，不将编码完成当作验收或生产部署。

## V109 原实物盘盈的真实独立闭环

`/supply/asset-gains` 使用同店原 `store_fixed_asset.id` 和实际法人账套，不创造第二套实物，也不以手填 FO 资产占位。`GET /context?hotelId=` 返回真实账套、候选实物、部门、已发布资产类别和真实科目。`POST` 冻结实际日期、价值、责任部门、类别及本主体财务处理依据；数据错误须撤回重建而非改写原估值。已有财务卡、已核对迁移资产或明确已关联资本来源的实物不能再次认盘盈。

`/{id}/originals` 归档真实 multipart 估值/盘点依据原件，复用 `app.finance.attachments-root` 受保护财务原件卷及原文件安全扫描、不可变 SHA、字节完整性校验，不旋转压缩。`/{id}/submit` 必须有真实原件并冻结指纹，原制单人不可自审。`/{id}/review` 仅后台配置、冻结且独立的财务/总监及相应读取、核算、复核权限可操作；批准生成真实 `FS_ASSET_GAIN.ACCEPTED` 会计来源与待复核凭证，既不是现金收入，也不是采购付款或重复费用。HTTP 批准会重新校验原文件存在与字节指纹；原件篡改/丢失会保留待审并明确失败。

`/{id}/card` 只能在真实凭证由另一财务独立复核过账、未红冲且实际科目净额等于原估值后承接唯一财务卡，不能凭 APPROVED 标签直接建卡。来源保存实际 `gain_id`；后续直线折旧、实物调拨、同主体价值调拨、盘亏和处置均复用原财务卡生命周期，不另计购置。月结按真实盘盈审核、原件、过账及唯一卡片依赖核对，未建卡的真实盘盈仍列资产来源，不隐藏为零。系统不代替外部估值专家，不自动保证税法/会计处理合规。

审批/企微目标：`{tab:'supply',screen:'asset-gains',gainId}`，页面先从授权真实申请反查门店，禁止默认第一店。原件下载、归档和跨店读取仍需实际来源权限。

新增 `FinanceAssetGainIntegrationTest` 9 个真实 PG/app-role 场景（迁移到 V111）覆盖原件→独立估值→独立过账→唯一卡、自审/错人/跨店/RLS、缺原件、重复来源/建卡篡改、原实物漂移、原件字节篡改、并发幂等及后续折旧的真实部门承接。已承接财务卡的原确认凭证不能孤立总账红冲而遗留有值卡片。尚待根任务统一 Maven/全量迁移、浏览器及部署验收，不称已经上线。

V111 采购及真实领用按原行、实际部门和冻结项目传递辅助核算维度；盘盈使用独立确认的真实部门，后续折旧回查原卡片来源。迁移期初未提供辅助维度、调拨后原部门已不属于当前门店，均明确标未分配，不臆测新部门。待摊运行先保存真实不可变分配，再按同一份快照生成双方分录和来源快照，不二次拆分或重复按比例分摊。

尚未生成凭证的领用/已耗用价差来源，如后续独立追加真实 `fs_movement_finance_classification` 或 `fs_price_cost_allocation`，会创建唯一 append-only `fa_auxiliary_completion`，绑定原事件请求指纹及真实分类复合主键。原事件快照不改，原明确部门/员工/交易对方/项目及原名称快照不覆盖；只补真实缺维度后重试来源。已有凭证即使仍为草稿也不回填历史，需另走开放期受控重分类。Dimensions 新增 3 项场景后共 27 项（V111），尚待根任务统一回归。

月结不会要求仍在建的 CIP 卡生成业务禁止的折旧；真实转固按实际投用月份开始检查，即使月中投用也不能漏掉该月。已处置状态不粗略豁免历史应提折旧；已提足在用卡不虚造新的折旧任务。
