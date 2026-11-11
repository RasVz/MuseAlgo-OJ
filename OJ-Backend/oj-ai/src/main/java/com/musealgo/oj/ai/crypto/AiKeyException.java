package com.musealgo.oj.ai.crypto;

/** 主密钥或服务密钥不可用；消息固定为安全摘要，不携带底层异常文本。 */
public class AiKeyException extends RuntimeException {

    private final String errorCode;

    public AiKeyException(String message) { this("AI_SERVICE_KEY_UNAVAILABLE", message); }

    private AiKeyException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static AiKeyException master(String message) { return new AiKeyException("AI_MASTER_KEY_NOT_READY", message); }
    public String errorCode() { return errorCode; }
}
