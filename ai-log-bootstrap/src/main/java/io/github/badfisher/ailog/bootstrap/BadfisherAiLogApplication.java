package io.github.badfisher.ailog.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Independent Java 21 application entry point. */
@SpringBootApplication(scanBasePackages = "io.github.badfisher.ailog")
@MapperScan(basePackages = "io.github.badfisher.ailog.persistence",
        annotationClass = org.apache.ibatis.annotations.Mapper.class)
@EnableTransactionManagement(proxyTargetClass = true)
public class BadfisherAiLogApplication {

    public static void main(String[] args) {
        SpringApplication.run(BadfisherAiLogApplication.class, args);
    }
}