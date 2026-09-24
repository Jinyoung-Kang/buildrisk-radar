package com.buildrisk.radar.common.web;

import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 관리 API(규칙 변경·배치 실행·매핑 규칙 추가)는 X-Admin-Token 필수 (8-1). */
@Component
public class AdminTokenInterceptor implements HandlerInterceptor {
    public static final String HEADER = "X-Admin-Token";
    private final AppProperties props;

    public AdminTokenInterceptor(AppProperties props) { this.props = props; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String method = req.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) return true;
        String expected = props.adminToken();
        String given = req.getHeader(HEADER);
        if (expected == null || expected.isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "서버에 ADMIN_TOKEN 이 설정되지 않았습니다.");
        }
        if (given == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "관리 토큰(X-Admin-Token)이 올바르지 않습니다.");
        }
        return true;
    }
}
