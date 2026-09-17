# Pilot 分级 CI 与受控部署

## 目标与边界

本方案优先缩短可证明安全的日常 Web 页面修改等待时间，但不降低生产安全边界：

- `main` 的 push，以及目标为 `main` 的 Pull Request，自动执行变更分类。
- Web 与后端验证在需要时并行运行，pnpm 与 Maven 使用 GitHub Actions 缓存。
- 一个提交只构建一次；最终制品以完整 Git Commit SHA 命名并绑定分类计划、文件哈希和迁移指纹。
- CI 永远不会自动连接云端。
- 云端快速部署只能人工触发 `.github/workflows/pilot-deploy.yml`，并经过 GitHub `pilot` Environment 审批。
- `FULL` 永远不能进入快速部署；数据库、基础设施、权限、身份、依赖、CI、运维工具以及不能确定的修改全部回到原有完整发布门禁。

用户原有操作习惯不需要改变。完成修改后仍然提出“合并主线、推送、部署”即可；执行者根据 CI 的分类结果触发对应发布，不需要用户判断发布类型。

## 工作流

### 自动 CI：`ci.yml`

`tools/deployment/Get-PilotDeploymentPlan.ps1` 是唯一分类依据。工作流只接受以下严格合同：

| 通道 | 允许范围 | CI | 可否快速部署 |
| --- | --- | --- | --- |
| `WEB_ONLY` | 明确白名单中的 Web 源码、样式、静态资源及 Web 测试 | Web 测试、类型检查、Pilot 构建 | 可以，只切换 Web 不可变目录 |
| `BACKEND` | **冻结**。策略版本 3 不产生此通道；核心运行时语义无法仅靠关键词可靠证明安全 | 不适用 | 不可以；一律升级为 `FULL` |
| `FULL` | Web 与 Core 混合、数据库、基础设施、配置、权限、安全、依赖、CI、工具、其他应用、文档和未知路径 | Web、Core API、OTA 全量并行验证 | 不可以，必须使用原有完整发布门禁 |

分类器输出非 `PASS`、JSON 无效、字段缺失、未知通道、空变更集或 Git 范围不能解析时，CI 立即失败，不会自动降级成危险的快速部署。

CI 生成以下制品：

- `pilot-plan-<40位SHA>-<CI run id>-<run attempt>`：部署分类计划。
- `pilot-web-component-<40位SHA>-<CI run id>-<run attempt>`：已测试的 Web 构建中间制品，短期保留。
- `pilot-core-component-<40位SHA>-<CI run id>-<run attempt>`：完整发布使用的 Core API 构建中间制品，短期保留。
- `pilot-release-<40位SHA>-<CI run id>-<run attempt>`：唯一可进入部署审批的最终制品，保留 30 天。

最终制品至少包含：

- `deployment-plan.json`；
- `release-manifest.json`；
- 按通道包含 `web.zip`，以及/或 `core-api.jar` 与 `run-migration-once.sh`。

`release-manifest.json` 绑定源提交、分类计划 SHA-256、每个文件的 SHA-256 和大小。Web 清单额外绑定压缩包哈希、`index.html` 哈希，以及解压后整棵 Web 文件树的 `treeSha256`；文件树使用 `sha256(sorted-path-colon-size-colon-content-sha256-v1)`，即按相对路径排序后，把每个文件的路径、大小和内容 SHA-256 纳入总指纹。后端清单额外绑定 JAR 中全部数字 Flyway 迁移的版本和内容指纹。

### 人工部署：`pilot-deploy.yml`

人工部署必须提供：

1. 已进入 `main` 的完整 40 位 Commit SHA；
2. 勾选人工确认；
3. 通过 GitHub `pilot` Environment 的 Required reviewer 审批。

工作流不会重新构建。它只接受同一提交的成功 `main` push CI run，并固定该次 attempt 的 Artifact ID、API SHA-256 digest、名称和 CI run ID。审批前和审批后都会按同一 Artifact ID 重新读取元数据、下载并验证归档 digest，再校验提交、通道、分类计划及文件哈希；批准后才开放 Environment secrets。CI run 在审批等待期间被 rerun 也不能替换已批准制品。

部署分类不信任待部署提交中自带的旧分类器。工作流必须从**触发时当前 `main`** 固定一份可信部署策略，使用其中的 `Get-PilotDeploymentPlan.ps1` 重新分类目标提交和完整累计区间，并要求分类计划与清单同时绑定当前策略版本 `3` 和该分类器文件的 SHA-256。若审批期间 `main` 已前进，工作流会停止，必须从新的 `main` 重新触发和审批。分类策略或分类器内容更新后，使用旧策略生成的历史制品会因策略版本或分类器哈希不匹配而被阻断；不得绕过，应部署一个已包含当前策略并由当前 CI 生成制品的新 `main` 提交，或按 `FULL` 门禁发布。

审批后、上传任何文件前，工作流还会通过 root-owned 固定入口的 `STATUS` 命令读取服务器状态文件 `/var/lib/hotel-ai-os-deploy/deployed-source-commit`，发布账号不能直接读取、覆盖或删除该文件：

1. 文件必须只有一个严格的 40 位 Git SHA；
2. 该 SHA 必须是目标 SHA 的祖先；
3. 工作流重新分类“云端已部署 SHA 到目标 SHA”的完整累计区间；
4. 累计通道和所有安全布尔值必须与制品分类计划一致，否则升级为 `FULL` 并停止。

这个门禁避免云端跳过了一个中间 `FULL` 提交后，后续纯页面提交被错误地当作 `WEB_ONLY`。服务器安装脚本在取得发布锁后还会再次核对预期基线 SHA，防止读取状态与原子切换之间发生并发发布。

部署行为如下：

- `WEB_ONLY`：上传 `web.zip`，服务器校验压缩包、首页和解压后完整文件树哈希，安全解压、原子切换 Web 软链接，并从本机 HTTPS 入口核对实际返回的首页。
- `BACKEND`：当前冻结，不向 GitHub 部署身份开放；所有 Core API 运行时、资源、配置和构建变化进入 `FULL`。
- `FULL`：工作流只写出“需要完整门禁”的摘要，不读取 SSH secrets、不连接服务器，也不修改云端。

快速安装失败时，服务器脚本回切原软链接并验证恢复状态。只有新版本健康检查成功后，它才原子更新 `deployed-source-commit`。

上传目录的生命周期由 Workflow 与服务器安装器共同做精确、受边界约束的清理：Workflow 在 `/tmp/hotel-ai-os-fast-release/<release-id>` 创建唯一的 `0700` staging 目录并立即登记退出清理；上传或 SSH 中途失败时，只删除五个白名单文件并 `rmdir` 该精确目录。安装器取得发布锁后，把整个目录原子移动到 root-only 的 `.quarantine`，立即收回目录和叶子文件的 root 权限，再按清单大小进行有界复制，并在 root-owned 快照上复核精确大小与 SHA-256。无论成功或失败，大型隔离制品和快照都会安全清理，诊断依赖审计日志，防止重复失败耗尽磁盘。

如果服务器已经记录为目标 SHA（例如安装成功但最后一次 SSH 回包丢失后重试），工作流不会把空 Git 区间重新分类或重复切换。它会调用固定入口的 `VERIFY WEB_ONLY` 模式，同时核对当前线上 `index.html`、完整文件树哈希和 HTTPS 响应；验证通过即按幂等成功处理。

## GitHub 首次配置

在仓库 Settings 中创建名为 `pilot` 的 Environment，并完成以下设置：

1. 配置至少一名 Required reviewer；条件允许时启用 Prevent self-review。
2. Deployment branches 只允许 `main`。
3. 在确认以上保护实际生效后，添加 Environment variable：
   - `PILOT_ENVIRONMENT_PROTECTED=true`
   - `PILOT_FAST_STAGE_ROOT=/tmp/hotel-ai-os-fast-release`
4. 添加以下 Environment secrets，禁止写入仓库、Workflow 文本、Artifact 或日志：
   - `PILOT_SSH_HOST`
   - `PILOT_SSH_USER`（值必须严格为 `hotel-ai-deploy`）
   - `PILOT_SSH_PRIVATE_KEY`
   - `PILOT_SSH_KNOWN_HOSTS`

`PILOT_SSH_KNOWN_HOSTS` 必须由独立可信通道取得，不能在部署期间使用 `ssh-keyscan` 临时信任目标。SSH 私钥必须与下节一次性配置时提供的公钥配对，并且只供独立、最小权限的 `hotel-ai-deploy` 身份使用；不得复用个人日常密钥、`root` 或具有宽泛 sudo 权限的 `ops` 身份。

### 服务器一次性启用条件

首次启用快速通道之前，必须先通过现有完整门禁完成一次健康的基线发布。服务器管理员准备与 GitHub Environment 私钥配对的 OpenSSH 公钥文件；该文件必须为 `root:root`、`0600`、普通非符号链接且只包含一把有效公钥。公钥和已审核的 `61/62` 脚本都必须位于整条父目录链由 `root:root` 拥有且组/其他用户不可写的位置；Bootstrap 会固定已打开文件描述符并复制到 root-only 快照后再使用。然后以 root 身份执行：

```bash
/absolute/path/to/infra/production/bootstrap/62-configure-fast-lane-deployer.sh \
  /root/pilot-fast-deploy.pub \
  <已验证完整发布的40位小写Git提交SHA>
```

`62-configure-fast-lane-deployer.sh` 会一次性完成以下受控配置：

1. 创建专用系统用户和组 `hotel-ai-deploy`，要求其没有任何附加组、密码保持锁定，并以 `restrict` 选项安装唯一 SSH 公钥；其 home、`.ssh` 与 `authorized_keys` 始终由 root 控制；
2. 在与发布共用的独占锁内，把已审核的 `61-install-fast-lane-release.sh` 和最小 sudo 规则作为同一事务激活：切换期间先撤销专用 sudo 入口，任一步骤或受控信号失败都会把安装器、sudo 规则及既有 root-only 前版本副本恢复到原状态；原先没有安装器时失败会恢复为“仍不存在”，绝不会把陈旧 `.previous` 激活为当前命令。部署前 Workflow 会通过 `POLICY` 精确核对策略版本与固定入口 SHA-256；
3. 创建 `/tmp/hotel-ai-os-fast-release`（`root:hotel-ai-deploy`、`1730`）、其 `.quarantine`（`root:root`、`0700`）、`/var/lib/hotel-ai-os-deploy`（`root:root`、`0750`）和其中的 `staging`（`root:root`、`0700`）；
4. 在上述任何配置变更前，确认后端与 Web 的 `current` 都指向各自 release 根目录的直接子目录、两侧 root-owned `.source-commit` 都等于给定基线、Core 健康进程确实从后端 `current` 运行，并确认本机实际 HTTPS 响应逐字节哈希等于 Web `current/index.html`；全部通过后才以 `root:root`、`0640` 初始化 `deployed-source-commit`，若已有状态与给定基线不一致则拒绝覆盖；
5. 安装并用 `visudo` 校验最小 sudo 规则，只允许该用户以 root 调用固定入口的 `POLICY`、`STATUS`、`VERIFY WEB_ONLY` 和 `WEB_ONLY`。`BACKEND` 当前冻结且不授予；该身份不能取得任意 root shell、传入任意 root 环境，也不能以 root 运行完整发布或维护命令。

完成后必须执行 `sudo -l -U hotel-ai-deploy` 审计有效权限，并确认 GitHub 中的 `PILOT_SSH_USER` 正好为 `hotel-ai-deploy`。Workflow 会再次硬性校验用户名和固定 staging 根目录，使用 `root`、`ops` 或其他账号会直接失败。

固定入口采用位置参数，不接受 Workflow 通过 `sudo env` 注入任意 root 环境。快速安装接口分别为：

- `POLICY`：返回服务器固定入口策略版本和自身 SHA-256；
- `STATUS`：返回当前可信部署基线；
- `WEB_ONLY <release-id> <stage-root> <expected-source-commit> <source-commit> <web-sha> <web-size> <index-sha> <web-tree-sha>`；
- `VERIFY WEB_ONLY <source-commit> <index-sha> <web-tree-sha>`：只做线上身份、完整制品与健康核验。

发布 ID 同时包含目标 SHA、来源 CI run、部署 Workflow 的 `run_id` 与 `run_attempt`，因此失败重试不会复用已经创建的不可变目录。

Workflow 只向唯一 staging 目录上传制品和审计清单，绝不上传或执行目标提交中的安装脚本。固定入口、受控目录或状态文件缺失，或者其归属、权限、内容异常时，快速部署立即停止，必须由 root 管理员修复或重新运行完整门禁完成初始化。

本方案首次上线，以及以后 `61/62` 策略本身发生变化时，root 管理员先用**当前已验证线上基线 SHA**重新运行 `62-configure-fast-lane-deployer.sh`，再执行完整发布。`62` 与发布使用同一把锁；它会先验证两条线上 release 身份、Core 实际运行目录、HTTPS 实际内容和全部既有路径，再以可回滚事务更新安装器与 sudo 策略。基线不匹配或线上身份无法证明时，不会创建账号、目录或状态，也不会替换 SSH key、固定入口或 sudo 规则。

此后每一次 `FULL` 发布都必须走同一闭环。`60-install-release.sh` 在任何软链接变更前自动执行 `BEGIN_FULL`，哨兵同时绑定目标 commit、release ID 以及切换前的后端/Web 精确路径；其接口为：

```bash
60-install-release.sh \
  <release-id> <source-commit> \
  <core-api.jar-sha256> <run-migration-once.sh-sha256> \
  <index.html-sha256> <web-tree-sha256>
```

脚本会在复制前后同时核对 JAR、迁移运行器、`index.html` 与完整 Web 文件树，写入不可变 release 元数据并切换。完成既有的备份/恢复验证、迁移、服务启动与冒烟检查后，必须把 `100-final-audit.sh` 作为唯一正常收尾步骤；它从 root-owned 哨兵自行读取 commit 与 release ID，复核磁盘制品、Core 进程、实际 HTTPS 返回内容及系统审计后，自动调用 `MARK_FULL` 原子推进 `deployed-source-commit` 并清除哨兵。日常流程不再手工输入 `MARK_FULL` 参数。

若完整发布中途失败，快速和新的完整发布都会保持阻断：

- 两条线上软链接均未变化时，root 可用 `ABORT_FULL <commit> <release-id>` 验证切换前路径和健康状态后安全清除哨兵；
- 已完成安装但尚未最终审计时，用 `RESUME_FULL <commit> <release-id>`核对恢复上下文，继续服务启动、冒烟与 `100-final-audit.sh`；
- 只有一条软链接已变化时不得 `ABORT_FULL`，必须先由 root 修复为一致的目标或回退点，再继续审计。`hotel-ai-deploy` 永远不能运行这些维护命令。

未设置 `PILOT_ENVIRONMENT_PROTECTED=true` 时，即使有人点击了 Workflow，部署作业也会 fail closed。这个变量不是审批的替代品，只是防止 Environment 尚未配置完成时误上线；真正的审批由 GitHub Environment Required reviewers 强制执行。

## 分支保护建议

将下列 CI check 设为 `main` 的 Required status checks：

- `Classify deployment lane`
- `Verify CI and deployment policy contracts`
- `web`（保留原有 check 名称）
- `core-api`（保留原有 check 名称）
- `ota-platform`（保留原有 check 名称）
- `Assemble immutable Pilot release artifact`

条件作业不需要时会显示为 skipped。`Assemble immutable Pilot release artifact` 只在所有必需作业成功或按计划跳过后运行，是最终合并门禁。

首次合并本方案时，因变更包含 `.github/**`、`tools/**` 和 `infra/**`，分类结果应当是 `FULL`。这是预期的 fail-closed 行为；快速通道只会从后续符合白名单的提交开始生效。

## 日常操作

业务侧日常口令和操作习惯保持不变：仍然是“合并主线、推送、部署”。新增的一次性服务器配置、分类选择、制品完整性校验和快速/完整通道路由由发布流程承担，不要求业务用户判断使用 `WEB_ONLY` 还是 `FULL`。

1. 合并并 push 到 `main`。
2. 等待该 SHA 的 `Continuous integration` 成功。
3. 在 Actions 中打开 `Deploy immutable Pilot release`，输入完整 SHA 并勾选确认。
4. 审批人核对 SHA、通道、变更范围及 CI 结果后批准 `pilot` Environment。
5. 查看 Workflow Summary 中的发布 ID、通道和完整性结果。

如果制品超过 30 天已过期，并且该提交的计划仍绑定当前可信策略，可以重跑该提交原有的 push CI run；新的 `run_attempt` 会生成不同名称、ID 和 digest 的新制品，并需要重新触发部署审批。不能上传本地文件替换，也不能让部署阶段重新构建。如果分类器已经更新，则按前述规则部署包含当前策略的新 `main` 提交，或走 `FULL` 门禁。

## 存储监控与人工保留

快速通道使用不可变 release 目录，因此部署次数增加后磁盘占用会持续增长。安装器在 Web 发布前要求 release 文件系统至少有 2 GiB 可用空间，并在创建 root 快照前额外要求“制品大小 + 64 MiB”的空间；这只是最后一道阻断，不替代日常监控。运维应监控相关挂载点的可用容量和 inode，并定期清点：

- `/opt/hotel-ai-os/releases`：后端不可变版本；
- `/srv/www/hotel-ai-os/releases`：Web 不可变版本；
- `/tmp/hotel-ai-os-fast-release/.quarantine`：只允许存在发布期间的瞬时隔离目录；
- `/var/lib/hotel-ai-os-deploy/staging`：只允许存在发布期间的瞬时 root-owned 快照。

当前不配置 release 目录自动删除任务。清理必须由 root 管理员在维护窗口按人工保留策略执行并留下审计记录：始终保留当前软链接指向的版本、最近一个已验证回退点和仍在审计/观察期内的版本；只清理已确认不再被任何软链接引用、无需回退且超过组织保留期的目录。隔离区或 staging 在发布结束后仍有内容属于异常，应先核对日志和发布锁再处理。清理前后都要核对当前软链接、`deployed-source-commit`、服务健康和剩余空间；不得在部署锁被占用或发布进行中清理。

## 安全与回退原则

- 不允许给部署工作流增加 `push`、`pull_request`、`schedule` 等自动触发器；CI 中有合同检查阻止这一变更。
- 不允许绕过清单直接部署 Action 临时目录中的任意文件。
- 不允许使用分支名、标签或短 SHA 作为部署身份。
- 不允许使用目标提交自带的旧分类器替代触发时当前 `main` 的可信策略，也不允许忽略策略版本或分类器 SHA-256 不匹配。
- 不允许只按最后一个提交分类；实际部署必须覆盖服务器当前 SHA 到目标 SHA 的完整累计范围。
- 不允许从待部署提交的 staging 目录执行具有 root 权限的脚本；只能调用服务器预安装的 root-owned 固定入口。
- `BACKEND` 快速通道当前冻结；Core API 的任何运行时、资源、配置或构建变化均进入 `FULL`。
- 不允许在快速通道执行 Flyway、数据库备份替代操作或基础设施变更。
- `FULL` 继续执行现有的加密备份与恢复验证、迁移校验、完整安装、服务启动、冒烟测试和最终审计。
- 回退点是安装脚本切换前读取的不可变 release 目录，而不是重新构建旧提交。

## 验证

仓库内的分类器、清单生成器和服务器快速安装脚本各自包含合同测试。CI 还会检查：

- 部署 Workflow 仅有 `workflow_dispatch` 入口；
- 必须声明 `pilot` Environment；
- 必须保留 Environment 保护标记；
- 必须固定触发时当前 `main` 的可信分类策略，核对策略版本与分类器 SHA-256，并用该分类器重新分类；
- 必须读取固定的云端部署 SHA 并重验累计变更范围；
- Web 制品必须绑定并复核 `web.zip`、`index.html` 和完整文件树三个层级的哈希；
- SSH 身份必须为最小权限的 `hotel-ai-deploy`，且只能通过预安装 sudo 白名单调用固定入口；
- 必须调用服务器预安装的 root-owned 安装入口，禁止上传安装脚本；
- 必须明确阻断 `FULL`；
- Workflow 中不存在明显的嵌入式私钥或口令。

GitHub 在接受 Workflow 文件时完成 YAML 与 Actions 表达式语法校验；启用前还应在非生产分支执行一次 `FULL` 影子运行，并分别用一个纯 Web 提交验证制品与审批路径、用一个 Core API 提交验证 `FULL` 阻断路径。
