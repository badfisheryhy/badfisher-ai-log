package io.github.badfisher.ailog.bootstrap.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.badfisher.ailog.bootstrap.web.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 管理端请求参数校验异常处理器。 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
        ModuleConfigController.class,
        ClassifyRuleController.class,
        SuppressRuleController.class,
        GroupGovernanceController.class,
        PersonalWorkbenchController.class,
        DashboardController.class,
        PipelineController.class
})
public class ManagementValidationExceptionHandler {

    /** 返回包含字段名和校验原因的统一错误响应。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception) {
        String message = validationMessage(exception);
        log.warn("event=management_request_validation_failed 管理端请求参数校验失败：{}",
                message);
        return ApiResponse.error(Integer.valueOf(HttpStatus.BAD_REQUEST.value()), message);
    }

    private static String validationMessage(MethodArgumentNotValidException exception) {
        Map<String, String> fieldMessages = new LinkedHashMap<String, String>();
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors();
        for (FieldError fieldError : fieldErrors) {
            if (!fieldMessages.containsKey(fieldError.getField())) {
                fieldMessages.put(fieldError.getField(), readableMessage(fieldError));
            }
        }
        if (!fieldMessages.isEmpty()) {
            StringBuilder message = new StringBuilder("请求参数校验失败：");
            for (Map.Entry<String, String> entry : fieldMessages.entrySet()) {
                if (message.length() > "请求参数校验失败：".length()) {
                    message.append("；");
                }
                message.append(entry.getKey())
                        .append("（")
                        .append(entry.getValue())
                        .append("）");
            }
            return message.toString();
        }
        ObjectError globalError = exception.getBindingResult().getGlobalError();
        return "请求参数校验失败：" + readableMessage(globalError);
    }

    private static String readableMessage(ObjectError error) {
        if (error == null || error.getDefaultMessage() == null
                || error.getDefaultMessage().trim().isEmpty()) {
            return "参数值不符合要求";
        }
        return error.getDefaultMessage().trim();
    }
}
