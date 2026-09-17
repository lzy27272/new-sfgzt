# TECH-V0.2-PILOT.8 云端内部Pilot部署报告

| 项目 | 结果 |
|---|---|
| 产品/设计基线 | PRODUCT-V1.4 / DESIGN-1.1 / TECH-DESIGN-1.0 |
| 部署范围 | 批次A身份与V39底座、批次B董事长受限交办；不含批次C/D |
| 目标 | 腾讯云Ubuntu / `https://www.sfgzt.cn` / GitHub `lzy27272/AIzhongtai:main` |
| 状态 | DEPLOYED / HEALTHY / FEATURES OFF / FORMAL TECH-V0.2 NO-GO |
| 日期 | 2026-09-12 |

## 1. 收口与制品

- 功能提交：`e001c2ae2c58445bae553667301f0c691c2e7edf`；该提交已快进推送至GitHub `main`并作为首次成功激活的Pilot.8功能基线。
- 后端：`hotel-ai-os-core-api-0.2.0-pilot.8.jar`，首次部署SHA-256为`535937c1bb31c50c26a1b2378e46fd084bb879beb6938b65afa59b373eac02b5`。
- Web：Pilot构建的首次`index.html` SHA-256为`32f1d6574bb8484309f763697d38a9e00949c79d8bab5799131a7083c25cbd76`；公网回读哈希一致。
- 源码变更、JAR、Web压缩包及部署脚本均通过只读敏感信息扫描，0命中、0错误。
- 后端全量测试233项，0失败、0错误、3跳过；Web契约测试58/58 PASS；OpenAPI契约及Pilot生产构建PASS。

## 2. 数据与回滚保障

- 部署前执行服务器既有加密PostgreSQL备份任务，备份文件权限`0600 root:root`，密文SHA-256校验PASS。
- Flyway从V38迁移至V39；服务器健康门禁确认JAR版本39、数据库版本39、失败迁移0。
- 首次切换因systemd尚未加载既有迁移unit，在执行迁移前失败；自动回滚恢复旧Core/Web软链接，API保持UP、数据库保持V38。
- 执行`systemctl daemon-reload`并重新激活已校验候选后迁移成功；该过程证明应用软链接回滚路径有效，没有进行数据库破坏性回退。

## 3. 稳定态验证

| 检查 | 结果 |
|---|---|
| Core API / Caddy | active / active |
| 内网健康 | `UP` |
| 公网首页 | HTTP 200 |
| 服务器与公网首页哈希 | 一致 |
| Flyway JAR / DB | 39 / 39，失败迁移0 |
| 未授权`/api/v1/iam/me` | 401 |
| 未授权`/api/v1/executive-tasks` | 401 |
| 最近5分钟Core API warning | 0 |
| GitHub/云端最终版本 | 最终收口时回读并保持一致 |

Browser插件不可用，且Playwright自带Chromium未安装；遵循前端测试流程，使用机器已有Microsoft Edge与Playwright 1.62.1，不下载新浏览器。桌面1440×900和移动390×844均确认：

- URL和页面标题正确；
- 登录页有完整有意义内容，没有框架错误层；
- 页面加载的同源资源包含`TECH-V0.2-PILOT.8`；
- 登录按钮在空表单→填入→清空密码过程中按禁用→启用→禁用变化，全程没有提交凭证；
- 控制台错误/警告、页面异常及请求失败均为0；
- 截图视觉检查未见白屏、遮挡、溢出或不可读控件。

## 4. 功能边界

- 云端`core-api.env`不存在任何`GROUP_MANAGEMENT_*`变量，应用使用默认false；租户白名单为空。
- 未创建或映射真实董事长、集团总经理、集团副总经理、行政人事主管或行政人事任职。
- 区域经理继续`DEFERRED / NOT IN USE`。
- 批次C周/月计划、批次D提醒Worker和真实人员Pilot数据包尚未实现或启用。
- 本次为内部Pilot增量部署，不等于TECH-V0.2正式发行，不解除既有正式发布门禁。

## 5. 2026-09-13 企业微信一键邀请增量发布

- GitHub `main`功能提交：`7909a1468a4510e7467a08ee98f190410bdf2a9b`。
- 发布版本：`20260913-pilot8-7909a14`；后端SHA-256为`b0dc7c24b3da9353cfc912e970125d4ac57545eac60238de177b2563a6538121`，Web `index.html` SHA-256为`bebc0ea73fa3c960cd0daae9ec17648c1e2e00237708fdc8436f20f887119e9b`，公网回读一致。
- 发布门禁：Web 70项契约测试、Pilot生产构建、企微后端专项32项及后端全量242项通过（0失败、0错误、3跳过）；空库Flyway V1→V45通过。
- 云端先完成加密PostgreSQL备份，再由Flyway V44迁移至V45；Core API与Caddy均为active，JAR/数据库均为45且失败迁移为0，部署后Core API warning为0。
- 公网首页返回200，未授权`/api/v1/iam/me`返回401，公开静态资源已回读到“一键邀请”。未使用真实员工资料执行手机号注册或审批；本增量仍属于内部Pilot，不改变TECH-V0.2正式NO-GO边界。

## 6. 2026-09-14 全岗位工作模板配置增量发布

- 功能提交为`21f874c2023f6ffa0e9d89a7018703defbbcb0dc`；为保留新仓库既有初始化历史，发布树通过无文件改动的`131568a330b60bb1f570531c61b34db00b096f7f`合并提交接入`lzy27272/AIzhongtai:main`，本地默认`origin`同步迁移至该仓库。
- 云端不可变发布版本为`20260914-pilot8-131568a`。后端JAR SHA-256为`f91c8760a5ec3e444580ae0e457928e2bc5eb5546899089db1fdc92f9b699468`；Web `index.html` SHA-256为`32b89783528b61c13aeab42cd6bb52ea599a8caaa35818cfa2e4b5fa95a41e80`，公网回读哈希一致。
- 部署前生成加密PostgreSQL备份`hotel_ai_os-auto-20260914T112332+0800.dump.enc`，密文及校验文件均为`root:root:0600`，SHA-256校验通过。Flyway由V45迁移至V48，JAR/数据库版本一致，失败迁移为0。
- Core API与Caddy均为`active`，健康状态为`UP`；公网首页返回200，未授权`/api/v1/iam/me`和`/api/v1/work-packages`均返回401，部署后5分钟Core API warning/error为0。
- 公网静态资源已回读到“所有岗位通用工作模板配置”；本次5个发布制品扫描63个归档、23726个条目，敏感信息0命中、0错误。
- 本次仍为内部Pilot增量发布，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态，也不自动启用尚未审批的功能开关或真实人员映射。

## 7. 2026-09-14 企业微信员工入职提交反馈修复

- 功能提交为`a69241f`；修复员工注册信息不合规时“提交审核”按钮被静默禁用的问题。点击提交后会明确标记姓名、手机号、账号、密码、确认密码及门店/岗位错误；校验通过后仍使用原接口和服务端权限边界。
- 云端不可变发布版本为`20260914-pilot8-a69241f`。后端JAR SHA-256为`8da50cdff2fe7b5bdce2457391d871b9aef352250ccb796db1addc43537a459c`；Web ZIP SHA-256为`3e7dfc99b4cbb6119ba2dcbad1eb27254e5c39c3fcba158773d9363acbc35306`；Web `index.html` SHA-256为`6052ef1d6080ac82f9d64b4afb3cb70a9f21fc00da84dff9e3ababe5eb53344b`，服务器与公网回读一致。
- 部署前生成加密PostgreSQL备份`hotel_ai_os-auto-20260914T121150+0800.dump.enc`；密文及SHA校验文件均为`root:root:0600`，SHA-256复核通过。无新增数据库迁移，健康门禁为Flyway JAR V48、数据库V48、失败迁移0。
- 首次执行在正式安装前的临时解压阶段发现上一批`/tmp/hotel-ai-os-release/web`残留并进入交互询问，已立即中止；旧Core/Web软链接、服务健康和候选目录均确认未变化。清理经绝对路径校验的临时子目录后重新执行，安装、迁移门禁和原子切换成功。
- 部署后Core API与Caddy均为`active`，健康状态为`UP`；公网首页返回200，未授权`/api/v1/iam/me`返回401，公开主资源已回读到“密码必须为10至128位”和“提交信息尚未完整”，启动后的warning/error/exception为0。
- 验证：Web全量77项、TypeScript和Pilot生产构建通过；移动端390×844页面闭环10项通过。5个变更文件和5个发布制品（含63个归档、23726个条目）敏感信息扫描均为0命中、0错误。
- 本次仍为内部Pilot增量发布，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态；未使用真实员工资料执行注册或审批。

## 8. 2026-09-14 企业微信多人复用邀请与集团总部入职申请

- 功能提交为`0c7c04ad99a519e9e23cfccd678474f6cf421c8b`；员工一键邀请改为120分钟共享邀请，同一二维码可由多名员工同时扫码，每次扫码创建独立申请，员工提交不会消耗共享邀请，仅固定时间到期后失效。注册组织选项同时包含启用中的集团总部与门店。
- 集团总部入职申请在审核前允许岗位待分配；审核端只提供`GROUP_MANAGEMENT`且不含TENANT授权或不可委派权限的安全岗位，集团董事长、平台管理员等受保护岗位不能通过入职审核授予。
- 云端不可变发布版本为`20260914-pilot8-0c7c04a`。后端JAR SHA-256为`35c349c76a632f3a10551b8b22de93a1fb153a3d438a30a8b5cb96a7d46c64c8`；Web ZIP SHA-256为`304717be3bf29f6c7b14f6d7ef8c1bc3e2183e9e8585ff00cd0add0e87f2e42a`；Web `index.html` SHA-256为`a7e4ce8e71dd34677b88b665aec64442e9d09d8b31edd447ed9b39c62c0e14f7`，服务器与公网回读一致。
- 部署前生成加密PostgreSQL备份`hotel_ai_os-auto-20260914T130523+0800.dump.enc`；密文及SHA校验文件均为`root:root:0600`，SHA-256复核通过。Flyway由V48迁移至V49，JAR/数据库版本均为49，失败迁移0。
- 部署后Core API与Caddy均为`active`，健康状态为`UP`；公网首页返回200，未授权`/api/v1/iam/me`返回401，公开静态资源已回读到“同一二维码在有效期内可供多名员工分别使用”，启动后的warning/error/exception为0。
- 验证：后端全量251项（0失败、0错误、3跳过）、企微入职专项35项、Web全量79项和专项12项、TypeScript、Pilot生产构建及Flyway V1→V49通过；桌面及390×844手机真实页面闭环11项通过，控制台错误、页面异常与请求失败均为0。5个发布制品敏感信息扫描0命中、0错误。
- 未使用真实员工资料执行线上注册或审批；本次仍为内部Pilot增量发布，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态。

## 9. 2026-09-14 企业微信工作台 callback 下载页兼容修复

- 功能提交为`aa90f09b62c282c32490c6670de14862ad46e8d0`。根因是企业微信工作台应用主页直接指向OAuth回调地址，首次打开不携带`code/state`；原401非HTML响应被iOS企业微信识别为名为`callback`的下载文件。修复后仅在两个参数同时缺失时安全启动工作台OAuth；单参数缺失、Cookie冲突和伪造状态仍关闭式拒绝。
- 云端不可变发布版本为`20260914-pilot8-aa90f09`。后端JAR SHA-256为`8a2164010e28e45c525b68e6dcd1a38781a08db9addd00704a785f9dd6e7dc5f`；Web沿用上一稳定包，ZIP SHA-256为`304717be3bf29f6c7b14f6d7ef8c1bc3e2183e9e8585ff00cd0add0e87f2e42a`，`index.html` SHA-256为`a7e4ce8e71dd34677b88b665aec64442e9d09d8b31edd447ed9b39c62c0e14f7`。
- 部署前生成并验证加密PostgreSQL备份`hotel_ai_os-auto-20260914T135543+0800.dump.enc`；Flyway JAR/数据库均为V49，失败迁移0。Core API与Caddy均为`active`，健康状态为`UP`，部署启动窗口内warning/error/exception均为0。
- 公网响应验证：无参数`/api/v1/integrations/wecom/oauth/callback`返回302并跳转企业微信OAuth，设置新的工作台校验Cookie、清除残留入职绑定Cookie、使用no-store策略，无`Content-Disposition`且响应体为空；只提供`code`的异常请求返回401且不跳转。
- Browser实页验证到达企业微信授权域名并显示“请在企业微信客户端打开链接”，未再出现callback文件下载页。外部Browser没有真实企业微信客户端登录态，因此最终账号交换与工作台落地仍需在用户企业微信客户端复验。
- 控制器专项5项、后端全量253项（0失败、0错误、3跳过）和Flyway V1→V49通过；5个发布制品扫描63个归档、23728个条目，敏感信息0命中、0错误。本次仍为内部Pilot增量发布，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态。

## 10. 2026-09-14 企业微信多岗位账号登录恢复

- 功能提交为`0ff58b9ddc9c9ad3842a4183dbb6cb306f9997fa`。生产OAuth记录证明14:09的请求已通过状态和浏览器Cookie校验并领取供应商授权码，失败点位于身份解析；根因是绑定巡检把员工新增第二个有效任职错误视为必须暂停，与既定的一人多岗位规则冲突。
- 修复后，多岗位员工的原默认任职只要仍有效，绑定保持ACTIVE并刷新任职快照；仅因`MULTIPLE_ACTIVE_ASSIGNMENTS`被历史误暂停的绑定自动恢复。默认任职缺失或失效、人工暂停、账号/员工失效等状态继续关闭式拦截。合法工作台OAuth在身份解析失败时跳转中台HTML故障页，避免iOS企业微信再次把空401回调识别为下载文件；入职绑定流和非法状态仍返回401。
- 云端不可变发布版本为`20260914-pilot8-0ff58b9`。后端JAR SHA-256为`9ce2c1df4b6250471e52640b34bf778568bc0ac405bf1566bcc8e807f92ed08b`；Web沿用稳定包，ZIP SHA-256为`304717be3bf29f6c7b14f6d7ef8c1bc3e2183e9e8585ff00cd0add0e87f2e42a`，`index.html` SHA-256为`a7e4ce8e71dd34677b88b665aec64442e9d09d8b31edd447ed9b39c62c0e14f7`，服务器回读一致。
- 部署前生成加密PostgreSQL备份`hotel_ai_os-auto-20260914T144613+0800.dump.enc`；5个发布制品敏感信息扫描0命中、0错误。Core API与Caddy均为`active`，健康状态为`UP`，Flyway JAR/数据库均为V49且失败迁移0，部署启动后的warning/error/exception为0。
- 生产数据验证：绑定巡检已将多岗位账号`xiajun`和`sfglzy777`恢复为`ACTIVE`，清除`MULTIPLE_ACTIVE_ASSIGNMENTS`原因与岗位选择标记，并保留各自有效默认任职；永久删除账号仍维持REVOKED，不受本次恢复逻辑影响。
- 公网与Browser验证：无参数OAuth回调返回302企业微信授权跳转，使用no-store策略、响应体为空且无`Content-Disposition`；`/wecom-auth`显示“无法安全打开中台”和明确凭证错误，页面无框架错误层，控制台错误/警告为0；“返回中台登录”可正常进入`/#/`登录页。真实企业微信授权码交换需由员工在企业微信客户端重新打开工作台完成最终复验。
- 后端专项43项、全量256项（0失败、0错误、3跳过）及Flyway V1→V49通过。本次仍为内部Pilot增量发布，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态。

## 11. 2026-09-15 工作台任务下达与完成率分层看板

- 功能提交为`226c084339bbc889a0c7147079e865cc7b3eb839`，已快进合并并推送至GitHub`lzy27272/AIzhongtai:main`。工作台新增直接打开正式任务创建流程的“一键下达任务”；完成率汇总增加门店、部门和员工层级的当日/月度完成率、待提交与逾期数据。
- Flyway V53为集团副总经理角色及现有集团岗位功能方案补齐`task.create`和`task.dispatch`，实际任务目标继续由既有服务端租户、组织树及门店范围策略裁剪。生产只读核验显示1个租户角色和2个已发布/草稿岗位方案版本同时具备两项权限。
- 云端不可变发布版本为`20260915-pilot8-226c084`。后端JAR SHA-256为`9f18b2eede9faa6706e8eb864425eea674f53ada4cbb69350940be3b31aec684`；Web ZIP SHA-256为`46ae6d24888b6c18bca6cc88d5005d8aee771a67e9866984fcc7bfb94d7598a0`；Web `index.html` SHA-256为`d536e400417ee5fb70b19e95d61eae096264d9b34045d15bc29954583f2ce772`，服务器与公网回读一致。
- 部署前生成加密PostgreSQL备份`hotel_ai_os-auto-20260915T092035+0800.dump.enc`，备份任务返回成功，密文及校验文件均为`root:root:0600`。Flyway JAR/数据库均为V53，失败迁移0。
- Core API与Caddy均为`active`，健康状态为`UP`；公网首页返回200，未授权`/api/v1/iam/me`返回401，部署后10分钟Core API warning为0。发布脚本保留旧Core/Web软链接并启用失败自动回滚，本次未触发回滚。
- 验证：Web 102项测试、TypeScript及Pilot生产构建通过；后端集团副总权限迁移与店长分层汇总专项4项通过，完整Flyway V1→V53迁移和生产JAR构建通过。Codex内置浏览器访问线上页面连续超时，因此本轮未完成登录后真实业务账号交互验收，也未改用未经授权的浏览器替代；真实集团副总账号的按钮与下钻数据仍需业务侧复验。
- 本次仍为内部Pilot增量发布，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态。

## 12. 2026-09-15 工作台当日口径刷新与横向浏览修复

- 功能提交为`8ed46fb2ef2a3d7f2635d36b3c6229a58b5427b7`，已快进合并并推送至GitHub`lzy27272/AIzhongtai:main`。工作台按上海时区维护当前日期并在跨日、重新聚焦或恢复可见时刷新；旧日补交不再进入今日状态，门店卡片显示数据日期，过期汇总不再展示为今日完成率。
- 门店详情表格增加可见、可键盘聚焦的横向滚动容器，在窄窗口和超宽内容下可以浏览右侧完整字段。
- 云端不可变发布版本为`20260915-pilot8-8ed46fb`。后端沿用V53稳定JAR，SHA-256为`9f18b2eede9faa6706e8eb864425eea674f53ada4cbb69350940be3b31aec684`；Web ZIP SHA-256为`699900bd1467c6a54f3ad278fff3d80952247e3a867b8cf39d074f7a426fa2e4`；Web `index.html` SHA-256为`d770a5361f5687853728d946683c0f5e14c3b35d7a13aa0ea85772e402237b0f`，服务器与公网回读一致。
- 部署前生成并校验加密PostgreSQL备份`hotel_ai_os-auto-20260915T112759+0800.dump.enc`。无新增数据库迁移，Flyway JAR/数据库保持V53且失败迁移0。
- 首次候选切换完成安装和迁移后，健康脚本瞬时以SIGPIPE 141退出，发布脚本按设计自动恢复旧Core/Web软链接并重启服务；回滚后服务健康为UP。确认候选目录哈希、迁移结果和健康脚本独立复跑均通过后，使用三次健康重试及旧版本回滚保护重新激活候选，未执行数据库破坏性回退。
- 最终Core API与Caddy均为`active`，运行进程目录和两个`current`软链接均指向本次release；健康状态为`UP`，公网首页返回200，未授权`/api/v1/iam/me`返回401，激活后的warning日志为0。
- 验证：Web 104项测试、TypeScript及Pilot生产构建通过；1280×900浏览器检查确认日期标识正常、控制台错误为0。真实登录账号的跨日刷新与横向拖动仍需业务侧最终复验。
- 本次仍为内部Pilot增量发布，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态。

## 13. 2026-09-15 横向证据照片强校验与主管端图片画廊

- 功能提交为`01733b0e5c2a0d8af6cf2228f1283de483c1fc93`，已快进合并并推送至GitHub `lzy27272/AIzhongtai:main`。工作记录和任务证据中的新JPG/JPEG/PNG照片在前端选择时即校验方向，服务端按EXIF归一化后再次强制`宽度 > 高度`；竖向和正方形照片关闭式拒绝，非图片补充文件不受影响。
- 主管打开团队工作详情即可直接看到全部照片，图片按检查区域、房号/实例、来源、上传时间及扫描状态标记，并统一在16:9横向画框内按原图比例显示；大图查看支持前后切换，历史照片不做破坏性转换。
- 云端不可变发布版本为`20260915-pilot8-01733b0`。后端JAR SHA-256为`e577d6741367ba290c8b3cb0df93dd0cb687369ed25a97e7e271b63e94b38ecd`；Web ZIP SHA-256为`bfd9a6eabd23d775dbd5cb553bb5a744a595ab4692f0ad9ed0308186097d9b71`；Web `index.html` SHA-256为`11ef63684d87995b81d82b129787f12bf2944ab029520745b7783df053837065`，服务器与公网回读一致。
- 部署前加密PostgreSQL备份`hotel_ai_os-auto-20260915T133022+0800.dump.enc`返回`POSTGRES_BACKUP_OK`，密文及SHA校验文件均为`root:root:0600`。本次无新增数据库迁移，Flyway JAR/数据库均为V53且失败迁移0。
- Core API与Caddy均为`active`，健康状态为`UP`；公网首页返回200，未授权`/api/v1/iam/me`返回401。部署脚本使用旧Core/Web软链接作为回滚目标，本次所有门禁一次通过，未触发回滚。
- 验证：Web全量108项、TypeScript及Pilot生产构建通过；后端照片方向、正方形拒绝、EXIF旋转归一化和既有业务专项通过，生产JAR构建成功。5个发布输入扫描63个归档、23739个条目，敏感信息0命中、0错误。
- Codex内置浏览器因运行组件版本不完整无法启动，故本轮没有宣称主管真实账号的线上画廊视觉验收或手机端真实拍摄验收，也未改用其他浏览器规避该故障；公网制品一致性与服务端发布门禁已独立通过。
- 本次仍为内部Pilot增量发布，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态。

## 14. 2026-09-15 企业微信任务链接会话保持修复

- 功能提交为`b8ad8547e05ad1600e40de08fc40b2cddd50e0eb`，已快进合并并推送至GitHub `lzy27272/AIzhongtai:main`。生产OAuth记录证明任务链接已完成授权和一次性交换，重复登录根因是企业微信WebView重建后仅存于页面内存及`sessionStorage`的会话状态丢失。
- 修复后OAuth交换同时设置`__Host-`前缀、Secure、HttpOnly、SameSite=Lax且与JWT到期时间一致的Cookie；前端只持久化不含令牌的会话到期标记。显式Bearer头继续优先，普通密码会话仍仅保存在内存；退出会清除企微Cookie，匿名认证端点不受残留Cookie阻断。
- 云端不可变发布版本为`20260915-pilot8-b8ad854`。后端JAR SHA-256为`2692b290debd313defbba3d54b363353ac902e79256c5533c470783b9bd4baeb`；Web ZIP SHA-256为`0aea5e14320d537341f160d9f32c48110e0784ea23899818dda06e6e6529f37c`；服务器与公网`index.html` SHA-256均为`197bcc3576246cf7fb089fd15a4d43220385b511ed3ad5621828195566670469`。
- 部署前生成本地加密备份`hotel_ai_os-daily-20260915T150014+0800.dump.enc`，密文与校验文件均为`root:root:0600`，密文SHA-256为`a773abfcdb44fa7524e7b17959856cfe65c7b97da78a22450b242f91192ecc96`，脚本已完成解密比对和`pg_restore --list`可恢复性验证。本次无数据库迁移，Flyway JAR/数据库均为V53，失败迁移0。
- 13:43后服务器备份与全局健康脚本新增异地挂载强制检查，但`/etc/hotel-ai-os/backup-offsite.env`和远程挂载尚未配置；全局健康脚本还保留旧常量Flyway V22，与生产V53不一致。首次激活因此触发自动回滚到`20260915-pilot8-01733b0`，旧版本服务健康保持UP。经用户明确授权，仅排除该外部存储门禁，以候选哈希、应用健康、Flyway V53/53、失败迁移0、私有端口隔离、本地备份校验、Core API/Caddy、公网200、未授权401和旧版本回滚保护重新激活成功。异地备份与健康脚本常量必须后续修复，不得把本次例外解释为门禁已关闭。
- 部署后Core API与Caddy均为`active`，健康状态为`UP`；退出接口公网返回204并下发清除`Secure; HttpOnly; SameSite=Lax`企微Cookie，公开首页和服务器`index.html`哈希一致，激活后Core API日志未出现warning/error/exception。
- 验证：后端全量264项通过（0失败、0错误、3跳过），Web全量108项、TypeScript及Pilot生产构建通过。Codex内置浏览器组件版本不完整，真实企业微信客户端内的任务点击免登录仍需测试账号最终复验。
- 本次仍为内部Pilot增量部署，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态。

## 15. 2026-09-15 企业微信任务链接顶层换票修复

- 功能提交为`284f2752156a596e3b5d9a19fd3dcefa89520e41`，已快进合并并推送至GitHub `lzy27272/AIzhongtai:main`。生产审计已证明OAuth授权与一次性交换完成；本次进一步修复部分iOS企业微信WebView不稳定接收异步换票响应Cookie，导致每个新任务仍进入账号密码登录页的问题。
- 企微任务入口改用浏览器顶层表单POST换票；服务端消费一次性码后在303跳转响应设置Secure、HttpOnly、SameSite=Lax的`__Host-hotel_ai_wecom_session`，再跳回经过白名单校验的任务Hash。前端仅消费并立即清理`wecom_session=1`非敏感标记，JWT不进入URL或Web存储；旧JSON接口继续兼容。
- 云端不可变发布版本为`20260915-pilot8-284f275`。后端JAR SHA-256为`eeaeb2c3319b221151e331e4bbc9baba91c4d159a4c59d336d9b42a364b56a88`；Web ZIP SHA-256为`3450d74b8d9357cb7a6704f0494960e6e47b5ec97ad5070aa717fd86587bc09b`；服务器与公网`index.html` SHA-256均为`02fe035692aea48ceee5d6f24a2578e57a8b19d4b067868ed6273370d047b841`。
- 部署前生成加密PostgreSQL备份`hotel_ai_os-daily-20260915T185329+0800.dump.enc`，权限为`root:root:0600`，脚本已执行`pg_restore --list`，密文SHA-256为`1f68a3828290ec62a90f44e8d049c93ebcdba02a7f7e197d9cc9f90835f25ff6`且校验通过。无新增数据库迁移，Flyway保持V53且失败迁移0。
- `/etc/hotel-ai-os/backup-offsite.env`与异地挂载仍未配置，备份服务在本地备份完整性检查完成后按预期以`OFFSITE_BACKUP_CONFIG_REQUIRED`退出。本次沿用用户明确授权的内部Pilot例外，通过本地加密备份、候选哈希、服务健康、Flyway V53、私有端口隔离、公网200/401和旧版本自动回滚保护完成切换；异地备份门禁仍保持未关闭。
- 部署后Core API、Caddy、PostgreSQL、fail2ban和ClamAV均为`active`，运行进程目录与两个`current`软链接均指向新release，健康状态为`UP`；公开首页返回200，未授权身份接口返回401，顶层换票端点空凭证返回400，公开主资源包含新换票路径和会话标记，启动窗口无warning/error/exception。
- 验证：后端全量267项通过（0失败、0错误、3跳过），Web全量111项、TypeScript及Pilot生产构建通过；5个发布输入扫描63个归档、23,740个条目，敏感信息0命中、0错误。真实企业微信客户端任务免登录仍需测试账号点击至少两个不同任务完成最终复验。
- 本次仍为内部Pilot增量部署，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态。

## 16. 2026-09-16 企业微信任务免重复登录双通道修复

- 功能提交为`6f746bbb322a4d6f4855e0e5ab1288c29f372b42`，已快进合并并推送至GitHub `lzy27272/AIzhongtai:main`。生产OAuth审计显示用户最近两次任务点击均已完成供应商授权和服务端一次性交换，重复进入账号密码页的实际失败点是部分iOS企业微信WebView不稳定保存或回传HttpOnly会话Cookie。
- 修复后，OAuth回调只签发未消费的一次性交换码并302跳转到同源`/wecom-auth`；Web以同源JSON请求消费该码，同时建立仅存在当前页面内存中的JWT会话，服务端仍下发Secure、HttpOnly、SameSite=Lax Cookie作为辅助通道。即使WebView丢弃Cookie，当前任务也可依靠内存会话直接进入；一次性码不写入Web存储，使用后立即失效，任务目标继续经过白名单校验。
- 云端不可变发布版本为`20260916-pilot8-6f746bb`。后端JAR SHA-256为`04077d642292171cea317075375570f5cd7553ed80b243d338b361ae45e1f5f8`；Web ZIP SHA-256为`a7c3077411e3c22e42efbe90fe71e7eb579fbff24c6120b1ccc9f5cdc2b58a8b`；服务器与本地Web `index.html` SHA-256均为`64c66cc659b6f8096549c5680e757d88f8ec1171e88e44695a7ad248d7f921fe`。
- 部署前生成并校验本地加密PostgreSQL备份`hotel_ai_os-daily-20260916T015346+0800.dump.enc`，权限为`root:root:0600`，密文SHA-256为`92f7eeaa68c84aab4f067cc639896291cc5cee88904d72df5b57386523dd01f0`；备份脚本已完成解密后的`pg_restore --list`可恢复性检查。异地备份仍未配置，服务按预期以`OFFSITE_BACKUP_CONFIG_REQUIRED`退出，本次继续沿用已明确批准的内部Pilot例外，不表示异地备份门禁已满足。
- 部署采用旧版本软链接自动回滚保护，最终Core API和Caddy均为`active`，健康状态为`UP`，Flyway保持V53且失败迁移0；公网首页返回200，未授权身份接口返回401，`/wecom-auth`返回200，企微OAuth启动入口返回302。线上Web制品已确认包含JSON交换路径且不再包含旧顶层表单换票路径，新服务启动窗口内warning及以上日志为0。
- 验证：后端全量268项通过（0失败、0错误、3跳过），Web全量113项及企微入口专项7项通过，TypeScript与Pilot生产构建成功；5个发布输入敏感信息扫描0命中、0错误。Codex内置浏览器组件缺失，无法代替真实企业微信客户端完成最终账号点击；测试账号仍需从企业微信点击任一任务确认无需输入中台账号密码。
- 本次仍为内部Pilot增量部署，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态。

## 17. 2026-09-16 企业微信会话绑定RLS事务修复

- 功能提交为`01daaf31878ee910914a5e5964b835007b6cfb6f`，已快进合并并推送至GitHub `lzy27272/AIzhongtai:main`。用户视频确认从任务返回后再次进入会落到本地账号登录页；服务端生产只读复现实验进一步证明，同一绑定查询在事务外受强制RLS影响返回0行，在设置租户上下文的同一事务内返回5行。
- 根因是`WeComSessionBindingGuard.requireActive`未建立事务，而其对同类`currentVersion`方法的内部调用不会经过Spring事务代理；`set_config('app.tenant_id', ..., true)`因此在下一条RLS查询前已随自动提交失效，合法企微Cookie会话被错误判定为绑定失效并返回401。修复后公开入口方法建立短生命周期只读事务，使租户上下文与绑定查询位于同一数据库事务；绑定版本不符、暂停或撤销仍关闭式拒绝。
- 云端不可变发布版本为`20260916-pilot8-01daaf3`。后端JAR SHA-256为`0038ffb1f8eca96ce833dad1a4e4a88290bfddbcce559831b316f91390b52b34`；Web沿用上一稳定制品，服务器与公网`index.html` SHA-256均为`64c66cc659b6f8096549c5680e757d88f8ec1171e88e44695a7ad248d7f921fe`。
- 部署前生成并独立校验本地加密PostgreSQL备份`hotel_ai_os-daily-20260916T093333+0800.dump.enc`，密文及校验文件均为`root:root:0600`，密文SHA-256为`09ffdb6210869258316a890da51b7a3ea20fc34a3f47690b5291edc81b546873`；解密后的`pg_restore --list`通过，临时明文随后删除。异地备份仍未配置，服务按预期以`OFFSITE_BACKUP_CONFIG_REQUIRED`退出，本次继续沿用已明确批准的内部Pilot例外，不表示异地备份门禁已满足。
- 验证：新增真实PostgreSQL、非超级用户、`NOBYPASSRLS`及强制RLS集成测试会在旧实现失败、修复后通过；企微绑定专项4项通过，后端全量269项通过（0失败、0错误、3跳过），Flyway V1→V53通过。生产JAR扫描61个归档、23715个条目，敏感信息0命中、0错误。
- 部署后Core API、Caddy、PostgreSQL、fail2ban和ClamAV均为`active`，运行进程目录及两个`current`软链接均指向新release，健康状态为`UP`；Flyway数据库版本V53且失败迁移0，Core API和PostgreSQL继续只监听`127.0.0.1`，公网首页返回200，未授权身份接口返回401，激活窗口warning/error/exception为0。旧release `20260916-pilot8-6f746bb`保留为回滚目标，本次未触发回滚。
- Codex内置Browser组件缺失，且不从生产进程提取认证密钥伪造用户会话，因此无法代替真实企业微信客户端完成最后一步交互验收。测试账号仍需连续执行“打开任务→返回→再次打开同一或另一任务”，确认第二次直接进入任务且不出现账号密码页。
- 本次仍为内部Pilot增量部署，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态。

## 18. 2026-09-16 董事长与管理岗位任务下达入口修复

- 功能提交为`bbb4592f8b17a47acde949302d3b336aa956dafa`，已快进合并并推送至GitHub `lzy27272/AIzhongtai:main`。董事长工作台按服务端能力进入专用高管交办流程；其他具备`task.create`与`task.dispatch`的管理岗位不再因缺少可选的`template.read`或`standard.read`而阻断任务下达，可选权限不存在时前端不会请求对应接口，也不会展示对应控件。
- 云端不可变发布版本为`20260916-pilot8-bbb4592`。后端没有生产代码变化，继续复用已包含企微RLS事务修复的稳定JAR，SHA-256为`0038ffb1f8eca96ce833dad1a4e4a88290bfddbcce559831b316f91390b52b34`；Web发布包SHA-256为`6d8801e3938b7b4568839c18f523bbf1d473dcdd4fb1c0c7a3ebde9b8a1f96ac`；服务器与公网`index.html` SHA-256均为`c03623bd5fdb5bcc8ff50c7c0297e557eb564090ee7d6ac085b263d77169a417`。
- 部署前生成并校验本地加密PostgreSQL备份`hotel_ai_os-daily-20260916T143643+0800.dump.enc`及运行配置备份`...T143643+0800.env.enc`，两者均为`root:root:0600`且SHA-256清单校验通过。异地备份仍未配置，备份服务在本地备份完成后按预期以`OFFSITE_BACKUP_CONFIG_REQUIRED`退出；本次继续沿用已批准的内部Pilot例外，不表示异地备份门禁已满足。
- 董事长高管任务能力通过独立、最小化环境文件启用：仅设置`GROUP_MANAGEMENT_EXECUTIVE_TASKS_ENABLED=true`和目标租户白名单；未启用工作计划或提醒Worker，也未修改既有`core-api.env`。生产只读核验显示2个有效董事长任职，已发布董事长岗位方案包含`executive-task.read/assign`，不包含通用`task.create/task.dispatch/template.read`。
- 部署后Core API、Caddy和PostgreSQL均为`active`，运行进程目录及两个`current`软链接均指向本次release，健康状态为`UP`；Flyway保持V53且失败迁移0，Core API与PostgreSQL继续只监听`127.0.0.1`。公网首页返回200，未授权`/api/v1/iam/me`及`/api/v1/executive-tasks`均返回401，启动窗口未发现warning/error/exception。
- 验证：Web全量118项、聚焦20项、TypeScript及Pilot生产构建通过；高管任务后端集成测试6项通过。Codex内置Browser运行组件缺失，未伪造生产用户会话，因此董事长专用按钮和普通管理岗任务表单的登录后点击仍需由业务测试账号完成最终复验。
- 本次仍为内部Pilot增量部署，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态。

## 19. 2026-09-17 当日工作自动汇总、审核督办与评估建议

- 功能提交为`d97cc2ea648a09c97640e37ae962202645e73f3a`，已快进推送至GitHub `lzy27272/AIzhongtai:main`。门店当日已提交工作会自动投影到当日日报草稿：待审核记录保持`已提交待审核`，审核通过后才转为完成或异常，驳回时移除失效投影；日报重复聚合保持幂等，并为草稿执行自愈同步。
- 截止时间后仍存在待审工作或待审日报时，系统按最新审核状态向直属主管的个人企业微信发送督办；已处理记录不会被旧队列重复提醒，待审数量变化时可生成新提醒。工作审核与日报审核提醒仅发个人企微，未放宽到群聊渠道。
- 日报评估层基于已提交工作中明确的异常、问题、后续动作及表单问题字段生成可追溯的风险摘要、改进建议和事实链；“无异常/正常”等否定信息会被过滤。当前为内部确定性规则引擎，未接入外部大模型，也没有对外传输酒店工作数据。
- 云端不可变发布版本为`20260917-pilot8-d97cc2e`。后端JAR SHA-256为`5f6e75a460fd9c1f157aa6f6f59fbada2a303425dca53b2c2b9febc9e5ad925b`；Web ZIP SHA-256为`1e2b0787ef8a6f3eb124ba3d4c867ebd530f28379f4d2bda4aadd7bd1c2ed38e`；服务器与公网`index.html` SHA-256均为`116d30a87b84720ffc861cffccc4bf184a0d108c63a0a1ac3967925c4a351144`。
- 部署前生成并独立校验数据库加密备份`hotel_ai_os-daily-20260917T083802+0800.dump.enc`和运行配置加密备份`sifangguan-ota-runtime-daily-20260917T083802+0800.env.enc`，两者及校验文件均为`root:root:0600`，SHA-256分别为`c61024edfcc0cb5918ffb21006a0a53a505fe1bcd6b8e82bb63709c82effd8f2`和`15c2d8ac4fb5ed093fe025afcd094f0f44183d117b0cc65f3618af243e307828`；备份脚本已完成解密比对及`pg_restore --list`恢复性检查。异地备份仍未配置，服务在本地备份完成后按预期以`OFFSITE_BACKUP_CONFIG_REQUIRED`退出；本次继续沿用已批准的内部Pilot例外，不表示异地备份门禁已满足。
- 本次无新增数据库迁移。激活前后均通过候选JAR与数库全量Flyway版本及校验和一致性校验，最终JAR/数据库均为V53，失败迁移0。部署使用旧版软链接自动回滚保护，本次未触发回滚，上一版`20260916-pilot8-bbb4592`仍保留为回滚目标。
- 验证：Web全量120项、TypeScript及Pilot生产构建通过；后端日报聚合、督办发送专项7项通过。远端全量Maven验证的唯一错误是临时构建包未包含`docs/openapi.yaml`，实际代码测试无断言失败；该契约文件本轮未修改。5个发布输入扫描63个归档、23,744个条目，敏感信息0命中、0错误。
- 部署后Core API、Caddy、PostgreSQL、fail2ban和ClamAV均为`active`，运行进程目录及两个`current`软链接均指向新release，健康状态为`UP`；Core API与PostgreSQL继续只监听`127.0.0.1`。公网首页返回200，未授权`/api/v1/iam/me`及`/api/v1/executive-tasks`均返回401，激活窗口warning/error/exception为0。
- 生产进程已确认管理自动化Worker、企微发送Worker及Pilot租户白名单均已启用；现有7条启用的日报投递策略，开始时间为18:55、20:00和22:00。上线后Worker已回填前一营业日7份日报草稿，生成2条系统预填的待审项与7条可用规则评估。已生成1条日报待复核督办，但该条当前直属审核人账号未有有效企微绑定，因此可在中台通知中查看，暂不会发送个人企微；行政人事补齐该主管绑定后，后续新督办才能经个人企微送达。
- Codex内置Browser运行组件缺失，本轮无法进行登录后真实业务账号视觉验收，也未改用未经授权的浏览器替代；业务侧仍需在晚间汇总时段复验真实门店日报、主管个人企微提醒及评估事实链展示。
- 本次仍为内部Pilot增量部署，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态。

## 20. 2026-09-17 V54/V55 稳定性、数据留存与绩效快照增量发布

- 发布提交为`8b62f52364021d145e76c8e6a2574b577adf5d7b`，已推送至GitHub `lzy27272/AIzhongtai:main`。GitHub Actions运行`35172735966`三项门禁全部通过：Core API 280项（0失败、0错误、3跳过）、Web 133项（全部通过）并完成TypeScript/Vite生产构建、OTA平台113项（0失败、0错误、1跳过）。
- 云端不可变发布版本为`20260917-pilot8-8b62f52`。后端JAR SHA-256为`96efb18987d27a21396e63e2451ecbba087802aa1e1c4d9187843c0c9abc679b`；服务器、本地构建与公网`index.html` SHA-256均为`c98a9ba761c2fd56f63fcf874ec22f55271589fd39fca5c6bd23bc58e14dd05c`。公网逐一回读26个Web文件并与服务器文件比对，26/26一致。
- 部署前生成并验证数据库加密备份`hotel_ai_os-daily-20260917T100815+0800.dump.enc`和运行配置加密备份`sifangguan-ota-runtime-daily-20260917T100815+0800.env.enc`，两者及校验文件均为`root:root:0600`；SHA-256分别为`a6ca8e9808a91336a4752afc1c1922b2d4fc1571049f705b908c3f76869e34b6`和`34845b28cf91677e66182223f4f441d0b57c035218f5b5be72d5ae288a2df737`。备份脚本已完成解密后的`pg_restore --list`验证；异地备份仍未配置，服务在本地备份成功后按预期以`OFFSITE_BACKUP_CONFIG_REQUIRED`退出，本次继续沿用已批准的内部Pilot例外。
- Flyway由V53迁移至V55，V54/V55均成功且失败迁移为0；新动态健康脚本已安装，候选JAR与数据库V1至V55的全部迁移版本和校验和一致。全局健康检查的应用、迁移、私有端口、磁盘和本地备份门禁均通过，最终只停在尚未配置的异地备份门禁。
- V55建立租户级数据留存策略和不可覆盖的员工日/月绩效快照。当前策略为图片5天、文档30天、员工/PMS/OTA/KPI汇总36个月、原始载荷90天；图片清理开关为`false`，清理Worker使用默认关闭配置。上线前后附件`DELETED/deleted_at`数量均为0，本次迁移和部署没有删除任何历史图片或文件。
- 集团管理员被纳入账户级系统管理员身份解析，但实际可见组织范围仍由`role_assignment`控制；修复了其无员工任职时数据留存接口被误判403的问题。Linux CI与生产初始化脚本统一安装PDF所需文泉驿TrueType字体，两个投资PDF渲染测试均已恢复通过，现有云机字体文件也已核验存在。
- 部署后Core API、Caddy、PostgreSQL、fail2ban和ClamAV均为`active`；运行进程目录及Core/Web两个`current`软链接均指向新release，内网健康为`UP`。受保护的数据留存策略、清理预览和团队工作台接口未登录均返回401，新进程激活窗口Core API warning/error/exception与Caddy error均为0，根分区使用率14%（148GB可用）。
- 当前绩效快照由工作台汇总查询触发并按内容哈希幂等、版本化写入，尚未增加独立的全租户定时快照任务；结构化工作记录仍持续保留。Codex内置Browser运行组件缺失，因此本轮不宣称登录后数据留存页面或真实岗位工作台的视觉交互验收，需由业务测试账号完成最终UAT。
- 本次仍为内部Pilot增量部署，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态。

## 21. 2026-09-17 服务端身份解析与岗位工作台路由修复

- 发布提交为`5012c379410b2143e591be3b6b164ae702dfef7b`，已快进合并并推送至GitHub `lzy27272/AIzhongtai:main`。GitHub Actions运行`35178033307`全部通过：Core API、Web和OTA平台三项作业均为`success`；Web本地141项测试、TypeScript和Pilot生产构建再次通过。
- 本次修复生产Web普通入口在服务端身份尚未解析或已失效时错误恢复浏览器缓存岗位的问题：真实会话必须先通过`/api/v1/iam/me`，401会清理Bearer与服务端Cookie会话，并通过会话代次避免旧请求清除新登录；Pilot发布和云端安装器均强制校验`X-Hotel-AI-Authorization`认证契约。
- 云端不可变发布版本为`20260917-pilot8-5012c37`。后端无代码变化，复用已验证JAR，SHA-256为`96efb18987d27a21396e63e2451ecbba087802aa1e1c4d9187843c0c9abc679b`；服务器、本地构建与公网`index.html` SHA-256均为`4b9667b20e492266b8f003e9d388aeb3284e8b854c5dfff55600067dc201b686`，主JS为`fdbb3b0b15f7a17d3a2219f7b2ae561fcf9528e1debcf436a369812c207e51f5`，主CSS为`a7b23517a2fd3c4324f57ae01fd55f04c74c711a57d8a354995a80ae8fa8550d`。
- 部署前生成并验证数据库加密备份`hotel_ai_os-daily-20260917T113414+0800.dump.enc`和运行配置加密备份`sifangguan-ota-runtime-daily-20260917T113414+0800.env.enc`，两者及校验文件均为`root:root:0600`；SHA-256分别为`f7e2ff2a16311020ebb9d2df08173ea78ad081f028d1bbd79eeab289a73395fd`和`b7d12a00eed336842d174b07dd376118b51e40c0c7d1b8e4064a92e21f51455f`。异地备份仍未配置，服务在本地备份成功后按预期停在`OFFSITE_BACKUP_CONFIG_REQUIRED`；本次继续沿用已批准的内部Pilot例外，不表示异地备份门禁已满足。
- 第一次激活在切换前因备份目录仅root可读导致前置断言退出；当时新release目录尚未创建，两个软链接仍指向旧版且服务保持`active`。门禁改为使用最小`sudo test/stat/sha256sum`后重新执行，安装、迁移、重启及自动回滚保护全部通过，本次未触发回滚，旧版`20260917-pilot8-8b62f52`完整保留。
- 部署后Core API、Caddy、PostgreSQL、fail2ban和ClamAV均为`active`，Core/Web软链接与Core进程目录均指向新release，内网健康为`UP`；Flyway JAR与数据库全量版本和校验和一致，最新V55、失败迁移0。Core API与PostgreSQL继续只监听`127.0.0.1`，根盘使用率14%、可用148GB。
- 公网首页返回200，公网与内网未授权`/api/v1/iam/me`均返回401，格式正确但不存在的账号登录返回401；公开主资源确认`AUTH_MODE=bearer`并包含新认证请求头。激活窗口Core API warning/error/exception与Caddy error均为0，26个Web文件已完成服务器和公网关键资源哈希回读。
- Codex内置Browser因本机已安装客户端与服务组件版本不一致而无法启动，本轮不宣称完成登录后的真实账号视觉点击验收；已由SSR渲染测试证明未登录显示登录页、服务端身份解析期间不显示缓存工作台。业务侧仍需使用至少一个普通员工账号和一个多岗位管理账号完成最终UAT。
- 本次仍为内部Pilot增量部署，不改变TECH-V0.2正式版本的`Unreleased / NO-GO`状态。
