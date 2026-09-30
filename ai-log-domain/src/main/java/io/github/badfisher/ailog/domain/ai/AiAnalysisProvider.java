package io.github.badfisher.ailog.domain.ai;

/**
 * AI 分析 Provider 端口。
 *
 * <p>应用与领域层只依赖本端口，不依赖任何厂商 SDK；
 * 实现方必须遵守以下边界，违反即视为实现缺陷：</p>
 *
 * <ul>
 *   <li>只接受 {@link SanitizedAiEvidence}，原始证据禁止进入外发链；</li>
 *   <li>不得修改 Issue、Event 或任何持久化状态；</li>
 *   <li>不得自动设置 expected，规则建议只能通过结果字段返回；</li>
 *   <li>必须返回结构化、可校验的 {@link AiAnalysisResult}；</li>
 *   <li>失败必须抛出 {@link AiProviderException}，不允许吞错或返回半结构化内容。</li>
 * </ul>
 */
public interface AiAnalysisProvider {

    /**
     * 对单个 Issue 的脱敏证据执行 AI 分析。
     *
     * @param request 分析请求，含脱敏证据与 Prompt 版本
     * @return 结构化分析结果
     * @throws AiProviderException 网络、限流、服务端、请求或响应结构错误时抛出
     */
    AiAnalysisResult analyze(AiAnalysisRequest request);
}
