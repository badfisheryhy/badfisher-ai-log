package io.github.badfisher.ailog.bootstrap.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** 使用统一问题类型枚举校验人工输入，错误仍归属原请求字段。 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ManualProblemTypeValidator.class)
public @interface ManualProblemType {

    String message() default "问题类型不受支持";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
