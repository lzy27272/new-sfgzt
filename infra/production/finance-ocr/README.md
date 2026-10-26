# 财务本地图片识别的启用与回退

该服务与中台部署在同一受控环境，通过 `127.0.0.1:8095` 通信，不建立公网识别入口，不向云 OCR 供应商传输凭证。Java 仍执行上传授权、安全扫描、原件完整性核对、发票数据库排重和审批。OCR 只提供待核对候选，不能代表税务验真或审批通过。

## 发布前检查

- Core API、Web、`V116__finance_local_ocr_results.sql` 与识别服务应使用同一待发布版本。识别结果追加存储，原件及已有提取元数据的不可变规则保持有效。
- 按常规发布流程完成数据库备份和恢复验证、迁移检查及相关回归。不要编辑已执行迁移或临时放宽原件保护触发器。
- 首先用合成凭证验证服务。真实样本须由资料所有者授权，在本环境处理；不得上传至开源项目公开演示或 issue。
- 模型与依赖在构建时准备齐全，核对 `apps/finance-ocr` 的版本、模型哈希与许可证说明。运行时不下载模型。
- 上线前核实服务器剩余内存和 CPU；下面提供的内存上限为 2GB，需通过实际票据及并发验收，不能把本机性能当成云服务器性能。

## 启动方式：选择其一

### 正常生产发布：同提交 CI 离线包与固定 FULL 入口

生产发布采用 systemd。`finance-ocr.zip` 必须来自同一提交的成功 CI 制品，包含 Python 3.12 / Linux x86_64 固定 wheel、模型哈希、源码、逐文件清单和 Linux 合成验收证据。线上不下载依赖或模型。外层发布清单的 ZIP、包内清单、requirements 三个 SHA 都须匹配；仅在 JSON 中声明提交号不能证明来源，来源由 CI 制品身份与完整发布入口核对。

管理员首次部署前将同提交、已核对哈希的以下工具安装到固定 root 所有、不可由部署账号修改的路径。`hotel-ai-deploy` 只能调用完整发布协调器，不获得这些工具或任意 shell 的独立 sudo 权限：

- `/usr/local/sbin/hotel-ai-os-finance-ocr-release`：本目录同名脚本，0755。
- `/usr/local/lib/hotel-ai-os/finance-ocr/`：`release.py`、`probe.py`、本目录的 `hotel-ai-os-finance-ocr.service`，以及 `tools/deployment/finance_ocr_bundle.py`，文件 0644，目录 0755。

候选包内的 service 必须与管理员安装的固定模板逐字一致。即使上传者自行重算了包哈希，也不能通过上传 systemd 配置让候选代码以 root 执行。创建 venv、离线 pip、首次导入、OCR 和合成探针均以 `hotelai` 运行；root 只核验、复制受控文件及管理固定服务。系统需事先具备 `/usr/bin/python3` 3.12、`venv`/`ensurepip` 与 ONNX Runtime 的必要系统库；缺失时在预检阶段失败，不由安装 helper 联网补包。

完整发布协调器使用以下协议，参数只有受控发布编号和完整提交 SHA，不接受任意安装路径或命令：

1. `PREPARE <release-id> <source-commit>`：读取 root 私有 `/tmp/hotel-ai-os-release/{release-manifest.json,finance-ocr.zip}`，离线安装到 `/opt/hotel-ai-os/finance-ocr/releases/<release-id>`，在 `127.0.0.1:8096` 临时预热并实际识别内存中的合成图片。当前 Core 与 8095 服务保持运行。
2. 完成常规数据库及原件备份、配对恢复、隔离升级演练等门禁，再停止 Core。`60-install-release.sh` 保留原六个参数；检测到 OCR 制品时必须通过 `ASSERT_PREPARED`，将 `.finance-ocr.json` 绑定到新 Core 版本。已有同提交/同编号 FULL 标记时使用严格 `RESUME_FULL`，不覆盖标记。
3. `ACTIVATE <release-id> <source-commit>`：要求 Core 已停止，冻结旧 worker 指针、服务、启用状态和两个环境文件；原子换指针、启动8095并做真实 HTTP 识别烟测，再原子写 Core 的三个 OCR 配置。随机 token 只在服务器首次生成，私有文件保存，不进入制品、命令参数或日志。
4. 按完整流程完成迁移、启动 Core 与页面验收。`100-final-audit.sh` 在原 `MARK_FULL` 前调用 `VERIFY`，检查 Core/worker 同源身份、逐文件哈希、进程工作目录、仅回环监听、授权拒绝和真实合成推理。
5. 最终审计成功后才 `COMMIT`。切换失败自动恢复 OCR 部分；完整发布后续失败由协调器明确调用 `ROLLBACK`。这两个动作均不恢复数据库，不自动切换或重启旧 Core。数据库已迁移后的恢复必须遵循原完整发布规则。

回退快照在 `/var/lib/hotel-ai-os-deploy/finance-ocr/<release-id>/`，root 私有。回退前核对全部快照与当前文件；有人独立修改配置、激活了另一版本或快照损坏时停止自动恢复，留给管理员处理，避免覆盖新配置。保留原件、识别结果、候选版本与诊断记录。

下面的 Compose 与手动启动用于受控环境参考，不替代上述生产 CI、备份恢复和完整发布门禁。

### Docker Compose

在受控发布目录按仓库结构保留 `apps/finance-ocr` 和本目录文件。使用受保护的环境文件提供至少 32 字符随机 `FINANCE_OCR_TOKEN`，不把密钥提交或写进命令历史。

```sh
docker compose --env-file /etc/hotel-ai-os/finance-ocr.env \
  -f infra/production/finance-ocr/compose.yml build
docker compose --env-file /etc/hotel-ai-os/finance-ocr.env \
  -f infra/production/finance-ocr/compose.yml up -d
curl --fail http://127.0.0.1:8095/health
```

Compose 仅把端口绑定到主机回环地址，运行网络设为内部网络。不要将该端口加入 Caddy 的公网反向代理。若 Core API 也运行在容器中，应重新配置私有网络寻址和访问范围，而不是改为公开端口。

### Python 虚拟环境与 systemd

按 `apps/finance-ocr/README.md` 准备受控 Python 环境和固定依赖，将每版源码与虚拟环境放在 `/opt/hotel-ai-os/finance-ocr/releases/<release-id>/{app,venv}`，由 root 管理 `current` 指针。服务文件使用已有 `hotelai` 用户；应用代码和模型应可读，不可由 Web 用户改写。

将本目录的 service 安装至 `/etc/systemd/system/`。私有 `/etc/hotel-ai-os/finance-ocr.env` 中只配置服务所需密钥，文件应为 root 所有、hotelai 可读、权限 0640。

```sh
systemctl daemon-reload
systemctl enable --now hotel-ai-os-finance-ocr.service
curl --fail http://127.0.0.1:8095/health
```

## 中台连接配置

在受保护的 Core API 环境文件中配置相同密钥和地址，然后按发布流程重启 API：

```text
FINANCE_LOCAL_OCR_ENDPOINT=http://127.0.0.1:8095/recognize
FINANCE_OCR_TOKEN=<与识别服务一致的私有随机值>
FINANCE_LOCAL_OCR_TIMEOUT_MS=15000
```

未配置时功能明确显示未启用；服务失败、繁忙或超时时，上传成功的原件仍保留，表单允许人工核对，不能把这种情况显示为已成功识别。通过已保存原件的“重新识别”恢复，不需员工再次拍摄或上传。客户端和服务均不应将票面文字、原件内容或密钥写入日志。

## 页面验收与回退

1. 上传 JPG 发票、交易记录、付款凭证，确认候选分别进入对应类别，人工已填值不被覆盖，发票不会自动勾选确认。
2. 对多金额、低清晰度、多份支付凭证检查待核对提示，不能以最后一份自动覆盖前一份。
3. 停止识别服务，确认原件上传、草稿保存继续成功，恢复后可直接重新识别。
4. 用合成同号不同图片验证数据库排重；相似图片只提醒，不单独拒绝报销。
5. 检查收票池与直接报销两条入口，以及桌面和手机布局。

回退时清空 Core API 的 `FINANCE_LOCAL_OCR_ENDPOINT` 并按发布流程重启 API，然后停止识别服务。保留追加识别记录和原件，不删除数据库表、修改迁移校验和或回退已提交的报销事实。既有人工填写、发票排重与审批流程继续工作。

## 本机实施验证（2026-10-26）

- Web 最新全套 538 项测试通过，TypeScript 检查与试点构建通过；Core API 构建成功，7 套财务定向测试 64 项通过，包括真实 PostgreSQL 原件保护、租户隔离、同号不同图排重，以及原外部识别和采购冻结回归。
- Worker 28 项测试通过。25 个固定依赖 wheel 通过官方 PyPI SHA 校验，离线 hash-lock 安装和依赖一致性检查通过。
- Windows 本机真实 CPU 合成 JPG 验收：20 位票号、金额、中文抬头、买方税号、二维码及原件 SHA 可读取；进一步使用实际 Java HTTP 客户端提取为对应候选，`confirmationRequired=true`，未把测试二维码当成税务发票二维码或含税金额。单一样本性能见 worker README，不外推实际票据准确率或云端性能。
- 本机隔离浏览器（1440×1000、390×844）5 条流程通过：明确上传入口、候选补空白且不自动确认、人工值保护、失败按原件重试不重传、多凭证需明确金额且刷新保留、收票版本冲突保护；未发现相关控制台错误、空白页或横向裁切。
- Compose 文件及构建目录、回环端口、内部网络和 Spring YAML 已解析检查。当前电脑没有 Docker，尚未执行目标 Linux 镜像构建、云端部署或真实票据泛化验收。未修改生产配置或处理真实财务单据。

2026-10-27 部署接线的本机验证：新增 18 项文件/命令合同覆盖启动与烟测失败恢复、首次安装回退、配置漂移拒绝、固定服务模板、同源制品哈希、保留独立配置、最终核验、迁移密钥环境隔离与 root 边界；原 60/100 完整性合同 14 项通过，三份 Bash 脚本语法通过。命令与 systemd 在合同中模拟；Windows 缺少符号链接权限，指针行为在本机模拟，Linux CI 使用真实符号链接。尚不能据此声称服务器安装、真实 systemd 切换或线上识别通过。
