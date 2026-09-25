package com.buildrisk.radar.common.security;

import com.buildrisk.radar.common.error.ErrorResponse;
import com.buildrisk.radar.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/** 보안 필터에서 나가는 401·403·429 도 API 공통 오류 형식 {code, message, traceId} 으로 */
final class JsonErrorWriter {
    private JsonErrorWriter() {}

    static void write(HttpServletResponse res, ObjectMapper mapper, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(mapper.writeValueAsString(new ErrorResponse(code, message, MDC.get(TraceIdFilter.MDC_KEY))));
    }
}
