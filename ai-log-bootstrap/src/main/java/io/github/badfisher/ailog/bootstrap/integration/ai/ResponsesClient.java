package io.github.badfisher.ailog.bootstrap.integration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Responses 协议传输端口；供应商处理自己的连接和参数，不参与业务状态机。 */
public interface ResponsesClient {
    JsonNode createResponse(ObjectNode body, String model);
}
