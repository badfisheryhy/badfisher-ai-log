# 开发文档

适用：0.1.0-SNAPSHOT / JDK 21。开始前阅读[设计文档](DESIGN.md)，部署步骤见[使用文档](USAGE.md)。

## 1. 构建与目录

Maven reactor 按 common → domain → parser → analysis → ingestion → application → datasource → persistence → bootstrap 构建，依赖关系以各模块 POM 为准。业务模型不要反向依赖 Spring MVC 或数据库实体；外部协议在 bootstrap 适配，状态机在 application/领域端口和 persistence 中实现。

```sh
java -version
mvn -version
mvn clean verify
```

只构建当前模块及其上游可以使用 `mvn -pl ai-log-bootstrap -am test`。项目没有内置 Maven Wrapper，命令依赖本机 Maven。根 POM 通过 Enforcer 拒绝非 JDK21，不能仅用本机高版本编译后声称符合 JDK21。

生产源码位于各模块 `src/main`，测试位于 `src/test`。包名前缀 `io.github.badfisher.ailog`。配置模板在 config，合成日志在 samples/logs，建表审查副本在 sql。

不提交 target、.m2、.tools、runtime-data、.env、application-local.yml、IDE 缓存、真实运行日志、数据库导出或源码缓存。不要复制其他仓库 Git 历史、远程地址和本机绝对路径。

## 2. 编码与变更要求

先阅读调用入口、配置、上下游、事务与测试，再修改。保持现有多行 Java 风格和清楚的业务步骤，遵循阿里 Java 开发规范；不为缩小 Diff 压缩链式调用、Lambda、SQL 或多个语句。

- JDK21 是版本基线，不要求把普通模型全部改 record，也不要求虚拟线程化。
- 新依赖先检查已存在的公共能力；不得恢复私有 starter 或私有父 POM。
- 不用 null/空成功/固定结果充当未实现服务。可选能力关闭时保留明确状态和错误。
- HTTP/文件/源码读取在长事务外执行；新增异步使用命名 Executor，说明队列、拒绝、MDC 和 shutdown 行为。
- 修改任务状态同时检查租约/token、超时恢复、并发重复执行、重试与计数；不扩大线程数掩盖状态错误。
- 参数校验既在 API 边界也在领域关键写入处生效。新增管理路由必须配置权限并覆盖越权测试。
- 配置中只提交占位符和安全默认值。异常和日志不得输出 API Key、密码、Authorization 或未脱敏证据。

提交前检查 `git diff --check`、`git diff --stat` 和具体文件 diff。新增文件未被跟踪时需一并审阅。不要全库自动格式化；不修改与任务无关的文件。

## 3. 扩展日志能力

### 3.1 新格式

1. 在 parser 添加或扩展 LogHeaderParser，保持事件头与续行边界清晰。
2. 用合成样本覆盖正常头、INFO/ERROR、Cause/Suppressed、多行堆栈、乱码/超长行、无时间和未知格式。
3. 检查无时区时间映射、日期过滤及 INFO 关联 ID 提取。
4. 验证同一格式同时用于 ERROR 读取和 INFO 上下文；不要维护两套不一致正则。
5. 修改格式文档、示例和负面样例。不能以支持一个案例宣称支持所有业务日志。

普通部署优先使用 `badfisher.log-pattern`，只在现有解析器不能表达时增加实现。文件名能力独立于日志头，不在 parser 中硬编码系统名。

### 3.2 分类与聚合

扩展分类优先使用已有规则表和管理 API。确定性分类发生在 AI 之前。归一化或稳定指纹变更必须同时评估 fingerprintVersion、重放结果、聚合次数、样本选择和幂等身份，不能静默将旧算法结果混入新算法。

至少验证：动态字段归一后同类聚合、不同异常不误合并、空身份丢弃、抑制不入聚合、批次刷盘次数守恒、多文件任务独立、重复处理不重复累计。

### 3.3 INFO

保持按需扫描，无全量 INFO 表或无界内存索引。新关联方式必须证明不会跨请求匹配；不得默认用线程名关联。保留条数、字符、文件与解压后字节四层预算；超长物理行被截断也必须计入扫描预算。证据 hash 修改需升级版本并说明复用影响。

## 4. AI 和源码扩展

`AiAnalysisProvider` 是应用侧端口，`AiProviderConfiguration` 根据配置注册实际 provider/model。Responses 传输通过独立 OpenAI/DeepSeek 客户端接入，不增加第二层睡眠重试；一次 Attempt 的重试由持久化状态机控制。

新增供应商首先验证是否真的兼容 Responses 的请求、输出、usage、拒绝/截断与工具返回格式。仅兼容 Chat Completions 的服务需要独立适配，不能只改 base-url。

DeepSeek 已提供独立 Responses 传输适配，配置和验证见 [DeepSeek 接入](DEEPSEEK.md)。`ResponsesClient` 仅作为 bootstrap 内的传输端口，领域端口仍是 `AiAnalysisProvider`；供应商差异不得进入任务状态机或 OpenAI 请求逻辑。

普通输出严格校验枚举/长度/类型/未知字段；不能依赖模型“通常会遵守”。变更 prompt 或结构化 schema 时同步版本、结果解析器、证据序列化与合同测试。记录实际模型和请求标识，不把用量缺失补成已知零消耗。

源码工具必须维持只读边界：仓库根来自配置与证据坐标，校验 realpath、链接、扩展名、文件大小和字符/行预算；模型参数不能被拼成 shell 命令。日志及源码内容作为不可信证据，不能升级为修改权限。新增工具需要路径穿越、超限、空结果与重复调用测试。

目前源码目录可变，后续固定 commit 快照时应先设计 snapshot 身份、创建/引用/释放生命周期以及引用验证；不要仅增加一个 commitId 字符串就宣称可重放。

## 5. 数据库与接口演进

MySQL 为运行数据库，H2 只用于隔离测试，不是第二个受支持部署数据库。MySQL `UPDATE ... JOIN`、前缀索引等语法必须通过真实 MySQL 验收，不能根据 H2 通过推断兼容。

Flyway V1 是新空库基线，17 张应用表。正式发布后不可改已执行的 V1，新增迁移版本并验证空库与升级路径；无自动 DROP、clean、repair、baseline。修改 SQL 同时检查 Entity、Mapper XML、Repository、查询 DTO、OpenAPI、索引及事务；审查副本保持一致。

当前 API 包装为 code/message/data；同步批次返回业务计数，错误并非全部统一 envelope。新增异步提交需先保存持久化命令并定义 operationId、领取、续租、恢复及幂等键，不能只 `@Async` 后立即返回成功。

治理状态属于永久 GroupGovernance。新 Event、AI 成功或显式重跑不能静默覆盖人工处理结果。乐观锁使用服务返回版本；认领与指派是不同操作。规则候选如后续实现，必须先验证、再人工确认、最后发布，不能直接进入正常分类。

## 6. 验证与发布门槛

### 6.1 默认自动化验证

`mvn clean verify` 执行非 live 测试并打包。覆盖解析/异常结构/脱敏、聚合、任务领取与恢复、SQL 契约、治理相关持久化、INFO 预算、文件匹配以及 Responses 本地 HTTP 合同。

`StandaloneApplicationTest` 启动真实 Spring/Tomcat，使用隔离 H2 和合成文件，检查认证、OpenAPI、模块创建、解析入库及重复解析计数；仅为执行 H2 建表替换 MySQL 前缀索引语法，不声称完成 MySQL/Flyway 验收。

`OpenAiResponsesClientTest` 使用本地 HttpServer，验证路由/鉴权/模型/请求字段、429 无隐藏重试、错误正文不泄露、拒绝与截断处理，不产生真实模型费用。

`InfoContextEnricherTest` 验证同 ID 和窗口匹配、跨种类 ID 不误关联、无 ID 不猜测、条数/字符预算、跨 gzip 文件实际扫描量、脱敏与证据哈希变化。

Windows 未授予创建符号链接权限时，相关符号链接边界测试通过 assumption 显式跳过，应在具备权限的 Linux/Windows 环境补跑。

当前 JDK21 构建仍会报告部分测试代码的 deprecated/unchecked 提示，以及 Mockito/Byte Buddy 动态加载代理提示；未通过隐藏 warning 处理。它们不是测试失败，但在后续测试维护时应替换弃用调用、收敛原始泛型，并按 Mockito 文档配置测试代理。当前编译、测试和打包结果不代表已消除全部编译器提示。

### 6.2 显式 MySQL 全链路测试

`AiPipelineIntegrationTest` 需要一个专用空 MySQL 库；测试会建表和写入合成记录，不自动清理表。拒绝使用已有表的库，测试完成后由使用者处理专用库。它调用本地模拟 Responses 服务，不使用真实 API Key。

设置环境变量 `BADFISHER_TEST_DB_USERNAME`、`BADFISHER_TEST_DB_PASSWORD` 后执行：

```sh
mvn -pl ai-log-bootstrap -am test -Dtest=AiPipelineIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dbadfisher.test.mysql.url='jdbc:mysql://localhost:3306/badfisher_ai_log_test?serverTimezone=UTC'
```

未设置该 JDBC 系统属性时此测试显式跳过。测试设计覆盖真实 Flyway 初始化、HTTP 解析、AI prepare 幂等、INFO 进入请求和 Item/Attempt 成功保存。H2 曾因不支持 MySQL `UPDATE ... JOIN` 无法执行此路径，不能替换生产 SQL 来伪造该验收。当前交付环境没有 MySQL 服务，这项验证未执行成功。

### 6.3 发布前检查

| 项目 | 验收依据 |
| --- | --- |
| 构建 | JDK21 的 clean verify、测试统计和可执行 JAR |
| API | 未认证/无权、正确请求、错误参数、查询、并发版本冲突 |
| MySQL | 空库 Flyway、完整解析与 AI 持久化、重启恢复及重复触发 |
| OpenAI / DeepSeek | 分别使用显式授权的测试账号，验证普通分析、拒绝、限流、超时、工具循环、用量与脱敏 |
| 日志 | 文本、JSONL、自定义格式、gzip、日期、轮转、INFO 上限及计数守恒 |
| 部署 | JAR、Compose、目录权限、重启、数据保留和备份恢复 |
| 治理 | 单项审核、认领/指派、解决/验收/重开、新事件不覆盖状态 |
| 可选集成 | 实际启用的 SSH/Git/Loki/对象存储/通知逐项验收 |
| 发布内容 | 许可证及代码发布权利、公共依赖、无专属数据/密钥/机器路径、无历史仓库信息 |

源码已公开发布到 [GitHub](https://github.com/badfisheryhy/badfisher-ai-log) 和 [Gitee](https://gitee.com/badfisher/badfisher-ai-log)，已加入 Apache-2.0 LICENSE 和 NOTICE。当前已提供实现不等于上述每项已验收：真实 MySQL、OpenAI/DeepSeek、Compose 和可选外部服务尚未在交付环境完成验证；正式发行版、镜像或 Maven 包尚未发布。

## 7. 0.1.0 收尾与后续开发

已完成的收尾项：Controller 使用 DTO/View 返回治理、过滤规则和问题详情；补齐 `sync-api-enabled` 示例与使用说明；加入 Apache-2.0 LICENSE 和 NOTICE；将源码上传至 GitHub 和 Gitee 公开仓库。

持续保留的边界：数据库实体新增字段不得自动暴露给 API，AI 活动执行指针和内部状态锁不进入问题详情；新增或引入代码仍需确认发布权及许可证兼容性。仓库公开不代表正式发行版或完整环境验收已经完成。

后续能力按以下顺序推进：

1. 在专用 MySQL 和测试模型账号完成主流程验收，修复由实际运行证明的问题。
2. 完善固定 commit 源码快照和证据引用验证。
3. 将 parse/dispatch 包装为持久化通用异步操作，保留现有状态机与失败可追踪性。
4. 补齐本地报告存储/受保护下载，以及所选调度器装配；日志目录根白名单已实现，继续保留路径逃逸和空根拒绝测试。
5. 补齐贡献流程和发行说明，通过发布验收后创建版本标签并发布首个正式发行版；保留已有 LICENSE 和 NOTICE。
6. 再评估独立 Agent/前端需求，避免提前增加部署组件。

每一步根据具体变更更新相应使用或设计文档；不生成无意义的工作量报告。提交说明必须区分静态检查、编译、自动化测试、真实环境验收与正式发布。
