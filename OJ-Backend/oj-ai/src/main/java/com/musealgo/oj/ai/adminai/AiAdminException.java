package com.musealgo.oj.ai.adminai;

import lombok.Getter;

/** 管理接口的受控业务异常；消息不得包含密钥、密文或供应商原始响应。 */
@Getter
public class AiAdminException extends RuntimeException {

    private final int code;

    public AiAdminException(int code, String message) {
        super(message);
        this.code = code;
    }
}
