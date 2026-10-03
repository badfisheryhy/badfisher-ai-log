# 使用与部署

适用版本：0.1.0-SNAPSHOT，JDK 21。默认部署为一个后端进程和一个 MySQL 数据库。Redis、配置中心、外部身份中心和调度平台都不是本地解析的前置条件。

## 1. 准备环境与构建

安装 JDK 21、Maven 3.9；用 `java -version` 和 `mvn -version` 确认 Maven 也使用 JDK 21。项目使用公开 Maven 依赖，无需私有父 POM。执行：

```sh
mvn clean verify
```

JAR 位于 `ai-log-bootstrap/target/ai-log-bootstrap-0.1.0-SNAPSHOT.jar`。默认测试不需要真实 OpenAI 密钥；完整 MySQL 测试需要单独启用，见开发文档。

## 2. 账号与配置

在交互终端运行以下命令，输入至少 12 个字符的密码，复制打印出的 BCrypt 哈希。工具不启动 Spring，也不连接数据库。

```sh
java -Dloader.main=io.github.badfisher.ailog.bootstrap.security.PasswordHashTool -cp ai-log-bootstrap/target/ai-log-bootstrap-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

PowerShell 将 `-Dloader.main=...` 整个参数放在单引号内。哈希含 `$`，设置变量或写 `.env` 时使用单引号，避免 shell 展开。不要把明文密码或真实哈希提交到仓库。

复制 `config/application.example.yml` 为 `config/application-local.yml`，设置环境变量 `ADMIN_PASSWORD_HASH`。账号由配置文件维护，修改后重启生效。`id` 是治理操作的稳定人员标识，必须唯一且为正数；更改用户名后仍应保留同一人的 id，不要给另一人复用历史 id。

示例模板只有一个管理员。添加成员时设置独立 id、username、password-hash 和 `admin: false`。普通成员可以访问查询及有权限的治理操作，不能修改模块、规则或触发流水线。详细治理权限由服务层再次校验。

配置中的密码、密钥优先用 `${环境变量}` 引用。服务默认绑定 `127.0.0.1`。远程访问时通过配置了 HTTPS 的反向代理使用 HTTP Basic；账号密码随每次请求发送，不使用浏览器业务会话。

## 3. 使用已有 MySQL

准备 MySQL 8.4 专用空数据库，例如由数据库管理员创建：

```sql
CREATE DATABASE badfisher_ai_log CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

为应用分配此数据库的建表、索引和读写权限，设置：

| 环境变量 | 内容 |
| --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/badfisher_ai_log?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC` |
| `DB_USERNAME` | 专用数据库用户 |
| `DB_PASSWORD` | 该用户密码 |
| `ADMIN_PASSWORD_HASH` | 上一步生成的哈希 |
| `LOG_ALLOWED_ROOT` | 本地原始日志允许根的绝对路径；复制配置模板时必须设置，Compose 内固定 `/logs` |
| `LOG_TIMEZONE` | 无偏移日志的业务时区，默认 `Asia/Shanghai` |
| `BUSINESS_PACKAGES` | 业务堆栈包前缀，例如 `com.example`；多个前缀可用 YAML 列表 |

启动：

```sh
java -jar ai-log-bootstrap/target/ai-log-bootstrap-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod --spring.config.additional-location=file:./config/application-local.yml
```

Flyway 自动执行 `db/migration/V1__initial_schema.sql`，建立 17 张应用表及自己的迁移历史表；无需逐张手动建表。`sql/schema.sql` 是供审查的同内容副本，不要先手动执行后再让 Flyway 重复执行。已有非空库不会自动 baseline。后续迁移新增 V2、V3，不能改已发布的 V1。

`prod` 或 `production` profile 会检查数据库配置和显式账号。检查健康状态：

```sh
curl http://127.0.0.1:8080/actuator/health
```

业务 API、OpenAPI JSON `/v3/api-docs` 和 `/swagger-ui/index.html` 均需要账号。默认只开放 health、info 两个 Actuator 端点，其中 info 仍需认证。

## 4. Docker Compose

先完成 JAR 构建、哈希生成、配置模板复制。再将 `.env.example` 复制为 `.env`，填入独立的 `DB_PASSWORD`、`DB_ROOT_PASSWORD` 和 `ADMIN_PASSWORD_HASH`。不要设置相同的管理员与数据库密码。

```sh
docker compose config --quiet
docker compose up --build -d
docker compose logs -f app
```

模板包含 MySQL 8.4 和 JDK 21 应用镜像；应用以非 root 用户运行。数据库不映射宿主机端口；应用只映射宿主机回环地址 8080。`HOST_LOG_DIRECTORY` 默认将仓库合成样例挂到容器 `/logs`，只读；数据库和运行目录分别使用命名卷。

应用的工作目录需要可写，用于脚本提取和运行产物。挂载自定义路径时，确保 UID 10001 有相应读写权限。当前镜像面向本地日志模式，未安装 SSH/rsync/Git 工具，不用于直接验证这些可选集成。

停止使用 `docker compose down`。保留命名卷可保留数据，不要为普通重启删除卷。本交付环境没有 Docker，模板尚未经过实际容器启动验收。

## 5. 从样例日志开始

仓库 `samples/logs` 为合成内容，日志日期固定为 `2026-01-01`。本地 JAR 部署将下面 JSON 的目录改成此目录在服务端的绝对路径（Windows JSON 推荐写 `E:/.../samples/logs`）；Compose 使用 `/logs`。

以下为 POSIX shell 的 curl 写法，`-u admin` 会交互询问密码。PowerShell 可用 `curl.exe` 和 JSON 文件配合 `--data-binary @文件名`，避免引号转义差异。

创建模块：

```sh
curl -u admin -H 'Content-Type: application/json' \
  -d '{"environment":"test","systemCode":"demo","moduleCode":"service","remoteDirectory":"/logs"}' \
  http://127.0.0.1:8080/api/management/module-configs/create
```

本地模式必须先配置 `badfisher.sync.allowed-log-roots`，示例配置从 `LOG_ALLOWED_ROOT` 读取一个绝对根目录；可在 YAML 中列出多个根。空列表拒绝读取，根目录须已存在且不能是盘符或文件系统根。ERROR 和 INFO 均受约束，符号链接/junction 不能逃逸允许根；远程同步缓存仍由 `root-directory` 单独管理。

本地模式的 `remoteDirectory` 表示本机目录，字段名为兼容现有模块模型保留。SSH 地址和用户不必填写。默认启用 ERROR、关闭全量日志同步，源码同步默认关闭。

解析：

```sh
curl -u admin -H 'Content-Type: application/json' \
  -d '{"environment":"test","systemCode":"demo","logDate":"2026-01-01","maximumFiles":10}' \
  http://127.0.0.1:8080/api/pipeline/parse
curl -u admin http://127.0.0.1:8080/api/pipeline/tasks
```

这是同步批次接口：返回前完成本批文件处理，响应含 `successCount`、`failureCount`。不要把 HTTP 200 当作所有文件业务成功。每次最多 100 个文件；只能处理已结束的业务日期。相同模块、日期、规范文件路径和指纹版本的成功任务不会重复累计；不同轮转文件分别处理。已解析文件不支持追加尾追踪，需在文件停止写入后使用。

无日期文件名同样可用，解析时按日志时间筛选请求日期；无法识别时间的事件仍可能进入降级处理，因此请先验证自定义日志头。匹配很多历史轮转文件时建议使用带日期模板缩小范围。

查询聚合问题可调用 `POST /api/management/issue-groups/page`，详情、事件分页与筛选字段从 OpenAPI 获取；选项接口返回 `label/value` 时，显示 label、提交 value。

## 6. ERROR / INFO 配置

模板中的默认 glob 覆盖 `error.log`、`service-error.log`、`service-error.log.1`、`service-error.log.gz` 等。INFO 类似。支持普通 UTF-8 文本和 `.gz`；不递归扫描子目录。纯数字日期名或混合应用日志可以显式配置：

```yaml
badfisher:
  timezone: Asia/Shanghai
  sync:
    enabled: false
    business-packages: [com.example]
    error-file-patterns:
      - 'application-{date}.log'
      - 'application-{date}.log.gz'
    info-file-patterns:
      - 'application-{date}.log'
      - 'application-{date}.log.gz'
  info-context:
    enabled: true
    max-events: 20
    max-characters: 8192
    window-seconds: 120
    max-files: 20
    max-scanned-bytes: 67108864
```

占位符：`{date}` 为 yyyy-MM-dd，`{compactDate}` 为 yyyyMMdd，`{prefix}` 来自模块 `logFilePrefix`。同名普通文件和 gzip 同时存在时优先普通文件。禁止 `..` 与路径分隔符；子目录通过模块目录指定。文件名只决定读取候选，内容级别仍必须满足 ERROR 或 INFO 对应条件。混合日志可让两个 pattern 指向同一文件。

INFO 只在 AI 分析所选 ERROR 样本时读取，按同模块、同 traceId/TID 或同 requestId、前后 120 秒匹配。traceId 与 requestId 不会交叉匹配；缺失关联标识不会仅凭线程名或时间猜测。默认最多 20 条、8192 字符、20 个文件、总计 64 MiB 解压后扫描字节。达到限制会标记不完整，未命中、文件缺失和读取失败也都有状态，不伪造上下文。

被选中的 INFO 在脱敏后随 AI Item 的证据快照保存，因此可以复核当时发送的上下文；不会把所有 INFO 单独入库，不提供全量 INFO 查询接口。

支持的日志头、JSON 字段与自定义正则见[设计文档](DESIGN.md#3-日志输入契约)。

## 7. 配置并执行 AI

先设置以下环境变量后重启：

```text
AI_ENABLED=true
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_API_KEY=通过环境注入的真实密钥
OPENAI_MODEL=账号中可用且支持相应 Responses 能力的模型名称
```

默认仅允许 HTTPS。本地模拟服务或可信内网 HTTP 必须显式设置 `badfisher.ai.providers.openai.allow-insecure-http: true`，该开关不会关闭 HTTPS 证书校验。

DeepSeek 官方 Responses 接入使用独立配置和 Compose 叠加文件，见 [DeepSeek 接入与验证](DEEPSEEK.md)。原有 OpenAI 配置和启动命令保持有效。

`OPENAI_BASE_URL` 不要带 `/responses`；客户端会追加一次。兼容服务必须支持 Responses 协议，仅支持 Chat Completions 的地址不能直接使用。普通分析要求 JSON 输出；工具分析还要求 function calling 与相应结构化输出能力。不固定模型名称，以账号实际权限与供应商支持为准。

`badfisher.ai.providers.openai.models.<模型名称>.max-tokens` 可覆盖默认输出上限 2000。更换模型后创建新任务或显式重跑，不静默改写旧结果。示例：

```yaml
badfisher:
  ai:
    enabled: true
    default-provider: openai
    default-model: ${OPENAI_MODEL}
    providers:
      openai:
        base-url: ${OPENAI_BASE_URL:https://api.openai.com/v1}
        api-key: ${OPENAI_API_KEY}
        connect-timeout-seconds: 10
        read-timeout-seconds: 60
        max-attempts: 3
        retry-backoff-millis: 1000
```

AI 关闭时已完成的解析无需重做。从 `/api/pipeline/tasks` 取实际成功任务 id，创建首次分析，再调度：

```sh
curl -u admin -H 'Content-Type: application/json' \
  -d '{"analysisTaskId":1}' http://127.0.0.1:8080/api/pipeline/ai/prepare
curl -u admin -X POST http://127.0.0.1:8080/api/pipeline/ai/dispatch
curl -u admin http://127.0.0.1:8080/api/pipeline/ai/tasks
curl -u admin http://127.0.0.1:8080/api/pipeline/ai/tasks/1/items
curl -u admin http://127.0.0.1:8080/api/pipeline/ai/items/1/attempts
```

示例 id 均需替换。prepare 对同解析任务和默认 provider/model 的首次批次幂等；AI 已开启的解析可能已经创建初始批次。dispatch 会处理当前可运行队列，不只处理刚创建的一条任务；批次内部使用受限线程池。到期重试需要再次 dispatch，可由外部调度器调用 API，当前默认没有自动定时调用此接口。

GET 列表默认最多 100 条，下一页传 `?beforeId=本页最小id`。Item 包含结果、证据快照和用量相关状态；Attempt 包含真实供应商请求标识（可用时）、耗时、用量、错误。一次工具分析 Attempt 可以包含多个 HTTP 请求，不能将 Attempt 数直接当 HTTP 请求数。

## 8. 可选源码分析

将要读取的源码放到 `badfisher.git-sync.workspace/environment/systemCode/moduleCode`，默认例如 `runtime-data/source/test/demo/service`。使用专用目录，分析期间不要切换分支或更新文件。源码工作区读取权限是应用权限的一部分，不接受模型自行指定仓库根路径。

```yaml
badfisher:
  ai:
    prompt-version: ai-issue-v5-tools
    function-calling:
      enabled: true
      max-tool-calls: 6
      max-search-results: 20
      max-read-lines: 160
      max-source-file-bytes: 2097152
      max-source-characters: 24000
```

关闭工具分析时将 prompt-version 恢复 `ai-issue-v4`。提供 `resolve_log_site`、`read_method`、`read_source`、`search_code` 四个只读工具，不运行模型生成的 shell、不修改代码。额度耗尽后发送一次禁止继续工具调用的收尾请求。

当前读取专用目录，尚未自动建立不可变 commit 快照，因此不能声称结果已绑定部署版本。代码准备、日志日期与部署版本的对应关系由使用者确认。源码缺失不是模型可以猜测内容的授权。

Git 默认只接受 HTTPS。确需 HTTP 时显式设置 `badfisher.git-sync.allow-insecure-http: true`；Java 校验和内置脚本共同执行，禁止重定向及隐式协议降级。账号凭据的 `allowed-hosts` 限制继续有效。

手工远程日志同步 API 默认关闭。需要时在 `badfisher` 下设置 `sync-api-enabled: true`，启用 `/api/log-sync/sync-module`、`/api/log-sync/sync-system` 和 `/api/log-sync/retry-task`；这些接口要求管理员认证，且仍受禁止人工同步环境配置约束。`sync-module` 只同步业务时区的昨日日志。本地 `/api/pipeline` 解析不依赖此开关。启用前需配置并验收 SSH 来源、凭据和远程同步缓存目录。

同步来源使用 `MANUAL` / `SCHEDULER`，错误日志调度渠道统一为 `SCHEDULER`。`badfisher.sync.manual-sync-denied-environments` 控制禁止人工同步的环境，示例默认含 `prod`、`production`；设为空列表显式允许所有环境，管理员认证仍保留。跨实例锁使用 `lock-mode: DISTRIBUTED`，具体实现由装配层提供；旧 `REDIS` 配置需更新，不做静默回退。

旧数据库中的 `XXL_JOB` / `SCHEDULE` 字符串不会被启动过程自动改写；本次不执行历史数据迁移。部署已有数据库时应先确认这些字段的消费方兼容性。

SSH 同步、Git 同步、Loki、通知、对象存储为可选适配。默认不启用通知/上传；远程 SSH 路径仍使用已有日期文件命名策略，通用 glob 目前用于本地读取。XXL handler 代码保留，但没有默认注册 XXL 执行器，需要单独装配和验收。

## 9. 常见问题

| 现象 | 检查步骤 |
| --- | --- |
| Maven 报 Java 版本不满足 | `mvn -version`，本项目只构建 Java 21 |
| 启动无法连接数据库 | 检查 JDBC 地址、库是否存在、用户权限和最内层异常，不先修改业务表 |
| Flyway 报非空库或校验失败 | 确认使用独立库及已执行版本，不自动 repair/baseline |
| API 401 | 检查用户名、BCrypt 哈希、配置是否被加载 |
| API 403 | 检查账号 admin 标志及当前治理动作权限 |
| 解析 failureCount > 0 | 查看结构化错误日志、目录读权限、文件 glob 与日志日期；不按 HTTP 200 判定业务成功 |
| 无 INFO 上下文 | 查看 evidenceSnapshotJson 的 infoContextStatus、关联 ID、业务时区、窗口和文件模式 |
| AI 返回 400 | 检查是否启用、模型路由和参数；普通业务错误为 400，不承诺所有错误统一封装 |
| AI Item WAITING | 查看 nextRetryTime，时间到后再次 dispatch |
| AI INVALID_RESPONSE | 查看结果结构或拒绝/截断状态；不能当成正常分析结果 |
| AI 429 或 5xx | 查看 Attempt 与重试状态，不在客户端外再叠加无限重试 |

当前验证范围和发布前未完成项见[开发文档](DEVELOPMENT.md#6-验证与发布门槛)。