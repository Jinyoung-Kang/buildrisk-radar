package com.buildrisk.radar.common.error;

import java.io.Serial;

public class ApiException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;
    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() { return code; }

    public static ApiException notFound(ErrorCode code, String what) {
        return new ApiException(code, what + "을(를) 찾을 수 없습니다.");
    }
}
