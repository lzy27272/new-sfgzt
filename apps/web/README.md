# Hotel AI OS Web

React + TypeScript + Vite 前端。TECH-V0.2 默认读取真实 `/api/v1`，覆盖前台员工、前厅主管、客房主管、店助、店总、区域/运营（OTA经理）六类验收身份，以及工作台、工作包、岗位工作、团队工作、规则、任务、标准评价和通知。

## Sprint 2.1 P0业务页

- 前厅主管：团队工作详情、工作记录复核、基于异常记录创建整改任务。
- 客房主管：巡检图片多选上传、附件列表/预览/删除、按工作包绑定标准创建评价。
- 店总：门店经营快照、风险事项和未完成任务驾驶舱。
- 区域/运营：授权组织树内的多门店提交、任务、逾期、评价失败和漏交对比。
- 六角色切换只用于本地验收；角色、权限和组织范围仍由服务端数据库解析。

P0页面依赖以下后端契约：

```text
GET    /api/v1/dashboards/hotels
GET    /api/v1/dashboards/hotels/{hotelId}
GET    /api/v1/dashboards/operations
GET    /api/v1/work-expectations/{expectationId}
GET    /api/v1/work-data/records/{recordId}
POST   /api/v1/work-data/records/{recordId}/actions/review
POST   /api/v1/work-data/records/{recordId}/attachments/upload  (multipart字段名：file)
GET    /api/v1/work-data/records/{recordId}/attachments
GET    /api/v1/work-data/attachments/{attachmentId}/content
DELETE /api/v1/work-data/records/{recordId}/attachments/{attachmentId}
POST   /api/v1/standard-evaluations
POST   /api/v1/tasks
```

门店列表和单店驾驶舱均要求`dashboard.hotel`；多门店经营驾驶舱单独要求`dashboard.operations`，两项权限互不替代。

工作包详情必须返回精确的 `standardVersionId`；页面不会猜测“最新标准”。任务创建使用原工作记录的责任任职作为负责人、当前主管任职作为验收人。附件上传假设后端完成对象存储落盘、大小/类型校验和扫描状态维护。

## 本地运行

```powershell
pnpm install
pnpm dev
```

Vite 将 `/api` 代理到 `http://localhost:8080`。

## 身份与权限

- 前端首先调用 `GET /api/v1/iam/me`。
- 菜单、账号名称、有效任职和组织上下文以服务端返回结果为准。
- `pnpm dev` 默认使用 `VITE_AUTH_MODE=dev-header`，只发送 `X-Tenant-Id` 与 `X-Actor-Id`。角色、权限和组织范围仍由服务端数据库解析。
- 普通 `pnpm build` 默认使用 `VITE_AUTH_MODE=server`，仅适用于明确采用服务端 Cookie/SSO 会话的部署。当前公网 Pilot 必须使用 `pnpm build:pilot`；Bearer JWT 通过 `X-Hotel-AI-Authorization` 发送，由 Caddy 转换为后端 `Authorization`。如部署环境直接连接 Core API，可用 `VITE_BEARER_HEADER` 显式覆盖。生产界面不会显示验收账号切换器。
- 切换任职只改变工作业务上下文，不在客户端授予权限。

### 企业微信任务入口 V1

- 群卡片的任务按钮先访问 `GET /api/v1/integrations/wecom/oauth/start?returnTo=%23/tasks%3Fview%3Dmine%26taskId%3D<UUID>`，由后端完成企微 OAuth 和 state 校验。
- 后端在已校验 OAuth state 与浏览器校验 Cookie 的回调中完成一次性交换，通过同源200中转页先提交 HttpOnly 会话 Cookie，再以无脚本的页面刷新进入目标任务；一次性码不会进入浏览器 URL。`/wecom-auth` 仅保留用于兼容短期内已生成的旧回调。
- 后端会再次校验 `returnTo` 只能指向允许的站内工作台、任务或日报路由，再生成最终跳转地址。
- 企微 Secret 不进入前端；用户短期令牌只通过 `Secure`、`HttpOnly`、`SameSite=Lax` Cookie 传递，不写入 URL、`localStorage` 或 `sessionStorage`。`sessionStorage` 仅记录不含凭据的会话来源标记。
- 普通 Pilot 账号密码登录保持原有流程，不经过企微入口。

## 演示回退

正式构建默认不使用演示数据。仅在需要离线走查视觉时显式启用：

```powershell
$env:VITE_ENABLE_DEMO_FALLBACK='true'
pnpm dev
```

也可访问 `?demo=1`。回退只在 API 请求失败时发生；API 正常返回空集合时显示真实空状态。

## 构建

```powershell
pnpm build:pilot
```

该命令读取 `.env.pilot` 并强制 Bearer 登录；当前公网 Pilot 发布不得用普通 `pnpm build` 替代。`vite --configLoader runner` 用于兼容 Windows 受限工作区，不改变产物格式。
