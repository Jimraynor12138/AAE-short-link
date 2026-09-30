package com.shortlink.common.exception;

import lombok.Getter;

/**
 * 业务异常：携带错误码与用户可读信息
 */
@Getter
public class BizException extends RuntimeException {

    private final String code;

    public BizException(String message) {
        this("A0001", message);
    }

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }
}
