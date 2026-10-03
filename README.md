# badfisher-ai-log

面向 Java 应用的日志分析后端：解析 ERROR、归类聚合异常、按需提取 INFO 上下文，再通过可配置的 Responses API 分析原因，支持 OpenAI 与 DeepSeek。提供问题治理和任务查询 API，无业务前端。

当前版本 `0.1.0-SNAPSHOT`，JDK 21 单版本。工程已包含实现与自动化测试，尚未发布发行版。真实 MySQL、容器部署和真实 OpenAI/DeepSeek 账号联调仍需完成发布验收。

后续其他模型我也会尝试，可以当作一个干净的基础模型框架大家自行组合这样子，希望能改大家带来一些启发或者便利。

## 能力

- 常见 Spring Boot / Logback 文本、JSONL、自定义日志头正则、多行 Java 异常和 gzip 文件。
- 日志文件名通过 glob 配置，不绑定某个系统名称；ERROR 与 INFO 分别选择文件。
- ERROR 进入事件、聚合问题与治理数据；INFO 只在 AI 分析时按关联标识和时间窗口抽取，不建立全量 INFO 库。
- 独立 OpenAI / DeepSeek Responses HTTP 客户端，可配置地址、密钥、模型、超时和重试；不依赖私有 AI Starter。
- AI 批次、单问题分析、尝试记录、证据快照、用量和失败状态持久化；源码只读工具循环可选。
- BCrypt 本地账号、管理员接口、OpenAPI、Flyway 建表；默认只需应用和 MySQL。

## 构建与启动

需要 JDK 21、Maven 3.9、MySQL 8.4（或 Docker Compose）。先确认 `mvn -version` 使用 Java 21：

```sh
mvn clean verify
```

产物：`ai-log-bootstrap/target/ai-log-bootstrap-0.1.0-SNAPSHOT.jar`。

在交互终端生成管理员密码哈希，命令会隐藏密码输入：

```sh
java -Dloader.main=io.github.badfisher.ailog.bootstrap.security.PasswordHashTool -cp ai-log-bootstrap/target/ai-log-bootstrap-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

将 `config/application.example.yml` 复制为 `config/application-local.yml`。设置 `ADMIN_PASSWORD_HASH`、`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`LOG_ALLOWED_ROOT`（已存在的本地日志根目录绝对路径），连接专用空数据库，然后运行：

```sh
java -jar ai-log-bootstrap/target/ai-log-bootstrap-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod --spring.config.additional-location=file:./config/application-local.yml
```

默认地址 `http://127.0.0.1:8080`；API 文档 `/swagger-ui/index.html`，OpenAPI JSON `/v3/api-docs`。账号不提供默认密码。Windows PowerShell 设置环境变量使用 `$env:变量名='值'`；命令中的 Java `-D...` 参数在 PowerShell 中建议用单引号包裹。

DeepSeek 配置、源码工具兼容和验收步骤见 [DeepSeek 接入](docs/DEEPSEEK.md)。

完整流程见 [使用与部署](docs/USAGE.md)，包含 Compose、创建模块、示例解析、启用 AI、查询结果和排障。

## 文档

- [设计文档](docs/DESIGN.md)：数据流、模块、ERROR/INFO 契约、AI 与治理边界。
- [开发文档](docs/DEVELOPMENT.md)：构建、扩展、测试、数据库演进和发布门槛。
- [使用与部署](docs/USAGE.md)：从空库到 API 操作的完整步骤。

仅使用合成示例和公开依赖。独立 Agent、业务前端、自动修改源码、向量库与多租户不在当前版本范围。源码快照固定、通用异步提交及本地报告下载仍是后续工作，详见设计文档的实施边界。

## 许可证与署名

本项目采用 [Apache License 2.0](LICENSE)，作者署名为 **badfisher**，项目来源为 https://gitee.com/badfisher/badfisher-ai-log 。

分发本项目或衍生作品时，须依 Apache-2.0 第 4 条提供许可证副本，保留相关版权与署名声明，并保留 [NOTICE](NOTICE) 中适用的项目来源说明；修改过的文件须注明修改。署名可按许可证要求放在随附 NOTICE、源码、文档或适用的第三方声明展示位置，无须强制在产品首页展示。

建议在 README 或第三方依赖说明中使用：

> 本项目使用 badfisher 的 badfisher-ai-log，采用 Apache-2.0 许可证。项目地址：https://gitee.com/badfisher/badfisher-ai-log

正式发布前，代码所有者仍须确认全部代码与随附材料拥有合法发布权，第三方依赖遵循其各自许可证。
