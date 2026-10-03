# DeepSeek 接入与验证

适用 `0.1.0-SNAPSHOT` / JDK 21。本接入针对 DeepSeek 官方 Responses API，协议依据为 [API 参考](https://api-docs.deepseek.com/api/create-response/) 和 [兼容表](https://api-docs.deepseek.com/guides/responses_api/)（核对日期：2026-10-03）。只支持 Chat Completions 的中转服务不属于本接入范围。

## 1. 实现边界

- `ResponsesClient` 是 bootstrap 内的协议传输端口，不进入领域模型或持久化模块。
- `OpenAiResponsesClient` 保持原有请求、结果、超时和错误处理，只声明实现该端口；两个现有分析 Provider 只将构造依赖改为接口，提示词、结果校验和工具状态机不变。
- `integration/ai/deepseek/DeepSeekResponsesClient` 独立管理 HTTP 连接与 DeepSeek 参数；`api-type: DEEPSEEK_RESPONSES` 显式选择它。未配置 `api-type` 仍使用原有 `OPENAI_RESPONSES`，不根据供应商名、模型名或域名猜测协议。
- 每个供应商拥有自己的密钥、连接超时和客户端。模型的 `reasoning-effort` 仅由 DeepSeek 客户端读取；OpenAI 请求不自动增加该参数。
- 一次 HTTP 调用没有隐藏重试。429 为限流，5xx 为服务端错误，网络/超时为网络失败；其他非 2xx（含 401、402）为请求错误。持久化 Item/Attempt 状态机决定可重试错误的次数与退避。
- 不新增业务 API、数据库表、缓存或供应商间自动降级。结果继续走既有校验、用量记录和人工治理流程。

## 2. 本地 JAR 配置

先按[使用与部署](USAGE.md)配置数据库、管理员和允许日志根，构建 JAR。已有 `application-local.yml` 时保留原文件，只复制 DeepSeek 模板；首次配置时复制两个模板：

```sh
cp config/application.example.yml config/application-local.yml
cp config/application-deepseek.example.yml config/application-deepseek-local.yml
```

两个本地文件均已忽略，不提交真实凭据。

在本地交互终端安全注入 `DEEPSEEK_API_KEY`，并设置：

```sh
export AI_ENABLED=true
export DEEPSEEK_BASE_URL=https://api.deepseek.com
```

管理员、数据库和 `LOG_ALLOWED_ROOT` 环境变量沿用使用文档。JAR 启动不会自动加载 `.env`；只有 Compose 使用 `.env`。

```sh
java -jar ai-log-bootstrap/target/ai-log-bootstrap-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=prod \
  --spring.config.additional-location=file:./config/application-local.yml,file:./config/application-deepseek-local.yml
```

后面的 DeepSeek 配置覆盖默认 AI 路由，不覆盖账号、数据库或日志读取配置。`base-url` 不带 `/responses`，客户端追加一次；生产使用 HTTPS，本地 HTTP 模拟服务才显式开启 `allow-insecure-http`。

模板选用官方文档示例模型 `deepseek-flash`。选其他支持 Responses 的模型时，同时修改 `default-model` 和 `providers.deepseek.models` 下的模型键；不要只修改默认名称后误用新建模型的默认预算。模型名称由配置决定，不在实现中固定。

普通分析使用 `ai-issue-v4`，工具默认关闭，`reasoning-effort: none` 显式关闭思考模式。允许的配置值为 `none`、`low`、`high`、`max`；不配置时采用供应商默认行为。`max-tokens` 对应 `max_output_tokens`，应用限制为 1～32768，模板 4096 为联调起点，不保证任意证据都能完成。思考模式的输出预算包含推理 Token，应结合截断、耗时和用量调整预算与超时。

## 3. Docker Compose

原有 `compose.yml` 和 OpenAI 环境变量不变；DeepSeek 使用单独叠加文件。完成上面的本地模板复制，将 `.env.example` 复制为 `.env`，填入已有数据库、管理员变量以及 `DEEPSEEK_API_KEY`，设置 `AI_ENABLED=true`。

```sh
docker compose -f compose.yml -f compose.deepseek.yml config --quiet
docker compose -f compose.yml -f compose.deepseek.yml up --build -d
docker compose -f compose.yml -f compose.deepseek.yml logs -f app
```

默认 OpenAI 变量可以留空，因为 DeepSeek 模板关闭了 OpenAI 路由。没有容器启动记录时，不把配置验证视为容器运行验收。

## 4. 源码分析与思考模式

按使用文档准备专用源码目录 `workspace/environment/systemCode/moduleCode`。在 DeepSeek 本地配置中设置：

```yaml
badfisher:
  ai:
    prompt-version: ai-issue-v5-tools
    function-calling:
      enabled: true
      max-tool-calls: 6
```

思考模式可在实际模型配置中改为 `reasoning-effort: high`，再根据实际调用调整输出预算。关闭工具时恢复 `prompt-version: ai-issue-v4` 与 `function-calling.enabled: false`，避免协议版本不匹配。

工具开关和 Prompt 版本仍是既有全局配置；同时启用多个供应商时共同使用，不承诺按路由独立切换工具模式。

DeepSeek 不保存服务器会话，每轮发送完整输入历史。原有工具循环会回传全部输出，包括明文 reasoning 和每个 function call 对应的结果；推理正文只用于内存中的下一轮，不写入最终分析审计。DeepSeek 边界去掉 `store`、`include`、`parallel_tool_calls`、`max_tool_calls` 及 OpenAI 推理历史中的加密/摘要字段；带服务器会话 ID 或缺少明文的加密推理历史被拒绝。

DeepSeek 可能在一个响应里提出多个工具调用，不能靠 `parallel_tool_calls: false` 限制。现有执行器顺序执行并计数，额度耗尽的额外调用返回明确错误并保留配对，随后用 `tool_choice: none` 收尾。重复（含跨轮复用）或空工具调用 ID 在执行前拒绝。路径、符号链接、文件类型、字符、行数与检索预算仍由现有只读执行器校验。没有源码证据时不生成源码事实。

## 5. 供应商切换与历史任务

任务保存创建时的供应商与模型，调度使用任务记录，不使用执行时的新默认值。切换默认供应商仅影响之后按默认配置创建的任务；旧结果不会被批量替换。需要重新分析时通过既有创建任务或显式重跑流程操作。

仍有待执行的 OpenAI 任务时，不要直接使用模板的 `openai.enabled: false`：保留 OpenAI 路由、密钥和对应模型配置，待旧任务完成后再禁用。Prompt 版本也必须与正在执行的普通/工具任务匹配。

供应商和模型路由是任务快照，但输出预算、推理强度等参数取运行配置，尚不是完整请求参数快照。变更这些配置前先处理在途任务，再创建新任务或显式重跑；不宣称旧任务具有严格可复现参数。`modelProfileCode` 尚未接入请求参数持久化。

`evidenceHash` 仅包含证据与 Prompt/脱敏版本，不作为跨供应商调用缓存。本次不增加结果复用；未来若加入复用，须独立纳入供应商、模型和请求参数 profile。

## 6. 验证与排障

本地测试使用合成日志、隔离源码目录和本地 HTTP 服务，不消耗真实模型额度：

```sh
mvn -pl ai-log-bootstrap -am test \
  -Dtest=OpenAiResponsesClientTest,DeepSeekCompatibilityTest,AiProviderIsolationTest,AiTaskJobServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn clean verify
```

macOS 的默认临时目录包含 `/var` 符号链接，路径安全测试可能拒绝它。使用项目内真实临时目录：

```sh
mkdir -p .tools/test-tmp
mvn clean verify -DargLine="-Djava.io.tmpdir=$PWD/.tools/test-tmp"
```

测试覆盖 OpenAI 原合同、DeepSeek 请求参数、供应商凭据隔离、默认供应商变更不改投旧任务、普通结果与用量、无隐藏重试、完整工具历史、多个调用、额度耗尽和拒绝非法结果。模拟测试不替代真实 DeepSeek 或 MySQL 验收。

真实联调按使用文档先解析一条合成日志，再调用 `/api/pipeline/ai/prepare` 和 `/api/pipeline/ai/dispatch`，检查实际请求模型、任务/Item/Attempt 状态、最终 JSON 和 Token 记录。随后分别启用源码分析、思考模式与低预算截断案例。完整 MySQL 测试仍需专用空库，见开发文档。

| 现象 | 检查方式 |
| --- | --- |
| 启动提示缺少密钥 | 确认真实变量进入 Java/容器环境，AI 禁用时不应调用外部服务 |
| API 401 / 402 | 检查 DeepSeek 凭据、账号和余额；请求错误不自动重试 |
| API 429 / 5xx | 检查 Attempt 退避和次数，恢复后按现有流程再次 dispatch |
| INVALID_RESPONSE / incomplete | 核对完成状态、空内容、JSON 字段和输出预算；不将截断结果写成成功 |
| 旧任务找不到路由 | 恢复该任务保存的供应商和模型配置，不改投其他供应商 |
| 工具分析失败 | 检查专用源码目录、Prompt 版本、只读工具参数和预算 |

错误审计不保留上游 HTTP 错误正文，排查时不要通过打印密钥、完整源码或未脱敏证据获取错误详情。
