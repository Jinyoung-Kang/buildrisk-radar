# =============================================================================
# Makefile — 건설·부동산 위험 모니터 원커맨드 (FR-701)
#
#   make up          스택 기동 (db·redis·api·worker·web) → http://localhost:3400
#   make obs         관측 (Prometheus :9490 · Grafana :3401)
#   make smoke       외부 API 키 확인 (응답은 fixtures/smoke/ 에 키를 가린 채 저장)
#   make batch-all   전체 배치를 순서대로 실행 (batch-only 컨테이너, 처음 약 30~60분)
#   make regions     지역만: 경계 → 총가구 → 미분양 → 가격지수 → 지표 → 규칙 (DART 키 없이도 가능)
#   make job JOB=financialStatementJob   Job 하나 실행 (STOPPED·FAILED 면 restart)
#   make test        백엔드 단위·통합 테스트 (Testcontainers — Docker 필요) + 웹 타입 검사
#   make e2e         Playwright (스택이 떠 있어야 함) · make load  k6 부하 · make ratelimit  429 확인
#   make status      Job 마지막 상태 · 데이터 기준 시점     make logs / make psql / make down / make reset
# =============================================================================

SHELL := /bin/bash
COMPOSE := docker compose
BATCH := $(COMPOSE) --profile batch run --rm

.DEFAULT_GOAL := help
.PHONY: help env up obs down restart logs build smoke batch-all regions job test test-app test-web e2e load ratelimit status psql reset

help:
	@grep -E '^#   make' Makefile | sed 's/^#   //'

# .env 가 없으면 만들고, 비어 있는 비밀값(서비스 토큰 · DB 앱 계정 · Redis · 관리자 · 분석가 · Grafana)을 무작위로 채웁니다.
# 값은 화면에 출력하지 않습니다 — 로그인 비밀번호는 .env 에서 확인하세요.
SECRETS := ADMIN_TOKEN DB_APP_PASSWORD REDIS_PASSWORD ADMIN_PASSWORD ANALYST_PASSWORD GRAFANA_ADMIN_PASSWORD
env:
	@test -f .env || (cp .env.example .env && echo "→ .env 를 만들었습니다. 외부 API 키 값을 채워 주세요.")
	@for k in $(SECRETS); do \
	  if ! grep -qE "^$$k=[^[:space:]#]+" .env; then \
	    v=$$(openssl rand -hex 24); \
	    if grep -qE "^$$k=" .env; then sed -i.bak -E "s|^$$k=.*|$$k=$$v|" .env && rm -f .env.bak; else printf '%s=%s\n' "$$k" "$$v" >> .env; fi; \
	    echo "→ $$k 를 생성했습니다 (.env)"; fi; done

up: env
	$(COMPOSE) up -d --build
	@echo ""
	@echo "  화면      http://localhost:3400   (로그인: admin / .env 의 ADMIN_PASSWORD)"
	@echo "  API 문서  http://localhost:3400/swagger-ui/index.html"
	@echo "  관측      make obs → Grafana http://localhost:3401 (admin / .env 의 GRAFANA_ADMIN_PASSWORD)"
	@echo "  데이터가 비어 있으면: make regions (키 3종) 또는 make batch-all (DART 포함)"

obs: env
	$(COMPOSE) --profile obs up -d prometheus grafana

build: env
	$(COMPOSE) build

down:
	$(COMPOSE) down

restart:
	$(COMPOSE) restart api worker web

logs:
	$(COMPOSE) logs -f --tail=100 api worker web

smoke:
	python3 tools/smoke.py

batch-all: env
	$(BATCH) -e BATCH_RUN_JOBS=all batch

regions: env
	$(BATCH) -e BATCH_RUN_JOBS=boundaryLoadJob,sgisHouseholdJob,kosisUnsoldJob,roneIndexJob,aptTradeJob,standardizeMetricJob,ruleEvalJob batch

job: env
	@test -n "$(JOB)" || (echo "사용법: make job JOB=financialStatementJob" && exit 1)
	$(BATCH) -e BATCH_RUN_JOBS=$(JOB) batch

test: test-app test-web

test-app:
	cd app && ./gradlew test

test-web:
	cd web && npm ci --no-audit --no-fund && npm run typecheck

# 관리자 로그인 시나리오는 .env 의 ADMIN_PASSWORD 를 환경변수로만 넘김 (화면·로그에 출력하지 않음)
e2e:
	cd web && npx playwright install chromium && E2E_ADMIN_PASSWORD="$$(grep -E '^ADMIN_PASSWORD=' ../.env | cut -d= -f2-)" npx playwright test smoke security insight

# k6 부하 (compose 네트워크 안에서 web 프록시 경유). 레이트리밋은 IP 당이라 부하 측정 동안만 끔
load:
	RATE_LIMIT_PER_MINUTE=0 $(COMPOSE) up -d api && sleep 15
	docker run --rm --network buildrisk-radar_default -v "$$PWD/ops/k6:/scripts:ro" grafana/k6:0.55.0 run /scripts/load.js; \
	  status=$$?; $(COMPOSE) up -d api; exit $$status

# 레이트리밋 동작 확인 (429 + Retry-After)
ratelimit:
	docker run --rm --network buildrisk-radar_default -v "$$PWD/ops/k6:/scripts:ro" grafana/k6:0.55.0 run /scripts/ratelimit.js

status:
	@curl -s localhost:3400/api/v1/batch/jobs | python3 -c "import json,sys;d=json.load(sys.stdin);[print(f\"{j['name']:<24}{(j['last'] or {}).get('status','-'):<11}{'실행 중' if j['running'] else ''}{'' if j['keyConfigured'] else '  (키 없음: '+j['envKey']+')'}\") for j in d['jobs']]"
	@curl -s localhost:3400/api/v1/dashboard | python3 -c "import json,sys;f=json.load(sys.stdin)['freshness'];print('기준:',f)"

psql:
	$(COMPOSE) exec db psql -U buildrisk

# 데이터까지 모두 지우고 새로 시작
reset:
	$(COMPOSE) down -v
