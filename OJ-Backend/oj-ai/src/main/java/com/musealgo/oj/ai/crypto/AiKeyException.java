package com.musealgo.oj.ai.crypto;

/** 主密钥或服务密钥不可用；消息固定为安全摘要，不携带底层异常文本。 */
public class AiKeyException extends RuntimeException {

    public AiKeyException(String message) {
        super(message);
    }
}
