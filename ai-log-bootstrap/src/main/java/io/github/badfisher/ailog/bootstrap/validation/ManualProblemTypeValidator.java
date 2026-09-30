package io.github.badfisher.ailog.bootstrap.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import io.github.badfisher.ailog.domain.issue.ProblemType;

/** 不重复维护分类编码；空值交由请求字段的 NotBlank 约束处理。 */
public class ManualProblemTypeValidator implements ConstraintValidator<ManualProblemType, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || ProblemType.manualCodes().contains(value);
    }
}
