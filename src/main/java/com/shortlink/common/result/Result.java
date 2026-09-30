package com.shortlink.common.result;

import lombok.Data;

/**
 * 统一响应结构
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> {

    /** 成功码 */
    public static final String SUCCESS_CODE = "0";

    private String code;
    private String message;
    private T data;

    private Result(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, "success", data);
    }

    public static Result<Void> success() {
        return success(null);
    }

    public static <T> Result<T> failure(String code, String message) {
        return new Result<>(code, message, null);
    }

    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
    }
}
