# Hotel AI OS 技术版本记录

| 项目 | 当前值 |
|---|---|
| 当前技术发行 | TECH-V0.1 |
| 当前状态 | TECH-V0.1已发布；TECH-V0.2-PILOT.8持续部署至云端内部Pilot，移动草稿恢复与证据照片横竖自适应已进入内部Pilot发布链；TECH-V0.2正式发布门禁仍NO-GO |
| 当前产品蓝图 | PRODUCT-V1.4 |
| 当前API主版本 | API-V1（/api/v1） |
| 当前OpenAPI契约 | 已发布0.1.0-sprint1；云端Pilot运行0.2.5-pilot.8 |
| 当前数据库迁移 | 已发布DB-V4；云端Pilot运行Flyway V55 |
| 当前后端制品 | 云端内部Pilot运行0.2.0-pilot.8；不可变制品与部署SHA由完整发布协调器记录 |
| 最后更新 | 2026-09-17 |

## 0. 文档职责

本文件记录系统实际实现和验收到哪个技术版本，以及下一版本需要达到什么完成门槛。

规则：

- 只有规划和方案时，状态只能是“规划中”。
- 已经开始编码才可以标记“开发中”。
- 测试通过但业务未验收，只能标记“待验收”。
- 只有测试、验收、制品和文档齐全，才能标记“已发布”。
- 产品蓝图版本和技术发行版本必须分开记录。

## 1. 四条版本序列

| 版本类型 | 用途 | 升级条件 |
|---|---|---|
| PRODUCT-Vx.x | 产品定位、管理链、中心边界和设计原因 | 产品架构发生变化 |
| TECH-Vx.x | 实际完成并验收的系统能力 | 可独立验收的技术里程碑完成 |
| API-Vx | HTTP向后兼容边界 | 发生破坏性接口变化 |
| DB-Vx | Flyway数据库迁移位置 | 新增正式迁移 |

补充制品版本：

- OpenAPI文档版本记录接口契约制品。
- 后端JAR版本记录构建制品。
- 前端构建版本应在正式发布时增加构建号或Git提交号。

## 2. 技术版本状态

| 状态 | 含义 |
|---|---|
| 规划中 | 只有目标、范围和方案，尚未编码 |
| 开发中 | 已进入代码、迁移或页面实施 |
| 测试中 | 功能范围冻结，正在执行测试 |
| 待验收 | 测试已通过，等待产品或业务验收 |
| 已发布 | 验收通过，制品、迁移和文档可追溯 |
| 已废弃 | 停止新增能力，但可能仍在运行 |
| 已退役 | 不再部署和维护 |

## 3. 版本总览

| 技术版本 | 状态 | 核心能力 | 产品基线 |
|---|---|---|---|
| TECH-V0.1 | 已发布 | 基础组织权限、标准中心基础、岗位与经营数据入口、基础驾驶舱 | PRODUCT-V1.2 |
| TECH-V0.2-PILOT.5 | 已由PILOT.6替代 / 保留为回滚历史 | 真实PostgreSQL、真实应用账号、组织岗位人员维护、七岗位专属工作包、真实填报、图片附件、团队权限及驾驶舱 | PRODUCT-V1.2 |
| TECH-V0.2-PILOT.6 | 已由PILOT.7替代 / 保留为回滚历史 | 统一工作提交、多附件、任务下达与证据、CEO岗位工作/任务/门店驾驶舱模板治理 | PRODUCT-V1.2 |
| TECH-V0.2-PILOT.7 | 内部修复版 / 技术与公网业务闭环PASS / 待持续门店试用 | 可点击角色工作台与驾驶舱、原子任务投递、任务读取隔离、自动刷新接收、无标准人工验收、全角色任务目标矩阵、UAT数据清理 | PRODUCT-V1.2 |
| TECH-V0.2-PILOT.8 | 云端内部Pilot持续迭代 / 移动草稿恢复与证据照片横竖自适应进入内部发布链 / 正式发行仍NO-GO | 业务任职、董事长受限交办、企业微信入职、岗位与人员治理、店长日常工作闭环、管理工作台一键下达、分层完成率、横竖自适应证据画廊、移动端断点草稿及企微任务深链免重复登录；真实业务账号仍需持续复验 | PRODUCT-V1.4 |
| TECH-V0.2 | RC Final技术验证PASS / Release NO-GO / Unreleased | 标准→工作包→记录→评价→规则→任务→执行→验收闭环 | PRODUCT-V1.2 |
| TECH-V0.3 | 预实施计划V1.1已输出 / 待技术冻结 / 未启动 / 开工前须按届时当前产品基线重评 | AI Gateway、工作/经营/点评/CEO Agent、AI简报和AI主动发现 | 原设计PRODUCT-V1.2；当前须重基线PRODUCT-V1.4 |
| TECH-V0.4 | 建议阶段 | 绩效复盘、知识沉淀和标准优化建议 | PRODUCT-V1.2 |
| TECH-V0.5 | 建议阶段 | 集团规模化、外部适配、数据质量和跨区域试点 | PRODUCT-V1.2或后续蓝图 |
| TECH-V1.0 | 目标版本 | 集团内部正式使用 | 届时有效PRODUCT版本 |

## 4. TECH-V0.1

### 4.1 状态

已发布，Sprint 1验收通过。

发布日期：2026-07-17。

### 4.2 已完成能力

- 多租户基础模型。
- 集团、区域、门店、部门组织树。
- 员工、岗位和一人多岗。
- IAM权限点、角色、角色权限和范围授权数据模型。
- 企业标准分类、定义、版本、范围和发布。
- 表单定义、表单版本、工作记录和附件元数据。
- 经营指标定义与门店指标录入。
- CEO和店总基础驾驶舱。
- 审计日志与事务Outbox写入。
- PostgreSQL RLS租户隔离。

### 4.3 发行基线

| 项目 | TECH-V0.1基线 |
|---|---|
| 数据库 | Flyway V1—V4 |
| 业务表 | 25张 |
| RLS | 24张表启用并强制执行 |
| API | /api/v1，30个业务操作 |
| OpenAPI | 0.1.0-sprint1 |
| 后端 | Java 21、Spring Boot 3.5.3、0.1.0-SNAPSHOT |
| 前端 | React + TypeScript + Vite页面框架 |
| 后端测试 | 11项通过 |

### 4.4 已知技术债务

- 正式JWT/SSO尚未接入。
- 持久化RBAC尚未成为运行时授权来源。
- 前端仍主要使用演示数据。
- Outbox没有完整可靠消费。
- 标准JSON结构和已发布不可变性仍需加固。
- 附件只有元数据，没有完整对象存储链路。

### 4.5 证据

- docs/HOTEL-AI-OS-V0.1-TECHNICAL-FREEZE-REPORT.md。
- docs/SPRINT-1-ACCEPTANCE.md。
- docs/TEST-REPORT.md。
- docs/openapi.yaml。
- database/migrations/V1__sprint1_foundation.sql。
- database/migrations/V2__tenant_row_level_security.sql。
- database/migrations/V3__sprint1_demo_seed.sql。
- database/migrations/V4__grant_runtime_privileges.sql。

## 5. TECH-V0.2

### 5.1 状态

2026-07-17首轮业务UAT结论为BLOCKED / NO-GO；Sprint 2.1完成业务P0修复后，运行`20260717-2317-s21-final`通过六角色和三闭环技术走查。2026-07-18又以`20260718-0112-tech-v02-rc-final`重新验证正式JWT路径、真实后台Worker、附件扫描、可复现制品和数据库恢复演练。

最新RC Final技术验证为`PASS`，但正式Release仍为`NO-GO`：目标企业SSO、10方签署、真实现场照片与目标附件链、有效Git提交/正式标签、目标持久化环境及运维保障没有全部完成。TECH-V0.2不得标记为“已发布”，当前正式技术发行仍为TECH-V0.1，Sprint 3未启动。

2026-07-19完成`TECH-V0.2-PILOT.4`内部测试通道：`https://www.sfgzt.cn`接入本机真实PostgreSQL、双层访问保护和应用真实账号；除PILOT.3全流程外，新增组织、岗位、人员编辑、启用/停用和未使用数据受控删除。公网真实CEO账号完成8个主数据维护步骤，普通前台修改接口仍为403。该结论只允许受控门店开展内部测试，不改变正式TECH-V0.2的NO-GO状态。

### 5.2 目标

打通：

    标准
      ↓
    工作包
      ↓
    岗位工作记录
      ↓
    标准评价
      ↓
    企业规则
      ↓
    管理任务
      ↓
    负责人执行
      ↓
    指定人员验收

### 5.3 首批岗位

- 前台员工。
- 前厅主管。
- 客房主管。
- 店助。
- 店总。
- 区域运营。

### 5.4 当前候选能力

- 已实现工作包定义、版本、范围、分配和工作期望基础能力。
- 已实现前台班次与OTA跨店责任范围的数据基础。
- 已实现岗位工作记录兼容扩展、提交和复核链路。
- 已实现确定性标准评价与人工评价基础能力。
- 已实现企业规则定义、版本、模拟、发布和幂等消费；动作限定为CREATE_TASK和CREATE_NOTIFICATION。
- 已实现管理任务状态机、SLA、证据、返工和验收基础能力。
- 已实现站内通知基础能力。
- 已实现六角色真实API页面并通过生产构建与25张页面截图验收。
- 已实现受信JWT身份边界、数据库RBAC、Scope Resolver及Outbox自动投影；真实企业SSO部署和定时恢复调度尚未完成。
- 已实现真实图片上传、附件列表/下载/删除、结构校验和SHA-256核对。
- 已实现工作期望MISSED检测、提醒、规则建任务、任务OVERDUE和升级处理。
- 已实现店总门店驾驶舱、风险事项与未完成任务汇总，以及区域多门店驾驶舱。

### 5.5 当前候选数据库与API

- 已新增V5—V16迁移，V1—V4保持不变；V12负责事件可靠性与不可变性加固，V13补齐区域驾驶舱权限，V14增加Pilot本地账号认证，V15补齐七类运营岗位工作，V16增加企业模板、工作补充和证据策略。
- 工作树候选当前共53张业务表。
- V16新增三张租户表全部启用并强制执行RLS。
- API继续在/api/v1内向后兼容扩展。
- 生产身份使用受信JWT/SSO声明，最终角色、权限和组织范围由服务端数据库解析；开发请求头仅接受tenant/actor。
- OpenAPI 0.2.2-pilot.4已覆盖71个路径、91个操作和61个模型；新增6个主数据维护操作，重复operationId为0。

以上为工作树技术候选事实，不代表TECH-V0.2已完成业务验收或正式发布。

### 5.6 完成门槛

- 标准→工作→任务→执行→验收真实闭环。
- OTA异常、前台客诉、漏交升级和一人多岗四条E2E通过。
- 六个岗位完成UAT。
- 所有新租户表启用并强制RLS。
- 重复事件不重复建任务。
- 已发布标准、工作包和规则不可修改。
- 任务合法迁移全部通过，非法迁移全部拒绝。
- V4升级和空库安装均通过。
- Sprint 1全部回归继续通过。
- 无P0/P1缺陷。
- OpenAPI、测试、截图、部署和恢复文档齐全。
- 工作期望检测与任务升级由真实后台Worker自动执行，并完成幂等、重试、告警和恢复验收。
- 六角色在目标环境通过正式SSO/JWT登录并完成业务签字。
- 发布制品、Git标签、SHA-256、备份恢复和回滚证据齐全。

### 5.7 实施依据

- docs/SPRINT-2-PLAN.md。
- docs/SPRINT-2-IMPLEMENTATION-REPORT.md。
- docs/TEST-REPORT.md。
- database/migrations/V5__sprint2_work_packages_and_records.sql至V12__rule_event_reliability_and_immutability.sql。

### 5.8 当前验证与未完成项

- 后端标准测试33项，失败0、错误0、跳过1；跳过项为默认关闭的Live UAT测试，已另行显式运行1/1通过。
- 前端生产构建通过；25张真实API页面截图加载检查全部通过。
- 最终UAT共执行83次API请求：77次正向2xx、6次预期400/403、非预期失败0；83个Correlation ID均存在且唯一。
- 场景A、B最终为COMPLETED，场景C形成MISSED提醒、OVERDUE与ESCALATE证据；前厅主管另完成1次手工整改任务创建并取消。
- 数据库导出12份，包含环境、六角色账号、一人多岗、附件、4次评价、5个UAT任务、任务时间线、通知、升级和规则动作。
- 权限负向验收6/6通过，覆盖团队数据、跨门店、跨区域、范围外指派、自我验收和附件越权。
- 客诉专用事件`COMPLAINTREPORTED`已落库；当前客诉整改规则监听评价完成事件并确定性创建任务。
- 工作未完成检测和任务升级由UAT脚本调用SLA接口完成；`WORK_EXPECTATION_SLA_SCHEDULER_ENABLED`默认关闭，真实Worker发布门槛未通过。
- 对象存储、企业恶意文件扫描、持久化UAT备份恢复、正式SSO和生产调度告警仍属于发布环境门槛。
- 完整JSON Schema验证、工作记录命令全链路幂等和工作包完整Web编辑器仍为非P0技术债务。
- 真实客房现场照片业务补测、正式签字、Git标签、制品SHA-256和目标环境部署/回滚记录完成前，TECH-V0.2不得迁入Released。
- 最终业务UAT报告：docs/SPRINT-2.1-UAT-ACCEPTANCE-REPORT.md。
- 正式Final UAT报告：docs/HOTEL-AI-OS-TECH-V0.2-SPRINT-2.1-FINAL-UAT-REPORT.md。
- 最终证据索引：docs/uat/evidence/20260717-2317-s21-final/README.md。

### 5.9 TECH-V0.2-PILOT.3验证补充

- 公网入口：`https://www.sfgzt.cn`，演示回退关闭。
- 数据库：本机PostgreSQL 14.22，Flyway V14，租户RLS继续强制执行。
- 页面闭环：新增门店、岗位、员工账号和两个岗位任职；创建、发布、下发工作包；新员工登录后仅看到1个授权门店，工作记录最终状态`SUBMITTED`，图片附件提交成功。
- 浏览器结果：控制台0错误、页面0错误、请求失败0、服务端5xx为0。
- 后端回归：48项、失败0、错误0、跳过2。
- 修复回归：工作包详情的PostgreSQL数组字段已转换为Java列表，HTTP JSON序列化定向集成测试1/1通过。
- 证据：`docs/uat/TECH-V0.2-PILOT.3-UAT-REPORT.md`、`docs/uat/evidence/pilot3/pilot3-browser-uat.md`和`docs/uat/evidence/pilot3/pilot3-ui-full-flow.md`。
- 运行边界：当前Windows电脑是Pilot服务器，关机、断网、睡眠或未恢复Core API/PostgreSQL会导致门店无法访问。

### 5.9 RC Final技术收口（2026-07-18）

- UAT批次：`20260718-0112-tech-v02-rc-final`。
- 六角色真实授权路径和三条业务闭环均通过；使用本地临时RS256 OIDC/JWKS签发Bearer JWT，10项认证负向用例均返回401，6项业务越权用例均按预期拒绝。
- 真实后台Worker完成工作漏交检测、提醒、规则消费、任务逾期和升级；UAT中手工SLA处理和手工Outbox恢复调用次数均为0。
- 后端回归37项，失败0、错误0、跳过2；跳过项为需显式编排的Live UAT与数据库恢复演练，二者已分别独立实跑并留证。
- API请求89次，非预期失败0；25/25页面证据通过，控制台错误0、网络失败0。
- 正式模式附件扫描使用ClamAV 1.5.3及签名CVD库，干净文件通过、标准EICAR测试样本被检出；扫描器缺失、超时或异常时上传失败关闭。
- DB-V13 / Flyway V13、本地PostgreSQL 14.22冷备恢复演练PASS；13个迁移和49张强制RLS表通过验证。
- 两次独立构建载荷指纹一致：`1652df5d4e3e1beb0418765584468cc849b80c1913286ad910fad64164a8d98c`，但当前`.git`无有效HEAD，制品无法绑定源码提交或正式标签。
- Sprint 2.1业务P0/P1技术问题已关闭，当前开放P1为0；发布级REL-P0仍有目标企业SSO、10方签署、真实现场照片与目标附件链、有效Git提交/标签、目标持久化数据库运维保障未关闭。
- 发布结论：`NO-GO`。TECH-V0.2继续为Unreleased，正式发行仍为TECH-V0.1；Sprint 3未启动。
- 报告：`docs/TECH-V0.2-RELEASE-CANDIDATE-FINAL-REPORT.md`。

### 5.10 RC2身份生命周期收口（2026-07-18）

- 修复员工账号最后一个当前有效任职失效后，仍在有效期内的签名JWT可能保留角色授权的问题；账号停用或最后任职失效后下一请求立即401。
- 验证一人多岗仍有其他有效任职时继续可用，并只返回剩余任职；无employee记录的租户级账号不受员工任职规则误伤。
- 后端完整回归41项，失败0、错误0、跳过2；身份生命周期定向测试4/4通过。
- RC2六角色与三闭环UAT：89次请求、非预期失败0，10次认证拒绝与6次业务权限拒绝均符合预期；Worker手工SLA/Outbox调用均为0。
- RC2双构建指纹一致：`daf7a779fca869ee0208c7ae4588aff3d3f111ee1732f6ff5477612d42a1f1bb`，5项制品校验PASS。
- 本地仓库已初始化`main`，但尚无首个提交、受控身份、远程仓库或RC标签；manifest仍为`source.commit=null`。
- 证据：`docs/uat/evidence/20260718-0144-tech-v02-identity-lifecycle/README.md`、`docs/uat/evidence/20260718-0154-tech-v02-rc2/README.md`。
- 正式结论不变：TECH-V0.2仍为Unreleased，目标企业SSO、10方签署、现场照片/目标附件链、源码标签和目标运行保障未关闭，Sprint 3未启动。

### 5.11 RC2最终证据一致性审计（2026-07-18）

- `TECH-V0.2 Release Candidate Final Report`更新为RC-FINAL-V1.2，三条闭环的动态工作记录及任务标识已与最终批次`20260718-0154-tech-v02-rc2`对齐；数据库恢复清单哈希与固定运行归档引用已完成同源修正。
- 权威统计保持不变：DB-V13、API `/api/v1`、41项后端测试零失败、89次UAT请求零非预期失败、5项RC制品校验PASS。
- 新增只读证据一致性校验器，当前对报告版本、API摘要、三场景动态标识、数据库、制品、恢复演练、截图和发布状态的10项检查全部通过。
- 只读REL-P0门禁复验结果为1 PASS / 5 BLOCKED；仅本地后台Worker证据通过，目标企业SSO、10方人工签署、现场照片与目标附件链、源码到制品追溯、目标运行保障仍未通过。
- 本次仅修正文档证据引用，不提升TECH版本，不改变数据库、API、权限或业务行为。
- 正式结论：TECH-V0.2保持Unreleased，当前正式发行仍为TECH-V0.1，Sprint 3未启动。

### 5.12 Live UAT测试宿主停机加固（2026-07-18，本地关闭）

- RC2归档日志中的停机阶段数据库连接错误定位为测试宿主资源关闭顺序问题，不属于已完成业务请求失败。
- 第一次手工关闭Spring上下文的修复使Hikari先于PostgreSQL停止，但破坏Spring Test的`afterTestClass`监听器并以BUILD FAILURE结束；该失败事实继续归档。
- 最终方案使用合并默认监听器的自定义`TestExecutionListener`，让`DirtiesContext`先完成Spring上下文、调度器和Hikari关闭，再释放Embedded PostgreSQL与运行标记；失败路径继续释放后续资源并保留suppressed异常。
- 生命周期测试3/3 PASS；最终完整回归48项，失败0、错误0、跳过2。真实Live宿主复验1/1、错误0、BUILD SUCCESS；Hikari于`13:11:27.817 +08:00`完成关闭，PostgreSQL于`13:11:27.968 +08:00`停止，托管进程与临时令牌均已清理。
- 本次不改变生产代码、DB-V13、API `/api/v1`或RC2业务证据，也不关闭任何外部REL-P0门禁。
- 证据：`docs/uat/evidence/20260718-0215-tech-v02-local-hardening/README.md`、`docs/uat/evidence/20260718-0218-tech-v02-shutdown-order/README.md`、`docs/uat/evidence/20260718-1306-tech-v02-shutdown-order-fixed/README.md`。

### 5.13 RC3发布安全与外部证据门禁加固（2026-07-18）

- 首次机器可读发布包扫描在两套RC2后端JAR中发现4次数据库密码赋值命中；主数据源与Flyway密码回退值已确认会被原样打包，并在缺少外部变量时进入数据库认证路径。
- 主配置已改为必须由`DB_PASSWORD`和`DB_MIGRATION_PASSWORD`外部注入且不允许JAR内置回退；应用主入口在Spring上下文、迁移、数据库连接和监听端口之前校验密钥；UAT Docker启动器在启动数据库前校验必需变量非空，示例文件不再预置密码；新增静态与入口回归测试。
- 无密钥JAR启动在0.2秒内失败，未渲染Spring Banner、未尝试数据库连接或监听端口，日志只包含缺失变量名且不包含密钥值。`TECH-V0.2-rc.3-local`两次独立构建的5项制品完全一致，载荷指纹为`546fc5175d97af2e0bbe3736468b1366d8890e89a6c6a6d761db4d40eba089ee`。深度扫描160个文件、120个归档、43,830个归档条目，0命中、0错误，结论`PASS / CLEAN`。
- 新增外部证据包生成与校验流程：受控签署平台导出或证书证据必填；身份和签名ID归一后检查唯一性；证据路径拒绝重解析点；真实签署和目标环境材料统一存入Git忽略的`.uat-runtime/release/`。发布门禁默认值和示例输入已统一到`TECH-V0.2-rc.3`及`reproducibility-rc3-formal`路径；正式制品必须在干净HEAD和注解RC标签建立后重新生成，不得沿用`rc.3-local`或RC2制品。收口总控是唯一正式入口，固定执行15项本地一致性、门禁输入生成、独立重算和最终6项门禁；READY只从四阶段全PASS、最终6/6及消费SHA一致性推导。生成器经同目录暂存文件复算后才以备份式原子替换晋升规范输入；替换后的备份清理失败会用备份恢复原目标，恢复失败则保留备份；最终门禁锁定并返回实际消费SHA。总控严格校验大小写状态、副作用字段存在、整数类型和零值。外部证据负向测试4/4、总控负向测试6/6、契约测试19/19均PASS，覆盖控制器测试旁路拒绝、Git分支/标签引用指纹、原子晋升、异常清理及提交后清理失败回滚。
- 若旧回退值曾在任何环境实际使用，正式发布前必须轮换并留存受控记录。本地工具不生成签字、批准、Git提交或标签，也不声称能独立证明现实身份。
- 本次不改变DB-V13、API `/api/v1`、组织模型、一人多岗或权限隔离；正式发布门禁仍为1 PASS / 5 BLOCKED，TECH-V0.2继续Unreleased，Sprint 3未启动。
- 证据：`docs/uat/evidence/20260718-1315-tech-v02-rc3-local-hardening/README.md`、`docs/releases/TECH-V0.2-EXTERNAL-EVIDENCE-BUNDLE.md`、`docs/TECH-V0.2-RELEASE-CANDIDATE-FINAL-REPORT.md`。

### 5.14 外部证据主体绑定加固（2026-07-18）

- 审计发现目标SSO和目标运维验收文档各要求10方专项签核，但原结构化输入只强制单一责任人；现场照片只声明SHA-256，未读取原文件；Git追溯只覆盖本地HEAD、标签和制品，未绑定受控远程、提交身份和仓库责任人审批。
- 目标SSO和目标运维现分别强制10个冻结角色的独立真人审批，并检查唯一签名ID、唯一签署人、精确状态、零保留意见、受控签署保证及证据文件哈希。
- 现场照片现从`originalFilePath`读取单次锁定字节快照；派生文件URI、SHA-256和字节数必须与文件名、照片声明及对象存储上传、下载、恢复哈希一致。缺路径、名称/大小/SHA不一致或把验收JSON本身伪装成照片都会失败关闭。
- Git追溯现要求唯一且相同的受控fetch/push URL、远程分支与跟踪提交、HEAD作者/提交者身份；仓库责任人审批必须绑定精确远程、分支、HEAD、RC标签、manifest SHA-256和远程发布时间，并满足“HEAD提交时间 ≤ 远程发布时间 ≤ 仓库审批时间”。本地远程跟踪引用本身不被解释为外部发布证明，受控Git平台导出仍由仓库与发布责任人核验。
- 最终门禁重新读取派生证据URI并比对SHA-256，避免外部证据重算与最终6项门禁之间的文件替换窗口；照片、manifest和制品均使用锁定字节快照。
- 针对性回归18/18 PASS，覆盖10方签核正反路径、大小写严格状态、受控远程与时间顺序、原图合法路径，以及文件名、大小、SHA和自引用篡改拒绝。该结论只关闭本地证据协议与校验器缺陷，不产生任何真实签署、Git提交、标签或目标环境事实。
- 本次无数据库、API、页面、组织、一人多岗或权限模型变更；TECH-V0.2仍为1 PASS / 5 BLOCKED，保持Unreleased，Sprint 3未启动。

### 5.15 发布门禁稳定快照终审（2026-07-18）

- 在5.14主体绑定加固后继续执行独立对抗复核，关闭可强制转换的`openReservations`和Worker/XML计数、任意附件存储类型、角色大小写与额外项、重复制品、schema/版本/RC标签宽松比较、无时区时间戳、Worker/manifest/SHA256SUMS末端未重验、manifest瞬时A→B→A替换及锁定字节解析不兼容UTF-8 BOM等边界。
- 最终协议要求严格整数和大小写、带`Z`或显式偏移的ISO 8601时间、精确审批角色集合、精确`OBJECT_STORAGE`、五类正式制品各唯一一次；最终门禁从首次锁定的同一字节快照完成哈希、大小与解析，并在作出结论前重新校验全部登记证据。
- 稳定快照独立实跑：外部证据主体绑定与负向测试`34/34`、最终门禁证据绑定`24/24`、收口契约`19/19`、总控负向`6/6`、本地证据一致性`15/15`，PowerShell AST为0错误；测试临时残留、签署、审批、提交、标签、发布状态修改和网络写入均为0。未发现剩余可复现的P0/P1/P2门禁绕过或假PASS路径。
- 默认门禁仍为REL-P0-01本地Worker证据PASS、其余5项BLOCKED；默认总控在`15/15`本地一致性后因正式外部证据包不存在而停止，门禁输入写入数为0。正式External Bundle、规范Gate Inputs、受控Git HEAD/远程/标签和正式RC3制品均不存在。
- 本次仍无数据库、API、页面、组织、一人多岗或权限模型变更；TECH-V0.2保持Unreleased，当前正式版本仍为TECH-V0.1，Sprint 3未启动。

### 5.16 Pilot固定域名部署（2026-07-18）

- 发布通道：`TECH-V0.2-PILOT.1`，静态演示数据内部测试版；不连接真实API、数据库、JWT或附件。
- 当前办公室Windows电脑作为Pilot单机服务器；Caddy自动服务仅监听`127.0.0.1:4180`，Windows和光猫均不开放Pilot入站端口。
- WAN IPv4处于运营商CGNAT，部署采用Cloudflare Tunnel出站架构；`sfgzt.cn`权威Nameserver已迁移到Cloudflare。
- Named Tunnel `sifangguan-pilot`和Windows自动服务`SifangguanPilotTunnel`已建立；`www.sfgzt.cn`及根域名已路由到固定Tunnel，根域名308跳转到`https://www.sfgzt.cn`，历史Quick Tunnel已停止。
- 正式域名Cloudflare双边缘IP和独立外部抓取均返回200及正确产品内容；历史Quick Tunnel桌面、移动、导航和控制台QA均PASS。当前本机Meta代理仍有旧DNS缓存，不作为外部访问失败判定。
- 本条只记录Pilot部署能力，不改变DB-V13、API-V1、组织模型、一人多岗、权限隔离、标准中心、规则中心或任务状态机；ICP备案及TECH-V0.2其余正式门禁仍未关闭，TECH-V0.2继续Unreleased，Sprint 3未启动。

### 5.17 Pilot真实PostgreSQL UAT（2026-07-18）

- 发布通道升级为`TECH-V0.2-PILOT.2`，从静态演示切换到持久化PostgreSQL、真实Core API和数据库权限解析；`?demo=1`不再能够启用演示回退。
- PostgreSQL 14.22数据目录位于`D:\SifangguanHotelAIOS\Data\PostgreSQL`，仅监听`127.0.0.1:55432`；数据库完成Flyway V1—V13和Sprint 2.1 UAT数据，运行角色非超级用户、无`BYPASSRLS`，49张租户表强制RLS。
- Core API仅监听`127.0.0.1:18080`；由Caddy在同一受保护站点下代理`/api/*`，公网只经过Cloudflare Tunnel，数据库和API端口没有Windows入站开放。
- Pilot提供CEO、前台员工、前厅主管、客房主管、店助、店总、区域/运营7个验收身份；权限从数据库解析，前厅主管的两项有效任职验证一人多岗交集逻辑。
- 企业规则中心已通过真实API完成创建、草稿修改、发布及数据库持久化；本机和公网域名端到端验收均读取到7角色与7条数据库规则。
- Caddy与Tunnel为Windows自动服务；PostgreSQL与Core API因当前无管理员令牌采用用户登录启动项，重启后需登录该Windows用户方可恢复业务API。该限制必须在正式生产前改为受管服务或云端托管。
- 当前认证是“站点Basic Auth＋受保护的验收身份请求头”，仅适用于内部Pilot；它不等于目标企业SSO/JWT，不能关闭TECH-V0.2正式发布的SSO门禁。
- 本条不新增Flyway迁移，不改变API-V1，不改变组织、一人多岗、权限隔离、标准中心、规则中心或任务状态机；TECH-V0.2仍为Unreleased。

### 5.18 Pilot登录中断恢复与PostgreSQL服务化（2026-07-19）

- 故障根因不是账号或Cloudflare：Core API进程仍存活，但`127.0.0.1:55432`拒绝连接，登录事务因Hikari连接池无可用连接而超时。
- 恢复真实PostgreSQL后，CEO本机登录返回HTTP 200并签发JWT；公网组织、岗位、人员8步主数据UAT重新执行全部PASS。
- PostgreSQL现注册为`SifangguanPostgreSQL` Windows服务，采用延迟自动启动和三级失败重启；运行状态检查同步输出数据库服务状态。
- 本次无数据库迁移、无API契约变化、无业务数据重写，不改变TECH-V0.2正式发布NO-GO状态。
- Core API仍依赖部署用户登录启动项，是Pilot阶段保留的运行限制；正式生产前必须改为受管服务或云端部署。

### 5.19 TECH-V0.2-PILOT.5岗位能力验证补充（2026-07-19）

- 公网入口：`https://www.sfgzt.cn`，页面版本为`TECH-V0.2-PILOT.5`，演示回退关闭。
- 数据库：本机PostgreSQL 14.22，Flyway V15；V15只追加岗位工作数据与下发实例，不破坏既有组织、一人多岗、权限隔离和标准模型。
- 岗位范围：前台员工、前厅主管、客房主管、店助、店总、OTA运营助理、OTA运营经理均具有角色专属结构化工作表单、已发布工作包和真实工作实例。
- 写入结果：七个运营岗位均完成真实记录写入；客房主管图片附件存在性验证PASS；用户新建的店总任职自动获得店总工作包。
- 权限结果：执行岗位团队工作接口403且菜单隐藏；主管、店助、店总、OTA经理和CEO按既有组织范围访问团队数据；门店及区域驾驶舱只对对应授权角色开放。
- 公网页面结果：8个真实账号全部PASS；7个岗位均能打开结构化填报表单，CEO工作包创建表单全部关键字段可用；浏览器控制台、页面错误、非预期请求失败和服务端5xx均为0。
- 运行恢复：PostgreSQL、Caddy和Cloudflare Tunnel为自动服务；Core API已增加当前Windows用户登录触发任务并实测退出码0。SYSTEM级启动任务仍需管理员会话，正式生产前应服务化或迁移云端。
- 证据：`docs/uat/TECH-V0.2-PILOT.5-ROLE-CAPABILITY-UAT.md`及`docs/uat/evidence/pilot5-role-capability/`。

### 5.20 TECH-V0.2-PILOT.6公网登录恢复（2026-07-22）

- 故障表现：公网登录页可见，但提交账号后Cloudflare报告源站响应无效；Caddy代理业务API返回502。
- 根因：Core API Java进程停止，当前用户登录恢复任务未处于可用注册状态；Caddy、Tunnel和PostgreSQL均保持运行。
- 恢复结果：Core API重新启动并健康为`UP`；`SifangguanPilotCoreApiUser`重新注册为登录触发加每5分钟健康检查，实测最近退出码0；公网真实CEO登录HTTP 200并签发JWT。
- 入口调整：取消不兼容部分移动端和内置浏览器的外层Basic Auth，用户直接进入应用登录页；未登录业务API继续返回401。
- 安全边界：应用账号连续失败5次临时锁定，登录后使用短期JWT；服务端RBAC、组织范围和PostgreSQL RLS保持不变。
- 版本影响：无数据库迁移、无业务数据修改、无API主版本变化；PILOT.6继续内部测试运行，TECH-V0.2仍为Unreleased。

### 5.21 PRODUCT-V1.3集团管理与工作计划预实施冻结（2026-09-11）

- 状态：业务设计已冻结 / 技术草案待评审 / NOT CODED / NOT DEPLOYED。
- 产品基线升级为PRODUCT-V1.3，但当前技术发行和TECH-V0.2-PILOT.7运行状态不变。
- 冻结范围：新增集团董事长只读角色、集团总经理角色和岗位；既有`GROUP_VICE_PRESIDENT`统一作为集团副总经理；集团总经理→集团副总经理→区域经理的经营管理层级；集团副总经理及以下管理任职提交周/月计划，直属主管逐项审批后自动生成本人岗位任务。
- 技术候选：沿当前Pilot增量时可使用`TECH-V0.2-PILOT.8`，但该编号须在开工批准时确认；不得静默占用已规划给AI的TECH-V0.3。
- 数据库：本轮无迁移。实施时必须从届时下一可用编号追加，V1—V38历史迁移保持不可变。
- API和页面：本轮不改变`/api/v1`契约或现有页面；候选接口、权限、状态机和页面写在技术冻结草案中。
- 验证：仅完成基线与设计一致性核查，没有运行功能测试；不得据此宣称功能可用或已部署。
- 依据：`docs/V1.3-ARCHITECTURE-FREEZE.md`、`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-V1-DESIGN-FREEZE.md`、`docs/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-TECHNICAL-FREEZE-DRAFT.md`。

### 5.22 PRODUCT-V1.4集团身份与受限交办修订冻结（2026-09-11）

- 状态：DESIGN-1.1已冻结 / 技术草案DRAFT-V0.2待评审 / NOT CODED / NOT DEPLOYED。
- 产品基线升级为PRODUCT-V1.4；PRODUCT-V1.3未实施并作为历史冻结保留，当前技术发行和TECH-V0.2-PILOT.7运行状态不变。
- 身份与岗位：集团总经理和集团CEO合并为同一岗位身份；新增唯一`GROUP_GENERAL_MANAGER`岗位并映射既有`CEO`角色，不新增同义系统角色。CEO账号级授权范围继续保留，但业务命令必须单独保存并校验总经理任职。董事长使用`GROUP_CHAIRMAN`角色和岗位任职，但不获得管理后台权限。
- 组织关系：区域经理为`DEFERRED / NOT IN USE`，`OTA_OPERATION_MANAGER`保持OTA运营经理本义；新增`HR_ADMINISTRATION`和`HR_ADMINISTRATION_SUPERVISOR`，两者均直属集团总经理，指定副总经理通过独立`INDIRECT_LEADER`关系进行首期只读督导。
- 任务边界：董事长候选专用权限`executive-task.assign`只允许向有效集团总经理或集团副总经理任职创建并立即派发任务；董事长任职为`REVIEWER`，只能验收或退回本人交办任务。该入口与计划生成任务使用不同来源和幂等键。
- 工作计划：集团副总经理、行政人事主管及其他显式授权管理岗提交，精确直属主管审批；行政人事普通岗位、集团总经理和董事长不提交，间接领导不代审。
- 技术候选仍可使用`TECH-V0.2-PILOT.8`，但编号须在开工批准时确认；本轮无迁移、API、代码、页面、账号、任职或运行时权限变更。
- 依据：`docs/V1.4-ARCHITECTURE-FREEZE.md`、`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-V1.1-DESIGN-FREEZE.md`、`docs/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-TECHNICAL-FREEZE-DRAFT.md`。

### 5.23 TECH-DESIGN-1.0正式技术冻结（2026-09-11）

- 状态：FROZEN / IMPLEMENTATION READY / NOT CODED / NOT DEPLOYED；当前技术发行和TECH-V0.2-PILOT.7运行状态不变。
- 版本：PRODUCT-V1.4、DESIGN-1.1；目标正式固定为`TECH-V0.2-PILOT.8`、Core API/Web `0.2.0-pilot.8`、OpenAPI `0.2.5-pilot.8`，API主版本继续为`/api/v1`。
- 身份：增加独立`businessActorAssignmentId`技术合同。CEO保留账号级权限，但总经理审批、验收等责任动作必须使用服务端验证的有效`GROUP_GENERAL_MANAGER`任职；平台管理员不能代行。
- 迁移：实施目标固定为单一事务迁移`V39__group_management_roles_and_work_plans.sql`；本轮只预留编号和结构合同，没有创建迁移文件、执行数据库变更或修改V1—V38。
- 安全：董事长只走专用交办服务，权威来源记录与任务参与人共同授权；全集团任务只返回最小字段，其他来源或非本人交办任务动作全部403。新租户表必须强制RLS。
- 工作计划：精确直属主管逐项决定任务内容、完成时间和提醒，只有整单通过才在单事务内生成N项对应N任务；间接领导首期只读。
- 实施：按A身份/迁移/契约、B董事长交办、C周/月计划、D提醒/回归/Pilot准备四批推进。真实人员任职映射不阻塞通用编码，但阻塞目标租户启用和部署。
- 授权边界：本次没有代码、页面、API、账号、任职、运行时权限或部署变化；进入批次A仍须明确“开始编码”。
- 依据：`docs/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-TECHNICAL-FREEZE.md`、`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-V1.1-IMPLEMENTATION-PLAN.md`。

### 5.24 TECH-V0.2-PILOT.8批次A本地实施（2026-09-11）

- 状态：BATCH A CODE COMPLETE / FEATURES OFF / NOT DEPLOYED；线上仍为PILOT.7，本记录不提升正式发布状态。
- 身份：新增服务端验证的`businessActorAssignmentId`，分离CEO/平台管理员账号级授权与岗位责任动作任职；首次身份请求保持业务演员为空并返回任职列表，前端随后默认主任职或允许显式选择本人有效任职，错误分类为稳定400/403。
- 数据库：新增单一Flyway V39，原子增加3个SYSTEM角色、4个岗位、8项权限、11张强制RLS租户表、任务可信来源及直属/间接统一防环关系；不新增区域经理、不改写OTA运营经理、不写入真实任职。
- 前端/API：Core API与Web候选版本为`0.2.0-pilot.8`，OpenAPI为`0.2.5-pilot.8`；前端统一发送业务任职头，新增董事长和行政人事岗位展示，CEO保留账号级展示与独立任职选择。
- 门禁：工作计划、高管交办、提醒Worker三项开关默认关闭；任一开启而租户白名单为空或非法时应用启动失败。B/C/D业务能力尚未编码或启用。
- 验证：Java编译、专项身份/开关/真实PostgreSQL迁移测试、Web 54项契约测试和生产构建通过；全量结果和剩余门禁见批次A实施记录。
- 授权边界：本轮授权只覆盖批次A；进入B、Pilot启用和部署均需分别批准。
- 依据：`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-BATCH-A-IMPLEMENTATION-REPORT.md`。

### 5.25 TECH-V0.2-PILOT.8批次B本地实施（2026-09-12）

- 状态：BATCH B CODE COMPLETE / FEATURES OFF / NOT DEPLOYED；线上仍为PILOT.7，未迁移运行库。
- 闭环：新增六个`/api/v1/executive-tasks`专用接口，原子创建任务、参与人、权威交办记录、CREATE/DISPATCH时间线、提醒、审计、Outbox和通知，并支持董事长验收/退回本人交办。
- 安全：目标固定为同租户集团根组织下有效GM/副总任职；服务端派生验收人和来源，未知/伪造字段失败关闭；董事长通用任务、动作及证据API统一拒绝。
- 前端：董事长工作台与任务中心只使用专用最小投影；交办模块独立懒加载；移除“全部功能”和管理后台入口，移动端“我的”仅保留改密/退出，“区域多门店”统一更名为“多门店经营”。
- 验证：详见批次B实施记录；功能关闭、身份错误、目标越权、伪造字段、非本人动作、幂等和事务原子性均有自动化证据。
- 下一门禁：不自动进入批次C；工作计划、提醒Worker、真实人员映射、租户启用和部署仍需分别授权。
- 依据：`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-BATCH-B-IMPLEMENTATION-REPORT.md`。

### 5.26 TECH-V0.2-PILOT.8云端内部Pilot收口（2026-09-12）

- 状态：BATCH A/B DEPLOYED / FEATURES OFF / C—D NOT STARTED；不改变TECH-V0.2正式发行NO-GO。
- 版本：功能提交`e001c2ae2c58445bae553667301f0c691c2e7edf`已推送GitHub并首次成功激活；最终文档收口提交继续以同一构建、测试和门禁重部署，最终GitHub `main`与云端不可变release目录须保持一致。
- 数据库：部署前生成加密备份并校验SHA-256；Flyway从V38升级至V39，JAR与数据库迁移版本一致，失败迁移数为0。
- 制品：后端JAR、Web及部署脚本敏感扫描0命中、0错误；云端JAR和公网`index.html`哈希与本地部署包一致。
- 运行：Core API与Caddy均为active，内网健康状态UP；未授权身份与高管交办接口均返回401；最近启动日志无warning级别事件。
- 功能边界：云端没有`GROUP_MANAGEMENT_*`配置，三项能力使用默认false且租户白名单为空；V39未创建真实人员任职，区域经理继续冻结。
- 公网：`https://www.sfgzt.cn`返回200；Microsoft Edge/Playwright桌面及移动端登录页、Pilot.8资源、表单交互、控制台与请求失败检查全部PASS。
- 依据：`docs/TECH-V0.2-PILOT.8-CLOUD-DEPLOYMENT-REPORT.md`。

### 5.27 工作台当日口径与横向浏览增量发布（2026-09-15）

- 状态：CODE COMPLETE / DEPLOYED TO INTERNAL PILOT；不改变TECH-V0.2正式发行NO-GO。
- 功能：上海时区当前日期进入个人工作、团队工作和工作台汇总资源键；页面跨日、重新聚焦或恢复可见时刷新，旧日补交记录不再混入今日状态。门店汇总增加数据日期，服务端日期过期时隐藏完成率；门店详情提供可见且可键盘聚焦的横向滚动区域。
- 版本：功能提交`8ed46fb2ef2a3d7f2635d36b3c6229a58b5427b7`已快进合并并推送GitHub主线；云端不可变release为`20260915-pilot8-8ed46fb`。
- 制品：后端沿用V53稳定JAR；Web ZIP SHA-256为`699900bd1467c6a54f3ad278fff3d80952247e3a867b8cf39d074f7a426fa2e4`，`index.html` SHA-256为`d770a5361f5687853728d946683c0f5e14c3b35d7a13aa0ea85772e402237b0f`，服务器与公网回读一致。
- 部署：加密PostgreSQL备份`hotel_ai_os-auto-20260915T112759+0800.dump.enc`通过校验。首次激活因健康脚本瞬时SIGPIPE 141触发自动回滚；确认迁移成功、服务健康和候选哈希无误后，以健康重试及旧版本回滚保护重新激活成功。最终Core API/Caddy均active，健康UP，Flyway JAR/数据库53/53，公网首页200，未授权身份接口401，启动后warning为0。
- 验证：Web 104项测试、TypeScript、Pilot生产构建及1280×900浏览器检查通过；线上真实登录后的数据刷新和横向操作仍由业务账号完成最终复验。
- 依据：`CHANGELOG.md`、`docs/TECH-V0.2-PILOT.8-CLOUD-DEPLOYMENT-REPORT.md`。

### 5.28 横向证据照片与主管图片画廊增量发布（2026-09-15）

- 状态：CODE COMPLETE / DEPLOYED TO INTERNAL PILOT；不改变TECH-V0.2正式发行NO-GO。
- 功能：工作记录与任务证据的新照片在前端和后端双重强制横向；后端先按EXIF方向归一化，再拒绝竖向或正方形照片并保存方向审计元数据。主管端团队工作详情直接展示全部图片，按检查区域和房号/实例标记，统一使用16:9横向画框并支持大图切换；历史照片不改写。
- 版本：功能提交`01733b0e5c2a0d8af6cf2228f1283de483c1fc93`已快进合并并推送GitHub主线；云端不可变release为`20260915-pilot8-01733b0`。
- 制品：后端JAR SHA-256为`e577d6741367ba290c8b3cb0df93dd0cb687369ed25a97e7e271b63e94b38ecd`；Web ZIP SHA-256为`bfd9a6eabd23d775dbd5cb553bb5a744a595ab4692f0ad9ed0308186097d9b71`，`index.html` SHA-256为`11ef63684d87995b81d82b129787f12bf2944ab029520745b7783df053837065`，服务器与公网回读一致。
- 部署：加密PostgreSQL备份`hotel_ai_os-auto-20260915T133022+0800.dump.enc`返回`POSTGRES_BACKUP_OK`且权限为`root:root:0600`。无数据库迁移；Core API/Caddy均active，健康UP，Flyway JAR/数据库53/53且失败迁移0，公网首页200，未授权身份接口401。
- 验证：Web 108项、TypeScript、Pilot生产构建、后端照片方向/EXIF专项及生产JAR构建通过；5个发布输入扫描63个归档、23739个条目，敏感信息0命中、0错误。Codex内置浏览器因运行组件版本不完整未能启动，登录后真实画廊与手机横向拍摄仍需业务账号复验。
- 依据：`CHANGELOG.md`、`docs/TECH-V0.2-PILOT.8-CLOUD-DEPLOYMENT-REPORT.md`。

### 5.29 企业微信任务链接顶层换票增量发布（2026-09-15）

- 状态：CODE COMPLETE / DEPLOYED TO INTERNAL PILOT；不改变TECH-V0.2正式发行NO-GO。
- 功能：企微任务入口使用浏览器顶层POST消费一次性码，由303响应设置Secure/HttpOnly会话Cookie并跳回白名单任务Hash；只在地址栏短暂使用不含凭据且会立即清理的会话标记，解决部分iOS企业微信WebView异步换票Cookie不落盘而反复要求账号密码登录的问题。
- 版本：功能提交`284f2752156a596e3b5d9a19fd3dcefa89520e41`已快进合并并推送GitHub主线；云端不可变release为`20260915-pilot8-284f275`。
- 制品：后端JAR SHA-256为`eeaeb2c3319b221151e331e4bbc9baba91c4d159a4c59d336d9b42a364b56a88`；Web ZIP SHA-256为`3450d74b8d9357cb7a6704f0494960e6e47b5ec97ad5070aa717fd86587bc09b`，服务器与公网`index.html` SHA-256为`02fe035692aea48ceee5d6f24a2578e57a8b19d4b067868ed6273370d047b841`。
- 部署：加密PostgreSQL备份`hotel_ai_os-daily-20260915T185329+0800.dump.enc`完成导出、`pg_restore --list`及SHA-256校验；Core API/Caddy/PostgreSQL/fail2ban/ClamAV均active，健康UP，Flyway V53且失败迁移0，公网首页200、未授权身份接口401。异地备份尚未配置，本轮仅沿用已授权的内部Pilot例外，不视为门禁关闭。
- 验证：后端267项、Web 111项、TypeScript和Pilot生产构建通过；发布输入敏感信息扫描0命中、0错误。公网换票路由与静态契约已生效，真实企微客户端仍需用测试账号连续点击两个不同任务确认最终体验。
- 依据：`CHANGELOG.md`、`docs/TECH-V0.2-PILOT.8-CLOUD-DEPLOYMENT-REPORT.md`。

## 6. TECH-V0.3

### 6.1 状态

预实施计划V1.0于2026-07-17输出，V1.1于2026-07-18增加受限CEO Agent和《CEO AI经营简报》。2026-07-18已形成`docs/TECH-V0.3-TECHNICAL-FREEZE-DRAFT.md`，但状态仍为`DRAFT / 待数据安全与多方评审 / 未启动编码`。只有TECH-V0.2正式发布、冻结参数确认并完成独立批准后，技术冻结才能生效。

该预实施计划和草案原按PRODUCT-V1.2编制。当前产品基线已于2026-09-11升级为PRODUCT-V1.4，因此TECH-V0.3开工前还必须按届时当前PRODUCT版本完成影响评审并明确重基线；本次不覆盖历史草案，也不视为TECH-V0.3已更新。

产品负责人在TECH-V0.2 Final UAT选择B后单独授权先输出计划，用于技术审查和资源安排；该授权不等于允许绕过TECH-V0.2发布门禁开始开发。

### 6.2 目标能力

- AI Gateway。
- 模型注册、路由、降级和成本控制。
- Prompt版本管理。
- Agent注册和工具权限。
- AI调用日志和安全审计。
- 首个经营/管理分析Agent。
- 工作分析Agent、经营分析Agent和点评分析Agent。
- CEO Agent及每日《CEO AI经营简报》。
- CEO Agent使用受限服务主体，数据范围按租户、组织授权、CEO收件任职、简报策略和工具白名单取交集。
- CEO实际决定经`CEODECISIONRECORDED`进入规则/任务闭环，AI不得代CEO决策。
- AI报告生成、人工审核和版本发布。
- AI任务建议、人工批准事件与规则/任务接续。
- AI主动发现形成风险事件。
- 标准、规则、任务和知识上下文检索。
- 高影响动作人工确认。

### 6.3 不允许的捷径

- Agent直接访问或修改业务数据库。
- 大模型替代确定性阈值、状态和升级规则。
- 未记录模型、Prompt和输入输出的AI调用。
- AI建议直接形成处罚、晋升、调价等高影响动作。

### 6.4 完成门槛

在开始开发前必须：

- 关闭TECH-V0.2 Final UAT报告中的全部REL-P0发布阻断项并正式发布TECH-V0.2。
- 编制并批准《TECH-V0.3技术冻结报告》与数据安全评审。
- 按`docs/SPRINT-3-PLAN.md`确认模型供应商、预算、审核人、报告周期和点评数据入口。
- 确认CEO简报时区、数据截止时间、收件任职、风险口径、决策事项范围和审计责任人。

预实施计划：`docs/SPRINT-3-PLAN.md`。

## 7. TECH-V0.4建议阶段

目标：

- 绩效复盘。
- 标准执行率和任务闭环率。
- 重复问题识别。
- 企业知识沉淀。
- 标准优化建议与人工审批。

该阶段尚未冻结，不能作为当前承诺范围。

## 8. TECH-V0.5建议阶段

目标：

- 更大规模酒店、区域和角色试点。
- PMS、OTA、财务等外部系统适配框架。
- 数据质量和主数据治理。
- 容量、性能和灾备验证。
- 集团推广准备。

该阶段尚未冻结，不能作为当前承诺范围。

## 9. TECH-V1.0集团内部正式使用

### 9.1 目标

Hotel AI OS达到集团内部正式生产使用标准。

### 9.2 必须满足

- 核心管理闭环稳定运行。
- 正式SSO/JWT与账号生命周期管理。
- 权限和租户隔离专项安全评审通过。
- 生产部署、灰度、回滚和灾备流程可执行。
- 备份恢复完成真实演练。
- 日志、指标、追踪和业务告警齐全。
- 数据归档、隐私保护和审计策略落地。
- API兼容和数据库升级策略稳定。
- 业务运营手册、培训材料和支持流程齐全。
- 集团试点门店验收通过。
- 没有阻止正式使用的P0/P1问题。

TECH-V1.0只能在上述条件全部验收后标记已发布。

## 10. 技术版本编号规则

### 10.1 正式使用前

- TECH-V0.x：一个可独立验收的能力阶段。
- TECH-V0.x.y：该阶段内的修复或小型兼容增强。

### 10.2 正式使用后

- 主版本：发生需要明确迁移的破坏性变化。
- 次版本：向后兼容的新能力。
- 修订版本：向后兼容的修复、安全或性能改进。

### 10.3 不提升TECH版本的情况

- 仅讨论方案。
- 尚未编码的需求。
- 不改变系统行为的文字修正。
- 未通过验收的中间构建。

## 11. 发布记录模板

每个新TECH版本复制以下结构：

    ## TECH-Vx.x

    - 状态：
    - 目标版本：
    - 开发开始日期：
    - 测试完成日期：
    - 验收日期：
    - 发布日期：
    - 产品蓝图版本：
    - Git提交或标签：
    - 后端制品版本及SHA-256：
    - 前端制品版本及SHA-256：
    - OpenAPI版本及SHA-256：
    - 数据库迁移范围：

    ### 已完成能力

    ### 数据库变化

    ### API变化

    ### 权限和安全变化

    ### 兼容性

    ### 已知问题

    ### 测试与验收证据

    ### 部署、回滚和恢复

    ### 关联Change Log

## 12. 固定维护规则

### 12.1 开始变更时

1. 在CHANGELOG.md的Unreleased创建CHG记录。
2. 指定目标TECH版本。
3. 确认是否影响PRODUCT蓝图。
4. 记录数据库、API、权限、历史数据和回滚影响。

### 12.2 开发过程中

1. 数据库只新增Flyway迁移，不改已发布迁移。
2. OpenAPI与Controller同步更新。
3. 权限矩阵和租户隔离测试同步更新。
4. 测试报告记录新增和回归结果。

### 12.3 验收发布时

1. 把技术版本状态改为已发布。
2. 把对应Change Log从Unreleased移入正式版本。
3. 记录迁移范围、API版本、制品校验和和Git标签。
4. 链接测试报告、验收报告和截图。
5. 记录已知问题和恢复方案。

### 12.4 需要更新产品蓝图时

以下情况必须先更新docs/HOTEL-AI-OS-PRODUCT-BLUEPRINT.md：

- 产品定位变化。
- 顶层管理链变化。
- 新增或删除核心中心。
- 组织、一人多岗、权限隔离或标准版本原则变化。
- AI与确定性规则的职责边界变化。
