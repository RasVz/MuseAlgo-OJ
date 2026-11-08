package com.musealgo.oj.ai.adminai;

import lombok.Getter;

/** 管理接口的受控业务异常；消息不得包含密钥、密文或供应商原始响应。 */
@Getter
public class AiAdminException extends com.musealgo.oj.common.exception.BusinessException {

    private final int code;

    public AiAdminException(int code, String message) {
        super(new com.musealgo.oj.common.exception.ErrorDefinition(switch (code) {
            case 400 -> "AI_SERVICE_INPUT_INVALID";
            case 404 -> "AI_SERVICE_NOT_FOUND";
            case 409 -> "AI_SERVICE_CONFLICT";
            case 429 -> "AI_SERVICE_TEST_BUSY";
            case 503 -> "AI_SERVICE_UNAVAILABLE";
            default -> "AI_SERVICE_ERROR";
        }, code, message));
        this.code = code;
    }
}
