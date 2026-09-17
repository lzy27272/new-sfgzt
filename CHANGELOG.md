# Hotel AI OS Change Log

本文件记录Hotel AI OS每一次产品、技术、数据库、API、权限、安全、页面和文档变更。

记录原则：

- 所有变更先进入Unreleased。
- 只有完成开发、测试和验收后，才能移入正式TECH版本。
- 产品蓝图变更使用PRODUCT版本，技术交付使用TECH版本。
- 已发布记录采用追加式维护，不通过覆盖历史隐藏旧决策。
- 仅有规划、尚未编码的内容必须明确标记为规划中。

## 版本标识

| 类型 | 标识示例 | 当前值 | 含义 |
|---|---|---|---|
| 产品蓝图 | PRODUCT-V1.4 | PRODUCT-V1.4 | 产品为什么这样设计、管理链和领域边界 |
| 技术发行 | TECH-V0.1 | TECH-V0.1 | 当前真正完成并验收的系统能力 |
| API主版本 | API-V1 | /api/v1 | HTTP向后兼容边界 |
| 数据库迁移 | DB-V4 | 已发布DB-V4；云端内部Pilot运行Flyway V55 | 正式发布基线与当前Pilot迁移范围 |
| OpenAPI契约 | 0.1.0-sprint1 | 已发布0.1.0-sprint1；云端Pilot运行0.2.5-pilot.8 | 当前已发布与内部Pilot接口制品 |

禁止只写“V1.3”而不说明是PRODUCT、TECH、API还是数据库版本。

## Unreleased

### Added

#### CHG-20260917-069：酒店运营岗位日清模板、共享督办与日报闭环

- 日期：2026-09-17。
- 状态：Unreleased / CODE COMPLETE / INTERNAL PILOT DEPLOYMENT PENDING。
- 岗位模板：新增店长、两类店助（有前厅主管/无前厅主管）、前厅主管、服务管家、前台、客房主管、客房服务员、夜班服务员和早餐区负责人日清工作包与日报配置；门店可配置早餐、下午茶、后厨等设施适用条件，店助模式变更从下一营业日生效并同步重算工作与日报分配。
- 时间与提醒：支持白班、夜班及跨午夜班次，统一采用开始后第1分钟、截止前15分钟、逾期后5分钟和逾期20分钟升级直属主管；同一员工同一时刻的多项提醒按通知通道去重聚合。
- 共享执行：店长/店助以及前厅主管/服务管家的共享事项按门店营业日只完成一次，首个有效提交人作为实际执行人；共享记录只投影到实际执行人的岗位日报，完成数、待审核、异常和逾期按语义工作项去重，避免不同模板物理ID造成重复统计。
- 休息转交：模板声明支持休息日转交；员工在第一项工作选择“本人今日休息”后，可把当日当前及后续尚未结束的可转交事项批量转给同店有效任职人员，后续提醒只发送给受托人并保留逐项审计记录。
- 组织范围：门店级工作允许所属部门任职执行，工作列表、提交、共享事项、转交、提醒、日报和营业日解析统一使用“目标门店为祖先、任职部门为后代”的组织闭包校验；租户隔离、有效任职和跨门店拒绝保持不变。
- 证据与日报：照片、截图、PDF、Word、Excel等证据继续执行安全上传、哈希、访问控制和审计；日报自动汇总岗位事实、异常、完成率及人工补充内容。当前云端尚未配置外部AI识别供应商，附件明确标记为“需人工整理/确认”，不伪造AI摘要，也不阻断员工提交；真实OCR和“事实、分析、建议”需在供应商凭据与异步处理器启用后生效。
- 数据库：新增Flyway `V56__hotel_operational_role_daily_templates.sql` 与 `V57__authoritative_hotel_evidence_instances.sql`；迁移以追加方式发布模板、表单、工作包、分配、日报规则和门店权威证据清单，不删除历史工作记录与证据。
- 验证：Flyway V1到V57全链路迁移、店长/店助共享日报实际执行人专项、店助模式切换专项、适用条件、班次时段、部门任职到门店任务闭环和SLA提醒测试均通过；Web全量162项及Pilot生产构建通过；Core API完整回归308项通过、0失败、0错误、3项按环境跳过。

#### CHG-20260917-068：现场证据照片横竖自适应与移动草稿兼容

- 日期：2026-09-17。
- 状态：Unreleased / CODE COMPLETE / INTERNAL PILOT DEPLOYMENT APPROVED。
- 规则调整：替代`CHG-20260915-065`中“只允许横向照片”的旧上传约束；工作记录和管理任务现在接受可读取的JPG/JPEG/PNG横图、竖图和方图，不再要求员工固定横拍。
- 服务端：继续按EXIF方向归一化并安全重编码图片，加盖门店、服务端可信时间、工作项和证据编号水印；元数据记录归一化后的真实尺寸及`LANDSCAPE`、`PORTRAIT`或`SQUARE`展示方向。原有格式、像素、文件大小和恶意文件扫描边界不变。
- 展示端：团队工作证据画廊和任务附件预览按照真实宽高选择横向、竖向或方形画框；旧记录缺少方向元数据时，以图片加载后的自然宽高回退判断。
- 合并兼容：与同期手机端断点草稿能力合并，保留本机待上传附件恢复、冲突选择、上传中禁用和未上传证据阻止提交等保护。
- 数据库与权限：不新增Flyway迁移，不修改角色、组织范围或附件访问权限；新增方向信息写入既有`evidence_metadata` JSONB，历史记录无需批量改写。
- 验证：合并后Web全量156项、TypeScript及Vite Pilot生产构建通过；后端方向与任务证据专项17项已通过，完整Core API回归在发布门禁中执行。

#### CHG-20260915-067：企业微信任务链接顶层换票与免重复登录修复

- 日期：2026-09-15。
- 状态：Unreleased / CODE COMPLETE / DEPLOYED TO INTERNAL PILOT。
- 根因：上一版已确认企业微信OAuth授权和一次性交换成功，但部分iOS企业微信WebView不会稳定接收异步`fetch`响应设置的会话Cookie；新任务打开在新的WebView上下文中时仍会落回账号密码登录。
- 会话修复：企微任务入口改为浏览器顶层表单POST一次性换票，由服务端在303跳转响应中设置`__Host-`、Secure、HttpOnly、SameSite=Lax会话Cookie，再回到已校验的任务Hash；前端只消费并立即清理不含凭据的`wecom_session=1`标记，JWT不进入地址栏、历史记录或Web存储。旧JSON换票接口继续保留兼容。
- 安全边界：顶层换票端点仅在认证前开放，仍执行一次性码、租户、账号、绑定、任职与深链目标校验；伪造或重复会话标记不会建立登录状态，残留Cookie不会阻断重新授权。
- 验证：后端全量267项通过（0失败、0错误、3项按环境跳过），新增真实Spring Security链路下仅凭安全Cookie访问身份接口的集成测试；Web全量111项、TypeScript及Pilot生产构建通过。5个发布输入扫描63个归档、23,740个条目，敏感信息0命中、0错误。
- 发布：功能提交`284f2752156a596e3b5d9a19fd3dcefa89520e41`已快进合并并推送GitHub `lzy27272/AIzhongtai:main`，以不可变版本`20260915-pilot8-284f275`部署至腾讯云内部Pilot。后端JAR SHA-256为`eeaeb2c3319b221151e331e4bbc9baba91c4d159a4c59d336d9b42a364b56a88`，Web ZIP SHA-256为`3450d74b8d9357cb7a6704f0494960e6e47b5ec97ad5070aa717fd86587bc09b`，服务器与公网`index.html` SHA-256均为`02fe035692aea48ceee5d6f24a2578e57a8b19d4b067868ed6273370d047b841`。
- 运维：部署前加密备份`hotel_ai_os-daily-20260915T185329+0800.dump.enc`完成导出、`pg_restore --list`和SHA-256校验，密文摘要为`1f68a3828290ec62a90f44e8d049c93ebcdba02a7f7e197d9cc9f90835f25ff6`。服务器仍未配置异地备份挂载，备份服务在本地备份校验完成后以`OFFSITE_BACKUP_CONFIG_REQUIRED`退出；本次内部Pilot部署沿用已明确授权的例外，以候选哈希、Flyway V53、失败迁移0、本地加密备份、端口隔离、服务健康、公网200/401及旧版本回滚保护完成切换，不代表异地备份门禁已关闭。
- 复验边界：公网制品和服务端契约已验证；真实企业微信授权码只能在员工企业微信客户端产生，测试账号需重新点击两个不同任务，确认首次授权后均直接进入对应任务而不显示账号密码页。

#### CHG-20260915-066：企业微信任务链接会话跨WebView保持

- 日期：2026-09-15。
- 状态：Unreleased / CODE COMPLETE / DEPLOYED TO INTERNAL PILOT。
- 根因：生产OAuth审计记录显示任务链接已成功完成企业微信授权及一次性交换，但前端只在模块内存和`sessionStorage`保存登录状态；企业微信重建或刷新WebView后状态丢失，页面因此回到账号密码登录。
- 会话修复：OAuth交换成功后由服务端设置`__Host-`前缀、Secure、HttpOnly、SameSite=Lax、Path=/且与JWT到期时间一致的会话Cookie；前端仅持久化不含凭据的企微会话到期标记，WebView重载后继续由安全Cookie完成认证。显式Authorization头仍优先，普通账号密码JWT继续只保存在内存。
- 退出与边界：新增退出接口清理企微Cookie；登录、退出和OAuth匿名端点忽略残留企微Cookie，避免过期Cookie阻断重新授权。租户上下文、岗位权限和深链目标校验不变。
- 验证：后端全量264项通过（0失败、0错误、3项按配置跳过），Web全量108项、TypeScript和Pilot生产构建通过。线上退出接口返回204并清除Secure/HttpOnly/SameSite=Lax Cookie；公网首页200、未授权身份接口401、服务健康UP且启动日志无warning/error/exception。
- 发布：功能提交`b8ad8547e05ad1600e40de08fc40b2cddd50e0eb`已快进合并并推送GitHub `lzy27272/AIzhongtai:main`，以不可变版本`20260915-pilot8-b8ad854`部署至腾讯云内部Pilot。后端JAR SHA-256为`2692b290debd313defbba3d54b363353ac902e79256c5533c470783b9bd4baeb`，Web ZIP SHA-256为`0aea5e14320d537341f160d9f32c48110e0784ea23899818dda06e6e6529f37c`，服务器与公网`index.html` SHA-256均为`197bcc3576246cf7fb089fd15a4d43220385b511ed3ad5621828195566670469`。
- 运维例外：部署前本地加密PostgreSQL备份已完成导出、解密比对、`pg_restore --list`和SHA-256校验，但13:43后更新的服务器备份/健康脚本新增异地挂载强制项且尚未配置，同时健康脚本仍固定预期旧Flyway V22（生产实际V53），导致首次激活自动回滚。经用户明确授权，本次无数据库迁移的发布仅排除该外部存储门禁，以应用健康、Flyway V53/53、失败迁移0、端口隔离、本地备份、公网和回滚检查重新激活成功；异地备份配置及健康脚本版本常量仍为待处理运维项。
- 浏览器边界：Codex内置浏览器运行组件版本不完整，未完成真实企业微信客户端内的最终点击复验；测试账号需重新点击任务链接确认在企微授权有效期内不再出现账号密码登录页。

#### CHG-20260915-065：横向证据照片强校验与主管端图片画廊

- 日期：2026-09-15。
- 状态：Unreleased / CODE COMPLETE / DEPLOYED TO INTERNAL PILOT。
- 上传规则：工作记录和任务证据中的新JPG/JPEG/PNG照片统一要求横向；前端选择文件后立即读取方向并拒绝竖向或正方形照片，后端按EXIF方向归一化后再次校验`宽度 > 高度`，不能通过前端绕过。PDF、Word、Excel等既有补充文件不受照片方向规则影响。
- 证据元数据：后端保存原始尺寸、归一化尺寸、EXIF方向和是否完成方向归一化，便于后续审计；历史照片不批量改写。
- 审核体验：主管打开团队工作详情时直接加载全部照片，按检查区域、房号/实例、来源、时间及扫描状态展示；照片统一放入16:9横向画框并保持原图比例，支持大图前后切换。非图片附件继续以文件列表展示。
- 验证：Web全量108项、TypeScript及Pilot生产构建通过；后端照片方向/EXIF归一化与既有业务集成专项通过，生产JAR构建成功。5个发布输入扫描63个归档、23739个条目，敏感信息0命中、0错误。
- 发布：功能提交`01733b0e5c2a0d8af6cf2228f1283de483c1fc93`已快进合并并推送GitHub `lzy27272/AIzhongtai:main`，以不可变版本`20260915-pilot8-01733b0`部署至腾讯云内部Pilot。部署前加密备份、制品SHA-256、Flyway V53/53、Core API/Caddy、服务健康、公网首页哈希及未授权401边界均通过；本次没有数据库迁移。
- 浏览器边界：Codex内置浏览器运行组件版本不完整，无法启动线上实页交互检查；未使用其他浏览器工具规避该故障。公网制品与服务端门禁已独立通过，主管登录后的画廊视觉效果和手机端现场横向拍摄仍需业务账号最终复验。

#### CHG-20260915-064：工作台当日口径刷新与横向完整浏览

- 日期：2026-09-15。
- 状态：Unreleased / CODE COMPLETE / DEPLOYED TO INTERNAL PILOT。
- 当日口径：工作台按上海时区维护当前日期，并把日期纳入个人工作、团队工作和汇总资源键；页面保持打开跨日、窗口重新获得焦点或恢复可见时会自动刷新，旧营业日的补交记录不再混入“今日”状态。
- 数据标识：负责门店列表明确显示数据日期；当服务端汇总日期与当前日期不一致时不展示过期完成率，避免把昨天的数据误认为今天。
- 页面浏览：门店详情增加可见的横向滚动容器和键盘焦点，在窄窗口及表格超宽时可以浏览右侧完整内容。
- 验证：Web 104项测试、TypeScript及Pilot生产构建通过；1280×900浏览器检查确认日期标识正常、控制台错误为0。
- 发布：功能提交`8ed46fb2ef2a3d7f2635d36b3c6229a58b5427b7`已快进合并并推送至GitHub`lzy27272/AIzhongtai:main`，以不可变版本`20260915-pilot8-8ed46fb`部署至腾讯云内部Pilot。后端沿用已验证V53制品；部署前加密备份、制品SHA-256、Flyway V53/53、Core API/Caddy、公网首页哈希、未授权401边界和启动日志门禁均通过。
- 回滚记录：首次激活因健康脚本瞬时以SIGPIPE 141退出而自动恢复`20260915-pilot8-226c084`，当时迁移已成功且服务健康；复核候选哈希并独立通过健康脚本后，使用三次健康重试和原版本回滚保护重新激活成功，未执行数据库破坏性回退。

#### CHG-20260915-063：工作台一键下达任务与分层完成率看板

- 日期：2026-09-15。
- 状态：Unreleased / CODE COMPLETE / DEPLOYED TO INTERNAL PILOT。
- 工作台：具备任务创建与派发权限的管理岗位可在当前工作台直接打开正式任务下达表单；选择门店后自动带入当前管理门店，创建仍由服务端任务目标策略限制在本人租户、组织树和门店授权范围内。
- 权限：Flyway V53为集团副总经理角色以及现有已发布/草稿岗位功能方案补齐`task.create`、`task.dispatch`；权限只开放既有创建入口，不扩大实际可下达目标范围。
- 完成率：团队工作汇总增加门店→部门→员工层级，当日及截至当日月完成率、待提交和当前逾期均可下钻查看；集团岗位只看授权门店和下属，门店岗位只看本店部门和员工。
- 验证：Web 102项测试、TypeScript及Pilot生产构建通过；后端集团副总权限迁移和店长分层汇总专项4项通过，完整Flyway V1→V53迁移通过；Core API生产JAR构建成功。
- 发布：功能提交`226c084339bbc889a0c7147079e865cc7b3eb839`已快进合并并推送至GitHub`lzy27272/AIzhongtai:main`，以不可变版本`20260915-pilot8-226c084`部署至腾讯云内部Pilot。部署前加密备份、制品SHA-256、Flyway V53/53、Core API/Caddy、服务健康、公网首页哈希和未授权401边界均通过；生产库1个租户角色和2个岗位方案版本已同时具备两项派发权限。
- 浏览器边界：Codex内置浏览器连接线上页面时连续超时，因此本轮未宣称登录后真实账号交互验收；没有改用未经授权的浏览器替代方案。公网HTTP、静态制品一致性和服务端门禁均已独立通过，真实集团副总账号的按钮及下钻数据仍需业务账号复验。

#### CHG-20260914-062：修复企业微信多岗位账号无法进入中台

- 日期：2026-09-14。
- 状态：Unreleased / CODE COMPLETE / DEPLOYED TO INTERNAL PILOT。
- 生产根因：14:09的企业微信OAuth请求已通过状态与浏览器Cookie校验并完成供应商授权码领取，但身份解析失败；后台为员工增加第二个有效任职后，绑定巡检把“存在多个有效岗位”一律判为异常，错误暂停了已有有效默认岗位的账号，与系统已支持的一人多岗位规则冲突。
- 绑定修复：员工存在多个有效任职时，只要原默认任职仍有效，继续保留该默认任职并刷新绑定快照，不再暂停账号；此前仅因`MULTIPLE_ACTIVE_ASSIGNMENTS`被暂停的绑定会自动恢复启用。默认任职缺失或失效、人工暂停、账号或岗位失效等风险状态仍保持关闭式拦截。
- 入口修复：工作台OAuth已通过合法状态及Cookie校验、但身份解析失败时，回调改为跳转中台HTML故障页，不再返回可能被iOS企业微信识别为`callback`下载文件的空响应；入职绑定流和伪造/缺失状态仍返回401。
- 验证：新增多岗位保持启用、历史误暂停自动恢复、OAuth身份失败转HTML页面等回归用例；后端专项43项及全量256项通过（0失败、0错误、3项按环境跳过），Flyway V1→V49完整迁移及生产JAR构建通过。
- 部署：功能提交`0ff58b9ddc9c9ad3842a4183dbb6cb306f9997fa`以不可变版本`20260914-pilot8-0ff58b9`部署至腾讯云内部Pilot；生产绑定巡检已将`xiajun`和`sfglzy777`恢复为ACTIVE并保留原默认任职。部署前加密备份、制品敏感信息扫描、SHA-256、Core API/Caddy、Flyway V49/49及启动日志门禁均通过；Browser实页确认身份失败显示中台HTML提示页而非下载页，返回登录交互正常且控制台错误/警告为0。

#### CHG-20260914-061：修复企业微信工作台入口被识别为 callback 下载文件

- 日期：2026-09-14。
- 状态：Unreleased / CODE COMPLETE / DEPLOYED TO INTERNAL PILOT。
- 根因：企业微信工作台应用主页直接打开通用OAuth回调地址，但首次进入不包含供应商返回的`code/state`参数；后端原先返回非HTML错误响应，iOS企业微信因此把末级路径`callback`识别为待下载文件。
- 服务端：仅当回调地址同时缺少`code`和`state`时，自动创建新的工作台OAuth状态及浏览器校验Cookie并跳转企业微信授权；正常授权回调和员工入职绑定回调继续按原流程处理。
- 安全：只缺一个参数、缺失或冲突的校验Cookie、伪造状态等请求仍关闭式拒绝；重启授权时同时清理可能残留的入职绑定校验Cookie，响应继续使用Secure、HttpOnly、SameSite=Lax及no-store策略。
- 验证：控制器专项5项及后端全量253项通过（0失败、0错误、3项按环境跳过），覆盖无参数入口自动授权、旧绑定Cookie清理、无下载响应头、单参数关闭式拒绝、正常工作台回调和入职绑定回调；Flyway V1→V49完整迁移通过。
- 部署：功能提交`aa90f09b62c282c32490c6670de14862ad46e8d0`以不可变版本`20260914-pilot8-aa90f09`部署至腾讯云内部Pilot；部署前加密备份、制品敏感信息扫描和SHA-256校验通过，Core API/Caddy均为active，Flyway JAR/数据库均为V49且失败迁移0。线上无参数回调返回302企业微信OAuth跳转、无`Content-Disposition`且响应体为空；单参数异常请求仍返回401。Browser实页到达企业微信“请在企业微信客户端打开链接”授权提示，未再进入callback文件下载页。

#### CHG-20260914-060：企业微信多人复用邀请与集团总部入职申请

- 日期：2026-09-14。
- 状态：Unreleased / CODE COMPLETE / DEPLOYED TO INTERNAL PILOT。
- 邀请：一键邀请改为独立的120分钟共享邀请；同一二维码和链接在有效期内可由多名员工同时扫码，每次扫码生成彼此隔离的企业微信验证及入职申请，任一员工提交后不会消耗共享邀请，仅超过固定有效期后失效。
- 组织：员工入职申请的“选择门店”扩展为“选择组织”，同时返回启用中的集团总部和门店；集团总部可选择“岗位待分配”，审核列表、分配选项和最终任职均保持总部组织归属。
- 数据库：新增Flyway V49，增加强制RLS的`wecom_open_onboarding_invitation`共享邀请表，并以`open_invitation_id`关联员工独立申请；旧版单员工邀请验证路径保持兼容。
- 权限与安全：集团总部审核只返回`GROUP_MANAGEMENT`岗位，并排除`TENANT`授权范围以及含任何不可委派权限的角色；集团董事长、平台管理员等受保护岗位不能通过员工入职审核授予。共享令牌仍只保存SHA-256摘要，员工OAuth、浏览器验证、资料及审批状态不在共享行中复用。
- 验证：Flyway V1→V49完整迁移通过；企微入职集成35项通过，覆盖同一令牌双线程并发扫码、独立申请、共享令牌保留、集团总部提交/安全岗位审核/落岗闭环；Web全量79项、专项12项和Pilot生产构建通过；桌面及390×844手机真实页面11项验收通过，控制台错误、页面异常与请求失败均为0。
- 部署：功能提交`0c7c04ad99a519e9e23cfccd678474f6cf421c8b`以不可变版本`20260914-pilot8-0c7c04a`部署至腾讯云内部Pilot；部署前加密备份及SHA-256校验通过，Flyway由V48迁移至V49，Core API/Caddy均为active，公网首页、未授权401、静态资源、制品哈希和启动日志门禁均通过；最终收口文档提交将推送`lzy27272/AIzhongtai:main`。

#### CHG-20260914-059：修复企业微信员工入职提交静默无响应

- 日期：2026-09-14。
- 状态：Unreleased / CODE COMPLETE / DEPLOYED TO INTERNAL PILOT。
- 根因：注册页把本地表单校验结果直接用于禁用“提交审核”按钮；密码少于10位等场景无法触发点击事件，页面也没有展示字段错误，用户看到的表现是点击后无反应且服务端未收到请求。
- 前端：提交按钮仅在请求处理中禁用；点击后统一执行姓名、手机号、账号、密码、确认密码及门店/岗位校验，并在字段旁和表单顶部明确提示错误。修正后继续沿用原有提交接口、手机号规范化及服务端租户/岗位/重复注册校验。
- 安全与权限：无数据库迁移、无权限扩张、无服务端契约变更；“岗位待分配”仍可进入审核，由管理员审批时补充岗位。
- 验证：TypeScript检查、Pilot生产构建及Web全量77项测试通过；移动端390×844真实页面闭环10项通过，覆盖短密码点击反馈、不发送无效请求、修正后仅提交一次、进入“申请已提交”状态，以及控制台、页面异常和请求失败均为0。
- 部署：功能提交`a69241f`以不可变版本`20260914-pilot8-a69241f`部署至腾讯云内部Pilot；部署前加密备份及SHA-256校验通过，Core API/Caddy均为active，Flyway JAR/数据库均为V48且失败迁移0，公网首页、静态资源新提示、未授权401边界、制品哈希和启动日志门禁均通过。

#### CHG-20260914-058：全岗位工作模板配置器与规则执行闭环

- 日期：2026-09-14。
- 状态：Unreleased / CODE COMPLETE / DEPLOYED TO INTERNAL PILOT。
- 配置器：工作包中心由单工作项编辑扩展为全岗位通用模板配置器；草稿版本支持新增、复制、移除、启停、拖动/按钮排序，并一次保存完整工作清单。
- 规则：每项工作可配置执行周期、时间窗、截止和宽限时间、适用星期、指定节假日/调休工作日、执行提醒/进度督办/逾期/升级提醒、接收对象、结构化证据点、拍照或文件类型、按房号等实例留证、附件限制、验收和日报汇总策略。
- 执行：停用项、非适用星期和配置为跳过的指定节假日不生成新工作；提醒时点按租户或固定时区解释，修复数据库时间归一化导致本地提醒延后执行的问题。
- 日报：`dailyReport`、`factLabel`、`includeEvidence`和`includeExceptions`进入已审核工作到日报的投影链路；手工补充工作和历史日报不被覆盖。
- 版本治理：已发布版本继续保持不可变；调整模板时创建新草稿版本，移除工作项不会删除历史工作、证据或日报记录。
- 数据库与权限：无新增迁移、无权限扩张；继续使用工作包已有岗位绑定、发布权限、租户和组织范围边界。
- 验证：全岗位配置真实浏览器闭环8项通过，浏览器控制台、页面异常、请求失败和服务端错误均为0；Web测试75项、Core API全量249项均通过（0失败、0错误、3项条件跳过），本次变更16个文件敏感信息扫描0命中、0错误。
- 部署：`20260914-pilot8-131568a`已部署至腾讯云内部Pilot，Flyway由V45升级至V48；公网首页、Core API、Caddy、未授权边界、制品哈希和近期日志门禁均通过。项目默认Git远端已迁移至`lzy27272/AIzhongtai:main`。

#### CHG-20260914-057：完成店长每日工作与逐房证据闭环

- 日期：2026-09-14。
- 状态：Unreleased / CODE COMPLETE / INTERNAL PILOT DEPLOYMENT APPROVED。
- 模板：依据《店长每日巡查与工作记录测试V1版9.13》形成店长试点模板V2，覆盖7项分时日常工作及日报；门店增加早餐服务和客房楼层配置，店长与店长助理可共享执行并保留委派链。
- 督办：每项工作具备提醒时间、逾期状态与企业微信深链，完成后进入直属主管复核；通过后自动投影到团队日报，日报由日常工作证据摘要与手工补充内容共同组成。
- 证据：支持现场相机拍照、可信时间及证据编号水印，并支持图片、PDF、Word、Excel附件；单文件上限20MB，单次提交设置200个文件的安全上限。
- 客房：查房固定填写5个不同房号、走脏房固定填写2个不同房号；每个房号独立上传至少1张对应照片，业务上不限制单房照片数量，并校验房号不重复、证据归属和数量闭环。
- 数据库：新增Flyway V46/V47，补齐日常工作闭环、日报自动投影、门店试点字段和店长模板V2数据结构。
- 权限与边界：继续使用服务端租户、组织范围、任职和主管关系校验；未扩大现有管理员或企业微信自动推送权限，现有生产功能开关保持不变。
- 验证：逐房上传真实浏览器验收通过；Web契约测试72项、TypeScript/Vite Pilot构建、店长闭环后端专项2项及Core API全量247项均通过（0失败、0错误、3项条件跳过），Flyway V1→V47完整迁移通过，并覆盖生产库已存在V2草稿时自动顺延至V3/V4的回归场景；本次变更33个文件敏感信息扫描0命中、0错误。

#### CHG-20260913-056：修复无启用部门门店无法申请已开放岗位

- 日期：2026-09-13。
- 状态：Unreleased / CODE COMPLETE / DEPLOYED TO INTERNAL PILOT。
- 根因：企微注册选项查询错误地把“门店下存在启用部门”作为必要条件；线上门店当前没有启用部门，因此已发布且允许企微员工申请的岗位全部被过滤。
- 服务端：新申请直接以启用门店作为任职组织，继续校验岗位已发布、允许企微员工申请并适用于所选门店；提交校验同时接受历史部门级申请，保持向后兼容。
- 前端：门店直属岗位只显示岗位名称，避免重复显示门店名称；部门级历史返回格式保持不变。
- 数据库与权限：无迁移、无角色或权限扩张；仍由服务端执行租户、门店、岗位发布状态及自助申请资格校验。
- 验证：新增“启用门店没有部门”集成回归；企微专项33项、Web 71项及完整后端243项通过（0失败、0错误、3项条件跳过），TypeScript/Vite Pilot生产构建通过，Flyway V1→V45完整迁移通过。
- 发布：功能提交`6bdb54a`已快进推送至GitHub `main`；腾讯云在加密备份成功后切换到`20260913-pilot8-6bdb54a`。Core API与Caddy均为active，健康门禁为`flyway_jar=45 flyway_db=45 failed_migrations=0`；公网首页返回200，未授权身份接口返回401，JAR/Web哈希与发布制品一致，启动warning为0。

#### CHG-20260913-055：统一企业微信一键邀请并增加手机号注册判重

- 日期：2026-09-13。
- 状态：Unreleased / CODE COMPLETE / DEPLOYED TO INTERNAL PILOT。
- 前端：企业微信人员绑定页取消“已有中台账号绑定”和“无中台账号注册”两个顶部入口，统一为“一键邀请”；员工完成企微身份验证后必须填写本人手机号。
- 服务端：手机号规范化为中国大陆11位号码，在提交审核前同时检查已有中台账号和其他未完结入职申请；命中时返回固定提示“手机号已注册”，不凭手机号自动接管既有账号。
- 数据库：新增Flyway V45，保存待审核手机号，并为中台账号手机号和开放入职申请手机号增加租户内唯一索引；审批通过后手机号同步写入账号与员工档案。
- 安全与兼容：手机号不写入日志、通知或审计；V45以前已进入审核的历史申请仍可继续审批。
- 验证：Web 70 项契约测试、TypeScript/Vite Pilot生产构建、Core API 企微专项 32 项及全量 242 项测试通过（0失败、0错误、3项跳过）；Flyway V1→V45完整迁移通过。
- 发布：功能提交`7909a1468a4510e7467a08ee98f190410bdf2a9b`已快进推送至GitHub `main`；腾讯云在加密备份成功后由Flyway V44升级至V45并切换到`20260913-pilot8-7909a14`，Core API与Caddy均为active，健康门禁为`flyway_jar=45 flyway_db=45 failed_migrations=0`。
- 公网：`https://www.sfgzt.cn`返回200，未授权`/api/v1/iam/me`返回401，公网首页哈希与服务器发布制品一致，新版“一键邀请”文本已回读；部署后Core API warning为0。未使用真实员工资料执行注册或审批。

#### CHG-20260912-054：部署TECH-V0.2-PILOT.8批次A/B到云端内部Pilot

- 日期：2026-09-12。
- 状态：Unreleased / `TECH-V0.2-PILOT.8` BATCH A/B DEPLOYED / FEATURES OFF / C—D NOT STARTED。
- 授权：产品负责人明确批准将收口提交推送到`lzy27272/codex`的`main`并部署到腾讯云`43.136.184.38`及`https://www.sfgzt.cn`。
- GitHub：功能提交`e001c2ae2c58445bae553667301f0c691c2e7edf`已快进推送；最终文档收口提交完成后使用同一门禁重部署并核对GitHub与云端版本一致。
- 数据库：部署前加密备份及SHA-256校验PASS；Flyway V38→V39 PASS，JAR/数据库均为39且失败迁移为0。
- 制品：源码67文件、JAR、Web和部署脚本敏感扫描均为0命中、0错误；云端JAR和公网首页哈希与本地制品一致。
- 运行：Core API/Caddy active、健康状态UP；未授权`/iam/me`及`/executive-tasks`返回401，最近启动日志0条warning。
- 公网：桌面1440×900与移动390×844的Microsoft Edge/Playwright验收PASS；页面身份、非空渲染、Pilot.8资源、表单交互、控制台、页面异常及请求失败均正常。
- 边界：云端未设置`GROUP_MANAGEMENT_*`变量，全部新增能力默认关闭；未创建真实人员任职，区域经理继续冻结，批次C/D未开始，TECH-V0.2正式发布状态仍为NO-GO。
- 关联文档：`docs/TECH-V0.2-PILOT.8-CLOUD-DEPLOYMENT-REPORT.md`、`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-BATCH-B-IMPLEMENTATION-REPORT.md`。

#### CHG-20260912-053：完成集团管理批次B董事长受限交办闭环

- 日期：2026-09-12。
- 状态：Unreleased / `TECH-V0.2-PILOT.8` BATCH B CODE COMPLETE / FEATURES OFF / NOT DEPLOYED。
- 实施授权：产品负责人在批次A通过后明确“下一步”；本轮只实施B1—B3，不进入批次C/D。
- 后端：新增专用列表、详情、目标、创建、验收、退回六个`/api/v1/executive-tasks`接口。创建命令在单事务内生成`PENDING_ACK`任务、精确ASSIGNEE/REVIEWER、董事长权威交办记录、双步时间线、提醒、审计、Outbox和任职通知。
- 安全：所有专用接口都要求有效董事长业务任职、专用权限和租户功能门禁；目标只返回集团根组织有效GM/副总任职；验收/退回同时校验本人REVIEWER、顶层可信来源及权威指令。董事长访问通用任务、动作和证据端点统一拒绝。
- 前端：增加独立懒加载高管交办页，董事长工作台计数和卡片仅使用专用最小投影；只在服务端能力、权限和业务任职同时成立时展示交办入口。董事长不再显示“全部功能”，移动端“我的”只有改密/退出，且多店导航改为“多门店经营”。
- 契约：OpenAPI `0.2.5-pilot.8`明确六个端点、严格请求模型、字段最小化投影及稳定错误码。任务来源显示优先使用顶层`creationSource`，不从快照推断新来源。
- 验证：高管交办专项、OpenAPI合同、Web 58项契约测试和TypeScript/Vite生产构建已通过；全量后端数据以批次B实施记录为准。
- 边界：功能开关保持默认关闭；未创建真实人员/任职，未迁移运行库，未构建发布制品，未部署。工作计划闭环仍属批次C。
- 关联文档：`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-BATCH-B-IMPLEMENTATION-REPORT.md`、`docs/API.md`、`docs/openapi.yaml`。

#### CHG-20260911-052：完成集团管理批次A身份、迁移与契约底座

- 日期：2026-09-11。
- 状态：Unreleased / `TECH-V0.2-PILOT.8` BATCH A CODE COMPLETE / FEATURES OFF / NOT DEPLOYED。
- 实施授权：产品负责人明确“开始编码”；依照冻结的分批门禁，本轮仅完成A0—A4，不进入B/C/D，不创建真实人员任职，不部署。
- 身份与API合同：增加服务端确认的可空`businessActorAssignmentId`；首次身份请求不带任职时返回null和完整有效任职列表，前端再默认选择主任职或允许切换本人有效任职。CEO/平台管理员继续保留账号级权限和TENANT范围，但责任动作可独立携带业务任职。任职格式、归属和动作不匹配使用稳定400/403错误码；`/iam/me`新增集团能力开关快照。
- 数据库：新增单一事务Flyway `V39__group_management_roles_and_work_plans.sql`，增加3个SYSTEM角色、4个岗位、8项权限、11张强制RLS租户表、直属/间接统一防环关系、工作计划结构、任务提醒、董事长权威交办关系及`management_task`可信来源；不修改V1—V38，不新增区域经理，不改写`OTA_OPERATION_MANAGER`，不猜测真实人员。
- 权限与岗位：集团总经理岗位唯一映射既有`CEO`角色；新增集团董事长、行政人事主管、行政人事角色/岗位。董事长只获得冻结白名单及专用交办权限，不进入管理后台；行政人事普通岗位默认不具备计划提交能力。
- 功能门禁：三项集团能力默认均为false；只要任一开关开启，租户白名单必须非空且全部为UUID，否则启动失败。当前未实现也未开放B批次董事长交办接口、C批次计划命令接口或D批次提醒Worker。
- 前端与版本：统一以业务任职发送`X-Assignment-Id`，保留CEO账号级展示与独立业务任职选择；增加新岗位展示并移除“区域经理→OTA运营经理”“行政人事→HR_KPI_ADMIN”的泛化别名。Core API/Web升至`0.2.0-pilot.8`，OpenAPI升至`0.2.5-pilot.8`，API主版本仍为`/api/v1`。
- 验证：Java编译、集团功能门禁测试、业务任职安全测试、真实PostgreSQL 14.22 V1→V39迁移测试、Web 54项契约测试和生产构建通过；全量后端最终计数见批次A实施记录。所有功能开关保持关闭。
- 下一门禁：只有单独收到“进入下一批次”且A批次最终证据通过后，才可进入B批次；Pilot启用与部署仍需另行批准。
- 关联文档：`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-V1.1-IMPLEMENTATION-PLAN.md`、`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-BATCH-A-IMPLEMENTATION-REPORT.md`、`docs/API.md`。

#### CHG-20260911-051：完成集团管理与工作计划正式技术冻结

- 日期：2026-09-11。
- 状态：Unreleased / TECH-DESIGN-1.0 FROZEN / IMPLEMENTATION READY / NOT CODED / NOT DEPLOYED。
- 产品蓝图版本：PRODUCT-V1.4；业务设计版本：DESIGN-1.1。
- 目标技术版本：正式固定为`TECH-V0.2-PILOT.8`；目标Core API/Web为`0.2.0-pilot.8`，目标OpenAPI制品为`0.2.5-pilot.8`，API主版本仍为`/api/v1`。
- 变更类型：Security / Architecture / Documentation / Planning。
- 修改内容：将技术草案评审结论冻结为TECH-DESIGN-1.0，明确CEO账号级授权与`GROUP_GENERAL_MANAGER`业务动作任职分离、董事长权威交办来源、精确直属审批、间接领导只读、计划批准原子转任务、提醒租约和敏感任务字段策略；同时拆出A—D四个实施批次及各自停止门禁。
- 数据库影响：本轮没有执行数据库变更。实施目标固定为单一事务迁移`V39__group_management_roles_and_work_plans.sql`，一次增加可确定的角色、岗位、权限、十一张租户表、可信任务来源和关系约束；V1—V38保持不可变，真实人员任职不由迁移猜测。
- API影响：本轮没有修改运行API。目标新增九个`/api/v1/work-plans`接口和六个专用`/api/v1/executive-tasks`查询/交办/验收接口，并完整定义业务任职、工作计划、高管交办和任务来源合同。
- 权限及租户隔离影响：本轮没有运行时授权变化。目标实现要求所有新租户表强制RLS；董事长不进入管理后台，只能向GM/副总交办并验收本人权威交办；平台管理员不能代行总经理审批。
- 历史数据影响：无。区域经理继续冻结不用，`OTA_OPERATION_MANAGER`历史零改写；技术草案DRAFT-V0.2作为评审历史保留。
- 回滚或降级方案：本轮只有文档，可撤回本次未提交文档变更；未来V39失败时整笔事务回滚，生产数据库只允许备份恢复或前向修复，不采用破坏性down迁移。
- 验证证据：完成后端、数据库和前端/API三路只读评审，统一选择单一V39方案并形成身份、安全、事务、RLS、迁移、前端和发布验收矩阵；未运行功能测试，不宣称功能可用。
- 授权边界：产品负责人指示“进入下一步”，本次只完成正式技术冻结和实施计划；进入批次A仍须明确“开始编码”，Pilot启用和生产部署须分别批准。
- 关联文档：`docs/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-TECHNICAL-FREEZE.md`、`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-V1.1-IMPLEMENTATION-PLAN.md`、`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-V1.1-DESIGN-FREEZE.md`、`docs/V1.4-ARCHITECTURE-FREEZE.md`。

#### CHG-20260911-050：修订集团身份、汇报关系与董事长受限交办

- 日期：2026-09-11。
- 状态：Unreleased / PRODUCT-V1.4 DESIGN-1.1 FROZEN / TECHNICAL REVIEW PENDING / NOT CODED / NOT DEPLOYED。
- 产品蓝图版本：PRODUCT-V1.4。
- 目标技术版本：尚未立项；若沿当前Pilot增量实施，候选为`TECH-V0.2-PILOT.8`，开工时另行确认。
- 变更类型：Added / Changed / Security / Documentation / Planning。
- 修改内容：集团总经理与集团CEO冻结为同一岗位身份，新增唯一`GROUP_GENERAL_MANAGER`岗位并映射既有`CEO`角色，不新增同义系统角色；集团暂不启用区域经理，且`OTA_OPERATION_MANAGER`不再兼容为区域经理；新增`HR_ADMINISTRATION`行政人事和`HR_ADMINISTRATION_SUPERVISOR`行政人事主管角色及岗位，二者均直属集团总经理，集团副总经理通过独立关系作为间接领导；董事长增加`GROUP_CHAIRMAN`岗位任职，只能向集团总经理或集团副总经理交办并验收本人交办的任务。
- 修改原因：按集团当前真实岗位设置消除CEO/集团总经理双身份，暂停尚不存在的区域经理层级，补齐行政人事汇报线，并在不开放管理后台或通用任务派发的前提下形成董事长交办闭环。
- 影响模块：产品蓝图、组织与权限、岗位任职、直属/间接汇报关系、工作计划、任务中心、导航、审计、Outbox及候选API。
- 数据库影响：本轮无数据库变更。候选实施需追加角色/岗位/任职种子、间接领导关系及工作计划相关表；董事长和CEO/GM使用真实岗位任职复用现有任务参与人模型。所有迁移须从届时下一可用编号追加，V1—V38历史迁移不修改。
- API影响：本轮无API变更。候选实施新增工作计划接口及受限`/api/v1/executive-tasks`交办入口；目标必须由服务端固定为有效`GROUP_GENERAL_MANAGER`或`GROUP_VICE_PRESIDENT`任职。
- 权限及租户隔离影响：本轮无运行时授权变化。董事长候选权限为全集团业务查看、参与人受限的`task.review`和专用`executive-task.assign`，不授予`*`、管理后台、通用`task.create/task.dispatch`、计划审批或其他目标派发。CEO既有账号级授权保持，但候选实现必须另行保留并校验总经理业务动作任职。两个人事岗位不复用高权限补充角色`HR_KPI_ADMIN`；副总间接领导首期只读督导，不获得计划审批、验收、代理或自动派活权。所有候选关系和计划表强制RLS。
- 工作计划影响：集团总经理/CEO仍不提交；集团副总经理、行政人事主管及其他显式授权的管理岗提交并由精确直属主管审批。行政人事普通岗位默认不提交；董事长交办与工作计划审批是两个来源、权限和幂等键均独立的任务入口。
- 历史数据影响：无。既有`CEO`、`GROUP_VICE_PRESIDENT`、`OTA_OPERATION_MANAGER`及`HR_KPI_ADMIN`代码和授权保持原义；PRODUCT-V1.3 / DESIGN-1.0未实施并作为被取代的历史冻结保留。
- 回滚或降级方案：本轮只有文档；恢复时可回到V1.3历史口径，但不能据此改变任何运行态。实施后的数据库、权限与部署回滚方案必须在开工技术冻结中补齐。
- 验证证据：在`main@788cfe724c082fc9716f28b66c276ac81c9488e9`只读核查现有`CEO`角色、`HR_KPI_ADMIN`补充角色、任务参与人任职约束和任务目标策略；本轮不运行功能测试，不宣称已实现或上线。
- 产品批准依据：产品负责人于2026-09-11明确补充集团岗位、身份、汇报和董事长交办口径。
- 技术批准人：待开工前技术评审确认。
- 关联文档：`docs/V1.4-ARCHITECTURE-FREEZE.md`、`docs/HOTEL-AI-OS-PRODUCT-BLUEPRINT.md`、`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-V1.1-DESIGN-FREEZE.md`、`docs/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-TECHNICAL-FREEZE-DRAFT.md`。

#### CHG-20260911-049：冻结集团管理角色与周/月工作计划审批转任务

- 日期：2026-09-11。
- 状态：Unreleased / PRODUCT-V1.3 DESIGN FROZEN / TECHNICAL REVIEW PENDING / NOT CODED / NOT DEPLOYED。
- 产品蓝图版本：PRODUCT-V1.3。
- 目标技术版本：尚未立项；若沿当前Pilot增量实施，候选为`TECH-V0.2-PILOT.8`，不得静默占用已规划给AI的TECH-V0.3。
- 变更类型：Added / Changed / Security / Documentation / Planning。
- 修改内容：新增集团董事长、集团总经理、集团副总经理的角色与层级设计；新增集团总经理、集团副总经理岗位设计；冻结集团副总沿用`GROUP_VICE_PRESIDENT`、店总继续使用`GENERAL_MANAGER`的兼容规则；冻结集团副总经理及以下管理任职提交周/月计划、有效直属主管逐项审批任务内容/完成时间/提醒、批准后原子生成本人岗位任务的闭环。
- 修改原因：建立集团总经理和集团副总经理位于区域经理之上的经营管理链，并将管理岗位的周期承诺转为可提醒、可执行、可验收的工作任务。
- 影响模块：产品蓝图、组织与权限、任职汇报关系、工作计划、任务中心、提醒、审计、Outbox、岗位页面和董事长集团只读视图。
- 数据库影响：本轮无数据库变更。候选实施需要新增工作计划、修订、审批、计划项决策、任务链接和任务提醒表，并从届时下一可用Flyway编号追加；V1—V38历史迁移不得修改。
- API影响：本轮无API变更。候选实施在`/api/v1`向后兼容增加工作计划查询、草稿、提交、修订、审批、退回、拒绝和撤回接口；正式实现前再更新OpenAPI。
- 权限及租户隔离影响：本轮无运行时授权变化。冻结新增`GROUP_CHAIRMAN`、`GROUP_GENERAL_MANAGER`、`work-plan.*`及只读`work-record.team-read`候选权限；所有计划审批岗位还需`task.read/task.review`并继续受任务`REVIEWER`参与人限制。董事长使用隔离账号级业务上下文和闭合只读白名单，不映射CEO、不进入配置管理员路径，也不合并兼职身份权限；所有候选租户表必须强制RLS，审批另校验精确直属主管任职与组织范围。
- 历史数据影响：无。既有`GROUP_VICE_PRESIDENT`角色、岗位、任职和授权保留，只在实施时受控统一中文展示名；既有CEO、店总和店助语义不变。
- 回滚或降级方案：本轮只有文档。后续若改变冻结口径，必须追加新的产品决策并取代本版本，不覆盖历史；实现后的数据库、权限和部署回滚方案在开工技术冻结时补齐。
- 验证证据：在`main@788cfe724c082fc9716f28b66c276ac81c9488e9`完成现状只读核查；本轮不运行功能测试，不宣称实现或上线。
- 产品批准依据：产品负责人于2026-09-11明确要求先完成修改前设定和冻结。
- 技术批准人：待开工前技术评审确认。
- 关联文档：`docs/V1.3-ARCHITECTURE-FREEZE.md`、`docs/HOTEL-AI-OS-PRODUCT-BLUEPRINT.md`、`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-V1-DESIGN-FREEZE.md`、`docs/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-TECHNICAL-FREEZE-DRAFT.md`。

#### CHG-20260909-048：收紧系统角色治理并修复发布契约与健康门禁

- 日期：2026-09-09。
- 状态：Unreleased / LOCAL CANDIDATE / FORMAL RELEASE STATUS UNCHANGED / NOT DEPLOYED。
- 角色治理：通用IAM只允许创建、配置和授予`CUSTOM`角色；`SYSTEM`角色继续由已发布岗位方案或受控迁移维护。CUSTOM角色的创建、权限精确替换和授权均写入审计，角色列表明确返回是否可编辑。
- 数据库：新增Flyway V38约束，拒绝非规范`role_type`和冒用保留系统角色代码的CUSTOM角色；迁移不修改现有角色、角色授权、角色权限或已发布岗位权限，保留V37已经恢复的标准管理岗位工作闭环。
- 身份安全：有效身份只有在`role_type=SYSTEM`时才赋予平台管理员、集团管理员、CEO和人事KPI管理员的特殊角色语义，阻止历史伪造CUSTOM角色代码形成权限旁路。
- API契约：补齐`GET /api/v1/dashboards/hotels`，修正区域驾驶舱需要`dashboard.operations`，并将通用IAM的`roleType`请求契约收窄为可省略的`CUSTOM`。
- 发布门禁：生产健康检查从当前发布JAR逐项计算全部数字Flyway迁移的兼容CRC32，并与数据库成功历史的版本和checksum精确比对；缺失、中间断档、数据库超前、checksum漂移、失败或非法历史均拒绝，同时拒绝当前软链接与实际运行进程目录不一致。
- 验证：最终快照后端全量回归220项通过（0失败、0错误、3跳过），Web契约测试53/53通过，Pilot前端构建通过，静态发布门禁32/32通过；构建JAR包含V1至V38共38项迁移，JAR内完整迁移状态与Flyway 11.7.2校验结果一致。当前Windows环境无可用Bash，`health-check.test.sh`的Ubuntu实机执行及真实`flyway_schema_history`交叉验证保留为部署前置门禁，尚未宣称完成。

#### CHG-20260907-047：修复内部 Pilot 导航布局、门店驾驶舱取数与岗位模块治理

- 日期：2026-09-07。
- 状态：Unreleased / `TECH-V0.2-PILOT.7` INTERNAL PILOT PATCH / FORMAL RELEASE STATUS UNCHANGED。
- 页面：桌面端恢复固定左侧垂直导航，移动端保留五栏底部导航；顶栏只承载运行上下文与账号操作，避免横向菜单截断。
- API与权限：新增`GET /api/v1/dashboards/hotels`，仅要求`dashboard.hotel`并按租户和组织范围返回可见门店；`/dashboards/operations`继续只面向具有`dashboard.operations`的区域多门店角色。
- 岗位治理：新增Flyway V37的`ui.module.*`展示能力，岗位模块可见性与页面内操作权限分别配置；标准岗位按评审矩阵迁移，自定义岗位只做一次兼容推导，后续由已发布岗位权限配置决定。V37同时仅对V35/V36自动发布且未人工编辑的标准管理岗恢复既有V18管理闭环权限，人工岗位配置与门店覆盖不被扩权。
- 发布安全：纳入非敏感`.env.pilot`，固定Bearer认证、Caddy授权头、API前缀与关闭演示回退；直接执行`build:pilot`不得再生成匿名演示身份页面。八角色只读验收同步到新版登录可访问名称、内存令牌与Flyway V37契约。
- 验证：Web测试53/53、TypeScript和Pilot Bearer构建通过；Edge + Playwright在1860、1440与390像素视口完成固定侧栏、五栏底部导航及无页面横向溢出检查。后端210项测试0失败/错误、3项条件性跳过，真实PostgreSQL已迁移至V37；公网八角色回归以部署门禁实跑结果为准。

#### CHG-20260812-046：启动行政人事部KPI考核与绩效复盘中心全阶段开发

- 日期：2026-08-12。
- 状态：Unreleased / KPI PERFORMANCE CENTER LOCAL PILOT DEPLOYED / PRODUCTION NO-GO。
- 授权与范围：产品负责人确认全部KPI业务规则并授权开发所有阶段；模块归属四方馆AI中台行政人事部，不归属OTA独立后台。
- 目标：建设版本化岗位模板、统一指标事实、单岗位月度责任快照、固定四周与月度考核单、负分及人工评分、复核异议、更正锁定、奖金结算、门店后台推送、Excel导入和审计导出。
- 首批岗位：集团副总、OTA运营经理、OTA运营助理、门店店长、店助、前厅主管、前台员工、客房部负责人、客房服务员；只预置OTA运营经理正式模板，其余岗位通过后台配置或后续考核表导入。
- 安全边界：不连接真实PMS/OTA、不读取或保存Cookie和凭据、不发送外部真实通知；数据缺失进入待核验，不以0分代替。
- 技术冻结：见`docs/KPI-PERFORMANCE-CENTER-TECHNICAL-FREEZE.md`。
- 实施结果：新增Flyway V25—V27、31张KPI表、25项KPI权限、版本化模板/指标/政策、固定四周与月度考核、人工评分、负分、复核异议更正、奖金结算、每日三时段巡检、异常SLA、自动化调度、Excel导入和UTF-8 CSV一键导出；多渠道逐渠道独立判定。
- Pilot部署：2026-08-12本地数据库由V23迁移至V27，Core API与Web均健康；已发布1套OTA运营经理官方模板并启用3个巡检时段。完整后端回归88项、空库27迁移和前端Pilot构建全部通过；Pilot前端强制`bearer`账号登录，未认证身份请求返回401，支持显式退出，启动脚本拒绝误用非Pilot认证构建；部署前逻辑备份95个文件，详见`docs/tasks/KPI-PERFORMANCE-CENTER-V1-IMPLEMENTATION-REPORT.md`。

#### CHG-20260725-045：完成Sprint 2D离线人工授权演练

- 日期：2026-07-25。
- 状态：Unreleased / `OTA-AUTOMATION-V0.1` / SPRINT 2D OFFLINE REHEARSAL COMPLETE / REAL PMS AUTHORIZATION BLOCKED / REAL CONNECTORS BLOCKED / PRODUCTION NO-GO。
- 授权与范围：产品负责人要求进入下一步骤；本轮只实现程序内人工授权流程的离线可操作演练，不授权浏览器启动、PMS联网、SecretStore解析、真实抓取、经营分析、P1告警、企微发送、UAT或生产发布。
- Helper：新增纯内存无I/O状态机，覆盖准备、等待、确认、取消、过期、失败和重新演练；精确绑定tenant、hotel、connector、connector version、config version、actor和attempt，所有状态的授权结果固定为`AUTH_REQUIRED`。
- 数据库：新增Flyway V6的attempt、追加式command receipt和两个受控CAS函数；强制RLS、活动演练唯一性、15分钟过期、幂等键、请求哈希、操作人和配置/行版本校验。API对两张表只有`SELECT`，无直接DML，写入只能走`SECURITY DEFINER`函数。
- API与后台：新增开始、最近状态恢复、指定查看、确认、取消和重新演练接口；写操作限`PLATFORM_ADMIN + CONNECTOR_AUTHORIZATION_MANAGE`并绑定当前操作人。后台只对已保存的`PMS + CONTROLLED_BROWSER`草稿显示演练面板，刷新后恢复状态并对响应安全标志再次Fail Closed。
- 固定安全结果：响应始终为`mode=OFFLINE_REHEARSAL`、`authorizationState=AUTH_REQUIRED`、`runtimeBlocked=true`、`pmsConnected=false`、`browserStarted=false`、`credentialsRead=false`；`OFFLINE_REHEARSAL_COMPLETE`只表示演练流程完成，不表示真实授权。
- 验证：Java Maven聚合按当前测试源码计`283`项，0失败/错误、2项条件式PostgreSQL跳过；PostgreSQL 14.22真实专项执行Flyway V1→V6、2项集成测试、ACL/catalog负控、结构校验及双连接前置版本CAS并发实测全部通过；Web`16/16`、TypeScript和Vite 42 modules通过；已知敏感会话标记命中`0`。初次独立复核发现的3项P1和1项P2均已修复，第二次独立复核为`P0=0 / P1=0`。
- 保留门禁：未使用或保存用户此前提供的Cookie；没有浏览器驱动、网络访问、SecretStore实现、真实会话、PMS适配器、采集、企微投递、部署或生产变更。
- 文档：新增`docs/tasks/OTA-AUTOMATION-V0.1-SPRINT-2D-OFFLINE-AUTHORIZATION-REHEARSAL-IMPLEMENTATION-REPORT.md`并同步任务索引、根README和设计讨论。

#### CHG-20260725-044：完成程序内PMS登录的离线安全骨架

- 日期：2026-07-25。
- 状态：Unreleased / `OTA-AUTOMATION-V0.1` / OFFLINE BROWSER SESSION SKELETON COMPLETE / REAL PMS LOGIN BLOCKED / REAL CONNECTORS BLOCKED / PRODUCTION NO-GO。
- 授权与范围：产品负责人要求继续编写程序，把PMS链接登录留到后续在程序内实施；本轮只实现受控登录离线骨架，不授权真实登录、Cookie读取/重放、网络访问、自动抓取或企微发送。
- 独立助手：新增`apps/ota-browser-session-helper`，实现会话生命周期与全范围绑定、HTTPS/显式端口/请求摘要精确白名单、逐跳公网地址复核、服务端固定非秘密配置Schema和脱敏错误码；模块无浏览器驱动、HTTP客户端、DNS查询、SecretStore实现、持久化、调度器或厂商适配器。
- API与Worker：API使用无生产实现的服务端绑定Port按操作人和授权尝试隔离start/probe/revoke；Worker只有命中独立可信操作清单的操作人、授权尝试、全部范围、版本、数据流、操作和Secret Binding字段后才能生成助手调用令牌。Manifest直接构造已收紧，命令与条目字符串输出隐藏SecretStore定位符；当前客户端仍固定失败为`BROWSER_SESSION_HELPER_NOT_ENABLED`。
- 后台配置：PMS模板和页面支持`CONTROLLED_BROWSER + BROWSER_SESSION`配置，只允许不透明SecretStore引用并明确禁止粘贴Cookie；模板保持`DRAFT_INTAKE_ONLY`和`executable=false`。
- 安全验证：Java聚合`254`项测试0失败/错误、2项条件式PostgreSQL跳过；Web测试`12/12`、TypeScript和Vite生产构建通过；敏感Cookie字段标记扫描通过；最终独立复核在当前离线范围为`P0=0 / P1=0 / P2=0`。
- 保留门禁：没有数据库迁移、真实连接器候选、SecretStore实现、网络出口、浏览器驱动、厂商Schema适配器或生产变更；现有真实Profile拒绝启动门禁未放宽。
- 文档：新增`docs/tasks/OTA-AUTOMATION-V0.1-BROWSER-SESSION-SKELETON-IMPLEMENTATION-REPORT.md`并同步项目索引、设计讨论和Worker边界。

#### CHG-20260725-043：评估Cookie自动采集并按厂商授权门禁阻断

- 日期：2026-07-25。
- 状态：Unreleased / Controlled Intake / COOKIE AUTOMATION BLOCKED / I1 VENDOR AUTHORIZATION REQUIRED / REAL CONNECTORS BLOCKED / PRODUCTION NO-GO。
- 请求：产品负责人提出使用Cookie登录后自动抓取别样红PMS。
- 官方依据：[酒店SaaS产品服务协议](https://pms.meituan.com/pms-min-web/productService.html)禁止未经授权获取接口数据，要求第三方技术对接接受厂商评估，未明示授权须另行书面取得；当前没有喷水池态六酒店、具体方法、字段、频率和环境的许可材料。
- 首选路径：官方公开[签名鉴权安全规范](https://docs.beyondh.com/apidoc/security.html)、[公共参数](https://docs.beyondh.com/apidoc/pubparam.html)和[Hotel API](https://docs.beyondh.com/apidoc/HotelApi.html)；先申请OpenAPI门店和方法权限，公开文档本身不视为已经开通。
- 当前处置：未重新打开或复用保留的临时Chrome Profile，未读取、提取、导出、保存或重放Cookie，未启动周期采集。
- 获批后边界：如OpenAPI不能覆盖冻结字段且厂商书面批准网页登录自动化，新增独立浏览器会话代理托管会话；Cookie不得进入现有Worker、API、数据库业务字段、日志、Git或后台配置，会话失效后转人工重新认证。
- 文档：新增Cookie/浏览器会话自动采集评估与授权后实施设计，并同步实例档案、受控登录清单、受控接入工作包、设计讨论和诊断工具边界。
- 实现影响：没有运行时代码、数据库迁移、真实连接器候选、Secret解析、egress、企业微信发送或生产变更。

#### CHG-20260725-042：完成别样红PMS有限受控登录观察并登记I1许可缺口

- 日期：2026-07-25。
- 状态：Unreleased / Controlled Intake / CONTROLLED LOGIN COMPLETE / OBSERVATION PARTIAL / I1 LICENSE EVIDENCE MISSING / REAL CONNECTORS BLOCKED / PRODUCTION NO-GO。
- 执行：门店授权人员在全新独立Chrome Profile中现场输入凭据并选择喷水池态六酒店；Codex未获取、输出或保存账号、密码、Cookie、Token或验证码。
- 只读证据：PMS主页自然调用`POST /hotelpms/api/v1/report/home/workbench/room`，无请求体、HTTP 200、JSON `data[]`为三字段对象结构（一个字符串、两个数字）；字段名和值未留存。另观察到营业日、房型和主页经营概览候选路径。
- 候选纠正：用户提供的`GET /hotelpms/api/v1/report/lion/manager/workbench/room`仅返回通用JSON包络，不能据此证明正确业务方法、字段语义或连接器可用。
- 隐私与安全：只留存路径、方法、状态码和匿名类型指纹；原始请求/响应、请求头、会话及业务值未进入聊天、Git、fixture或诊断文件。观察完成后已关闭隔离Chrome进程；临时Profile未删除、未提取会话。
- 门禁缺口：厂商/合同允许自动化只读观察的证据仍未登记。本次不升级为I1、UAT、候选或连接器通过；许可、频率和责任人补齐前停止扩大真实页面观察。
- 实现影响：仅新增受控诊断脚本和状态文档，没有数据库迁移、生产API、真实Worker连接器、Secret解析、周期抓取或企业微信发送。

#### CHG-20260724-041：启动别样红PMS隔离登录并更正入口分类

- 日期：2026-07-24。
- 状态：Unreleased / Controlled Login / LOGIN ORIGIN OPENED / AUTHENTICATION PENDING / REAL CONNECTORS BLOCKED / PRODUCTION NO-GO。
- 执行：按产品负责人“现在启动”指令，启动独立临时Profile的可见Chrome无痕窗口，禁用同步和扩展；凭据由门店授权人员现场输入。
- 分类更正：`https://pms.meituan.com/`确认为登录入口；原`…/hotelpms/api/v1/report/lion/manager/workbench/room`确认为登录后业务接口。
- 透明说明：第一次启动可能对原地址产生一次未认证导航；未读取响应、捕获业务数据、复用会话或执行写操作。
- 当前边界：等待人工认证；Schema观察、自动抓取、适配器代码、Secret解析、企微发送和生产均未开始。

#### CHG-20260724-040：确认别样红PMS测试账号的只读用途授权

- 日期：2026-07-24。
- 状态：Unreleased / Documentation & Intake / `OTA-AUTOMATION-V0.1` / READ-ONLY ACCOUNT USE AUTHORIZED / LOGIN LAUNCH PENDING / REAL CONNECTORS BLOCKED / PRODUCTION NO-GO。
- 确认内容：门店/集团授权专用账号用于受控只读自动化测试，仅限喷水池态六酒店；禁止改价、改房态、改单及其他全部写操作。
- 当前边界：该确认不等于立即登录指令；本机隔离浏览器尚未启动，候选地址仍为`NOT_CONTACTED`，未接收任何账号或秘密。
- 下一门禁：产品负责人明确选择“现在启动”后，才可打开本机隔离浏览器，由门店授权人员现场输入凭据；浏览器辅助进程必须与OTA Worker隔离。
- 实现影响：无运行时代码、数据库迁移、API或页面变更；真实连接器、`test/activate/run`、Secret解析、采集和企微发送继续阻断。
- 文档：同步受控登录清单、实例档案、工作包、编码门禁、业务/技术记录和项目索引。

#### CHG-20260724-039：确认别样红PMS专用测试账号与现场输入条件

- 日期：2026-07-24。
- 状态：Unreleased / Documentation & Intake / `OTA-AUTOMATION-V0.1` / ACCOUNT INPUT CONDITION CONFIRMED / LOGIN NOT AUTHORIZED / REAL CONNECTORS BLOCKED / PRODUCTION NO-GO。
- 确认内容：产品负责人确认可以提供专用最小权限测试子账号，并由门店授权人员在隔离浏览器中现场输入账号、密码及必要验证码。
- 秘密边界：当前没有接收账号别名、密码、Cookie、Token或验证码；未来凭据也不得进入聊天、代码、文档、日志、截图、fixture或原始HAR。
- 本机能力核验：Chrome和Edge可用，项目存在Playwright测试依赖；现有OTA Worker安全测试禁止直接驱动浏览器，因此未来受控浏览器必须使用独立辅助进程，不得放宽Worker离线边界。
- 剩余门禁：仍需门店/集团确认该账号只限喷水池态六酒店、可用于受控只读自动化测试且禁止全部写操作；厂商/合同自动化许可、隔离UAT、SecretStore和字段证据仍未完成。
- 安全与实现影响：没有打开浏览器、访问或登录候选地址，没有新增运行时代码、数据库迁移、API或页面。
- 文档：同步受控登录清单、实例档案、工作包、编码门禁、业务/技术记录和项目索引。

#### CHG-20260724-038：确认别样红PMS无可见版本并形成受控登录清单

- 日期：2026-07-24。
- 状态：Unreleased / Documentation & Intake / `OTA-AUTOMATION-V0.1` / LOGIN PREPARATION ONLY / I0 PARTIAL / REAL CONNECTORS BLOCKED / PRODUCTION NO-GO。
- 版本结论：产品负责人确认登录页/系统没有可见版本或Build，登记为`NO_VISIBLE_VERSION`；不得把URL中的`/api/v1/`作为产品版本。
- 兼容策略：后续使用受信任构建生成的capability/schema指纹、脱敏字段合同和fixture回归识别变化。
- 登录方案：针对“是否先尝试登录再对接”形成受控清单，顺序为专用最小权限账号与自动化许可 → 隔离浏览器中由授权人员现场输入凭据 → 只读观察 → 脱敏字段合同 → 离线适配器 → 另行审批影子联调。
- 安全边界：不允许把账号、密码、Cookie、Token或验证码交给Codex/开发人员；不得使用日常管理员账号、原始HAR、路径扫描、写操作或跨店访问。本轮未访问、未登录、未开放egress。
- 文档：新增`CONTROLLED-LOGIN-RUNBOOK.md`并同步实例档案、工作包、编码门禁、业务/技术记录和项目索引。
- 版本影响：不改变TECH-V0.1、TECH-V0.2、AI中台Sprint 3或OTA独立后台`0.1.0-SNAPSHOT`的现有发布判断。

#### CHG-20260724-037：确认别样红PMS候选地址为网络登录接口

- 日期：2026-07-24。
- 状态：Unreleased / Documentation & Intake / `OTA-AUTOMATION-V0.1` / I0 PARTIAL / REAL CONNECTORS BLOCKED / PRODUCTION NO-GO。
- 确认内容：产品负责人确认候选地址是美团别样红PMS系统需要网络登录后使用的网页接口。
- 分类更新：正式接入方式登记为`AUTHENTICATED_WEB_INTERFACE`，地址分类为`LOGIN_PROTECTED_PMS_WEB_INTERFACE`；不按厂商开放API登记，也不从路径推断HTTP方法、认证机制、字段或数据能力。
- 后续门禁：登录型网页接入必须另行完成自动化许可、受控浏览器/会话隔离、SecretStore直接录入、会话失效与重新认证、安全日志脱敏和默认拒绝egress评审。
- 安全与实现影响：没有访问、登录或探测候选地址，没有接收或复用Cookie/Token，没有新增运行时代码、数据库迁移、API或页面；I0仍为`PARTIAL`，下一项只确认版本/Build。
- 文档：同步首个实例档案、受控接入工作包、编码门禁、业务/技术记录和项目索引。
- 版本影响：不改变TECH-V0.1、TECH-V0.2、AI中台Sprint 3或OTA独立后台`0.1.0-SNAPSHOT`的现有发布判断。

#### CHG-20260724-036：确认首个PMS接入实例的正式产品名称

- 日期：2026-07-24。
- 状态：Unreleased / Documentation & Intake / `OTA-AUTOMATION-V0.1` / I0 PARTIAL / REAL CONNECTORS BLOCKED / PRODUCTION NO-GO。
- 确认内容：产品负责人按上一确认入口回复“确认”，将“美团别样红系统”由用户提供名称更新为产品负责人确认的正式产品名称；门店仍为喷水池态六酒店，来源仍为PMS。
- 未确认内容：正式厂商法律名称、版本/Build，以及候选地址属于厂家正式开放API、登录后的网页后台内部接口、自动报表或其他方式。
- 安全与实现影响：没有访问候选地址，没有新增运行时代码、数据库迁移、API或页面；I0继续为`PARTIAL`，真实连接器、egress、Secret、`test/activate/run`、采集和企微发送继续阻断。
- 文档：同步首个实例档案、受控接入工作包、编码门禁、业务/技术记录和项目索引。
- 版本影响：不改变TECH-V0.1、TECH-V0.2、AI中台Sprint 3或OTA独立后台`0.1.0-SNAPSHOT`的现有发布判断。

#### CHG-20260723-035：锁定首个OTA自动化PMS接入实例并登记候选地址

- 日期：2026-07-23。
- 状态：Unreleased / Documentation & Intake / `OTA-AUTOMATION-V0.1` / FIRST ADAPTER SELECTED / I0 PARTIAL / REAL CONNECTORS BLOCKED / PRODUCTION NO-GO。
- 范围选择：产品负责人选择“喷水池态六酒店＋PMS＋美团别样红系统”为首个受控接入实例；“美团别样红系统”按用户原文登记为待复核系统名称，不未经证据拆分正式厂商、产品或版本。
- 地址登记：用户提供`https://pms.meituan.com/hotelpms/api/v1/report/lion/manager/workbench/room`。离线解析确认其为HTTPS、主机`pms.meituan.com`、默认端口443、无URI user-info、查询参数、片段或可见凭据；按`UNVERIFIED_WEB_BACKEND_ENDPOINT / USER_PROVIDED_UNVALIDATED / NOT_CONTACTED`登记。
- 判断边界：路径中的`/api/v1/`不作为PMS产品版本或厂商正式开放API证明；单一`room`地址不证明营业日、订单间夜、房费、钟点房、退款冲销或实体库存字段能力。
- 剩余I0输入：正式厂商/产品名称、版本/Build、部署方式、正式接入方式、资料责任人和门店复核人；I1至I3仍需合法访问许可、厂商合同、字段字典、脱敏样例、SHA-256和人工金标准。
- 安全边界：本轮没有执行DNS、TLS、HTTP、浏览器、登录或目录探测，没有开放`pms.meituan.com:443` egress，没有接收凭据或响应数据，没有新增运行时代码、数据库迁移、API或页面。可信候选、`test/activate/run`、Secret解析、采集和企业微信发送继续阻断。
- 文档：新增`docs/tasks/ota-controlled-external-intake/intakes/pilot-01-bieyanghong-pms/`实例目录并同步受控接入工作包、根索引、任务台账、设计状态与编码门禁。
- 版本影响：不改变当前TECH-V0.1正式发布基线，不改变TECH-V0.2及AI中台Sprint 3现有判断；OTA独立后台仍为`0.1.0-SNAPSHOT`。

#### CHG-20260723-034：打开独立OTA单一适配器受控外部接入前置

- 日期：2026-07-23。
- 状态：Unreleased / Documentation & Intake / `OTA-AUTOMATION-V0.1` / CONTROLLED EXTERNAL INTAKE OPEN / FIRST ADAPTER SELECTION PENDING / REAL CONNECTORS BLOCKED / PRODUCTION NO-GO。
- 授权与范围：产品负责人要求“进入下一个步骤”；本轮只打开Sprint 2真实连接器的受控外部接入前置，不新设Sprint编号，不授权真实登录、联网、Secret解析、候选登记、抓取、企微发送、UAT或生产发布。
- 工作包：新增首个“门店＋来源＋厂商产品版本＋接入方式”选择门禁，以及I0范围、I1合法接入、I2字段能力、I3业务金标准、I4离线适配器、I5受信任制品、I6隔离运行和I7影子联调的分阶段证据与放行条件。
- 模板：新增单一来源资料、字段能力矩阵、OTA产品到PMS实体房型映射、脱敏金标准样例和制品准入检查表；明确多个套餐、含早/无早产品共享实体库存且不得相加，营业日切换不得写死为固定凌晨时间。
- 安全边界：模板禁止记录密码、Cookie、Token、Webhook、验证码、数据库密码和住客个人信息；真实秘密仍须由授权人员直接录入外部SecretStore。Sprint 2C登记的加载前制品准入、运行时摘要证明、命令回执/服务端请求哈希、默认拒绝egress、真实蓝绿切换和15分钟墙钟长测已纳入后续准入表。
- 代码与运行影响：无业务代码、数据库迁移、API或页面变更；未运行真实profile，可信候选清单仍为空，`test/activate/run`、外部网络和`message_enabled`继续硬阻断。
- 文档：新增`docs/tasks/OTA-AUTOMATION-V0.1-CONTROLLED-EXTERNAL-INTAKE-WORK-PACKAGE.md`及`docs/tasks/ota-controlled-external-intake/`模板目录，并同步根索引、任务台账、业务/技术状态和编码门禁。
- 版本影响：不改变当前TECH-V0.1正式发布基线，不改变TECH-V0.2及AI中台Sprint 3现有判断；OTA独立后台仍为`0.1.0-SNAPSHOT`。

#### CHG-20260723-033：完成独立OTA自动化Sprint 2C离线准入治理

- 日期：2026-07-23。
- 状态：Unreleased / `OTA-AUTOMATION-V0.1` / `0.1.0-SNAPSHOT` / SPRINT 2C OFFLINE ADMISSION GOVERNANCE COMPLETE / REAL CONNECTORS BLOCKED / PRODUCTION NO-GO。
- 授权与范围：产品负责人要求继续实施下一步；本次只建设离线合同准入治理、Worker执行前持久化基线消费和数据库服务身份轮换门禁，不使用真实账号、不访问PMS/携程/美团、不解析外部Secret、不抓取真实数据、不向企业微信群发送消息。
- 数据库V5：新增migration/deployment owner发布的不可变可信候选清单、候选绑定批准、追加式吊销、幂等命令回执和有效基线窄读函数；配置哈希变化、明确吊销或版本未激活均使批准失效。可信候选清单默认且当前保持为空，Intake占位模板不得登记为候选。
- 准入控制面：新增只读`GET /api/v1/ota/tenants/{tenantId}/hotels/{hotelId}/connector-contract-admissions`及后台就绪度面板；响应固定`CANDIDATE_UNAVAILABLE`、候选/批准/吊销不可用和`runtimeBlocked=true`，不包含批准、吊销、测试、激活或运行写入口。
- Worker：未来非本地连接器必须在`collect`前经数据库窄函数读取精确版本/stream批准基线并重新校验capability/schema指纹；缺少Reader、读取失败、无基线、已吊销、版本未激活或漂移均以固定原因码Fail Closed。仅三个内置simulation实现类和内置只读`FILE_FIXTURE`明确豁免。
- 身份轮换：为`CONNECTOR_WORKER`建立`STAGED → ACTIVE → DRAINING → RETIRED`蓝绿绑定和追加事件；15分钟有界tenant SELECT可供DRAINING完成切换前已取得且未过期的lease，但dispatch、claim、renew和直接事实/Outbox DML均仅允许ACTIVE，DRAINING不能续租。此轮没有执行真实凭据或连接池轮换。
- 验证：主代理最终复跑确认Maven聚合209项，失败0、错误0、条件式PostgreSQL跳过2；PostgreSQL 14.22 API 1/1、Worker 1/1专项补证通过。Web 11/11、TypeScript `tsc -b`和Vite 40 modules生产构建通过；数据库静态门禁确认17张control表、55张FORCE RLS租户表和27个append-only保护对象，部署one-shot V1→V5通过；迁移在`LOGIN/NOSUPERUSER/NOINHERIT/NOBYPASSRLS` owner下完成，post-grants、runtime grants、catalog和负向控制全部PASS。
- 安全复核：文档收口后P0=0、P1=0。已登记P2为：`artifact_digest`仅作非运行时制品/签名证明存档；轮换函数没有完整command receipt/idempotency；批准`request_hash`由caller提供且receipt未覆盖全部规范字段；未做真实并发写事务切换演练和真实15分钟墙钟长测。
- 已知边界：数据库轮换协议不替代撤销旧凭据、关闭旧连接池、以`pg_signal_backend`终止旧backend并确认`pg_stat_activity=0`的UAT编排；无真实适配器候选，无真实PMS/携程/美团连接器，无外部SecretStore/隔离网络，无真实企业微信投递；双店连续3个PMS营业日UAT及生产发布仍未开始。
- 文档：新增`docs/tasks/OTA-AUTOMATION-V0.1-SPRINT-2C-IMPLEMENTATION-REPORT.md`并同步根索引、任务台账、业务/技术设计状态和数据库/Worker/部署说明。
- 版本影响：不改变当前TECH-V0.1正式发布基线，不改变TECH-V0.2及AI中台Sprint 3现有判断；OTA独立后台尚未形成生产发行版本。

#### CHG-20260723-032：完成独立OTA自动化Sprint 2B真实接入准备层

- 日期：2026-07-23。
- 状态：Unreleased / `OTA-AUTOMATION-V0.1` / `0.1.0-SNAPSHOT` / Sprint 2 AUTHORIZED-IN PROGRESS / Sprint 2B OFFLINE PREPARATION COMPLETE / Real Connectors BLOCKED / Production NO-GO。
- 授权与范围：产品负责人要求继续实施下一步；本次只建设真实接入资料配置控制面和Worker安全收口，不使用真实账号、不访问PMS/携程/美团、不解析外部Secret、不抓取真实数据、不向企业微信群发送消息。
- 后台与API：新增按租户/门店的PMS、携程、美团接入准备页面和JDBC API，可登记厂商、产品版本、接入方式、外部酒店编码、账号别名、网络路由、轮询间隔及受控SecretStore引用；写入限`PLATFORM_ADMIN + CONNECTOR_CONFIG_MANAGE`，五类集团岗位保持跨租户只读；`test/activate/run`固定409阻断。
- Secret安全：请求只接受受控协议且不含URI user-info或凭据分隔符的不透明引用；响应不返回引用、版本或确定性指纹。非秘密配置修改在服务端沿用新接入方式仍需要的既有绑定，浏览器无需回显或重传Secret。
- 数据库V4：新增禁用、零能力、零允许主机的三个Intake模板与`CONFIGURATION_ONLY`模式；数据库硬拒绝其进入计划、作业、采集运行或checkpoint。合同批准基线强制RLS、追加写、审批账号与数据库会话账号一致，并撤销共享API角色直接INSERT权限。
- Worker：增加执行与落库共用结果安全门、Envelope/证据/水位时间关系校验、虚拟线程硬超时、周期租约续期和最终租约栅栏；超时、取消或失去租约后的迟到结果不能落库。
- 验证：Maven聚合193项，失败0、错误0、条件跳过2；两项跳过均在PostgreSQL 14.22专项执行通过。Web 10/10、TypeScript和Vite生产构建通过；数据库与部署静态门禁2/2 PASS；PostgreSQL完成Flyway V1→V4、API 1/1、Worker 1/1、RLS/ACL/catalog和配置态负向控制，审查发现的2项P1与3项P2均已修正。
- 已知边界：真实厂商文档、账号与酒店ID、外部SecretStore、隔离网络/浏览器、首次人工认证、Worker持久化批准基线消费、service principal轮换、真实企微投递、双店连续3个PMS营业日UAT及生产发布仍未完成。
- 文档：新增`docs/tasks/OTA-AUTOMATION-V0.1-SPRINT-2B-IMPLEMENTATION-REPORT.md`并同步根索引、任务台账、业务/技术设计状态和编码门禁。
- 版本影响：不改变当前TECH-V0.1正式发布基线，不改变TECH-V0.2及AI中台Sprint 3现有判断；OTA独立后台尚未形成生产发行版本。

#### CHG-20260723-031：完成独立OTA自动化Sprint 2A离线安全底座

- 日期：2026-07-23。
- 状态：Unreleased / `OTA-AUTOMATION-V0.1` / `0.1.0-SNAPSHOT` / Sprint 2 AUTHORIZED-IN PROGRESS / Sprint 2A COMPLETE / Real Connectors BLOCKED / Production NO-GO。
- 授权与范围：产品负责人授权实施Sprint 2下一步骤；本次仅建设真实连接器启用前的离线事实、安全和运行底座，不使用真实账号，不抓取真实门店数据，不连接携程/美团生产页面，不向企业微信群发送消息。
- 事实与水位：普通采集运行、Raw证据、Standard记录、尝试和checkpoint纳入同一事务；仅`SUCCESS + COMPLETE + FRESH`、完整验证通过且候选水位不缺失/不倒退时推进checkpoint，失败、部分或不可用结果不伪造为0。
- 连接器门禁：新增采集结果scope/source/evidence/idempotency/schema/可信时间校验、标准记录schema白名单、capability/schema指纹和执行链漂移检查；`sprint2-real`在SecretStore与隔离egress未实现时保持Fail Closed。
- 数据库V3：新增数据库会话角色与ACTIVE service principal一对一绑定，收紧dispatch/claim/renew/complete权限；普通采集支持精确5/15/30分钟槽，小时简报和模拟小时任务继续保持整点；Compose迁移链已纳入V3。
- 配置安全：后台只查询当前ACTIVE连接器版本的非撤销Secret状态，`FILE_FIXTURE`不展示历史Secret binding；不回显Secret值。
- 验证：修复后Maven聚合172项，失败0、错误0、条件跳过2；两项跳过均在PostgreSQL 14.22专项执行通过。Web 9/9、TypeScript与Vite生产构建通过；数据库/部署静态门禁2/2 PASS；PostgreSQL完成Flyway V1→V3、API 1/1、Worker 1/1、catalog gate及`NORMAL 10:05`全链路验证；最终复核无未关闭P0/P1。
- 已知边界：真实连接器仍需厂商资料、持久化批准基线、SecretStore/KMS、隔离网络、硬超时/心跳、身份轮换和受控联调；真实企微投递、两店连续3个PMS营业日UAT与生产发布均未实施。
- 文档：新增`docs/tasks/OTA-AUTOMATION-V0.1-SPRINT-2A-IMPLEMENTATION-REPORT.md`并同步根索引、任务台账、业务/技术设计状态和编码门禁。
- 版本影响：不改变当前TECH-V0.1正式发布基线，不改变TECH-V0.2及AI中台Sprint 3现有判断；OTA独立后台尚未形成生产发行版本。

#### CHG-20260723-030：完成独立OTA自动化Sprint 1模拟闭环实现

- 日期：2026-07-23。
- 状态：Unreleased / `OTA-AUTOMATION-V0.1` / `0.1.0-SNAPSHOT` / Sprint 0 COMPLETE / Sprint 1 simulation-only COMPLETE / Sprint 2 HOLD / Production NO-GO。
- 授权与范围：产品负责人明确确认“进入 Sprint 1”；本次只实现TECH-DESIGN-1.0中的模拟闭环，不接入真实PMS、携程、美团或企业微信，不向运营群发送消息，不提前启动Sprint 2。
- 控制面与页面：新增模拟租户/门店、来源连接器、实体库存池、OTA售卖产品、产品映射、目标和节奏配置；提供接入配置、实时经营监控、房型/目标/节奏、简报与告警历史四个独立页面，并保持跨租户只读及门店级配置权限边界。
- 数据库与调度：新增Flyway V2模拟闭环结构、动态数据库作业目录、类型化领取/续租/完成、Worker workload principal种子和最小权限；当前静态基线为14张control表、52张FORCE RLS租户表和22个追加事实保护对象。
- 模拟流水线使用`MOCK_PMS`、`MOCK_CTRIP`、`MOCK_MEITUAN`覆盖`BASELINE`、`INVENTORY_MISMATCH`、`SOURCE_UNAVAILABLE`和`LATE_BRIEF_REPLAY`；另实现`FILE_FIXTURE`普通采集边界。确定性模拟按PMS营业日、整点窗口、房费/钟点房及间夜口径生成指标、逐产品库存对账、P1、原始/调整后简报和禁发Outbox预览。
- 房态规则：多个套餐、含早/无早产品映射到同一实体库存池，但逐个与PMS可售量比较且永不相加；任一产品高于或低于PMS均生成P1，来源失败或过期只显示“无法判断”，不以0或旧值代替。
- 投递安全：所有模拟运行固定`simulation-only`和`deliveryMode=BLOCKED`；没有Webhook配置、网络调用、真实`@所有人`或企微重试/补发实投。
- 专项验证：普通Maven聚合138项0失败/错误，Web 9/9、TypeScript和Vite生产构建通过；真实PostgreSQL 14.22执行12个普通采集作业，并通过API→Worker→API五次运行闭环、V1+V2、RLS、追加事实和最小权限专项。最终安全复核发现的2个P1已修复并补负向测试，当前无未关闭P0/P1。
- 环境限制：当前环境无Docker CLI/psql客户端，完整Compose整栈未启动，不能记为容器级PASS。
- 文档：新增`docs/tasks/OTA-AUTOMATION-V0.1-SPRINT-1-IMPLEMENTATION-REPORT.md`并同步根索引、任务台账、业务/技术设计状态和编码门禁。
- 版本影响：不改变当前TECH-V0.1正式发布基线，不改变TECH-V0.2及AI中台Sprint 3现有判断；OTA独立后台尚未形成生产发行版本。

#### CHG-20260723-029：完成独立OTA自动化Sprint 0安全底座

- 日期：2026-07-23。
- 状态：Unreleased / `OTA-AUTOMATION-V0.1` / `0.1.0-SNAPSHOT` / Sprint 0 GO / Sprint 1 HOLD / Production NO-GO。
- 授权与范围：产品负责人明确下达“开始编码”；本次只实施TECH-DESIGN-1.0的Sprint 0，不提前实现Sprint 1至Sprint 4，不接入真实账号，不向正式运营群投递。
- 独立工程：新增`packages/ota-contracts`、`apps/ota-standalone-api`、`apps/ota-connector-worker`、`apps/ota-standalone-web`、`database/ota-migrations`、`infra/ota`和根聚合构建`ota-platform-pom.xml`；运行时不依赖现有AI中台API、Web或数据库迁移。
- 安全与认证：实现Argon2id本地账号、短期Access JWT、Refresh Token轮换与复用检测、CSRF/CORS/Cookie、安全响应头、追加审计、首位管理员受控引导、生产启动安全门禁和前端内存Token登录壳。
- 数据库：Flyway V1建立11张control表、12张FORCE RLS租户表、4类追加事实保护和7个固定角色；分离bootstrap/migration/API/Worker/Audit身份，采用逐对象GRANT，并撤销`public` schema的公共及运行角色CREATE权限。
- 部署：冻结PostgreSQL healthy → role bootstrap → Flyway → grants → verifier的一次性严格链路；API运行期默认关闭Flyway，镜像版本固定，不使用`latest`。
- 验证：Maven聚合58项测试0失败、0错误；普通构建中的1条条件式数据库测试由真实PostgreSQL 14.22专项1/1 PASS补证；Web 6项测试和生产构建PASS；数据库结构、部署结构和敏感值扫描PASS；最终安全复核无未关闭P0/P1。
- 保留限制：本机无Docker CLI，完整Compose链尚未容器级实跑；真实PMS/携程/美团连接器、小时计算、P1运行链路、企业微信投递、配置页面和双店UAT均未实现，不能宣称完整闭环可用。
- 文档：新增`docs/tasks/OTA-AUTOMATION-V0.1-ADR-001-LOCAL-AUTH.md`和`docs/tasks/OTA-AUTOMATION-V0.1-SPRINT-0-IMPLEMENTATION-REPORT.md`，同步任务索引、设计状态与编码门禁。
- 版本影响：不改变当前TECH-V0.1正式发布基线，不改变TECH-V0.2及AI中台Sprint 3现有判断；OTA独立后台尚未形成正式技术发行版本。

#### CHG-20260723-028：冻结独立OTA自动化业务与技术设计

- 日期：2026-07-23。
- 状态：Unreleased / Planning / `DESIGN-1.5` / `TECH-DESIGN-1.0` / Not Coded / Awaiting Explicit Authorization。
- 变更内容：完成`OTA-AUTOMATION-V0.1`业务设计及技术板块T0至T5确认，冻结独立Web、API、连接器Worker、独立PostgreSQL、统一实体房型库存、PMS营业日、房费收入与间夜口径、整点简报、即时P1、企微补发、本地账号/RLS及双店UAT边界。
- 编码就绪：形成`docs/tasks/OTA-AUTOMATION-V0.1-CODING-READINESS.md`；获得产品负责人明确“开始编码”授权后，只进入Sprint 0安全底座与工程骨架，后续严格按Sprint 1模拟闭环、Sprint 2真实连接器、Sprint 3分析/P1/企微、Sprint 4双店UAT与发布推进。
- 影响范围：仅文档、架构决策和实施门禁；无业务代码、无数据库迁移、无API或页面实现、无真实凭据、无正式群消息。
- 版本影响：不改变当前TECH-V0.1正式发布基线，不改变TECH-V0.2与Sprint 3现有判断；OTA独立后台尚未形成技术发行版本。

#### CHG-20260722-027：修复PILOT.7任务投递、驾驶舱深链与角色隔离

- 日期：2026-07-22。
- 状态：Unreleased / `TECH-V0.2-PILOT.7` / Internal Pilot Repair；TECH-V0.2尚未正式Released，Sprint 3未启动。
- 变更原因：PILOT.6工作台与门店驾驶舱摘要不可直接进入明细；CEO下发任务存在创建与派发分步失败、无任职时验收人解析失败；任务、评价和通知被前端当前岗位重复过滤；OTA与门店管理岗任务目标范围不满足实际管理要求。
- 后端：任务创建支持同一事务内立即派发；新增任务目标候选接口；`mine/team/review`视图正式区分；任务读取范围与下发目标范围分离；无标准临时任务支持受审计人工验收，有标准任务仍必须先完成标准评价；CEO可读取租户内授权门店驾驶舱。
- 权限：CEO可向租户内所有有效任职下达；店内主管、店助和店总可向本门店员工下达；OTA运营助理和经理可向全部门店管理岗位下达；基础员工保持只执行、不能下发。读取权限仍由角色授权范围决定，不因跨店下达能力扩大。
- 前端：工作台摘要卡、门店驾驶舱风险和未完成项增加真实深链；任务详情支持按ID加载；门店驾驶舱跳转后的团队工作和标准评价按`hotelId`过滤；任务创建改为一次性创建并下达，接收人从服务端授权候选选择；任务与通知页面15秒静默刷新并提供空页立即刷新；无标准任务增加验收通过/退回整改；任务动作只对真实执行人或验收人显示；修复详情刷新后参与人姓名快照解析。
- 数据库/API/制品：新增Flyway V17权限迁移；API主版本仍为API-V1，OpenAPI候选为`0.2.4-pilot.7`；后端和前端制品版本为`0.2.0-pilot.7`。
- 数据治理：清理前生成54表、2043行的一致性逻辑备份；清理历史UAT/UI组织、岗位、账号及其关联业务数据；保护正式角色、岗位、账号及全部非候选工作包依赖；清理脚本新增事务回滚DryRun、依赖断言与候选明细。
- 验证：后端65项全量测试（0失败、0错误、2跳过）、前端Pilot生产构建、严格OpenAPI解析、真实PostgreSQL迁移、真实账号API 15/15、公网只读功能8/8、公网8角色登录与下达入口8/8、公网真实UI任务闭环7/7皆PASS，记录在`docs/TECH-V0.2-PILOT.7-REPAIR-REPORT.md`。正式发布状态不因内部Pilot验证自动改变。

#### CHG-20260722-026：修复公网登录502并简化Pilot登录入口

- 日期：2026-07-22。
- 状态：Unreleased / `TECH-V0.2-PILOT.6` / Login Incident RESOLVED / Public Login PASS。
- 故障表现：登录页能够打开，但提交应用账号后Cloudflare显示源站响应无效或不完整；Caddy本机`/api/*`返回502。
- 根因：Caddy、Cloudflare Tunnel和PostgreSQL均正常，但Core API Java进程已经停止；当前用户登录恢复任务未处于可用注册状态，主机事件后没有自动拉起API。
- 恢复：重新启动真实Core API，重新注册并实测`SifangguanPilotCoreApiUser`计划任务；任务在用户登录时触发，并每5分钟执行健康检查和自动恢复，最近实测退出码为0，API健康状态为`UP`。
- 登录入口：移除不兼容部分移动端和内置浏览器的Caddy外层Basic Auth，访问网址后直接显示中台应用登录页。
- 安全边界：只有`POST /api/v1/auth/login`允许匿名调用；其他业务API未登录仍返回401。应用账号继续使用密码哈希、连续失败5次临时锁定、短期JWT、服务端RBAC/组织范围和PostgreSQL RLS。
- 验证：本机Caddy首页200、未登录业务API 401；公网首页200；公网真实CEO登录200并成功签发JWT；凭据和Token均未写入证据。
- 数据与架构：无数据库迁移、无业务数据修改、无组织/一人多岗/标准/规则/任务模型变化，TECH-V0.2仍为Unreleased。
- 文档与测试：更新PILOT.6公网页面验收脚本、内部测试操作说明、Pilot发布记录、Windows运行手册和部署UAT报告。

#### CHG-20260720-025：实施TECH-V0.2-PILOT.6真实操作闭环及CEO集团模板治理

- 日期：2026-07-20—2026-07-21。
- 状态：Unreleased / `TECH-V0.2-PILOT.6` / Internal Pilot ACTIVE / Real PostgreSQL, API and Public Browser UAT PASS。
- 产品蓝图版本：PRODUCT-V1.2；冻结组织树、一人多岗、组织数据范围、标准中心版本模型和任务状态机均未改变。
- 变更原因：PILOT.5虽能读取真实PostgreSQL，但“我的工作”、团队复核、任务下达和配置中心仍缺少面向真实门店测试所需的统一陈述、多附件、任务证据及模板编辑入口；集团还需要由CEO统一配置所有岗位标准工作、任务和门店驾驶舱模板。
- 数据库：新增Flyway V16；为岗位工作条目增加结构化提交策略，为工作记录增加完成情况、异常协同和下一步行动；新增不可变补充说明；新增版本化任务/门店驾驶舱企业模板；任务证据增加扫描状态；三张新增表启用并强制RLS。
- 权限：新增`template.read/manage/publish`；主管及以上只读取已发布模板，只有CEO拥有模板草稿管理和发布权限；模板、任务、记录和附件继续受租户、组织范围及精确任职约束。
- 后端：新增企业模板API；工作记录支持宽松草稿校验、完整提交校验、提交策略和提交后补充；工作/任务附件支持JPEG、PNG、PDF、DOCX、XLSX及20 MiB上限；任务证据实现上传、下载和提交前删除。
- 前端：新增CEO“集团模板配置”；岗位标准工作复用工作包版本/发布模型，任务模板和门店驾驶舱模板采用企业模板版本模型；任务中心支持新建并下达；我的工作支持草稿、多附件、文字陈述、提交和补充；团队工作只复核员工证据，不能代替员工修改附件。
- API与制品：API主版本继续为API-V1；OpenAPI候选升级为`0.2.3-pilot.6`；后端制品升级为`0.2.0-pilot.6`，前端版本升级为`0.2.0-pilot.6`。
- 部署与验证：后端完整回归53项、失败0、错误0、跳过2；前端Vite Pilot生产构建PASS；真实PostgreSQL升级至Flyway V16，52张表启用并强制RLS；部署前完成51张表的一致性逻辑备份和制品备份；八角色API UAT 8/8 PASS，真实组织→岗位→员工→任职→工作包→草稿→图片→提交闭环PASS；CEO模板写入、店总只读和403越权拒绝PASS；公网页面八角色严格验收8/8 PASS且业务请求无非预期4xx/5xx。
- 修复：关闭登录后静态回退身份、真实身份和主岗选择之间的竞态，避免店助驾驶舱短暂以部门ID请求酒店资源；两条历史管理事件死信通过受控汇报链修复、审计和重放关闭，修复后Outbox、管理事件和规则动作失败/死信均为0。
- 发布边界：`https://www.sfgzt.cn`当前运行PILOT.6并允许受控门店测试；TECH-V0.2正式版本仍为Unreleased，Sprint 3未因本次部署自动启动。

#### CHG-20260719-024：完成TECH-V0.2-PILOT.5全岗位真实工作与权限可用性修复

- 日期：2026-07-19。
- 状态：Unreleased / `TECH-V0.2-PILOT.5` / Real PostgreSQL ACTIVE / 8-Role API and Public Browser UAT PASS。
- 产品蓝图版本：PRODUCT-V1.2；未改变集团→区域→门店→部门组织模型、一人多岗、组织权限隔离或标准中心模型。
- 根因：此前只有早期固定UAT任职获得少量工作包；店总、店助、前厅主管及OTA岗位缺少角色专属已发布工作包，新建任职也不会自动获得工作，导致页面虽存在但显示空结果；部分Pilot保护账号曾在主数据验收中被停用。
- 数据库：新增Flyway V15，为前台员工、前厅主管、客房主管、店助、店总、OTA运营助理、OTA运营经理建立7套结构化岗位表单及已发布日清工作包；按当前有效任职动态下发并生成当日真实工作；恢复保护账号并补齐固定汇报链。
- 前端：工作包详情显示当前有效下发并按权限区分查看、发布和下发；员工选择后自动带出任职组织；“我的工作”增加可填报和已漏交筛选；无岗位任职的CEO不再生成或展示虚假的“我的工作”；门店驾驶舱完全按`dashboard.hotel`权限控制。
- 权限边界：执行岗位只读本人任职工作且团队接口403；主管及以上按组织范围查看团队；店助和店总可访问授权门店驾驶舱；OTA经理可访问授权区域多门店；CEO拥有租户配置能力但没有虚假个人工作。
- 验收：8个保护账号登录、组织、标准、工作包、我的工作、团队工作、任务、评价、通知和对应驾驶舱全部按预期；7个岗位均完成真实工作记录写入，客房主管图片附件验证PASS；新建店总任职自动获得店总工作包；公网Playwright 8角色全部PASS，浏览器控制台、页面错误、请求失败和5xx均为0。
- 运维：因当前会话无法创建SYSTEM启动任务，新增并安装当前Windows用户登录触发的`SifangguanPilotCoreApiUser`计划任务；实测退出码0且Core API健康为UP。正式生产仍建议改为SYSTEM服务或云端托管。
- 关联证据：`docs/uat/TECH-V0.2-PILOT.5-ROLE-CAPABILITY-UAT.md`、`docs/uat/evidence/pilot5-role-capability/pilot5-role-capability-audit.md`、`docs/uat/evidence/pilot5-role-capability/pilot5-role-browser-uat.md`。

#### CHG-20260719-023：修复Pilot登录中断并将PostgreSQL纳入Windows自动服务

- 日期：2026-07-19。
- 状态：Unreleased / `TECH-V0.2-PILOT.4` / Login Incident RESOLVED / Public UAT PASS。
- 产品蓝图版本：PRODUCT-V1.2；未改变组织模型、一人多岗、权限隔离、标准中心或业务数据结构。
- 故障表现：公网登录页提示需要有效Bearer JWT或长期停留在“正在验证”，组织、岗位、人员等真实业务功能无法进入。
- 根因：Core API与公网入口仍在运行，但本机PostgreSQL进程已经停止；原运行方式为当前用户临时进程，未注册Windows服务。只检查HTTP进程存活无法代表数据库可用。
- 处理内容：恢复`127.0.0.1:55432`数据库；新增`SifangguanPostgreSQL`延迟自动启动服务和三级失败重启策略；把手动启动脚本改为优先管理数据库服务；服务器状态检查增加数据库服务状态；新增安全安装脚本`tools/pilot/Install-PilotPostgresService.ps1`。
- 数据影响：没有重建数据库、没有修改Flyway版本、没有丢失业务数据；公网UAT产生的临时数据已清理。
- API与权限影响：API-V1、JWT格式、组织权限和数据范围均未改变。
- 验证结果：服务状态`Running/Automatic`；本机真实CEO登录返回HTTP 200且成功签发JWT；公网真实账号组织、岗位、人员8步闭环全部PASS；浏览器控制台、页面错误、非预期失败请求和服务端5xx均为0。
- 已知边界：Core API当前仍依赖部署用户登录启动项，正式生产前需要进一步改为受管Windows服务或迁移至云端运行环境。
- 关联证据：`docs/uat/evidence/pilot4-master-data/pilot4-master-data-uat.json`、`docs/PILOT-WINDOWS-SERVER-RUNBOOK.md`。

#### CHG-20260719-022：完成TECH-V0.2-PILOT.4组织、岗位与人员全生命周期维护

- 日期：2026-07-19。
- 状态：Unreleased / `TECH-V0.2-PILOT.4` / Real PostgreSQL ACTIVE / Public Master-Data UAT PASS。
- 产品蓝图版本：PRODUCT-V1.2；未改变集团→区域→门店→部门组织模型、一人多岗、组织数据范围或历史审计边界。
- 变更类型：Organization Configuration / RBAC / API / Frontend / Audit / Test / Documentation。
- 修改内容：为组织、岗位和人员增加编辑、启用/停用及受控删除API与页面操作；组织编辑保留固定类型和上级关系；员工可修改资料、登录名并按需重置密码；任职分配仅允许选择启用的组织、岗位和人员。
- 生命周期规则：组织停用同步停用下级组织、当前任职和直接组织范围授权；岗位停用结束当前任职；人员停用同步停用登录账号、结束当前任职和角色授权；重新启用不自动恢复旧任职或授权。
- 删除边界：只有已经停用、没有下级且从未被任职、权限、工作、任务或审计业务引用的主数据允许硬删除；存在历史引用时API明确拒绝并要求保留为停用状态，不级联删除业务历史。
- 权限与审计：所有写操作继续要求`org.manage`及现有租户/组织范围；岗位、人员和硬删除限租户级管理员；更新和删除写入审计日志；普通前台账号修改组织返回403。
- 数据库影响：无新增迁移，沿用Flyway V14现有状态字段、外键和FORCE RLS；无历史数据重写。
- API影响：API-V1向后兼容增加`PUT/DELETE /org/units/{id}`、`PUT/DELETE /org/positions/{id}`、`PUT/DELETE /org/employees/{id}`；组织、岗位和人员列表补齐生命周期字段。
- 验证结果：新增主数据生命周期集成测试3/3 PASS；后端完整回归51项、失败0、错误0、跳过2；Pilot前端生产构建PASS；公网真实CEO账号完成组织、岗位、人员的8个新建/编辑/停用/删除步骤，控制台0错误、页面0错误、非预期请求失败0、服务端5xx为0；临时数据已清理。
- 关联证据：`docs/uat/TECH-V0.2-PILOT.4-MASTER-DATA-UAT.md`、`docs/uat/evidence/pilot4-master-data/pilot4-master-data-uat.md`、`docs/PILOT-TEST-USER-GUIDE.md`。

#### CHG-20260719-021：完成TECH-V0.2-PILOT.3真实账号与公网页面全流程可用性收口

- 日期：2026-07-19。
- 状态：Unreleased / `TECH-V0.2-PILOT.3` / Real PostgreSQL Pilot ACTIVE / Public UI Full-Flow PASS。
- 产品蓝图版本：PRODUCT-V1.2；未改变组织模型、一人多岗、组织数据范围、标准中心、规则中心或管理闭环。
- 变更类型：Pilot Runtime / Authentication / Organization Configuration / Work Package / Attachment Security / Frontend / API / UAT / Documentation。
- 数据库与认证：新增Flyway V14本地Pilot账号认证迁移；公网应用改为真实账号密码登录并签发短期JWT，账号的组织范围、任职和业务角色均从PostgreSQL解析；外层HTTP Basic Auth继续保护整个站点，应用JWT通过独立请求头传入Caddy后再安全转换为上游Bearer认证，避免双层认证争用同一个`Authorization`头。
- 可操作页面：CEO可通过网页新增区域/门店/部门、岗位、员工登录账号、一人多岗任职及组织范围角色；可创建工作包草稿、绑定已发布表单和标准、发布、下发并生成当日工作；岗位员工可登录查看本人工作、提交结构化工作记录并上传图片附件；低权限账号不显示越权修改入口。
- 权限隔离：真实公网页面验收中，CEO读取租户全量组织；新建前台员工同时具有自定义兼岗和“前台员工”主岗，登录后仅能读取其被授权的1个门店，不能切换到模拟角色或创建组织/工作包。
- 附件安全：Pilot本机在AMSI和Defender扫描提供方均不可用时，只允许已成功解码、像素受限且由服务端重新编码的PNG/JPEG进入`SANITIZED_IMAGE`状态；生产默认仍失败关闭，真实扫描拒绝仍不可绕过。下载内容为去除元数据和附加负载后的规范化图片，不保证与原始字节逐字相同。
- 缺陷修复：修复工作包详情把PostgreSQL `smallint[]`驱动对象直接暴露给Jackson造成HTTP 500的问题；服务返回前把`weekdays`转换为脱离数据库连接的Java列表，并增加HTTP序列化回归断言。
- 验证结果：工作包定向集成测试1/1 PASS；完整后端回归48项、失败0、错误0、跳过2；真实PostgreSQL API闭环完成门店、岗位、员工账号、任职、范围角色、工作包发布/下发、工作生成、记录提交和附件上传；公网Playwright页面闭环PASS，浏览器控制台0错误、页面0错误、请求失败0、服务端5xx为0；生成员工可见组织数为1，工作记录最终状态`SUBMITTED`。
- 凭据治理：外层访问凭据与应用测试账号继续仅保存在本机受限ACL文件，不写入仓库、截图、验收JSON或聊天。
- 发布边界：这是可下发门店开展内部测试的Pilot版本，不把TECH-V0.2自动改为正式Released；组织/岗位/员工现阶段以新增和分配为主，密码自助重置、历史主数据编辑/停用和正式SSO仍属于后续完善项。
- 关联证据：`docs/uat/evidence/pilot3/pilot3-browser-uat.md`、`docs/uat/evidence/pilot3/pilot3-ui-full-flow.md`、`docs/uat/TECH-V0.2-PILOT.3-UAT-REPORT.md`、`docs/PILOT-TEST-USER-GUIDE.md`。

#### CHG-20260718-020：Pilot切换为真实PostgreSQL业务UAT

- 日期：2026-07-18。
- 状态：Unreleased / `TECH-V0.2-PILOT.2` / Real PostgreSQL UAT ACTIVE / Public Domain E2E PASS。
- 产品蓝图版本：PRODUCT-V1.2；未改变组织模型、一人多岗、权限隔离、标准中心模型、规则中心职责边界或任务状态机。
- 变更类型：Pilot Runtime / PostgreSQL / API / RBAC / Frontend / Security / Operations / Documentation。
- 数据库：在`D:\SifangguanHotelAIOS`部署PostgreSQL 14.22持久化实例，数据库`hotel_ai_os_uat`完成Flyway V1—V13和Sprint 2.1 UAT种子导入；运行账户为非超级用户且不具有`BYPASSRLS`，49张租户表启用并强制RLS；数据库仅监听`127.0.0.1:55432`，不对公网开放。
- 后端：Core API仅监听`127.0.0.1:18080`，启用数据库RBAC/RLS、自动化Worker、工作期望SLA调度、附件本地持久化及AMSI扫描；Caddy仅把`/api/*`反向代理至回环API。
- 前端：Pilot关闭演示回退和`?demo=1`强制演示能力，接入实时API；验收账号选择器扩展为CEO、前台员工、前厅主管、客房主管、店助、店总、区域/运营7个角色；规则中心支持按权限创建、修改/新版本及发布真实规则。
- 安全边界：公网入口继续经Cloudflare Tunnel；站点与API统一受Caddy Basic Auth保护；数据库、API和附件目录均不直接暴露；测试凭据只保存在本机受限ACL文件，不写入仓库或聊天。Pilot仍使用受保护的开发请求头身份模拟，不等于目标企业SSO。
- 启动方式：Caddy和Tunnel继续作为Windows自动服务；因当前执行上下文无管理员令牌，PostgreSQL和Core API采用当前用户登录启动项恢复。电脑重启后必须完成该Windows用户登录，真实业务API才会自动恢复。
- 验证结果：PostgreSQL 14.22、Flyway 13、一人多岗、7角色真实权限均通过；规则创建→修改→发布写入链通过；本机及`https://www.sfgzt.cn`公网Playwright验证读取7个角色和7条数据库规则，真实API标识可见。Cloudflare注入的Browser Insights脚本被本站严格CSP拦截，会产生一条无业务影响的控制台提示。
- 发布边界：这是内部Pilot UAT通道升级，不把TECH-V0.2改为正式Released，不替代目标SSO、真实现场签署、受控制品、备份恢复与正式生产运维门禁。
- 关联文件：`apps/web/.env.pilot`、`apps/web/src/App.tsx`、`apps/web/src/api/`、`apps/web/src/data/roles.ts`、`infra/pilot/Caddyfile.windows-tunnel`、`tools/pilot/`、`docs/PILOT-TEST-VERSION-RELEASE.md`、`docs/PILOT-WINDOWS-SERVER-RUNBOOK.md`。

#### CHG-20260718-019：生成贵州四方馆中台Pilot内部测试版与域名部署包

- 日期：2026-07-18。
- 状态：Unreleased / `TECH-V0.2-PILOT.1` Built / Local UI QA PASS / Custom Domain External QA PASS / ICP Pending。
- 产品蓝图版本：PRODUCT-V1.2；未改变组织模型、一人多岗、权限隔离、标准中心、规则中心或任务状态机。
- 变更类型：Branding / Pilot Channel / Frontend / Deployment / Documentation。
- 修改内容：产品展示名称统一为“贵州四方馆酒店管理有限公司中台”；新增Pilot Test Version、内部测试版和版本标识；新增隔离的`pilot`构建模式、纯演示数据通道、Caddy HTTPS与安全响应头部署配置；目标域名固定为`www.sfgzt.cn`。
- 安全边界：Pilot静态演示制品不访问真实API，不携带本地JWT、测试令牌、数据库或附件；本地Mock OIDC和开发请求头认证禁止公网暴露。
- Windows服务器：确认WAN IPv4 `100.64.185.139`属于运营商CGNAT，光猫IPv4端口映射不可形成公网入口；普通账号没有安全的IPv6单端口放行能力，因此采用Cloudflare Tunnel出站架构。Caddy v2.11.4以自动服务运行且仅监听`127.0.0.1:4180`；Windows不开放Pilot入站端口；cloudflared 2026.7.2签名有效。
- 固定域名部署：`sfgzt.cn`权威Nameserver已切换到Cloudflare；创建Named Tunnel `sifangguan-pilot`，凭据存放于Git忽略运行目录并限制ACL；安装自动服务`SifangguanPilotTunnel`；`www.sfgzt.cn`路由到回环Caddy源站，根域名经独立回环入口308跳转到`https://www.sfgzt.cn`；历史Quick Tunnel已停止。
- 验证结果：TypeScript/Vite Pilot生产构建PASS；历史Quick Tunnel的1440×1000桌面与390×844移动端渲染、任务中心导航和控制台均PASS；正式域名两个Cloudflare边缘IP返回200，独立外部抓取节点读取到正确标题和完整页面；本机Meta代理仍有旧DNS缓存导致普通本地curl握手失败，登记为环境已知限制；制品SHA-256为`0929DBEFAEE323E20E6113274A7C10C324C61441F301CA28BC8451C0E00DD094`。
- 外部阻塞：ICP备案号尚未确认；Pilot可访问不等于TECH-V0.2正式Released，也不替代目标SSO、真实现场证据、签署、受控Git制品和目标运行保障。
- 并行治理：产品负责人允许Pilot测试与Sprint 3后续开发并行；Pilot稳定通道与Sprint 3开发通道必须隔离，V1—V13不可修改，TECH-V0.2正式Released状态不因Pilot可测试而自动改变。
- 关联文件：`apps/web/src/product.ts`、`apps/web/.env.pilot`、`infra/pilot/`、`tools/pilot/`、`docs/PILOT-TEST-VERSION-RELEASE.md`、`docs/PILOT-WINDOWS-SERVER-RUNBOOK.md`。

#### CHG-20260718-018：完成TECH-V0.2发布门禁稳定快照终审

- 日期：2026-07-18。
- 状态：Unreleased / Local Release Gate Hardening PASS / Release NO-GO。
- 产品蓝图版本：PRODUCT-V1.2；组织模型、一人多岗、权限隔离、标准中心和管理闭环均未改变。
- 目标技术版本：TECH-V0.2。
- 变更类型：Fixed / Security / Release Governance / Tests / Documentation。
- 修复内容：将签署保留意见、Worker/测试计数改为严格整数；强制附件存储类型精确为`OBJECT_STORAGE`并拒绝未知或额外角色；冻结五类正式制品各唯一一次；严格校验schema、版本、角色、RC标签与带时区ISO 8601时间；最终门禁从首次锁定的同一字节快照解析Worker JSON/XML、manifest与SHA256SUMS并在结论前末端重验，拒绝manifest瞬时A→B→A替换；严格UTF-8解析兼容至多一个开头BOM。
- 验证结果：外部证据主体绑定与负向测试`34/34`、最终门禁证据绑定`24/24`、收口契约`19/19`、总控负向`6/6`、本地证据一致性`15/15`全部PASS；PowerShell AST 0错误；独立复核未发现剩余可复现的P0/P1/P2门禁绕过或假PASS路径。
- 副作用：测试临时残留、签署、审批、Git提交、标签、发布状态修改和网络写入均为0；无数据库迁移、API、业务页面或权限模型变更。
- 发布判断：正式外部证据包、规范门禁输入、真实SSO/签署/现场附件/目标运维材料、受控Git HEAD/远程/标签及正式RC3制品仍不存在；默认门禁保持1 PASS / 5 BLOCKED，TECH-V0.2继续Unreleased，Sprint 3未启动。
- 文档版本：主报告`RC-FINAL-V1.6`，候选说明`RC-NOTE-V1.4`。
- 关联文件：`tools/release/Test-TechV02ExternalEvidenceBundle.ps1`、`tools/release/Test-TechV02ReleaseGate.ps1`、`tools/release/tests/Test-TechV02ExternalEvidenceBundleNegative.ps1`、`tools/release/tests/Test-TechV02ReleaseGateEvidenceBinding.ps1`、`docs/TECH-V0.2-RELEASE-CANDIDATE-FINAL-REPORT.md`。

#### CHG-20260718-017：加固外部证据主体绑定与最终门禁复核

- 日期：2026-07-18。
- 状态：Unreleased / Local Release Governance Hardening PASS / Release NO-GO。
- 产品蓝图版本：PRODUCT-V1.2；组织模型、一人多岗、权限隔离、标准中心和管理闭环均未改变。
- 目标技术版本：TECH-V0.2。
- 变更类型：Fixed / Security / Release Governance / Tests / Documentation。
- 受阻根因：目标SSO和目标运维验收文档分别要求10方专项签核，但结构化证据此前只校验单一责任人；现场照片只声明SHA-256，未强制读取原始照片文件；源码追溯只验证本地HEAD、标签和制品，未绑定受控远程、提交身份或仓库责任人审批；最终门禁未逐一重读全部派生证据文件，存在外部重算后证据被替换的时间窗口。
- 修复内容：目标SSO与目标运维分别冻结10个指定审批角色，强制唯一签名ID、唯一真人签署人、`SIGNED/APPROVED`、零保留意见和受控签署保证；现场照片新增`originalFilePath`，从同一锁定字节快照派生文件URI、SHA-256与大小，并与文件名、声明值、上传、下载和恢复哈希逐项一致；源码追溯强制唯一且一致的受控Git fetch/push URL、远程分支与跟踪提交、HEAD作者/提交者身份，并要求`REPOSITORY_OWNER`审批绑定精确远程、分支、HEAD、RC标签、manifest SHA-256及发布时间，时间顺序必须为“提交不晚于远程发布、不晚于仓库审批”；最终6项门禁对所有证据URI与SHA重新读取核验，制品和照片均使用单次锁定快照。
- 模板治理：SSO、目标运维、现场照片、外部证据包和派生门禁输入示例同步新结构；所有示例仍为`PENDING`和空身份/空证据，不能作为正式材料。
- 验证结果：外部证据针对性套件18/18 PASS，覆盖大小写严格状态、10方签核正反路径、受控远程策略、发布时间顺序、真实文件字节绑定、文件名/大小/SHA篡改和验收JSON自引用拒绝；PowerShell AST解析0错误。完整收口契约、总控负向、最终门禁及文档一致性将在同一变更收口时再次执行。
- 影响：无数据库迁移、无API、无业务页面、无组织或权限模型变更；仅收紧正式发布证据协议和只读校验器。
- 剩余边界：本地工具不能生成或替代目标SSO事实、真人签署、真实现场照片、受控Git远程/提交/标签或目标运维事实；TECH-V0.2仍为1 PASS / 5 BLOCKED，保持Unreleased，Sprint 3未启动。
- 关联文件：`tools/release/Test-TechV02ExternalEvidenceBundle.ps1`、`tools/release/Test-TechV02ReleaseGate.ps1`、`tools/release/tests/Test-TechV02ExternalEvidenceBundleNegative.ps1`、`docs/releases/TECH-V0.2-EXTERNAL-EVIDENCE-BUNDLE.md`。

#### CHG-20260718-016：关闭RC发布包默认密码与证据门禁缺口

- 日期：2026-07-18。
- 状态：Unreleased / Local Security Hardening PASS / Release NO-GO。
- 产品蓝图版本：PRODUCT-V1.2；未改变组织模型、一人多岗、权限隔离、标准中心或管理闭环。
- 目标技术版本：TECH-V0.2。
- 变更类型：Fixed / Security / Release Governance / Test Infrastructure。
- 问题事实：首次机器可读发布包扫描在两套RC2后端JAR中发现4次`PASSWORD_ASSIGNMENT`命中；源到使用分析确认主数据源与Flyway密码带有JAR内置回退值，缺少外部变量时可进入数据库认证路径。
- 修复内容：密码配置改为无回退值的必填外部变量；应用主入口在创建Spring上下文、迁移、数据库连接和监听端口前校验两项密钥；UAT Docker启动器在启动数据库前校验必需数据库变量非空；UAT示例不再携带预设密码；新增发布配置与密钥预检回归测试；可复现构建工具支持独立输出根目录，避免覆盖历史候选制品。
- 证据治理：新增发布包深度敏感信息扫描器；新增外部证据包生成器、校验器、受控签署凭据要求和负向测试；签署身份按Trim/小写归一并拒绝前后空白；工作区证据路径拒绝symlink/junction/reparse point；正式证据和门禁输入统一保存到Git忽略的`.uat-runtime/release/`；发布门禁默认RC标识及示例输入统一到`TECH-V0.2-rc.3`，并要求Git标签建立后从同一HEAD生成`reproducibility-rc3-formal`制品，禁止把`rc.3-local`或历史RC2制品误作正式候选；发布收口总控作为唯一正式入口，逐级核验退出码、大小写严格JSON状态、严格类型副作用字段及门禁输入SHA漂移；READY状态只由四阶段全PASS、最终6/6和消费SHA一致性推导，调用方不能指定；生成器只写同目录唯一暂存文件，独立复算通过后以兼容Windows PowerShell的备份式原子替换晋升规范输入并清理暂存/备份；若替换后的备份清理失败则立即用备份原子恢复原目标，恢复失败时保留备份用于人工恢复；最终门禁锁定输入并返回实际消费SHA，任何阶段失败即停止。
- 验证结果：发布配置与入口预检定向测试4/4 PASS；无密钥JAR启动0.2秒内失败，未渲染Spring Banner、未连接数据库、未监听端口且不输出密钥值；后端完整回归48项、失败0、错误0、跳过2；Flyway V1—V13成功；真实Live UAT宿主1/1、错误0且Hikari先于PostgreSQL关闭；RC3双构建5项制品完全一致，载荷指纹`546fc5175d97af2e0bbe3736468b1366d8890e89a6c6a6d761db4d40eba089ee`；深度扫描160个文件、120个归档、43,830个归档条目，0命中、0错误；外部证据负向测试4/4 PASS；收口总控负向测试6/6、契约测试19/19 PASS，覆盖大小写状态拒绝、`-AsLibrary`旁路拒绝、输入字节锁定、已有/新建目标原子晋升、复算拒绝、验证异常清理及替换后清理失败回滚；治理及Git引用状态前后指纹一致，残留门禁输入文件0。
- 安全边界：若旧回退值曾在任何环境实际使用，环境责任人必须轮换并提交受控记录；本地工具不生成签字、不验证现实身份，只绑定受控平台/证书验证证据并失败关闭。
- 发布判断：本地代码级阻塞已关闭；正式门禁仍为1 PASS / 5 BLOCKED，TECH-V0.2继续Unreleased，Sprint 3仍未启动编码。
- 文档版本：收口总控及失败关闭流程纳入主报告`RC-FINAL-V1.4`和候选说明`RC-NOTE-V1.2`；未改变业务证据批次、TECH状态或正式发布结论。
- 关联证据：`docs/uat/evidence/20260718-1306-tech-v02-shutdown-order-fixed/README.md`、`docs/uat/evidence/20260718-1315-tech-v02-rc3-local-hardening/README.md`、`docs/releases/TECH-V0.2-EXTERNAL-EVIDENCE-BUNDLE.md`、`docs/releases/TECH-V0.2-RELEASE-BLOCKER-HANDOFF.md`、`docs/TECH-V0.2-RELEASE-CANDIDATE-FINAL-REPORT.md`。

#### CHG-20260718-015：修复Live UAT测试宿主资源关闭顺序

- 日期：2026-07-18。
- 状态：Unreleased / Test Infrastructure Hardening / 本地关闭。
- 产品蓝图版本：PRODUCT-V1.2；未改变冻结架构或业务管理链。
- 目标技术版本：TECH-V0.2。
- 变更类型：Fixed / Test Infrastructure / Reliability。
- 修改内容：Live UAT宿主通过合并Spring默认监听器的自定义`TestExecutionListener`，利用`afterTestClass`逆序语义让`DirtiesContext`先关闭应用上下文/调度器/Hikari，再释放Embedded PostgreSQL和运行标记；任一关闭失败时继续释放后续资源并保留suppressed异常；初始化中途失败时释放已启动的PostgreSQL；新增三项关闭顺序测试。
- 修改原因：RC2归档日志显示测试数据库先停止后，仍运行的后台Worker在应用结束前产生11条连接错误；该问题不影响已完成的89次业务请求，但会污染测试收尾日志并掩盖真正的停机异常。
- 数据库/API/生产影响：无；不新增迁移、不改变API或生产运行逻辑，仅加固opt-in Live UAT测试宿主。
- 验证结果：第一次手工关闭ApplicationContext的Live实跑虽然使Hikari先于PostgreSQL关闭，但触发Spring Test `afterTestClass`监听器错误并以BUILD FAILURE结束，该失败证据继续保留。修订为监听器方案后，生命周期测试3/3 PASS、最终完整后端回归48项失败0/错误0/跳过2；第二次真实Live UAT宿主复验1/1、失败0、错误0、BUILD SUCCESS，Hikari于`13:11:27.817 +08:00`完成关闭，PostgreSQL于`13:11:27.968 +08:00`停止，所有托管进程与临时令牌已清理。
- 发布边界：失败证据永久保留，不替换RC2业务证据，不改变1 PASS / 5 BLOCKED的正式发布判断。
- 验证证据：`docs/uat/evidence/20260718-0215-tech-v02-local-hardening/README.md`、`docs/uat/evidence/20260718-0218-tech-v02-shutdown-order/README.md`、`docs/uat/evidence/20260718-1306-tech-v02-shutdown-order-fixed/README.md`。

#### CHG-20260718-014：完成TECH-V0.2 RC2最终证据一致性审计

- 日期：2026-07-18。
- 状态：Unreleased / Documentation / Release Gate NO-GO。
- 产品蓝图版本：PRODUCT-V1.2；未改变任何冻结架构或管理模型。
- 目标技术版本：TECH-V0.2。
- 变更类型：Documentation / Release Governance。
- 修改内容：将`TECH-V0.2 Release Candidate Final Report`提升为RC-FINAL-V1.2，并把客房卫生、客诉、工作未提交三条闭环中的动态工作记录/任务标识修正为最终权威批次`20260718-0154-tech-v02-rc2`的实际值；修正数据库恢复演练SHA-256为同一链接证据中的实际值；将现场照片验收的运行状态引用从可变runtime指针改为固定归档路径；澄清89次请求总数已包含10次认证拒绝和6次业务越权拒绝；新增只读`Test-TechV02EvidenceConsistency.ps1`，自动核对报告、API、数据库、制品、恢复演练和截图证据；重新核对JUnit回归和六项REL-P0门禁。
- 修改原因：RC2复验会生成新的动态UUID，最终报告必须与同一批次API及数据库证据一一对应，不能引用前一轮运行标识。
- 数据库/API/权限影响：无；仅修正文档证据引用，不改变业务行为、数据库迁移、API契约、组织模型、一人多岗或权限隔离。
- 验证结果：41项后端测试失败0、错误0、跳过2；89次UAT请求非预期失败0；9份关键文档的43个本地链接全部有效；证据一致性检查10/10 PASS；只读发布门禁结果为1 PASS / 5 BLOCKED。RC2测试宿主停止顺序日志与未独立固化秘密扫描报告已登记为非发布阻断技术债。
- 发布判断：TECH-V0.2继续为Unreleased，Sprint 3未启动。
- 关联文档：`docs/TECH-V0.2-RELEASE-CANDIDATE-FINAL-REPORT.md`、`docs/uat/evidence/20260718-0154-tech-v02-rc2/api/summary.json`。

#### CHG-20260718-012：完成TECH-V0.2 RC Final技术收口并执行发布门禁复核

- 日期：2026-07-18。
- 状态：Unreleased / RC Final技术验证PASS / Release NO-GO。
- 产品蓝图版本：PRODUCT-V1.2；冻结架构、组织模型、一人多岗、权限隔离和标准中心模型均未改变。
- 目标技术版本：TECH-V0.2。
- 变更类型：Release Candidate / UAT / Security / Operations / Documentation。
- 完成内容：以RS256 Bearer JWT重新完成六角色UAT和三条业务闭环；真实后台Worker自动完成漏交检测、提醒、逾期与升级，UAT未手工调用SLA或Outbox恢复接口；正式模式附件扫描改为外部扫描器失败即拒绝；完成本地可复现制品与PostgreSQL备份恢复演练；修复员工最后有效任职失效后旧JWT仍可能保留角色授权的问题，并验证一人多岗剩余任职不受影响。
- 收口继续项：增加跨平台换行与二进制属性规则，排除可变UAT运行态；在原空`.git`目录上初始化本地`main`仓库；为现场照片UAT增加真实文件及来源元数据参数；增加目标企业SSO和目标环境运行保障正式验收载体。尚未建立首个提交或标签。
- 验证结果：后端41项测试，失败0、错误0、跳过2；身份生命周期4/4通过；RC2 UAT共89次请求、非预期失败0，其中10次认证拒绝和6次业务越权拒绝均符合预期；既有25/25页面证据继续有效；RC2双构建载荷指纹一致（`daf7a779fca869ee0208c7ae4588aff3d3f111ee1732f6ff5477612d42a1f1bb`）。
- 数据库影响：无新增迁移；候选仍为DB-V13 / Flyway V13，正式已发布基线仍为DB-V4。
- API影响：API主版本仍为API-V1（`/api/v1`），OpenAPI候选仍为`0.2.1-sprint2.1`。
- 发布判断：Sprint 2.1业务P0/P1技术问题已关闭且未发现开放P1；但目标企业SSO、10方签署、真实现场照片及目标附件链、有效Git提交/标签、目标持久化PostgreSQL运维保障仍未完成，REL-P0未全部关闭。
- 治理结论：TECH-V0.2保持Unreleased，当前正式发行仍为TECH-V0.1；不得创建正式`TECH-V0.2`标签，不启动Sprint 3编码。
- 关联文档：`docs/TECH-V0.2-RELEASE-CANDIDATE-FINAL-REPORT.md`、`docs/releases/TECH-V0.2-RELEASE-NOTE-RC.md`、`docs/releases/TECH-V0.2-TARGET-SSO-ACCEPTANCE.md`、`docs/releases/TECH-V0.2-TARGET-OPERATIONS-ACCEPTANCE.md`、`docs/uat/evidence/20260718-0154-tech-v02-rc2/README.md`。

#### CHG-20260718-013：准备TECH-V0.3技术冻结草案并加固启动边界

- 日期：2026-07-18。
- 状态：Unreleased / Planning / Sprint 3未启动。
- 产品蓝图版本：PRODUCT-V1.2；未改变冻结管理链。
- 目标技术版本：TECH-V0.3。
- 变更类型：Architecture / Security / Planning / Documentation。
- 修改内容：新增`docs/TECH-V0.3-TECHNICAL-FREEZE-DRAFT.md`，把首个可验收切片收敛为“前台客诉工作记录→工作分析Agent→人工复核→规则→任务→执行验收”；修正V14—V18候选迁移安全顺序，要求每批新增租户表在同一迁移内立即启用并强制RLS。
- 权限影响：不授予新权限，不创建服务主体，不启用模型调用；继续冻结组织、一人多岗、RBAC、OrgScopeResolver、任职有效期和FORCE RLS边界。
- 数据库/API影响：无实际迁移或接口；V14+及AI API仍为待批准候选。
- 启动结论：草案不构成G0-08关闭或开工批准；TECH-V0.2未Released前不得进入Sprint 3编码。
- 关联文档：`docs/TECH-V0.3-TECHNICAL-FREEZE-DRAFT.md`、`docs/SPRINT-3-PLAN.md`。

#### CHG-20260718-011：将受限CEO Agent纳入Sprint 3计划

- 日期：2026-07-18。
- 状态：Unreleased / Planning / 未启动编码。
- 产品蓝图版本：PRODUCT-V1.2，文档修订R1.1；未改变核心中心或冻结管理链。
- Sprint 3计划版本：S3-PLAN-V1.1。
- 目标技术版本：TECH-V0.3。
- 变更类型：Product Boundary / Planning / Security / Documentation。
- 修改内容：
  - 增加CEO Agent，定位为CEO每日管理助手。
  - 每日生成《CEO AI经营简报》，固定覆盖集团经营状态、风险酒店、重大事项、今日需CEO决策事项、AI建议和数据质量限制。
  - 增加受限AI服务主体、CEO收件任职、简报调度、决策事项、决策记录、页面、API、事件和专项UAT规划。
  - 有效数据范围按租户、服务主体授权、CEO收件任职、简报策略和Agent工具白名单取交集。
  - CEO Agent不得持有通配、跨租户、SUPERUSER、BYPASSRLS、IAM管理、标准/规则发布或任务写权限。
  - AI建议与CEO实际决定严格分离；只有人工记录的`CEODECISIONRECORDED`可进入规则和任务链。
  - Sprint 3候选周期由15个工作日调整为18个工作日，黄金评测集由60条调整为80条，验收由三场景扩为四场景。
- 修改原因：让CEO每天获得可执行的集团管理摘要，同时防止“集团视角”等同于“无限数据权限”。
- 影响模块：产品蓝图、Sprint 3预实施计划、TECH-V0.3候选范围和版本治理文档。
- 数据库影响：无实际迁移；V14—V18仍是候选规划，增加服务主体、授权、简报调度和CEO决策对象的设计。
- API影响：无实际接口；API-V1候选规划增加CEO简报、调度、查看确认、决策记录和审计接口。
- 页面影响：无实际页面；候选增加CEO今日简报、历史简报、决策卡和简报配置页面。
- 权限影响：只增加最小权限和交集授权规划，不授予无限权限，不改变现有组织、一人多岗、RBAC和FORCE RLS模型。
- 启动结论：仅完成规划更新；TECH-V0.2仍为Unreleased，Sprint 3仍未启动编码。
- 关联文档：`docs/SPRINT-3-PLAN.md`、`docs/HOTEL-AI-OS-PRODUCT-BLUEPRINT.md`、`docs/TECHNICAL-VERSION-HISTORY.md`。

#### CHG-20260717-010：输出Sprint 3 AI进入管理闭环预实施计划

- 日期：2026-07-17。
- 状态：Unreleased / Planning / 未启动编码。
- 产品蓝图版本：PRODUCT-V1.2，未改变产品方向或冻结管理链。
- 目标技术版本：TECH-V0.3。
- 变更类型：Planning / Architecture / Documentation。
- 修改内容：
  - 输出`docs/SPRINT-3-PLAN.md`，规划AI Gateway、工作分析Agent、经营分析Agent、点评分析Agent、AI报告和AI任务建议。
  - 冻结AI边界：所有模型调用经Gateway；Agent只用受控只读工具；AI结果先人工复核；批准后形成事件并由规则中心决定任务动作。
  - 规划候选Flyway V14—V18、API-V1兼容扩展、AI页面、真实Worker、成本/审计、安全控制和量化UAT。
  - 将TECH-V0.2的全部发布阻断项和TECH-V0.3技术冻结列为Sprint 3启动门禁。
- 修改原因：产品负责人在Final UAT选择B后单独授权先输出Sprint 3计划，以便提前进行技术审查和资源安排。
- 影响模块：仅计划与版本治理文档；无业务代码、数据库或API实际变更。
- 数据库影响：无；V14—V18仅为候选规划，当前工作树候选仍为DB-V13，正式已发布仍为DB-V4。
- API影响：无；0.3.0-sprint3仅为目标契约，当前候选仍为0.2.1-sprint2.1。
- 权限及租户隔离影响：无实际变更；计划要求继续保留服务端RBAC、OrgScopeResolver和FORCE RLS。
- 启动结论：Sprint 3计划已输出，但TECH-V0.2仍为Unreleased，Sprint 3编码仍未启动。
- 后续关系：本条只取代CHG-20260717-009中“暂不输出Sprint 3计划”的行政限制，不改变其`B——继续修复`、TECH-V0.2不得发布和Sprint 3不得开工的结论。
- 关联文档：`docs/SPRINT-3-PLAN.md`、`docs/HOTEL-AI-OS-TECH-V0.2-SPRINT-2.1-FINAL-UAT-REPORT.md`。

#### CHG-20260717-009：完成TECH-V0.2 Sprint 2.1正式Final UAT发布判断

- 日期：2026-07-17。
- 状态：Unreleased / Final UAT选择B / 继续修复。
- 产品蓝图版本：PRODUCT-V1.2，未改变产品方向。
- 目标技术版本：TECH-V0.2。
- 变更类型：Documentation / Release Gate。
- 修改内容：
  - 按版本、P0、三业务场景、六角色和正式发布条件重新审计最终证据。
  - 确认客诉存在专用业务事件`COMPLAINTREPORTED`，整改规则监听`STANDARDEVALUATIONCOMPLETED`。
  - 确认工作未完成检测与升级由UAT脚本调用SLA接口，未证明真实后台Worker自动执行。
  - 正式A/B发布判断选择`B——继续修复`。
- 修改原因：技术闭环通过不等于满足正式业务发布条件，必须按正式登录、Worker、业务签字、制品和目标环境逐项放行。
- 影响模块：发布治理、UAT报告和技术版本状态；无业务代码变更。
- 数据库影响：无；候选仍为DB-V13，正式已发布仍为DB-V4。
- API影响：无；候选仍为API-V1 / OpenAPI 0.2.1-sprint2.1。
- 权限及租户隔离影响：无；既有六角色权限和负向证据继续有效。
- 发布结论：TECH-V0.2保持Unreleased，当前正式发行仍为TECH-V0.1；不生成Release Note，不启动Sprint 3。
- 待关闭：真实Worker、目标环境SSO/JWT、真实客房照片与生产附件链、正式签字、可追溯制品、备份恢复和回滚演练。
- 验证证据：`docs/HOTEL-AI-OS-TECH-V0.2-SPRINT-2.1-FINAL-UAT-REPORT.md`、`docs/uat/evidence/20260717-2317-s21-final/README.md`。
- 后续关系：本条取代CHG-20260717-008的“CONDITIONAL GO”作为当前发布判断；CHG-20260717-008作为技术闭环检查点永久保留。

#### CHG-20260717-008：完成Sprint 2.1真实业务闭环修复与复验

- 日期：2026-07-17。
- 状态：Unreleased / 技术闭环UAT通过 / 业务签署BLOCKED / 有条件进入发布审批。
- 产品蓝图版本：PRODUCT-V1.2，未改变产品方向。
- 目标技术版本：TECH-V0.2。
- 修改内容：
  - 建立真实PostgreSQL UAT环境、六角色数据库账号、门店、工作包、标准和规则fixture。
  - 补齐前厅主管团队工作详情、复核和整改任务入口。
  - 补齐客房主管真实图片上传、附件管理和标准评价入口。
  - 补齐店总门店驾驶舱、风险事项和未完成任务汇总，并增加区域多门店驾驶舱。
  - 补齐工作期望MISSED检测、提醒、规则建任务、任务逾期和升级执行。
  - 完成客房卫生、客诉、工作未完成提醒升级三条真实业务闭环。
  - 增加6个权限与非法状态负向用例，覆盖团队数据、跨门店、跨区域、范围外指派、自我验收和附件越权。
  - 增加前厅主管手工创建整改任务并取消的真实证据。
- 修改原因：关闭首轮业务UAT的P0阻断项，使TECH-V0.2具备可复验的业务闭环证据。
- 影响模块：工作数据、附件、标准评价、企业规则、管理任务、通知、驾驶舱、IAM、UAT基础设施和页面。
- 数据库影响：新增Flyway V13权限迁移；V1—V12不变。UAT fixture不属于正式Flyway迁移。
- API影响：API-V1内向后兼容扩展；OpenAPI更新为0.2.1-sprint2.1，共68路径、85操作、58模型。
- 权限及租户隔离影响：保留组织、一人多岗、服务端范围解析和RLS模型；OTA运营经理只看到授权区域门店；6个负向用例均按预期拒绝。
- 验证证据：最终运行`20260717-2317-s21-final`，83次API请求中77次正向2xx、6次预期拒绝、非预期失败0，83个Correlation ID均存在且唯一；客房主管实际创建卫生标准评价；25张页面截图加载检查失败0，12份数据库记录；Live UAT 1/1通过。
- 验收结论：Sprint 2.1技术闭环、六角色自动化走查和权限负向验收PASS；业务正式签署BLOCKED；建议CONDITIONAL GO进入发布审批，TECH-V0.2正式发布仍为NO-GO。
- 发布门槛：真实客房现场照片业务补测与签字、Git标签、制品SHA-256、目标环境SSO/存储/调度/备份恢复与回滚记录。
- 关联文档：docs/SPRINT-2.1-UAT-ACCEPTANCE-REPORT.md、docs/uat/evidence/20260717-2317-s21-final/README.md。

#### CHG-20260717-005：实施TECH-V0.2管理闭环

- 日期：2026-07-17。
- 状态：Unreleased / 开发中、技术验收候选；尚未完成业务验收或正式发布。
- 产品蓝图版本：PRODUCT-V1.2。
- 目标技术版本：TECH-V0.2。
- 修改内容：
  - 实现工作包定义、版本、范围、分配和工作期望基础能力。
  - 扩展岗位工作记录、提交与复核能力。
  - 实现企业规则中心基础版、任务状态机、标准评价和站内通知。
  - 实现标准→工作→任务→执行→验收闭环的技术候选链路。
  - 首批岗位调整为OTA运营助理、OTA运营经理、前台员工、前厅主管、店助、店总。
  - 增加Outbox自动投影，将事务事件可靠转换为管理事件并触发确定性规则消费。
  - 完成事件类型规范化、失败动作恢复、按租户系统身份、已发布规则冻结和对象级权限加固。
- 修改原因：
  - 将企业标准转化为岗位每天可执行的具体工作。
  - 避免把所有日常工作直接任务化。
  - 支持OTA跨店运营与门店管理责任链。
- 影响模块：
  - 工作包、工作记录、事件、规则、任务、评价、通知、IAM和驾驶舱。
- 数据库影响：新增Flyway V5—V12迁移，其中V12为完整性与可靠性加固；工作树共50张业务表，25张Sprint 2新增表全部启用并强制执行RLS，V1—V4保持不变。
- API影响：在API-V1（/api/v1）内向后兼容增加工作包、工作记录、规则、任务、评价、通知和身份上下文接口；OpenAPI 0.2.0-sprint2包含63个路径、79个操作并已完成映射一致性复核。
- 权限影响：工作、任务和验收绑定精确employee_position_assignment，OTA范围与服务端授权取交集。
- 身份与安全：生产模式使用受信JWT/SSO声明并由数据库RBAC解析最终权限；开发请求头只接受tenant/actor，不接受客户端自报角色或组织范围。
- 规则边界：规则只执行确定性条件；当前动作限定为CREATE_TASK和CREATE_NOTIFICATION，大模型不参与阈值、状态或升级判断。
- 兼容性：不得修改V1—V4，不得破坏现有30个API操作。
- 验证证据：后端27/27自动化测试通过；前端六角色、8个页面的生产构建通过。
- 当前边界：对象存储与病毒扫描、完整JSON Schema、生产定时/升级Worker、工作记录及人工复核完整幂等、真实SSO生产部署、页面截图和业务验收尚未完成。
- 关联文档：docs/SPRINT-2-PLAN.md、docs/SPRINT-2-IMPLEMENTATION-REPORT.md、docs/TEST-REPORT.md。

#### CHG-20260717-006：建立长期文档治理

- 日期：2026-07-17。
- 状态：Unreleased / Documentation。
- 产品蓝图版本：PRODUCT-V1.2。
- 当前技术版本：TECH-V0.1。
- 修改内容：
  - 建立《Hotel AI OS产品总蓝图》。
  - 建立Change Log。
  - 建立技术版本记录。
  - 在README增加长期维护入口和强制更新规则。
- 修改原因：保证未来更新、优化和维护可追溯，防止产品版本、技术版本、API版本和数据库版本混淆。
- 影响模块：文档治理。
- 数据库影响：无。
- API影响：无。
- 业务代码影响：无。

#### CHG-20260717-007：记录TECH-V0.2首轮业务UAT

- 日期：2026-07-17。
- 状态：Unreleased / Business UAT Blocked / No-Go。
- 产品蓝图版本：PRODUCT-V1.2。
- 目标技术版本：TECH-V0.2。
- 修改内容：
  - 建立《TECH-V0.2 UAT业务验收报告》和截图证据索引。
  - 按前台员工、前厅主管、客房主管、店助、店总、区域/运营管理六个角色核对业务验收能力。
  - 按客房卫生、客诉、工作未完成三个场景核对端到端管理闭环。
  - 登记真实登录、附件图片、主管复核与整改、客诉规则事实、未提交检测、风险视图和多门店视图等P1阻断项。
- 修改原因：技术自动化通过不能替代真实业务UAT；禁止使用演示回退伪造验收证据。
- 验收结论：TECH-V0.2技术候选保持通过，业务UAT为BLOCKED / NO-GO，当前正式版本仍为TECH-V0.1。
- 发布影响：Sprint 3继续冻结；P0=0、P1=0且六角色、三场景、截图和签字齐全前，不得发布TECH-V0.2。
- 数据库影响：无。
- API影响：无。
- 业务代码影响：无。
- 关联文档：docs/uat/TECH-V0.2-UAT-BUSINESS-ACCEPTANCE-REPORT.md。
- 后续状态：该次NO-GO为历史检查点；其P0阻断项已由CHG-20260717-008修复并完成复验，不删除原记录。

## Released

### TECH-V0.1 — 2026-07-17

状态：已发布 / Sprint 1验收通过。

#### Added

##### CHG-20260717-004：完成Hotel AI OS基础底座

- 完成租户、集团、区域、门店、部门组织模型。
- 完成员工、岗位、一人多岗和上级任职模型。
- 完成权限点、角色、角色权限和范围授权数据模型。
- 完成标准分类、定义、版本、范围和发布基础能力。
- 完成表单、工作记录、附件元数据和经营指标基础能力。
- 完成CEO和店总驾驶舱框架。
- 完成审计日志和事务Outbox写入。

#### Security

- 服务查询显式携带tenant_id。
- PostgreSQL对24张表启用并强制执行RLS。
- 建立同租户组合外键和双租户隔离测试。
- 开发请求头认证默认关闭。

#### Database

- 数据库迁移达到DB-V4。
- 建立25张业务表。

#### API

- API主版本为API-V1。
- OpenAPI契约版本为0.1.0-sprint1。
- Controller与OpenAPI均包含30个业务操作。

#### Validation

- 后端11项测试通过。
- 真实PostgreSQL 14.22迁移和隔离测试通过。
- 前端生产构建通过。
- 后端JAR构建通过。

#### Known limitations

- 正式JWT/SSO和数据库驱动运行时RBAC尚未接通。
- React页面仍以演示数据为主。
- Outbox可靠投递和消费尚未实现。
- Rule Engine、任务中心、标准评价和AI Gateway尚未实现。

关联文档：

- docs/HOTEL-AI-OS-V0.1-TECHNICAL-FREEZE-REPORT.md。
- docs/SPRINT-1-ACCEPTANCE.md。
- docs/TEST-REPORT.md。

## 产品决策历史

### PRODUCT-V1.4 — 2026-09-11

状态：已冻结、当前有效；技术尚未实现。

#### CHG-20260911-050：修订集团身份、汇报关系与董事长受限交办

- 修改内容：
  - 集团总经理与集团CEO合并为同一岗位身份；`GROUP_GENERAL_MANAGER`岗位映射既有`CEO`角色，不新增同义角色。
  - 区域经理冻结不用；不新增`REGIONAL_MANAGER`，不再把`OTA_OPERATION_MANAGER`兼容为区域经理。
  - 新增行政人事、行政人事主管角色和岗位；二者均直属总经理/CEO，副总经理为独立表达的间接领导。
  - 董事长增加岗位任职，但仍无管理后台权限；专用任务入口只允许向总经理/CEO或副总经理交办并验收本人任务。
  - 延续管理岗周/月计划直属审批转任务，行政人事主管纳入、行政人事普通岗位默认不纳入。
- 修改原因：使产品冻结与集团当前真实岗位及领导关系一致，并把董事长业务交办限制在最小必要边界。
- 影响：取代PRODUCT-V1.3为当前产品基线；当前技术发行、API、数据库和部署状态不变。
- 关联文档：`docs/V1.4-ARCHITECTURE-FREEZE.md`、`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-V1.1-DESIGN-FREEZE.md`。

### PRODUCT-V1.3 — 2026-09-11

状态：已被PRODUCT-V1.4取代；未实现，历史永久保留。

#### CHG-20260911-049：冻结集团管理角色与周/月工作计划审批转任务

- 修改内容：
  - 新增独立的集团董事长只读角色，不替代或继承CEO。
  - 新增集团总经理角色和岗位；集团副总经理沿用既有`GROUP_VICE_PRESIDENT`角色和岗位。
  - 冻结集团总经理→集团副总经理→区域经理的经营管理层级，实际审批以有效直属主管任职为准。
  - 冻结集团副总经理及以下管理岗位提交周/月计划，直属主管逐项确认任务内容、完成时间和提醒。
  - 全部通过后，每个计划项原子、幂等地生成一个分发给提交任职的任务；任一项不同意时整单退回且不生成任务。
- 修改原因：分离集团观察权、经营管理权和系统配置权，并把管理承诺纳入任务执行与验收闭环。
- 影响：取代PRODUCT-V1.2为当前产品基线；当前技术发行、API、数据库和部署状态不变。
- 关联文档：`docs/V1.3-ARCHITECTURE-FREEZE.md`、`docs/tasks/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-V1-DESIGN-FREEZE.md`。

### PRODUCT-V1.2 — 2026-07-17

状态：已被PRODUCT-V1.3取代，历史永久保留。

#### CHG-20260717-003：冻结Hotel AI OS核心管理架构

- 修改内容：
  - 冻结组织与权限→标准→业务入口→分析→规则→任务→绩效→知识→标准优化管理链。
  - 增加AI主动发现，产品目标形成三类业务入口。
  - 明确标准中心与知识中心边界。
  - 冻结AI Gateway。
  - 明确规则负责确定性判断，AI负责理解、原因和建议。
  - 冻结组织、一人多岗、权限隔离和标准版本四个核心模型。
- 修改原因：建立未来10年可持续扩展的酒店集团第二管理体系。
- 影响：成为TECH-V0.1之后所有技术版本的产品基线。
- 关联文档：docs/V1.2-ARCHITECTURE-FREEZE.md。

### PRODUCT-V1.1 — 2026-07（历史补录）

原始精确日期未单独记录；本条于2026-07-17补录。

#### CHG-20260717-002：增加标准、规则和双入口

- 修改内容：
  - 增加企业标准中心，回答“什么是正确”。
  - 增加企业规则中心，回答“什么时候行动”。
  - 将单一店总日报入口调整为岗位工作数据和经营数据双入口。
- 修改原因：让数据判断能够产生确定性管理动作，并覆盖集团所有岗位。
- 影响：新增标准和规则产品边界，扩展业务数据入口。
- 状态：已被PRODUCT-V1.2取代，历史永久保留。

### PRODUCT-V1.0 — 2026-07（历史补录）

原始精确日期未单独记录；本条于2026-07-17补录。

#### CHG-20260717-001：建立Hotel AI OS初始产品蓝图

- 修改内容：
  - 确立“酒店集团第二管理体系”定位。
  - 提出平台化、模块化、配置化和权限隔离原则。
  - 提出集团→区域→门店→部门→岗位→员工组织模型。
  - 提出一人多岗和首个数据→分析→任务→执行→沉淀闭环。
- 修改原因：支撑集团从20家扩展到50、500和1000家酒店。
- 状态：已被PRODUCT-V1.1取代，历史永久保留。

## 变更类型

| 类型 | 适用范围 |
|---|---|
| Added | 新增产品或技术能力 |
| Changed | 改变既有行为或边界 |
| Fixed | 缺陷修复 |
| Security | 身份、权限、租户隔离或安全变更 |
| Database | 表、字段、索引、迁移或数据修复 |
| API | 接口、字段、状态或兼容性变更 |
| Performance | 性能和容量优化 |
| Deprecated | 宣布停止新增使用 |
| Removed | 删除能力 |
| Documentation | 仅文档和说明 |

## 标准变更模板

复制以下模板到Unreleased，每个重要变更分配唯一CHG编号：

    ### CHG-YYYYMMDD-NNN：变更标题

    - 日期：
    - 状态：Unreleased / Released
    - 产品蓝图版本：PRODUCT-Vx.x
    - 目标技术版本：TECH-Vx.x
    - 变更类型：
    - 修改内容：
    - 修改原因：
    - 影响模块：
    - 数据库影响：无 / 迁移编号与内容
    - API影响：无 / 向后兼容 / 破坏性
    - 权限及租户隔离影响：
    - 历史数据影响：
    - 回滚或降级方案：
    - 验证证据：
    - 产品批准人：
    - 技术批准人：
    - 关联蓝图、ADR、任务和测试报告：

## 固定维护流程

1. 开始设计前读取产品蓝图、Change Log和技术版本记录。
2. 为变更分配CHG编号并写入Unreleased。
3. 判断是否影响PRODUCT蓝图；影响时先完成产品评审和新蓝图冻结。
4. 确认目标TECH版本、数据库/API兼容性和权限影响。
5. 实施过程中同步维护迁移、OpenAPI和测试。
6. 验收通过后，把条目从Unreleased移入正式TECH版本。
7. 更新技术版本状态、测试证据、制品校验和和恢复说明。
8. 已发布历史只能追加更正记录，不直接删除。
