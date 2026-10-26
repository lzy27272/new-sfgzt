# 报销、独立收票与岗位待办 API

本文件记录已编写接口，不是生产发布或完整 V2 验收声明。前缀 `/api/v1/finance`；身份、实际生效任职、组织范围和权限沿用中台现有认证链。

## 报销明细及待配置草稿

原有 claims API 保留。`Draft.items` 支持 `id, categoryId, description, quantity, unitPrice, amount, departmentId, allocationMonth, invoiceIds`。数量和单价最多 4 位小数，逐行金额采用 `HALF_UP` 到分，完整明细合计必须等于申请金额。金额使用十进制字符串，不能用浮点数计算。

未配置主体/完整审批时仍可创建云草稿，详情给出 `configurationIssues`。不完整的明细、发票候选分别保存受限 JSON，GET 恢复，不虚造类别、税号或金额；正式提交比较完整明细数量并重新校验。未确认候选不能冒充正式发票。

多行单据不允许财务仅修改总额或首行类别覆盖所有行，应退回修改明细重审。四段审批沿用已确认默认链，审批快照与版本不可由后续配置静默改写。核算来源按每行处理方式与类别生成，未映射的来源不自动过账；存货、资产及预付不能默认记费用。

## 独立收票资料库

独立表 `finance_invoice_intake`，创建时不产生报销、费用、付款或票号占用。

- `POST /invoice-pool`：`{requestId,assignmentId}`。
- `GET /invoice-pool?page=0`：真实独立资料列表及全量 `total`，每页 50。
- `GET /invoice-pool/{id}`。
- `PUT /invoice-pool/{id}`：`{expectedVersion,invoiceCode,invoiceNumber,amount,buyerTaxNumber,confirmed}`，金额可空，确认时要求完整。
- `POST /invoice-pool/{id}/original`：multipart `file`；返回最新资料及版本。
- `GET /invoice-pool/{id}/original`：受权下载原始字节，`no-store`、附件模式、哈希复核。
- `POST /invoice-pool/{id}/associate`：`{claimId,expectedVersion}`，版本是目标报销当前版本。要求资料与目标均本人、有效任职、同组织、可编辑。返回目标报销详情。关联只复用受保护原件字节，不重新压缩/旋转/覆盖；正式提交才原子排重占票。
- `POST /invoice-pool/{id}/verification`：`{note}`，只记录独立有范围审核人的人工核对。状态 `MANUAL_CONFIRMED` 不等于外部验真；历史核对关联票号、金额和税号快照，修改后旧核对不冒充当前值。

返回资料字段：`id,assignmentId,hotelId,hotelName,invoiceCode,invoiceNumber,amount,buyerTaxNumber,confirmed,claimId,status,version,verificationStatus,createdAt,canEdit,originalName,mediaType,sizeBytes,sha256,extraction,scanStatus`。

单份原件不超过 20 MiB。PDF、JPG、PNG、XML、OFD 按实际内容识别并原件扫描。XML 禁止 DTD、外部实体和外部资源；OFD 限制目录穿越、条目数量及解压大小。OFD/XML 当前提供安全文字候选和原件下载，不伪造图片预览；照片 OCR、真实税务验真服务仍未接入。

## 付款与回单

原 `/claims/{id}/payment` 支持分次实际付款登记，按锁定报销累计余额校验，`requestId` 幂等。不会执行银行转账。详情包含 `paidAmount,unpaidAmount,canPay,canArchiveReceipts`。

指定出纳可在获批后通过原附件端点补传 `PAYMENT` 原件，不可修改发票或交易证据。`POST /claims/{id}/payments/{paymentId}/receipt` 接受 `{attachmentId}` 关联本单真实付款回单。已全额付款仍可补回单；记录付款、银行匹配和回单归档不是一个状态。

## 岗位工作台

`GET /todos?page=0&kind=review|payment|returned|receipt`（kind可省略）返回 `{items,total,counts:{review,payment,returned,receipt},page}`。counts是当前权限范围各类别全量精确数，total为当前筛选类别的精确数；报销和业务单据使用 SQL UNION 同谓词、统一排序分页，不拿首屏条数当总数。仍有效跨日待办不受今日工作日期限制。业务工资、税务明细继续检查独立权限。总览统计卡筛选此统一队列，不跳到只含报销的旧列表。

条目：`id,kind,title,hotelName,applicantName,amount,status,dueAt,waitingSince,mismatch,version,domain,documentType,target`。`domain=CLAIM|OPERATION`；同单可同时待付款和待补回单，因此渲染键应包含 domain/id/kind。`target` 为受保护导航参数，不是外链。

审批、退回、待付款写入现有 notification 幂等队列，WeCom 仅向当前匹配节点发送；FINANCE/FINANCE_OPERATION 安全 OAuth 路由均校验完整白名单参数。回查服务端权限和版本后办理，不依赖消息本身授权。

## AI 与剩余边界

现有 AI-data 增加 `FINANCE_CLAIMS` 真实有权结构化数据集，含单据来源、版本、期间、状态、金额和异常，排除姓名、银行账号、税号、原件。通过既有 AI-data 连接、快照和报告权限规则访问，明确不是完整总账报表。

尚未完整实现：实际 OCR/税务验真提供商适配、差旅标准制度、任意可发布单据类型流程模板/受控代理、自动 AI 财务月报模型执行器、原件到期保管审批及恢复演练。这些不能因接口或页面已存在而被验收为完成。
