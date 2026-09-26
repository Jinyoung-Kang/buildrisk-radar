package com.buildrisk.radar.common.security;

import com.buildrisk.radar.common.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 인증·인가 (ADR-013).
 * <ul>
 *   <li>조회(GET)는 공개 — 공개 데이터 기반 모니터. 변경은 역할 필요: 경보 확인(ACK) = ANALYST 이상, 규칙·배치·매핑·감사 = ADMIN.</li>
 *   <li>브라우저: 세션 로그인(Spring Session Redis, HttpOnly · SameSite=Strict 쿠키) + CSRF(XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더).</li>
 *   <li>스크립트: 서비스 토큰(X-Admin-Token) — 요청 단위 인증, 세션·CSRF 없음.</li>
 *   <li>레이트리밋(IP·분) · 로그인 잠금 · 변경 요청 감사 로그 · 보안 헤더.</li>
 * </ul>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    public static final String LOGIN_PATH = "/api/v1/auth/login";

    @Bean
    SecurityFilterChain api(HttpSecurity http, AppProperties props, ObjectMapper mapper, RateLimiter limiter,
                            AuditService audit, CookieCsrfTokenRepository csrfRepository,
                            org.springframework.context.ApplicationContext context,
                            SecurityContextRepository contextRepository,
                            @Value("${server.servlet.session.cookie.name:BR_SESSION}") String sessionCookie) throws Exception {
        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/**").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/alerts/*").hasAnyRole("ANALYST", "ADMIN")
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                // CSRF 는 쿠키로 인증되는 요청에만 의미가 있음: 서비스 토큰 요청과 세션 쿠키 없는(=익명) 요청은 제외.
                // 로그인은 세션 쿠키가 없어도 검사 (로그인 CSRF 방지).
                .csrf(c -> c.spa().csrfTokenRepository(csrfRepository)
                        .ignoringRequestMatchers(req -> ServiceTokenFilter.hasToken(req)
                                || (!hasCookie(req, sessionCookie) && !LOGIN_PATH.equals(req.getRequestURI()))))
                .securityContext(s -> s.securityContextRepository(contextRepository))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .requestCache(r -> r.disable())
                .logout(l -> l.logoutUrl("/api/v1/auth/logout")
                        .addLogoutHandler((req, res, auth) -> {
                            if (auth != null) audit.record(req, auth, "LOGOUT", auth.getName(), 204, null);
                        })
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                JsonErrorWriter.write(res, mapper, 401, "UNAUTHORIZED", "로그인이 필요합니다."))
                        .accessDeniedHandler((req, res, ex) -> {
                            if (ex instanceof CsrfException) {
                                JsonErrorWriter.write(res, mapper, 403, "CSRF_INVALID",
                                        "CSRF 토큰이 없거나 만료됐습니다. 페이지를 새로 고친 뒤 다시 시도하세요.");
                            } else {
                                JsonErrorWriter.write(res, mapper, 403, "FORBIDDEN", "이 작업을 할 권한이 없습니다.");
                            }
                        }))
                .headers(h -> h
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", "camera=(), microphone=(), geolocation=()"))
                        // JSON API 는 어떤 리소스도 불러오지 않음 — 응답이 문서로 해석돼도 스크립트 실행 불가
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(req -> req.getRequestURI().startsWith("/api/"),
                                new ContentSecurityPolicyHeaderWriter("default-src 'none'; frame-ancestors 'none'"))))
                .addFilterBefore(new RateLimitFilter(limiter, props.security().rateLimitPerMinute(), mapper), DisableEncodeUrlFilter.class)
                .addFilterBefore(new ServiceTokenFilter(props, mapper), CsrfFilter.class)
                .addFilterBefore(new AuditFilter(audit, mapper, req -> routeTemplate(context, req)), ServiceTokenFilter.class);
        return http.build();
    }

    /** MVC 매핑으로 경로 템플릿을 찾음 (거부된 요청의 감사 로그용). 매핑이 없거나 메서드가 안 맞으면 null */
    private static String routeTemplate(org.springframework.context.ApplicationContext context, HttpServletRequest req) {
        try {
            var mapping = context.getBean("requestMappingHandlerMapping",
                    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
            if (mapping.getHandler(req) == null) return null;
            Object p = req.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            return p == null ? null : p.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasCookie(HttpServletRequest req, String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return false;
        for (Cookie c : cookies) if (name.equals(c.getName())) return true;
        return false;
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(AppProperties props) {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();   // SPA 가 읽어 헤더로 되돌려 보냄
        repo.setCookiePath("/");
        repo.setCookieCustomizer(c -> c.sameSite("Strict").secure(props.security().cookieSecure()));
        return repo;
    }

    /**
     * 세션 쿠키를 명시적으로 정의 — Boot 는 내장 서버가 있을 때만 server.servlet.session.cookie.* 를 적용하므로
     * 테스트(MockMvc)·운영이 같은 속성을 쓰도록 여기서 고정합니다.
     */
    @Bean
    CookieSerializer cookieSerializer(AppProperties props,
                                      @Value("${server.servlet.session.cookie.name:BR_SESSION}") String name) {
        var c = new DefaultCookieSerializer();
        c.setCookieName(name);
        c.setCookiePath("/");
        c.setUseHttpOnlyCookie(true);
        c.setSameSite("Strict");
        c.setUseSecureCookie(props.security().cookieSecure());
        return c;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    @Bean
    PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }

    /** 계정은 환경변수로만 (DB 사용자 테이블 없음 — 운영자 2역할 서비스). 평문이면 기동 시 BCrypt 로 해시해 메모리에만 */
    @Bean
    UserDetailsService users(AppProperties props, PasswordEncoder encoder) {
        var s = props.security();
        List<UserDetails> users = new ArrayList<>();
        add(users, s.adminUsername(), s.adminPassword(), encoder, "ADMIN", "ANALYST");
        add(users, s.analystUsername(), s.analystPassword(), encoder, "ANALYST");
        if (users.isEmpty()) log.warn("ADMIN_PASSWORD 가 비어 있어 로그인 계정이 없습니다 — 변경 작업은 서비스 토큰(X-Admin-Token)으로만 가능");
        return new InMemoryUserDetailsManager(users);
    }

    private static void add(List<UserDetails> users, String name, String password, PasswordEncoder encoder, String... roles) {
        if (name == null || name.isBlank() || password == null || password.isBlank()) return;
        if (!password.startsWith("{") && password.length() < 12) {
            log.warn("계정 {} 의 비밀번호가 12자 미만입니다 — 로그인 비활성화", name);
            return;
        }
        String encoded = password.startsWith("{") ? password : encoder.encode(password);
        users.add(User.withUsername(name).password(encoded).roles(roles).build());
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
}
