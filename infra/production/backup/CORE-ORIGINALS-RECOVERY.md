# Core 与财务原件的日常配对备份

`backup-postgres.sh` 保留原 DB 与 state 的加密、30/12/3 保留和只读 Windows 导出合同；state 现在明确是 `CORE_AND_OTA_STATE_WITH_ORIGINALS`，不新增导出类型、账号或网络权限。

工作/任务/日报/采购/学院和财务原件必须位于稳定 `/var/lib/hotel-ai-os/attachments`。配置的 `FINANCE_ATTACHMENTS_ROOT`、`FINANCE_BUSINESS_ATTACHMENTS_ROOT`、`ACADEMY_STORAGE_ROOT` 必须是其真实子目录；默认分别是 `finance-originals`、`finance-business-originals`、`academy`。相对路径、release 目录、外置根、符号链接或未覆盖的嵌套文件系统会明确拒绝备份，不宣称这些原件已保护。运行中的 Core 进程路径环境也会核对，不输出秘密。

备份先在线复制大文件，再暂时停止原本运行的写入服务（包括 Core），在同一停写窗口二次同步、`pg_dump`、保存实际原件数据库索引及逐文件 SHA；无论成功失败都通过 trap 恢复原运行服务。线上原件不删除、不旋转重编码。已按原业务留存流程标 `DELETED` 的工作图片不要求虚造原文件；历史缺失原件或 SHA 不明的索引会拒绝成功，需核查实际留存依据，不能补假文件。

`99-install-operations.sh` 从受控发布 staging 安装以下文件，缺失就失败：`backup-postgres.sh`、`core-originals-manifest.py`、`core-originals-reference.sql`、`rehearse-core-originals-restore.sh`，以及原有导出/健康工具。安装更新不等于已经完成备份或恢复验证。

当真实日常备份成功产生同一时间戳的 DB/state 后，管理员执行：

```bash
sudo /usr/local/lib/hotel-ai-os/rehearse-core-originals-restore.sh \
 /var/backups/hotel-ai-os/postgres/daily/hotel_ai_os-daily-<timestamp>.dump.enc \
 /var/backups/sifangguan-ota/state/daily/sifangguan-ota-state-daily-<timestamp>.tar.gz.enc \
 <timestamp>
```

时间戳形如 `20261024T231000+0800`。工具验证密文校验和和配对身份，解密到独立 `0700` 临时目录，仅创建唯一 `hotel_ai_os_originals_restore_<epoch>_<pid>` 数据库。它实际运行 `pg_restore`，逐项核对恢复 DB 的原件索引、所有原件字节/哈希、迁移失败数和实际原件表强制 RLS。绝不覆盖 `hotel_ai_os` 或线上附件目录；清理只针对本次专用数据库和经真实路径核对的独立临时目录。

成功标记为 `PAIRED_DATABASE_ORIGINALS_RESTORE_OK`，受保护证明存入 `/var/backups/hotel-ai-os/postgres/originals-restore-proofs/<timestamp>.json`，并绑定两份密文的校验和。不能以 `pg_restore --list`、仅 DB 恢复或只有日常备份成功代替此证据。此改动不修改 `60`/`100`/CI 门禁；本次 FULL 发布另由发布人要求该配对证明 PASS，再执行原完整发布流程。

本地可运行合同/负向测试：`python3 infra/production/backup/tests/test_core_originals.py`。该测试覆盖实际文件哈希、原件缺失、错配 DB、路径逃逸、安全解包与配置覆盖，但不是生产 PostgreSQL 恢复已成功的证明。
