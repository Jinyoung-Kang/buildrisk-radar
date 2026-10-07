-- pf_report.corp_code 도 다른 dart 테이블처럼 ref.company 를 참조 (V16 에서 빠뜨림 — 적용된 마이그레이션은 고치지 않고 새 버전으로)
ALTER TABLE dart.pf_report ADD CONSTRAINT pf_report_corp_code_fkey FOREIGN KEY (corp_code) REFERENCES ref.company (corp_code);
