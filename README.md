# Hotel AI OS

酒店集团第二管理体系。当前产品基线为PRODUCT-V1.4；集团身份、岗位关系、董事长受限交办与周/月工作计划已完成DESIGN-1.1和TECH-DESIGN-1.0正式冻结。TECH-V0.2-PILOT.8批次A、B已完成编码、验证和云端内部Pilot部署：运行库已迁移至Flyway V39，集团管理身份底座与董事长仅向集团总经理/副总经理交办的专用闭环已进入部署制品；全部新增业务功能开关仍关闭，未映射真实人员，周/月工作计划与提醒批次C、D尚未开始。TECH-V0.1已发布。TECH-V0.2正式发行仍因正式签署、目标企业SSO、真实现场照片与目标附件链、有效Git标签以及目标环境运维保障未全部关闭而保持Unreleased。

当前可供门店开展受控内部业务测试的版本为`TECH-V0.2-PILOT.8`，产品名称“贵州四方馆酒店管理有限公司中台”，公网地址为https://www.sfgzt.cn。该Pilot运行于腾讯云Ubuntu主机，使用真实PostgreSQL和应用账号；公网、API、数据库迁移、制品哈希以及桌面/移动端登录页已完成部署复验。新增集团能力仍以功能开关失败关闭，只有后续完成真实任职映射和单独启用审批后才能使用。操作说明见`docs/PILOT-TEST-USER-GUIDE.md`，本轮报告见`docs/TECH-V0.2-PILOT.8-CLOUD-DEPLOYMENT-REPORT.md`。Pilot可用不改变TECH-V0.2正式版仍为Unreleased的发布判断。

## Sprint 1 范围

- 组织与权限：租户、集团、区域、门店、部门、员工、岗位、一人多岗、RBAC 数据模型。
- 企业标准中心：标准分类、定义、版本、适用范围和发布。
- 双入口数据：岗位工作记录与经营指标录入。
- 驾驶舱：CEO 和店总基础框架。

## Sprint 2 当前实现

- 工作包中心：定义、版本、适用范围、岗位分配与工作期望。
- 岗位工作：记录提交、复核及与标准、任职的精确关联。
- 企业规则中心：确定性事件条件、模拟、发布和幂等消费；当前动作限定为创建任务或站内通知。
- 任务执行中心：状态流转、SLA、证据、返工与验收。
- 标准评价：确定性评价与人工复核。
- 事件链路：事务Outbox自动投影为管理事件，并触发规则消费。
- 权限：生产使用JWT/SSO受信身份与数据库RBAC；开发请求头仅接受tenant/actor。
- 页面：前台员工、前厅主管、客房主管、店助、店总、区域运营六角色真实API工作台、任务、评价、通知和驾驶舱页面。
- 真实附件：图片上传、列表、下载、删除、结构校验和SHA-256核对。
- 未完成闭环：工作期望MISSED检测、提醒、规则建任务、任务OVERDUE和升级。
- 驾驶舱：店总门店风险/未完成任务汇总和区域多门店运营视图。

当前仓库代码基线包含Flyway V1—V38；V37分离导航模块与页面动作权限，V38加固SYSTEM/CUSTOM角色代码治理。租户业务表继续启用并强制RLS。当前Pilot OpenAPI为`0.2.4-pilot.7`（OpenAPI 3.1.0，81路径、67模型），API主版本仍为`/api/v1`，本轮无破坏性主版本变更。正式判断见`docs/TECH-V0.2-RELEASE-CANDIDATE-FINAL-REPORT.md`。

RC3本地加固运行移除了发布JAR中的数据库密码回退值，并在应用入口增加缺失密钥预检；48项后端测试零失败，真实Live UAT宿主1/1通过并确认Hikari先于PostgreSQL关闭；两次独立构建的5项制品指纹一致，深度敏感信息扫描覆盖160个文件和43,830个归档条目，0命中、0错误。无密钥启动在0.2秒内失败，未创建Spring上下文、未尝试数据库连接、未监听端口。该结果关闭本地代码级问题，但制品仍未绑定Git提交，不能替代正式发布门禁。

TECH-V0.2仍需完成目标企业SSO及账号生命周期验证、真实客房现场照片与目标对象存储/附件备份恢复、10方正式签字、有效Git提交和正式标签，以及目标持久化PostgreSQL的备份保留、加密、监控和运维签署，才能从Unreleased迁入Released。

AI Gateway、绩效复盘和知识中心继续保留为冻结架构模块，后续按技术版本实施。

## 仓库结构

```text
apps/core-api/       Java/Spring Boot 核心 API
apps/web/            React/TypeScript 管理端
database/migrations/ PostgreSQL 数据库迁移
docs/                架构、接口、页面、测试与计划
tools/               本地验证工具
```

## 构建与验证

项目使用JDK 21、Maven、Node.js和pnpm。当前工作区已在`.tooling/`准备便携JDK/Maven与项目级依赖缓存，该目录不会提交版本库。

后端完整测试会自动启动临时PostgreSQL、执行全部Flyway迁移和API集成测试：

```powershell
cd apps/core-api
mvn test
```

当前公网 Pilot 的前端生产构建（强制 Bearer 登录）：

```powershell
cd apps/web
pnpm install
pnpm build:pilot
```

普通 `pnpm build` 使用服务端 Cookie/SSO 模式，只能用于明确采用该认证模式的部署，不能作为当前公网 Pilot 发布包。

无依赖页面原型仍可直接打开：

```text
apps/web/prototype/index.html
```

已验证的生产构建产物位于`apps/core-api/target/`和`apps/web/dist/`。

Sprint 1实施与验收范围见`docs/SPRINT-1-IMPLEMENTATION-PLAN.md`；Sprint 2当前实现与未完成项见`docs/SPRINT-2-IMPLEMENTATION-REPORT.md`。

Sprint 3“AI进入管理闭环”预实施计划见`docs/SPRINT-3-PLAN.md`，当前V1.1已纳入受限CEO Agent及每日《CEO AI经营简报》。该计划用于产品和技术审查，不代表已允许启动Sprint 3编码。

TECH-V0.3技术冻结草案见`docs/TECH-V0.3-TECHNICAL-FREEZE-DRAFT.md`。草案已定义首个受控工作分析闭环和同迁移强制RLS原则，但在TECH-V0.2正式发布、数据安全评审和独立开工批准前不生效。

## 项目长期维护基线

后续所有系统更新、优化和维护必须同步检查以下三份文档：

- 产品总蓝图：`docs/HOTEL-AI-OS-PRODUCT-BLUEPRINT.md`
- 变更日志：`CHANGELOG.md`
- 技术版本记录：`docs/TECHNICAL-VERSION-HISTORY.md`

强制维护规则：

1. 每次产品、功能、数据库、API、权限、安全、页面或运维变更，先在`CHANGELOG.md`的Unreleased登记。
2. 如果改变产品定位、核心管理链、中心边界或不可破坏模型，必须先更新并冻结产品总蓝图。
3. 开始开发、进入测试、完成验收或正式发布时，必须更新技术版本状态和证据。
4. PRODUCT、TECH、API和DB版本分别编号，禁止只写无法判断含义的裸版本号。
5. 尚未开发的能力只能标记“规划中”，不得写成已完成。
6. 已发布记录采用追加式维护，不覆盖历史结论。

## PRODUCT-V1.4集团管理与工作计划冻结

- 当前架构冻结：`docs/V1.4-ARCHITECTURE-FREEZE.md`
- 当前业务设计冻结：`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-V1.1-DESIGN-FREEZE.md`
- 正式技术冻结：`docs/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-TECHNICAL-FREEZE.md`
- 实施计划：`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-V1.1-IMPLEMENTATION-PLAN.md`
- 历史技术草案：`docs/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-TECHNICAL-FREEZE-DRAFT.md`
- 历史冻结：`docs/V1.3-ARCHITECTURE-FREEZE.md`、`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-V1-DESIGN-FREEZE.md`
- 状态：PRODUCT-V1.4 / DESIGN-1.1 / TECH-DESIGN-1.0已冻结；实施批次已就绪，编码、数据库迁移、API变更和部署均未开始。
- 关键口径：集团总经理与集团CEO同岗；区域经理暂不启用；行政人事和行政人事主管均直属总经理、副总为间接领导；董事长无管理后台权限且只可向总经理/副总经理交办任务；总经理以下显式授权管理岗提交周/月计划，由精确直属主管逐项批准后生成提交岗位任务。

## 独立 OTA 自动化项目

## 行政人事 KPI 绩效考核中心

- 技术冻结：`docs/KPI-PERFORMANCE-CENTER-TECHNICAL-FREEZE.md`
- 实施与本地部署报告：`docs/tasks/KPI-PERFORMANCE-CENTER-V1-IMPLEMENTATION-REPORT.md`
- 数据库迁移：`database/migrations/V25__kpi_performance_center.sql`至`V27__kpi_inspection_and_automation.sql`
- 本地Pilot已迁移至Flyway V27；真实PMS/OTA/Cookie与外部通知仍保持关闭。

- OTA自动化房态对账与小时经营简报：`docs/tasks/OTA-AUTOMATION-V0.1-DESIGN-DISCUSSION.md`
- OTA自动化技术设计：`docs/tasks/OTA-AUTOMATION-V0.1-TECH-DESIGN.md`
- OTA自动化编码就绪报告：`docs/tasks/OTA-AUTOMATION-V0.1-CODING-READINESS.md`
- OTA自动化Sprint 0实施报告：`docs/tasks/OTA-AUTOMATION-V0.1-SPRINT-0-IMPLEMENTATION-REPORT.md`
- OTA自动化Sprint 1实施报告：`docs/tasks/OTA-AUTOMATION-V0.1-SPRINT-1-IMPLEMENTATION-REPORT.md`
- OTA自动化Sprint 2A实施报告：`docs/tasks/OTA-AUTOMATION-V0.1-SPRINT-2A-IMPLEMENTATION-REPORT.md`
- OTA自动化Sprint 2B实施报告：`docs/tasks/OTA-AUTOMATION-V0.1-SPRINT-2B-IMPLEMENTATION-REPORT.md`
- OTA自动化Sprint 2C实施报告：`docs/tasks/OTA-AUTOMATION-V0.1-SPRINT-2C-IMPLEMENTATION-REPORT.md`
- OTA自动化Sprint 2D离线人工授权演练实施报告：`docs/tasks/OTA-AUTOMATION-V0.1-SPRINT-2D-OFFLINE-AUTHORIZATION-REHEARSAL-IMPLEMENTATION-REPORT.md`
- OTA自动化程序内PMS登录离线骨架实施报告：`docs/tasks/OTA-AUTOMATION-V0.1-BROWSER-SESSION-SKELETON-IMPLEMENTATION-REPORT.md`
- OTA自动化受控外部接入工作包：`docs/tasks/OTA-AUTOMATION-V0.1-CONTROLLED-EXTERNAL-INTAKE-WORK-PACKAGE.md`
- OTA自动化接入资料模板：`docs/tasks/ota-controlled-external-intake/README.md`
- OTA首个接入实例（喷水池态六酒店｜美团别样红系统｜PMS）：`docs/tasks/ota-controlled-external-intake/intakes/pilot-01-bieyanghong-pms/README.md`
- OTA首个实例受控登录清单：`docs/tasks/ota-controlled-external-intake/intakes/pilot-01-bieyanghong-pms/CONTROLLED-LOGIN-RUNBOOK.md`
- OTA首个实例Cookie/浏览器会话自动采集设计草案：`docs/tasks/ota-controlled-external-intake/intakes/pilot-01-bieyanghong-pms/COOKIE-SESSION-AUTOMATION-DESIGN-DRAFT.md`
- OTA自动化本地账号安全ADR：`docs/tasks/OTA-AUTOMATION-V0.1-ADR-001-LOCAL-AUTH.md`
- 在研任务总索引：`docs/tasks/README.md`

当前OTA任务的业务设计`DESIGN-1.5`和技术设计`TECH-DESIGN-1.0`已全部确认并冻结。首个受控实例为“喷水池态六酒店＋PMS＋美团别样红系统”。2026-07-25已完成一次隔离浏览器人工认证、门店选择和有限只读结构观察：主页房态候选为无请求体`POST /hotelpms/api/v1/report/home/workbench/room`，但字段名、业务语义和许可仍未验证；用户提供的`lion/.../room`受控GET仅得到通用包络。同日先完成程序内PMS登录的离线安全骨架，再完成Sprint 2D离线人工授权演练：独立后台可持久化、刷新恢复、确认、取消和重新演练，但授权状态始终为`AUTH_REQUIRED`，运行态始终阻断。当前状态为`SPRINT 2D OFFLINE REHEARSAL COMPLETE / COOKIE AUTOMATION BLOCKED / I1 VENDOR AUTHORIZATION REQUIRED / REAL PMS AUTHORIZATION BLOCKED / REAL CONNECTORS BLOCKED / PRODUCTION NO-GO`。真实浏览器驱动、SecretStore实现、网络出口、PMS适配器、自动抓取、企微投递和生产仍未放行。

## Sprint 1 交付索引

- 架构冻结：`docs/V1.2-ARCHITECTURE-FREEZE.md`
- V0.1技术冻结报告：`docs/HOTEL-AI-OS-V0.1-TECHNICAL-FREEZE-REPORT.md`
- 数据库迁移：`database/migrations/`
- API契约：`docs/openapi.yaml`
- 页面说明：`docs/PAGE-FUNCTIONS.md`
- 测试报告：`docs/TEST-REPORT.md`
- Sprint 2详细实施方案：`docs/SPRINT-2-PLAN.md`
- 当前截图：`docs/sprint1-ceo-dashboard.png`、`docs/sprint1-gm-dashboard.png`、`docs/sprint1-standard-center.png`

## Sprint 2 开发候选索引

- 实施方案：`docs/SPRINT-2-PLAN.md`
- 本轮实施报告：`docs/SPRINT-2-IMPLEMENTATION-REPORT.md`
- 数据库迁移：`database/migrations/V5__sprint2_work_packages_and_records.sql`至`database/migrations/V13__sprint2_1_regional_dashboard_permission.sql`
- API契约：`docs/openapi.yaml`
- 页面说明：`docs/PAGE-FUNCTIONS.md`
- 自动化测试证据：`docs/TEST-REPORT.md`
- Sprint 2.1技术闭环UAT报告：`docs/SPRINT-2.1-UAT-ACCEPTANCE-REPORT.md`
- Sprint 2.1正式Final UAT报告：`docs/HOTEL-AI-OS-TECH-V0.2-SPRINT-2.1-FINAL-UAT-REPORT.md`
- Sprint 2.1最终证据：`docs/uat/evidence/20260717-2317-s21-final/README.md`
- TECH-V0.2-PILOT.7角色任务与驾驶舱修复报告：`docs/TECH-V0.2-PILOT.7-REPAIR-REPORT.md`
- TECH-V0.2-PILOT.7公网证据：`docs/uat/evidence/pilot7-public/`、`docs/uat/evidence/pilot7-all-roles/`、`docs/uat/evidence/pilot7-public-live-flow/`
- TECH-V0.2 RC Final报告：`docs/TECH-V0.2-RELEASE-CANDIDATE-FINAL-REPORT.md`
- TECH-V0.2 RC Release Note：`docs/releases/TECH-V0.2-RELEASE-NOTE-RC.md`
- TECH-V0.2 RC Final证据：`docs/uat/evidence/20260718-0112-tech-v02-rc-final/README.md`
- TECH-V0.2 RC2身份生命周期与闭环复验证据：`docs/uat/evidence/20260718-0154-tech-v02-rc2/README.md`
- TECH-V0.2 Live宿主停机复验证据：`docs/uat/evidence/20260718-1306-tech-v02-shutdown-order-fixed/README.md`
- TECH-V0.2 RC3本地安全加固证据：`docs/uat/evidence/20260718-1315-tech-v02-rc3-local-hardening/README.md`
- TECH-V0.2只读发布门禁组件（仅内部诊断，不构成发布授权）：`tools/release/Test-TechV02ReleaseGate.ps1`
- TECH-V0.2证据一致性校验器：`tools/release/Test-TechV02EvidenceConsistency.ps1`
- TECH-V0.2发布包敏感信息扫描器：`tools/release/Test-ReleaseSensitiveInformation.ps1`
- TECH-V0.2外部证据包校验器：`tools/release/Test-TechV02ExternalEvidenceBundle.ps1`
- TECH-V0.2唯一正式发布收口入口：`tools/release/Invoke-TechV02ReleaseClosure.ps1`
- TECH-V0.2外部证据包说明：`docs/releases/TECH-V0.2-EXTERNAL-EVIDENCE-BUNDLE.md`
- TECH-V0.2发布阻塞交接单：`docs/releases/TECH-V0.2-RELEASE-BLOCKER-HANDOFF.md`
- TECH-V0.2发布门禁输入示例：`docs/releases/TECH-V0.2-RELEASE-GATE-INPUTS.example.json`

本节表示Sprint 2.1业务功能P0/P1与RC Final本地技术验证已通过；发布级REL-P0仍未全部关闭，不表示TECH-V0.2已经正式发布。
收口总控是唯一正式入口，固定执行“本地证据一致性→外部证据生成→独立重算→6项发布门禁”；门禁输入先写同目录唯一暂存文件，复算通过后才原子替换规范输入，最终门禁锁定并返回实际消费字节的SHA，总控再核对前后漂移。任何字段缺失、类型错误、大小写状态不精确、退出码与JSON矛盾、stderr、SHA不一致或副作用声明缺失都会失败关闭；即使全部通过，也只输出可进入人工发布审批，不会修改版本状态或启动Sprint 3。
