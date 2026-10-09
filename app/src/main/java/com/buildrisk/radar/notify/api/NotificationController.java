package com.buildrisk.radar.notify.api;

import com.buildrisk.radar.common.role.ApiRole;
import com.buildrisk.radar.notify.app.NotificationQueryService.Delivery;
import com.buildrisk.radar.notify.app.NotificationQueryService.Inbox;
import com.buildrisk.radar.notify.app.NotificationQueryService.ReadRequest;
import com.buildrisk.radar.notify.app.NotificationQueryService.Subscription;
import com.buildrisk.radar.notify.app.NotificationQueryService.SubscriptionRequest;
import com.buildrisk.radar.notify.app.NotificationQueryService.TestResult;
import com.buildrisk.radar.notify.app.NotificationQueryService.Watch;
import com.buildrisk.radar.notify.app.NotificationQueryService.WebhookCreated;
import com.buildrisk.radar.notify.app.NotificationQueryService.WebhookRequest;
import com.buildrisk.radar.notify.app.NotificationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 알림 (ADR-025) — /me 는 로그인한 본인 것만(조회도 로그인 필요), /admin/webhooks 는 관리자 */
@RestController
@ApiRole
@RequestMapping("/api/v1")
@Tag(name = "알림", description = "받은 알림함 · 구독 · 관심 목록 · 웹훅 (ADR-025)")
@Validated
public class NotificationController {
    private final NotificationQueryService svc;

    public NotificationController(NotificationQueryService svc) { this.svc = svc; }

    private static boolean admin(Authentication a) {
        return a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_ADMIN"));
    }

    @GetMapping("/me/notifications")
    @Operation(summary = "받은 알림함 (최신순)")
    public Inbox inbox(Authentication auth, @RequestParam(defaultValue = "false") boolean unreadOnly,
                       @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
                       @RequestParam(defaultValue = "30") @Min(1) @Max(100) int size) {
        return svc.inbox(auth.getName(), unreadOnly, page, size);
    }

    @GetMapping("/me/notifications/unread-count")
    @Operation(summary = "읽지 않은 알림 수 (머리말 종 아이콘)")
    public Map<String, Integer> unread(Authentication auth) { return Map.of("unread", svc.unread(auth.getName())); }

    @PostMapping("/me/notifications/read")
    @Operation(summary = "읽음 표시 — ids 또는 all=true")
    public Map<String, Integer> read(Authentication auth, @RequestBody ReadRequest body) {
        return Map.of("updated", svc.markRead(auth.getName(), body));
    }

    @GetMapping("/me/subscriptions")
    @Operation(summary = "내 구독")
    public List<Subscription> subscriptions(Authentication auth) { return svc.subscriptions(auth.getName()); }

    @PostMapping("/me/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "구독 만들기 — 웹훅 채널은 관리자만")
    public Subscription create(Authentication auth, @RequestBody SubscriptionRequest body) {
        return svc.create(auth.getName(), admin(auth), body);
    }

    @PutMapping("/me/subscriptions/{id}")
    @Operation(summary = "구독 바꾸기")
    public Subscription update(Authentication auth, @PathVariable long id, @RequestBody SubscriptionRequest body) {
        return svc.update(auth.getName(), admin(auth), id, body);
    }

    @DeleteMapping("/me/subscriptions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "구독 지우기")
    public void delete(Authentication auth, @PathVariable long id) { svc.delete(auth.getName(), id); }

    @GetMapping("/me/watch")
    @Operation(summary = "관심 목록 (기업 · 지역)")
    public List<Watch> watches(Authentication auth) { return svc.watches(auth.getName()); }

    @PutMapping("/me/watch/{type}/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "관심 목록에 넣기")
    public void watch(Authentication auth, @PathVariable @Pattern(regexp = "COMPANY|REGION") String type,
                      @PathVariable @Pattern(regexp = "\\d{5}|\\d{8}") String key) {
        svc.watch(auth.getName(), type, key);
    }

    @DeleteMapping("/me/watch/{type}/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "관심 목록에서 빼기")
    public void unwatch(Authentication auth, @PathVariable @Pattern(regexp = "COMPANY|REGION") String type,
                        @PathVariable @Pattern(regexp = "\\d{5}|\\d{8}") String key) {
        svc.unwatch(auth.getName(), type, key);
    }

    @GetMapping("/admin/webhooks")
    @Operation(summary = "웹훅 목록 (주소는 호스트만)")
    public Map<String, Object> webhooks() { return Map.of("available", svc.webhooksAvailable(), "items", svc.webhooks()); }

    @PostMapping("/admin/webhooks")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "웹훅 등록 — https · 공인 주소만, 주소 · 비밀은 암호화 저장. GENERIC 서명 비밀은 이 응답에서 한 번만")
    public WebhookCreated createWebhook(Authentication auth, @RequestBody WebhookRequest body) {
        return svc.createWebhook(auth.getName(), body);
    }

    public record Enabled(boolean enabled) {}

    @PatchMapping("/admin/webhooks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "웹훅 켜기 · 끄기")
    public void enable(@PathVariable long id, @RequestBody Enabled body) { svc.setEnabled(id, body.enabled()); }

    @DeleteMapping("/admin/webhooks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "웹훅 지우기 (이 웹훅으로 보내는 구독 · 대기열도 함께)")
    public void deleteWebhook(@PathVariable long id) { svc.deleteWebhook(id); }

    @PostMapping("/admin/webhooks/{id}/test")
    @Operation(summary = "시험 메시지 보내기")
    public TestResult test(@PathVariable long id) { return svc.test(id); }

    @GetMapping("/admin/webhooks/{id}/deliveries")
    @Operation(summary = "최근 전송 50건")
    public List<Delivery> deliveries(@PathVariable long id) { return svc.deliveries(id); }
}
