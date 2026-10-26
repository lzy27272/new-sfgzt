# 增量状态备份与离线恢复

## 范围与明确边界

数据库仍是每天独立 `pg_dump`，不宣称 PostgreSQL WAL/块增量。Core 的全部附件树、财务与学院原件、恢复所需 OTA/PMS 状态和 runtime.env 使用文件级 SHA256 去重；不变文件复用已有不可变加密内容包，改变文件只写入新包。每包至多约 256 MiB 原始内容（单个更大文件独占一包）。包内有仍被引用的内容就保留整包，不以删除包内旧内容冒充精准回收。

每天保留30个恢复点，每月首个点保留12个，每年首个点保留3个；月年点只复制完整索引，不重新打全量状态包。每点索引直接列出全部包，没有父快照链。数据库跨tier继续原 `cp --reflink=auto` 语义，不使用硬链接。半完成的月年复制只按冻结数据库 SHA 查找并补齐同一对，不拿第二天状态代替。

新索引和内容包均使用既有 AES-256-CBC/PBKDF2/200000 次和独立随机盐。设备只能拉取密文，不下发备份解密密钥。密文完整性仍由 root 保护的 SHA256 清单及固定主机身份只读SFTP传输门禁承担；本次没有把既有格式改称 AEAD 或新增外部依赖。

Core/财务/工作原件没有任何删除步骤。原有 `analytics-retention/raw` 的独立90天源生命周期以及raw备份90天保持不变。

迟到文件重建raw包时，新包使用 `analytics-raw-YYYY-MM-DD-CIPHERTEXT_SHA256.tar.gz.enc` 不可变名称，不覆写设备旧清单引用的路径。旧无后缀包继续可读；备份保留按最近90个**源日期**计算，同日期受管修订一同保留，不误改成90个文件，也不改变线上raw原有清理规则。

## 文件与完整性

```
state/daily/sifangguan-ota-state-daily-STAMP.snapshot.json.enc
state/monthly/sifangguan-ota-state-monthly-YYYYMM.snapshot.json.enc
state/yearly/sifangguan-ota-state-yearly-YYYY.snapshot.json.enc
state/objects/CIPHERTEXT_SHA256.pack.enc
```

加密索引保存完整的 etc/var 文件清单、逐文件原始 SHA/大小、直接内容包引用、实际配对数据库密文 SHA/大小、numeric uid/gid、mode、mtime_ns 和 Linux xattrs（包括 POSIX ACL）。软链接、特殊文件、嵌套文件系统、缺少必需状态文件或不能读取属性时明确失败，不静默漏备份。Linux隔离恢复须用适当权限应用owner/ACL；Windows本地便携测试只证明内容，返回 `metadataRestored=false`，不冒称验证Linux权限。

普通文件按逐路径重建；相同内容会去重存储，但不会保留硬链接的共享inode拓扑。本次发布须确认源树不依赖硬链接语义；未来若业务引入此依赖，需先扩展并验收相应恢复语义，不能以文件SHA相同冒称inode关系也已恢复。

创建时校验新包解密后的真实文件内容；恢复验证全部依赖密文及每个恢复文件 SHA。原有 `core-originals-manifest.py` 还会将恢复后的原件与隔离恢复数据库中真实 reference/大小/SHA 精确核对。`STATE_CONTENT_PASS` 仅代表本地状态重建，并不是 PostgreSQL/生产恢复通过。

`prune` 在首个删除前检查所有当前历史索引、实际数据库配对和全部内容包。任何损坏、错密钥或缺依赖均停止，不因坏点太旧就先删历史。只删除超期索引/数据库及没有任何保留点引用的备份内容包，不修改线上源。一个命令内复用已验证不可变包的stat身份与SHA，不把可信缓存持久化。

export先完整复制并验证新闭包，再原子发布 `manifest-v1.json`，最后清理旧export文件；发布失败保留上一清单的所有导出依赖。客户端对新后缀/对象使用精确allowlist，验证每个恢复点、数据库配对和包引用。旧客户端会安全拒绝新格式，须先更新连接/修复包；不会静默跳过增量包。新版清单有独立128MiB/200000条上限，SFTP每批最多500条。全部文件和receipt验收后，用一次flush+原子替换写入 `control/accepted-bundle.json`（同时包含完整manifest和匹配receipt），之后才更新两份旧兼容文件并清理历史；修复模式继续保留历史。两份旧文件即使因中断不匹配也不是新版权威；bundle存在却损坏或错配时必须失败，不能偷偷回退旧文件。

首轮staging复制前及实际停写前均做容量预检：使用真实 Core/OTA/raw 字节数和 `pg_database_size`，合并同一文件系统上staging、新密文包/临时加解密、数据库plain/check与tier副本、export副本需求，再留4GiB余量；不预设压缩率或去重节省。不足时明确 `BACKUP_CAPACITY_INSUFFICIENT`，不进入停写/裁剪。该保守估算可能比实际增量需求大，不能用“每天已去重”绕过；它也不是并发写入的磁盘配额，仍须运维空间监控。

## 恢复

生产隔离演练仍使用原3参入口（不恢复到真实业务数据库）：

```bash
/usr/local/lib/hotel-ai-os/rehearse-core-originals-restore.sh \
  /var/backups/hotel-ai-os/postgres/daily/hotel_ai_os-daily-STAMP.dump.enc \
  /var/backups/sifangguan-ota/state/daily/sifangguan-ota-state-daily-STAMP.snapshot.json.enc \
  STAMP
```

脚本与备份/GC共用锁；解密完整状态自动取齐 `../objects/`，恢复隔离PG，再核对真实原件索引。证明保留原字段，增加 `stateFormat=SNAPSHOT_V2`、`stateRestore={indexSha256,database,packs,files,metadataRestored,...}`，`.archives.sha256` 包含DB、索引、每个依赖包，不只两份入口文件。旧 `.tar.gz.enc` 分支继续解密解包；历史包若原来没有 Core 原件清单，只能恢复它当时已有内容，不能标记“完整Core原件配对验证通过”。

Windows只下载 `.enc`，不会下载服务器 `.sha256`。从该异地副本恢复时，在**可信隔离恢复机**准备实际下载副本；保留权威 `control/accepted-bundle.json`。不需要联网：

恢复机需从同版运维包取得 `core-state-snapshots.py` 和同目录的 `publish-backup-manifest.py`（离线准备复用它的精确allowlist/闭包规则），以及已有 Python3/OpenSSL。若需验证Core reference，还需同版 `core-originals-manifest.py`、reference SQL 和 PostgreSQL 客户端。解密密钥独立安全移交，绝不由Windows只读拉取自动下载。

```bash
python3 core-state-snapshots.py prepare-offsite \
  --data-root /isolated/device-copy/data \
  --bundle /isolated/device-copy/control/accepted-bundle.json

python3 core-state-snapshots.py restore \
  --state-root /isolated/device-copy/data/sifangguan-ota \
  --index /isolated/device-copy/data/sifangguan-ota/monthly/sifangguan-ota-state-monthly-YYYYMM.snapshot.json.enc \
  --database /isolated/device-copy/data/hotel-ai-os/monthly/hotel_ai_os-monthly-YYYYMM.dump.enc \
  --snapshot ORIGINAL_STAMP --key /secure/offline-backup-encryption.key \
  --destination /isolated/empty-state
```

`prepare-offsite` 先按bundle内已验收清单和同ID回执验证整个副本的真实大小/SHA及闭包，全部通过后才物化受管checksum；不是把坏文件现算的hash当成可信依据。未提供显式bundle时优先读取data同设备根 `control/accepted-bundle.json`；仅当bundle不存在时才兼容 `--manifest ... --receipt ...` 的旧设备布局。旧清单不受48小时同步鲜度限制，历史恢复本应可用。`ORIGINAL_STAMP` 来自 `manifest.recoveryPoints`，月年文件名不等于原始snapshotId。保持密钥独立安全保管；失去密钥不能恢复。

之后仍须实际隔离PG恢复并执行 `core-originals-manifest.py verify`，不能用文件成功下载代替数据库/原件配对演练。此仓库的便携测试仅是本地fixture，不代表云端或Windows登记设备已部署、已同步或已完成灾难恢复。

恢复仍需空间容纳完整重建状态、完整隔离数据库及临时单包明文；逐包重建只保留约一个pack的临时blob，不再额外展开另一整份原件树。单个超256MiB原件可能让单包临时空间超过此阈值。上线前必须按实际原件/PG体积和 `df` 核算，去重不等于零恢复空间。

## 本地验证

```bash
python3 -m unittest discover -s infra/production/backup/tests -v
bash -n infra/production/backup/backup-postgres.sh
bash -n infra/production/backup/rehearse-core-originals-restore.sh
```

Windows测试继续运行 `windows-device/tests/Test-BackupPull.ps1` 和 `Test-BackupSyncFlow.ps1`。测试包含真实本地OpenSSL加解密、去重/改动/删除/重命名、30/12/3保留后的45点逐一状态重建、半提升重试、错配/缺失/损坏/错密钥、发布中断保护、旧tar真实解包、仅从设备导出布局准备并恢复、超过500条分批和失败receipt不清历史。
