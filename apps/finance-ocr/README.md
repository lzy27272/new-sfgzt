# 财务凭证本地 OCR sidecar

仅提供图片 OCR、二维码原文和外观相似指纹。**不判断发票真伪、不批准报销、不生成会计分录、不自动拒绝相似图片**。字段归类、票号排重和人工确认由 Core API 承担。

## 启动与集成合同

Python 3.12，工作目录为 `apps/finance-ocr`；入口 `python -m finance_ocr`。先按 `requirements.lock` 安装依赖。必须通过服务环境/受保护凭据文件配置随机的 `FINANCE_OCR_TOKEN`（32–256 个不含空格 ASCII 字符），不要写入仓库或日志。

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `FINANCE_OCR_HOST` | `127.0.0.1` | 仅私网/回环入口，不向互联网暴露 |
| `FINANCE_OCR_PORT` | `8095` | Core 调用地址 |
| `FINANCE_OCR_TIMEOUT_SECONDS` | `12` | IPC 与识别合计硬截止，范围 1–12 秒 |
| `FINANCE_OCR_STARTUP_TIMEOUT_SECONDS` | `120` | 启动或超时重建的模型预热截止，10–180 秒 |

`GET /health` 不要求 token，只返回 `{"status":"READY","provider":"RapidOCR"}`（200）或 `WARMING_UP`（503）。不得把健康成功当作票据准确率验收。

`POST /recognize`，`Authorization: Bearer <token>`，`Content-Type: application/json`，必须带唯一 `Content-Length`，不支持分块传输。JSON 必须恰有四个字符串字段，不接受 URL、磁盘路径或回调地址：

```json
{"sourceSha256":"<原件64位小写SHA256>","mediaType":"image/jpeg","contentBase64":"<原件标准Base64>","kind":"INVOICE"}
```

`mediaType` 只允许 `image/jpeg`、`image/png`；`kind` 只允许 `INVOICE`、`TRANSACTION`、`PAYMENT`。

```json
{"provider":"RapidOCR","sourceSha256":"<原件SHA>","blocks":[{"text":"识别文字","confidence":0.96}],"qrCodes":[],"imageHash":{"algorithm":"phash-dhash-v1","phash":"0000000000000000","dhash":"0000000000000000"},"warnings":[]}
```

Core 连接/请求截止应大于 sidecar 的 12 秒推理截止及短时回收余量，例如请求 15 秒。401 代表认证失败；400 为输入或原件 SHA 不一致；413 为大小/像素超限；415 为 MIME 不支持；422 为图片损坏或格式不匹配；429 为单工繁忙（不入队）；503 为预热/不可用；504 为已终止子进程的推理超时。错误只含固定 `code`，不含原件、文字或异常堆栈。

## 隐私、资源和边界

- 原件最大 20 MiB、JSON 最大 28 MiB、图片最大 4000 万像素；只接收静态 JPEG/PNG。先核验原件 SHA，再解码；EXIF 方向只用于内存视图，不修改、重存或删除原件。
- 同时最多 4 个 HTTP 处理线程、8 个连接积压；HTTP 处理槽耗尽时尽早返回 503 并关闭连接，客户端也可能收到连接关闭，应退避而非并发重试。单独的推理子进程同时只处理一个任务，无任务队列。请求体读取有绝对 10 秒截止；单次子进程调用最多 12 秒，挂起 IPC 也在截止内终止并重建。
- CPU ONNX，2 个计算线程/1 个跨算子线程；启动期预热后才 READY。超时后状态回到 WARMING_UP，不能当作成功重试结果。
- 三个模型必须存在且匹配固定 SHA。推理子进程的 Python socket 网络入口被禁用，只向引擎传入内存图片，绝不把二维码 URL 打开；真实请求不下载模型。生产还应使用受限服务账号、只读文件系统、CPU/内存上限和网络出站阻断，不能把 Python 网络防护当作操作系统沙箱。
- 不持久化票面/base64，不输出 HTTP 访问日志、第三方 OCR 文本或敏感异常；子进程 Python 与原生日志均丢弃。
- 最多 1000 个完整文字块、累计 100000 个 Java UTF-16 字符；最多 20 条完整二维码、每条 4096 字符。响应最多 512 KiB。超限跳过完整块/二维码并产生 `TEXT_LIMIT_REACHED`/`QR_LIMIT_REACHED`，**不截断成看似完整的票号**。`NO_TEXT_FOUND`/`LOW_CONFIDENCE_TEXT` 需要人工核对。
- pHash/dHash 对 EXIF 归正后的 RGB 图生成 64 位（16 hex）结果，算法标识固定 `phash-dhash-v1`；它们只提示相似图，不能证明同一发票，也不保证裁剪、旋转或改字后仍能识别。原件精确重复仍依据原 SHA。

## 依赖、模型与许可证

版本固定 RapidOCR **3.10.0**（维护中的 `rapidocr`，不用已逐渐淘汰的 `rapidocr_onnxruntime`）、ONNX Runtime CPU **1.30.0**、zxing-cpp **3.1.1**、ImageHash **4.3.2**、Pillow **13.0.0**，全部传递依赖及官方 wheel SHA 固定在 `requirements.lock`。初选 Pillow 12.3.0 在官方 PyPI 已列出安全公告，本服务不采用该旧版本。

RapidOCR 官方 wheel SHA256：`2fc34e26cd0f48514804a94f8832283039c7334076bff60768b1fed9ad068050`。构建只使用该 wheel 内置的三个模型，不从可变 `master` URL 拉模型：

| 模型 | SHA256 |
| --- | --- |
| `PP-OCRv6_det_small.onnx` | `090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f` |
| `PP-OCRv6_rec_small.onnx` | `6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884` |
| `ch_ppocr_mobile_v2.0_cls_mobile.onnx` | `e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c` |

RapidOCR 代码与这三份 PaddleOCR 衍生权重**版权归属不同**，官方声明分别以 Apache-2.0 分发；保留已安装发行包的 LICENSE、MODEL_LICENSES 和上游署名。zxing-cpp 为 Apache-2.0，ImageHash 为 BSD-2-Clause，ONNX Runtime 为 MIT，Pillow 为 MIT-CMU；其余依赖分别遵循各包授权，不能一概称为 RapidOCR 的 Apache-2.0。此适配器不包含 PyMuPDF 或商用 OCR API。

核查来源：

- [RapidOCR 官方 3.10.0 包及 SHA](https://pypi.org/project/rapidocr/3.10.0/)
- [代码许可证](https://github.com/RapidAI/RapidOCR/blob/v3.10.0/LICENSE)
- [官方独立模型许可、来源与哈希清单](https://rapidai.github.io/RapidOCRDocs/latest/model_licenses/)
- [当前 Python API 与预热行为](https://rapidai.github.io/RapidOCRDocs/latest/install_usage/rapidocr/usage/)
- [本地模型参数](https://rapidai.github.io/RapidOCRDocs/latest/install_usage/rapidocr/how_to_use_offline_model/)
- [zxing-cpp 官方 Python API](https://github.com/zxing-cpp/zxing-cpp/tree/master/wrappers/python)
- [ImageHash 项目和许可](https://github.com/JohannesBuchner/imagehash)

锁文件更新仅在受控构建机进行：下载固定版本及其依赖到空 wheelhouse，运行 `python verify_dependencies.py --wheelhouse <目录> --lock <新的锁文件>`。它逐包核对官方 PyPI release SHA、不接收未知制品、拒绝官方已记录漏洞的版本；然后用 `pip install --no-index --find-links <wheelhouse> --require-hashes -r <新锁文件>` 离线安装。审核锁文件后才替换；不得在真实识别请求中调用这些构建命令。

## 测试

```text
python -m unittest discover -s tests -v
```

测试使用合成图片/内存数据，无真实凭证或外部上传。`tests/real_engine_smoke.py` 是另行执行的合成中文 JPEG、二维码、CPU/时间/内存实测，不得把 mock 单测冒充真实引擎验收。Dockerfile 可从此目录构建；生产放行仍需目标 Linux 构建、限额内资源和内网连通验收。

### 2026-10-26 本机实测记录

Windows 本机隔离 Python 3.12 环境中，25 个实际下载 wheel 均与官方 PyPI 对应发行文件的 SHA256 一致；使用锁文件执行 `--no-index --require-hashes` 离线安装通过，`pip check` 无依赖冲突。锁文件每条续行尾只有一个 `0x5c`。28 项单测全部通过，无跳过。

使用一张 **194621 字节、1800×1200 的程序生成中文 JPEG**（醒目标记无效测试凭证）调用真实 CPU 引擎：冷预热 **2.672 秒**，识别 **1.875 秒**，推理子进程当时 RSS **153.23 MiB**，输出 9 个文字块。测试票号、金额、买方税号、中文文字、二维码和原件 SHA 核对均通过，warnings 为空。Java 客户端另经本地 HTTP 调用并重试核验，确认结构映射正确且 `confirmationRequired=true`。

这些是单个合成样本的本机结果，**不代表真实票据泛化准确率、生产性能或 Linux 构建通过**。Docker 基础 tag `3.12.15-slim-bookworm` 已与 [Docker 官方发布清单](https://github.com/docker-library/official-images/blob/master/library/python) 核对；本机没有可用 Docker，未执行镜像构建、拉取 digest 或目标机器验收。
