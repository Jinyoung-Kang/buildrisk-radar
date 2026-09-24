package com.buildrisk.radar.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

@RestController
@RequestMapping("/api/v1/mapping")
@Tag(name = "매핑", description = "미매핑 계정·지역 코드와 매핑 규칙 (FR-206, FR-405)")
public class MappingController {
    private final MappingService svc;

    public MappingController(MappingService svc) { this.svc = svc; }

    @GetMapping("/unmapped")
    @Operation(summary = "⑬ 미매핑 계정·지역 코드 목록 + 매핑률 + 유니버스 업종 분포")
    public Map<String, Object> unmapped() { return svc.unmapped(); }

    @GetMapping("/account-rules")
    @Operation(summary = "계정 매핑 규칙 목록")
    public List<Map<String, Object>> accountRules() { return svc.accountRules(); }

    @PostMapping("/account-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "계정 매핑 규칙 추가 (X-Admin-Token) — 재표준화 후 반영")
    public Map<String, Object> addAccountRule(@RequestBody MappingService.AccountRuleRequest body) {
        return svc.addAccountRule(body);
    }

    @DeleteMapping("/account-rules/{mapId}")
    @Operation(summary = "화면에서 추가한 계정 매핑 규칙 삭제 (X-Admin-Token)")
    public Map<String, Object> deleteAccountRule(@PathVariable int mapId) { return svc.deleteAccountRule(mapId); }

    @GetMapping("/region-codes")
    @Operation(summary = "출처별 지역 코드 매핑표")
    public List<Map<String, Object>> regionCodes(@RequestParam(required = false) String source) {
        return svc.regionCodes(source);
    }

    @PutMapping("/region-codes")
    @Operation(summary = "지역 코드 수동 매핑 (X-Admin-Token)",
            description = "출처 코드에 '/' 같은 문자가 있어(KOSIS 'C1/C2') 경로가 아닌 본문으로 받습니다. regionCd 가 null 이면 미매핑으로 되돌림")
    public Map<String, Object> mapRegion(@RequestBody MappingService.RegionMapRequest body) {
        return svc.mapRegion(body);
    }
}
