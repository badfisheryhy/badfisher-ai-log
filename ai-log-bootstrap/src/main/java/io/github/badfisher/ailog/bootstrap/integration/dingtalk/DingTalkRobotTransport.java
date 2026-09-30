package io.github.badfisher.ailog.bootstrap.integration.dingtalk;

/** 钉钉机器人 SDK 传输边界，供适配器隔离真实网络。 */
interface DingTalkRobotTransport {

    void sendText(String webhookUrl, String text) throws Exception;

    void sendMarkdown(String webhookUrl, String title, String markdown) throws Exception;
}
