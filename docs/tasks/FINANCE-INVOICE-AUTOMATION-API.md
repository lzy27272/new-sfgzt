# 真实OCR与外部发票验证（V95）

默认未配置、不会自动外传资料；本轮测试只用本机隔离HTTP提供方和模拟原件。文字解析、OCR候选、人工确认、外部验证是不同事实，不互相冒充；外部验证也不代表已抵扣、可报销或已经批准。

## 配置与提供方协议

配置 `hotel.ai.os.finance.invoice.ocr-endpoint`、`verification-endpoint`、`api-key`、`provider`。端点必须可信HTTPS（本地stub允许loopback HTTP），禁止跨站重定向。它是可配置服务适配器，不内置假OCR、假查验或未授权税务账号。

提供方接收POST JSON及 `Idempotency-Key: requestId`：

```
{requestId,operation:OCR|VERIFY,sourceSha256,invoiceFields:{invoiceCode,invoiceNumber,amount,buyerTaxNumber},mediaType,originalBase64?:OCR-only}
```

OCR需要真实原件才传base64，VERIFY仅传本次已确认字段和原件哈希，不额外传原件。企业须先确认提供方、数据传输与保密制度；每次调用还需员工/有权审核员明确勾选本次外传用途。

结果：`{provider,requestId,sourceSha256,externalReference,status,candidates?:{invoiceCode,invoiceNumber,amount,buyerTaxNumber},invoiceFields?:{...}}`。OCR状态CANDIDATES；VERIFY状态VERIFIED/NOT_FOUND/INVALID/INCONCLUSIVE。响应提供方、请求号、原件哈希与冻结字段必须匹配，不能把泛泛HTTP200当验真。候选字段/金额有长度和十进制范围检查，网络响应有体积上限。

网络超时或结果不确定时保留UNKNOWN和原请求号，不能以新请求盲目重传。`GET <same-endpoint>?requestId=...` 只查询提供方原请求结果，返回相同结构或PENDING。提供方须实现该幂等/查询契约；仅有“发送重试”能力的接口不能直接标为已可靠接入。

## 中台API

统一前缀 `/api/v1/finance/invoice-automation`。

- `GET /context` -> `{ocrReady,verificationReady,provider,setupPath,automaticTaxDeduction:false}`。
- `GET /intakes/{id}/jobs`：复用收票原件访问授权，返回真实请求、回执、来源哈希、授权人/时间、状态、不可变证明及matchesCurrentSource。查看其他店资料仍拒绝。
- `POST /intakes/{id}/jobs` `{requestId,operation,expectedIntakeVersion,sourceSha256,allowExternalTransfer:true,consentNote}`：先验证当前版本/原件哈希/权限并短事务固化授权；VERIFY还要求人工已确认字段。只有本人可编辑收票资料或有权审核员能发起，不因能看到菜单而自动获权。
- `POST /intakes/{id}/jobs/{job}/refresh`：只查询原请求结果，不重新传原件。UNKNOWN/PENDING同操作阻断再次外传；服务端成功结果/回执幂等，不重复生成证明。
- `POST /intakes/{id}/jobs/{job}/adopt` `{expectedIntakeVersion,confirmCandidateAdoption:true}`：仅本人未关联、任职仍有效的收票可明确采纳OCR候选；使用已有saveIntake版本防线，取消原确认标记，要求对照原件重新人工确认。不会后台静默覆盖已确认数据；同候选不能重复覆写后续人工修改。

## 事务、原件与证明

start/refresh入口NOT_SUPPORTED，固化和结果分别用显式短事务；外部HTTP与读取文件不持有账套、票据或审批行锁。原件只经 FinanceEvidenceService.downloadIntake 读取，原文件安全扫描状态、路径、大小、SHA256及既有授权均复核，不从用户提供的URL或任意路径读取。

`fia_job`保留冻结版本、规范化票号金额税号、原件SHA256、操作人明确授权、提供方及请求号；`fia_proof`保留真实回执编号/状态/时间和同一冻结来源，数据库禁止修改/删除。只有VERIFY=VERIFIED、回执绑定字段匹配、当前票号金额税号和原件仍与冻结一致，才附加既有 `finance_invoice_verification.EXTERNAL_VERIFIED` 事实。后续NOT_FOUND/INVALID/INCONCLUSIVE附加EXTERNAL_FAILED而不是继续显示旧VERIFIED；它是异常/不确定待核对，不直接代替业务审核驳回。改金额/税号后旧证明自动失配，不继续显示旧状态为当前验真。

OCR永远只产生候选，不自动抵扣、过账、开票或审批。重复发票号仍由正式报销提交事务的全租户票号占用防线控制，不能用OCR候选或外部验证绕过排重。
