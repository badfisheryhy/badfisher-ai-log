package io.github.badfisher.ailog.application.tool;

/**
 * 外部消息通知端口。
 *
 * <p>应用编排只声明通知内容，不感知钉钉 SDK、机器人地址、签名密钥或传输协议。</p>
 */
public interface NotificationTool {

    /**
     * 发送纯文本通知。
     *
     * @param text 文本正文
     */
    void sendText(String text);

    /**
     * 使用外部配置的默认标题发送 Markdown 通知。
     *
     * @param markdown Markdown 正文
     */
    void sendMarkdown(String markdown);

    /**
     * 使用调用方指定的标题发送 Markdown 通知。
     *
     * @param title 消息标题
     * @param markdown Markdown 正文
     */
    void sendMarkdown(String title, String markdown);
}
