package io.github.badfisher.ailog.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Springfox Swagger接口文档配置。 */
@Getter
@Setter
@ConfigurationProperties("swagger")
public class SwaggerProperties {

    /** 是否启用接口文档。 */
    private boolean enabled;
    /** 文档标题。 */
    private String title = "Badfisher AI Log API";
    /** 文档说明。 */
    private String description = "AI日志同步与分析服务接口";
    /** 接口版本。 */
    private String version = "2.0.0";
    /** 需要生成文档的Controller包。 */
    private String basePackage = "io.github.badfisher.ailog.bootstrap.controller";
    /** 网关转发路径前缀，本地直连时为空。 */
    private String pathMapping = "";
}
