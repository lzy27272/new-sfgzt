# 云端磁盘容量个人预警

独立运维任务，不修改中台功能、数据库结构、备份计划或原有健康检查。

- 周期：每周一 09:00，`Asia/Shanghai`。关机错过时由 `Persistent=true` 在恢复后补查。
- 范围：当前根文件系统 `/`（应用、数据库、上传附件、云端备份共用此盘）。
- 阈值：使用 `df` 显示的使用率；大于或等于配置阈值时通知，默认 70%。
- 正常情况只保留本地检查结果，不推送。不是实时监控；两次周检之间越线不立即报警。
- 收件人：必须是经用户确认的一个中台登录账号及其有效个人企微绑定。
- 每次发送重新只读验证租户、账号、员工在职状态、绑定及身份指纹。停用、撤销、重绑或匹配不唯一时停止发送，不改发给其他人或群。
- 无自动清理、扩容、授权或业务数据写入；通知中不包含企微 UserID、密钥或令牌。

## 安装

先运行 `node --test infra/production/health/disk-alert.test.mjs`。服务器要求 Node.js 18.19+、PostgreSQL `psql` 客户端、现有 `hotelai` 用户及有效企微应用配置。

1. 安装 `disk-alert.mjs` 到 `/usr/local/lib/hotel-ai-os/disk-alert.mjs`，root 所有、0644。
2. 根据 `disk-alert.config.example.json` 创建 `/etc/hotel-ai-os/disk-alert.json`，root:hotelai、0640。使用只读查询核对的真实账号 UUID、绑定 UUID、完整身份指纹。示例不能直接启用。
3. 安装独立的 `.service` 和 `.timer` 到 `/etc/systemd/system/`，先验证 unit，再 `daemon-reload`。
4. 在与服务相同的用户和环境下运行 `--mode dry-run`，校验真实磁盘和唯一有效收件人，不发送。
5. 可运行一次明确标为测试的 `--mode test`，核对企微接口接收成功和用户端收信。测试消息与正式阈值预警分开去重，不伪造磁盘占用。
6. 正式执行一次服务确认正常静默，然后启用 timer；核对下次执行时间。

所有密钥沿用服务器现有受保护的 `/etc/hotel-ai-os/core-api.env`，不要复制进仓库或命令行。SQL 使用现有应用账号、显式租户、只读事务、5 秒语句超时和 1 秒锁超时。

## 状态与失败处理

- `/var/lib/hotel-ai-os-disk-alert/last-result.json`：最近的检查/测试结果。
- 同目录 `delivery-state.json`：正式预警按北京时间周一所属周去重；通道测试按配置仅发送一次。
- `systemctl status hotel-ai-os-disk-alert.service` 和 `journalctl -u hotel-ai-os-disk-alert.service`：执行及失败记录，不输出敏感数据。
- API 返回非零、无效收件人或缺少消息编号均视为失败，不能写成发送成功。
- 网络超时可能发生在对方接收之后；不无限自动重试。人工重试复用原消息内容，并启用企微短期重复消息检查；不声称跨系统严格一次投递。
- 检查/消息发送失败会以非零退出和日志留痕。本任务没有备用告警通道，企微不可用时不能保证收到失败通知。
- 独立 timer 不依赖办公电脑开机；数据库和企微服务仍需可用。

停用仅需 `systemctl disable --now hotel-ai-os-disk-alert.timer`；不影响中台和现有备份。不要删除业务数据或现有备份来测试阈值。
