package com.buildrisk.radar.api;

import com.buildrisk.radar.common.error.ErrorResponse;
import com.buildrisk.radar.common.role.ApiRole;
import com.buildrisk.radar.common.security.AuditService;
import com.buildrisk.radar.common.security.LoginThrottle;
import com.buildrisk.radar.common.security.ServiceTokenFilter;
import com.buildrisk.radar.common.web.TraceIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "인증", description = "세션 로그인 (브라우저). 스크립트는 X-Admin-Token 서비스 토큰을 쓰세요.")
@ApiRole
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepository;
    private final CookieCsrfTokenRepository csrfRepository;
    private final LoginThrottle throttle;
    private final AuditService audit;

    public AuthController(AuthenticationManager authManager, SecurityContextRepository contextRepository,
                          CookieCsrfTokenRepository csrfRepository, LoginThrottle throttle, AuditService audit) {
        this.authManager = authManager;
        this.contextRepository = contextRepository;
        this.csrfRepository = csrfRepository;
        this.throttle = throttle;
        this.audit = audit;
    }

    public record LoginRequest(@NotBlank @Size(max = 60) String username, @NotBlank @Size(max = 200) String password) {}

    public record Me(boolean authenticated, String username, List<String> roles, String authType) {}

    @Operation(summary = "로그인 — 성공 시 세션 쿠키(BR_SESSION) 발급, 세션 ID·CSRF 토큰 교체. 연속 실패 시 429")
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        String ip = req.getRemoteAddr();
        var locked = throttle.lockedFor(body.username(), ip);
        if (locked.isPresent()) {
            audit.record(body.username(), "SESSION", "LOGIN_LOCKED", body.username(), 429, null, req);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(locked.getAsLong()))
                    .body(new ErrorResponse("TOO_MANY_REQUESTS",
                            "로그인 실패가 많아 잠시 잠겼습니다. " + Math.max(1, locked.getAsLong() / 60) + "분 뒤 다시 시도하세요.",
                            MDC.get(TraceIdFilter.MDC_KEY)));
        }
        Authentication auth;
        try {
            auth = authManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        } catch (AuthenticationException e) {
            throttle.failure(body.username(), ip);
            audit.record(body.username(), "SESSION", "LOGIN_FAILURE", body.username(), 401, null, req);
            // 아이디 존재 여부를 드러내지 않는 같은 메시지
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("LOGIN_FAILED",
                    "아이디 또는 비밀번호가 올바르지 않습니다.", MDC.get(TraceIdFilter.MDC_KEY)));
        }
        throttle.success(body.username(), ip);
        // 세션 고정 공격 방지: 로그인 전 세션이 있으면 ID 교체
        if (req.getSession(false) != null) req.changeSessionId();
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, req, res);
        // 인증 상태가 바뀌었으므로 CSRF 토큰도 교체
        csrfRepository.saveToken(csrfRepository.generateToken(req), req, res);
        audit.record(req, auth, "LOGIN_SUCCESS", auth.getName(), 200, null);
        return ResponseEntity.ok(me(auth));
    }

    @Operation(summary = "현재 사용자 — 익명이면 authenticated=false. 호출하면 XSRF-TOKEN 쿠키가 발급됩니다")
    @GetMapping("/me")
    public Me me() { return me(SecurityContextHolder.getContext().getAuthentication()); }

    private static Me me(Authentication auth) {
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            return new Me(false, null, List.of(), null);
        }
        List<String> roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).sorted().toList();
        String type = ServiceTokenFilter.AUTH_TYPE.equals(auth.getDetails()) ? "TOKEN" : "SESSION";
        return new Me(true, auth.getName(), roles, type);
    }
}
