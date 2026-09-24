# =============================================================================
# Makefile — 건설·부동산 위험 모니터 원커맨드 (FR-701)
#
#   make up          스택 기동 (db·redis·app·web) → http://localhost:3400
#   make smoke       외부 API 키 확인 (응답은 fixtures/smoke/ 에 키를 가린 채 저장)
#   make batch-all   전체 배치를 순서대로 실행 (batch-only 컨테이너, 처음 약 30~60분)
#   make regions     지역만: 경계 → 총가구 → 미분양 → 가격지수 → 지표 → 규칙 (DART 키 없이도 가능)
#   make job JOB=financialStatementJob   Job 하나 실행 (STOPPED·FAILED 면 restart)
#   make test        백엔드 단위·통합 테스트 (Testcontainers — Docker 필요) + 웹 타입 검사
#   make e2e         Playwright 스모크 (스택이 떠 있어야 함)
#   make status      Job 마지막 상태 · 데이터 기준 시점     make logs / make psql / make down / make reset
# =============================================================================

SHELL := /bin/bash
COMPOSE := docker compose
BATCH := $(COMPOSE) --profile batch run --rm

.DEFAULT_GOAL := help
.PHONY: help env up down restart logs build smoke batch-all regions job test test-app test-web e2e status psql reset

help:
	@grep -E '^#   make' Makefile | sed 's/^#   //'

# .env 가 없으면 만들고, ADMIN_TOKEN 이 비어 있으면 무작위 값으로 채웁니다.
env:
	@test -f .env || (cp .env.example .env && echo "→ .env 를 만들었습니다. 키 값을 채워 주세요.")
	@if ! grep -qE '^ADMIN_TOKEN=[^[:space:]#]+' .env; then \
	  tok=$$(openssl rand -hex 24); \
	  sed -i.bak -E "s|^ADMIN_TOKEN=.*|ADMIN_TOKEN=$$tok|" .env && rm -f .env.bak; \
	  echo "→ ADMIN_TOKEN 을 생성했습니다."; fi

up: env
	$(COMPOSE) up -d --build
	@echo ""
	@echo "  화면     http://localhost:3400"
	@echo "  API 문서 http://localhost:8410/swagger-ui"
	@echo "  데이터가 비어 있으면: make regions (키 3종) 또는 make batch-all (DART 포함)"

build: env
	$(COMPOSE) build

down:
	$(COMPOSE) down

restart:
	$(COMPOSE) restart app web

logs:
	$(COMPOSE) logs -f --tail=100 app web

smoke:
	python3 tools/smoke.py

batch-all: env
	$(BATCH) -e BATCH_RUN_JOBS=all batch

regions: env
	$(BATCH) -e BATCH_RUN_JOBS=boundaryLoadJob,sgisHouseholdJob,kosisUnsoldJob,roneIndexJob,standardizeMetricJob,ruleEvalJob batch

job: env
	@test -n "$(JOB)" || (echo "사용법: make job JOB=financialStatementJob" && exit 1)
	$(BATCH) -e BATCH_RUN_JOBS=$(JOB) batch

test: test-app test-web

test-app:
	cd app && ./gradlew test

test-web:
	cd web && npm ci --no-audit --no-fund && npm run typecheck

e2e:
	cd web && npx playwright install chromium && npx playwright test

status:
	@curl -s localhost:8410/api/v1/batch/jobs | python3 -c "import json,sys;d=json.load(sys.stdin);[print(f\"{j['name']:<24}{(j['last'] or {}).get('status','-'):<11}{'실행 중' if j['running'] else ''}{'' if j['keyConfigured'] else '  (키 없음: '+j['envKey']+')'}\") for j in d['jobs']]"
	@curl -s localhost:8410/api/v1/dashboard | python3 -c "import json,sys;f=json.load(sys.stdin)['freshness'];print('기준:',f)"

psql:
	$(COMPOSE) exec db psql -U buildrisk

# 데이터까지 모두 지우고 새로 시작
reset:
	$(COMPOSE) down -v
