# 学院中心实施记录

状态：本地实现与验收完成，未部署到云端。功能与效果图均经用户确认；本批次不改变既有生产发布状态。

基线：5766908；工作分支：feat/academy-center-20260923。当前远程主线 b4cba6a 已包含 V74—V78（财务、日清、库存及采购），学院顺延为 V79，保留已发布迁移。

## 业务结果

- 员工类型固定为实习员工（INTERN）、正式员工（REGULAR）、第三方委派（兼职）（SECONDMENT），与账号启停、主岗/兼岗分别保存。新增员工和新员工入职审批必填；历史空值显示未设置，不推断、不自动派发。既有身份换绑或任职调整保留原员工类型。
- 只有有效任职、有效企微绑定和启用账号同时满足的实习员工，才自动获得该岗位已发布成长地图。按最近组织范围匹配，每份任职只创建一个计划；待配置记录保留并自动补发。
- 员工类型变更为另外两类后，已派发计划继续学习、考试、补考、提醒及阶段推进，直到完成。调岗沿用现有“结束旧任职、新建任职”流程；旧计划暂停且历史保留。账号换绑后旧账号无法继续学习。
- 课程完成后按当前阶段课程、题型、知识点、难度随机组卷；及格才开放下一阶段。试卷与规则保留快照，刷新不换卷，超时按服务端已保存答案计分。支持补考间隔、次数上限与有审计记录的追加机会。
- 课程库、题库及 CSV 批量导入、地图阶段配置与版本发布、我的学习、历次考试记录、团队进度与提醒、员工工作台入口均已接入真实 API。

## 关键实现

| 范围 | 位置与约束 |
| --- | --- |
| 数据迁移 | database/migrations/V79__academy_center.sql；员工类型、12 张学院表、事务派发队列、强制租户 RLS |
| 后端 | apps/core-api/.../academy；员工锁、顺序学习会话、服务端计时、考试版本检查、事务判分与解锁 |
| 前端 | apps/web/src/features/academy；管理端和手机端、受控视频与逐页 PPT |
| 权限 | ui.module.academy、academy.learn、academy.team-read、academy.manage；管理配置要求租户范围，团队数据按授权组织过滤 |
| 通知 | 个人学院通知进入现有企微投递/重试链，直达本人计划；配置异常、逾期和机会用完通知授权管理者的站内消息 |
| 版本 | 已发布课程/地图需新建版本，已有计划继续引用旧版本；题目改版不影响已发试卷 |

学院管理权限默认授予平台管理员；团队查看可由既有岗位权限配置授权。管理通知复用当前岗位方案和组织范围解析，不能仅凭角色名称越界投递。

## API 入口

统一前缀为 /api/v1/academy，所有请求沿用现有登录、当前租户与任职身份。

| 用途 | 入口 |
| --- | --- |
| 配置目录 | GET /admin |
| 课程与媒体 | POST /courses、GET/PUT /courses/{id}、POST /courses/{id}/media、GET /courses/{id}/media |
| 课程发布与新版本 | POST /courses/{id}/publish、POST /courses/{id}/versions |
| 题目维护与批量导入 | POST /questions、POST /questions/import |
| 地图与发布 | POST /maps、GET/PUT /maps/{id}、POST /maps/{id}/publish、POST /maps/{id}/versions |
| 个人计划 | GET /me、GET /plans/{id} |
| 有效学习进度 | POST /sessions、POST /sessions/{id}/heartbeat |
| 考试 | GET /exams/{id}、POST /exams/{id}/start、PUT /exams/{id}/answers、POST /exams/{id}/submit |
| 团队看板及操作 | GET /team、GET /team/{id}、POST /team/{id}/remind、POST /team/{id}/attempts |

课程和地图保存/发布携带 revision；考试答案保存/交卷携带 version。提醒与追加机会成功返回 204，学院业务校验通过 ProblemDetail.detail 返回可读原因。员工及入职审核原有 API 新增 employeeType 字段。

## 运行配置

部署该分支并执行 V79 迁移后，按目标租户显式配置：

```dotenv
ACADEMY_WORKER_ENABLED=true
ACADEMY_TENANT_IDS=<目标租户UUID，多个以逗号分隔>
ACADEMY_FIXED_DELAY_MS=30000
ACADEMY_STORAGE_ROOT=/var/lib/hotel-ai-os/attachments/academy
ATTACHMENT_MAX_FILE_SIZE=200MB
ATTACHMENT_MAX_REQUEST_SIZE=202MB
```

学院定时任务默认关闭，启用时必须配置租户名单，不能使用示例租户代替生产租户。员工进入学院也会核验本人的派发条件，但无人打开页面时的补发、逾期提醒和超时处理依赖定时任务。多实例通过租户事务锁避免重复扫描。

默认课件目录位于现有持久附件卷内，需与数据库一起备份，不套用普通工作附件到期删除策略。现有 systemd 写权限覆盖该默认目录。Caddy CSP 已加入 media-src 'self' blob:，以支持带鉴权的课件播放。企业微信送达仍依赖现有企微配置、投递任务及有效绑定。

视频上限 200 MB、120 分钟，必须是浏览器可播放的 MP4；时长取服务端媒体元数据。PPT/PPTX 上限 30 MB、200 页，由 Apache POI 5.5.1 逐页生成图片，默认每页至少 5 秒，可调整为 1—600 秒。演示动画以静态页面呈现；发布前应预览，服务器须具备课件所用字体。视频鉴权后完整加载，较大文件在移动网络上首播等待更长。

## 验证范围

- 前端 TypeScript 检查、Vite 生产构建、330 项现有及新增测试通过。
- 后端相关测试共 84 项通过（学院 12、员工维护 7、企微入职 59、企微投递深链 4、身份角色类型 2）。学院 PostgreSQL 集成验证：实习派发幂等、两种员工类型转换后继续、绑定门槛、禁止跳看/倍速、允许回放、会话替换、账号换绑隔离、PPT 真文件渲染与逐页计时、稳定试卷和隐藏答案、超时评分、补考次数及追加、缺失地图补发、旧版本计划保留、题库异常恢复、并发地图发布互斥、跨账号/组织/租户隔离。
- 员工维护、企业微信入职审批及投递深链回归通过。
- 本地浏览器使用真实 Spring API、独立临时 PostgreSQL 和真实 MP4/PPTX；开发服务 http://127.0.0.1:5175，桌面 1440×1000、手机 390×844。已操作上传/预览/发布、题目新增与 CSV 导入、地图新版本发布、员工类型选择/修改/筛选、实际学习、考试保存后刷新、通过解锁和全部完成。
- 应用内 Browser 调用因缺失 browser-service.mjs 失败，用户明确允许改用本地 Playwright。控制台、页面身份、空白页、框架错误遮罩、宽度溢出及关键按钮状态均已检查。截图与临时浏览器脚本保留在仓库外的本次设计预览目录及临时目录。

尚未覆盖：生产环境部署、真实企业微信客户端送达及真机视频解码、超大课件与大量员工压力测试。没有向真实员工发送消息。

PPT 转换依据：[Apache POI 官方幻灯片渲染文档](https://poi.apache.org/components/slideshow/ppt-wmf-emf-renderer.html)。
