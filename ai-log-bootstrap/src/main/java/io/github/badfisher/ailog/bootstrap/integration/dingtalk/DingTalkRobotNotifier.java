package io.github.badfisher.ailog.bootstrap.integration.dingtalk;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.util.StringUtils;

import com.dingtalk.api.DefaultDingTalkClient;
import com.dingtalk.api.DingTalkClient;
import com.dingtalk.api.request.OapiRobotSendRequest;
import com.dingtalk.api.response.OapiRobotSendResponse;
import io.github.badfisher.ailog.application.tool.NotificationException;
import io.github.badfisher.ailog.application.tool.NotificationTool;

/** 基于钉钉官方 SDK 的群机器人消息适配器。 */
public final class DingTalkRobotNotifier implements NotificationTool {

    private static final String SIGN_ALGORITHM = "HmacSHA256";

    private final DingTalkRobotProperties properties;
    private final DingTalkRobotTransport transport;

    /** 使用真实钉钉 SDK 创建消息适配器。 */
    public DingTalkRobotNotifier(DingTalkRobotProperties propertiesValue) {
        this(propertiesValue, new DefaultDingTalkRobotTransport());
    }

    DingTalkRobotNotifier(DingTalkRobotProperties propertiesValue,
            DingTalkRobotTransport transportValue) {
        if (propertiesValue == null || transportValue == null) {
            throw new IllegalArgumentException("DingTalk properties and transport must not be null");
        }
        properties = propertiesValue;
        transport = transportValue;
        requireText(properties.getWebhookUrl(),
                "DingTalk webhook URL must be configured when notification is enabled");
        requireText(properties.getTitle(), "DingTalk title must not be blank");
    }

    @Override
    public void sendText(String text) {
        String content = requireText(text, "DingTalk text must not be blank");
        String webhookUrl = signedWebhookUrl();
        try {
            transport.sendText(webhookUrl, content);
        } catch (Exception exception) {
            throw new NotificationException("Failed to send DingTalk text notification", exception);
        }
    }

    @Override
    public void sendMarkdown(String markdown) {
        sendMarkdown(properties.getTitle(), markdown);
    }

    @Override
    public void sendMarkdown(String title, String markdown) {
        String messageTitle = requireText(title, "DingTalk title must not be blank");
        String content = requireText(markdown, "DingTalk markdown must not be blank");
        String webhookUrl = signedWebhookUrl();
        try {
            transport.sendMarkdown(webhookUrl, messageTitle, content);
        } catch (Exception exception) {
            throw new NotificationException("Failed to send DingTalk markdown notification", exception);
        }
    }

    private String signedWebhookUrl() {
        String webhookUrl = properties.getWebhookUrl();
        String secret = properties.getSecret();
        if (!StringUtils.hasText(secret)) {
            return webhookUrl;
        }
        long timestamp = System.currentTimeMillis();
        String content = timestamp + "\n" + secret;
        try {
            Mac mac = Mac.getInstance(SIGN_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), SIGN_ALGORITHM));
            byte[] signature = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            String encodedSignature = URLEncoder.encode(
                    Base64.getEncoder().encodeToString(signature),
                    StandardCharsets.UTF_8.name());
            String separator = webhookUrl.contains("?") ? "&" : "?";
            return webhookUrl + separator + "timestamp=" + timestamp + "&sign=" + encodedSignature;
        } catch (GeneralSecurityException | UnsupportedEncodingException exception) {
            throw new NotificationException("Failed to sign DingTalk robot webhook", exception);
        }
    }

    private static String requireText(String value, String errorMessage) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(errorMessage);
        }
        return value;
    }

    /** 使用钉钉官方 SDK 发送消息，并对业务响应统一执行成功校验。 */
    private static final class DefaultDingTalkRobotTransport implements DingTalkRobotTransport {

        @Override
        public void sendText(String webhookUrl, String text) throws Exception {
            OapiRobotSendRequest request = new OapiRobotSendRequest();
            request.setMsgtype("text");
            OapiRobotSendRequest.Text textMessage = new OapiRobotSendRequest.Text();
            textMessage.setContent(text);
            request.setText(textMessage);
            execute(webhookUrl, request);
        }

        @Override
        public void sendMarkdown(String webhookUrl, String title, String markdown) throws Exception {
            OapiRobotSendRequest request = new OapiRobotSendRequest();
            request.setMsgtype("markdown");
            OapiRobotSendRequest.Markdown markdownMessage = new OapiRobotSendRequest.Markdown();
            markdownMessage.setTitle(title);
            markdownMessage.setText(markdown);
            request.setMarkdown(markdownMessage);
            execute(webhookUrl, request);
        }

        private static void execute(String webhookUrl, OapiRobotSendRequest request) throws Exception {
            DingTalkClient client = new DefaultDingTalkClient(webhookUrl);
            OapiRobotSendResponse response = client.execute(request);
            if (response == null || !response.isSuccess()) {
                String errorCode = response == null ? "null" : String.valueOf(response.getErrcode());
                String errorMessage = response == null ? "empty response" : response.getErrmsg();
                throw new IllegalStateException("DingTalk rejected robot notification: "
                        + errorCode + ", " + errorMessage);
            }
        }
    }
}
