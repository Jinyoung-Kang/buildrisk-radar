#!/usr/bin/env python3
"""
외부 API 키 스모크 (W1 · FR-701) — 6개 제공자에 한 번씩 요청해 키·형식을 확인하고,
응답을 키를 가린 채 fixtures/smoke/ 에 저장합니다. 의존성 없음 (Python 3.11 표준 라이브러리).

    python3 tools/smoke.py                 # .env 를 읽어 전부 확인
    python3 tools/smoke.py --only dart,kosis
    python3 tools/smoke.py --capture-dart 00000000,11111111 --year 2025 --reprt 11012
        → 골든 테스트용 fnlttSinglAcntAll 응답을 app/src/test/resources/fixtures/dart/golden/ 에 저장
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "fixtures" / "smoke"
KEY_PARAMS = re.compile(r"(crtfc_key|apiKey|KEY|key|consumer_key|consumer_secret|accessToken)=([^&\s\"]+)")


def load_env() -> dict[str, str]:
    env = dict(os.environ)
    f = ROOT / ".env"
    if f.exists():
        for line in f.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                env.setdefault(k.strip(), v.strip())
    return env


def mask(s: str) -> str:
    return KEY_PARAMS.sub(r"\1=****", s)


def get(url: str, params: dict, timeout: int = 60) -> tuple[int, bytes]:
    full = url + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(full, headers={"User-Agent": "buildrisk-radar-smoke/0.1"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def save(name: str, body: bytes, secrets: list[str]) -> Path:
    OUT.mkdir(parents=True, exist_ok=True)
    text = body.decode("utf-8", errors="replace")
    for s in secrets:
        if s:
            text = text.replace(s, "****")
    p = OUT / f"{name}.json"
    p.write_text(text, encoding="utf-8")
    return p


class Result:
    def __init__(self):
        self.rows: list[tuple[str, str, str]] = []

    def add(self, name: str, ok: bool | None, detail: str):
        mark = "✓" if ok else ("–" if ok is None else "✕")
        self.rows.append((mark, name, detail))
        print(f"  {mark} {name:<8} {mask(detail)}", flush=True)


def dart(env, r: Result):
    key = env.get("DART_API_KEY")
    if not key:
        return r.add("DART", None, "DART_API_KEY 없음 — 건너뜀")
    today = dt.date.today()
    st, body = get("https://opendart.fss.or.kr/api/list.json",
                   {"crtfc_key": key, "bgn_de": (today - dt.timedelta(days=3)).strftime("%Y%m%d"),
                    "end_de": today.strftime("%Y%m%d"), "page_count": 3})
    d = json.loads(body)
    save("dart_list", body, [key])
    ok = d.get("status") in ("000", "013")
    r.add("DART", ok, f"list.json status={d.get('status')} {d.get('message')} total={d.get('total_count')}")


def kosis(env, r: Result):
    key = env.get("KOSIS_API_KEY")
    if not key:
        return r.add("KOSIS", None, "KOSIS_API_KEY 없음 — 건너뜀")
    st, body = get("https://kosis.kr/openapi/Param/statisticsParameterData.do",
                   {"method": "getList", "apiKey": key, "orgId": "116", "tblId": "DT_MLTM_2082",
                    "itmId": "13103871087T1", "objL1": "ALL", "objL2": "ALL", "prdSe": "M",
                    "newEstPrdCnt": 1, "format": "json", "jsonVD": "Y"})
    d = json.loads(body)
    save("kosis_unsold", body, [key])
    if isinstance(d, list):
        r.add("KOSIS", True, f"미분양 {len(d)}행 · 최신 {d[0].get('PRD_DE')} · 필드 {sorted(d[0])[:5]}…")
    else:
        r.add("KOSIS", False, f"err={d.get('err')} {d.get('errMsg')}")


def rone(env, r: Result):
    key = env.get("REB_API_KEY")
    if not key:
        return r.add("R-ONE", None, "REB_API_KEY 없음 — 건너뜀 (키 없이 부르면 샘플 5건만 옴)")
    period = (dt.date.today().replace(day=1) - dt.timedelta(days=45)).strftime("%Y%m")
    st, body = get("https://www.reb.or.kr/r-one/openapi/SttsApiTblData.do",
                   {"KEY": key, "Type": "json", "pIndex": 1, "pSize": 1000, "STATBL_ID": "A_2024_00045",
                    "DTACYCLE_CD": "MM", "WRTTIME_IDTFR_ID": period})
    d = json.loads(body)
    save("rone_sale_idx", body, [key])
    sec = d.get("SttsApiTblData")
    if not sec:
        return r.add("R-ONE", False, f"{d.get('RESULT')}")
    total = sec[0]["head"][0]["list_total_count"]
    rows = sec[1]["row"] if len(sec) > 1 else []
    r.add("R-ONE", len(rows) > 5, f"매매가격지수 {period} total={total} rows={len(rows)} (5건이면 키 미적용)")


def sgis(env, r: Result):
    ck, cs = env.get("SGIS_CONSUMER_KEY"), env.get("SGIS_CONSUMER_SECRET")
    if not ck or not cs:
        return r.add("SGIS", None, "SGIS_CONSUMER_KEY/SECRET 없음 — 건너뜀")
    base = "https://sgisapi.mods.go.kr/OpenAPI3"
    _, body = get(f"{base}/auth/authentication.json", {"consumer_key": ck, "consumer_secret": cs})
    a = json.loads(body)
    if str(a.get("errCd")) != "0":
        return r.add("SGIS", False, f"인증 실패 {a.get('errCd')} {a.get('errMsg')}")
    tok = a["result"]["accessToken"]
    _, body = get(f"{base}/stats/population.json", {"accessToken": tok, "year": 2024, "adm_cd": "31", "low_search": 1})
    d = json.loads(body)
    save("sgis_population_31", body, [ck, cs, tok])
    res = d.get("result") or []
    r.add("SGIS", bool(res), f"경기 시군구 {len(res)}건 · tot_family 예 {res[0].get('adm_nm')}={res[0].get('tot_family')}" if res else str(d.get("errMsg")))


def vworld(env, r: Result):
    key = env.get("VWORLD_API_KEY")
    if not key:
        return r.add("V-World", None, "VWORLD_API_KEY 없음 — 건너뜀 (boundaryLoadJob 은 SGIS 경계로 대체)")
    _, body = get("https://api.vworld.kr/req/data",
                  {"service": "data", "version": "2.0", "request": "GetFeature", "data": "LT_C_ADSIGG_INFO",
                   "key": key, "domain": env.get("VWORLD_DOMAIN", "http://localhost:3400"), "format": "json",
                   "geometry": "false", "attribute": "true", "crs": "EPSG:4326",
                   "geomFilter": "BOX(124.0,33.0,132.0,38.7)", "size": 10, "page": 1})
    d = json.loads(body).get("response", {})
    save("vworld_adsigg", body, [key])
    feats = (d.get("result") or {}).get("featureCollection", {}).get("features", [])
    r.add("V-World", d.get("status") == "OK", f"status={d.get('status')} total={d.get('record', {}).get('total')} "
          f"예 {[f['properties'].get('sig_kor_nm') for f in feats[:3]]} {d.get('error', '')}")


def kakao(env, r: Result):
    key = env.get("NEXT_PUBLIC_KAKAO_JS_KEY")
    if not key:
        return r.add("Kakao", None, "NEXT_PUBLIC_KAKAO_JS_KEY 없음 — 지도는 SVG 대체 표시")
    r.add("Kakao", True, f"JS 키 {len(key)}자 — 카카오 콘솔 플랫폼 Web 도메인에 http://localhost:3400 등록 필요")


def capture_dart(env, corps: list[str], year: str, reprt: str):
    key = env.get("DART_API_KEY")
    if not key:
        sys.exit("DART_API_KEY 가 필요합니다.")
    out = ROOT / "app" / "src" / "test" / "resources" / "fixtures" / "dart" / "golden"
    out.mkdir(parents=True, exist_ok=True)
    for c in corps:
        for fs in ("CFS", "OFS"):
            _, body = get("https://opendart.fss.or.kr/api/fnlttSinglAcntAll.json",
                          {"crtfc_key": key, "corp_code": c, "bsns_year": year, "reprt_code": reprt, "fs_div": fs})
            d = json.loads(body)
            if d.get("status") == "000":
                p = out / f"{c}_{year}_{reprt}_{fs}.json"
                p.write_text(body.decode("utf-8").replace(key, "****"), encoding="utf-8")
                print(f"  저장 {p.relative_to(ROOT)} ({len(d['list'])}행)")
                break
            print(f"  {c} {fs}: status {d.get('status')} {d.get('message')}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", help="쉼표 목록: dart,kosis,rone,sgis,vworld,kakao")
    ap.add_argument("--capture-dart", help="골든 fixture 로 저장할 corp_code 목록")
    ap.add_argument("--year", default=str(dt.date.today().year - 1))
    ap.add_argument("--reprt", default="11011")
    a = ap.parse_args()
    env = load_env()
    if a.capture_dart:
        return capture_dart(env, a.capture_dart.split(","), a.year, a.reprt)
    checks = {"dart": dart, "kosis": kosis, "rone": rone, "sgis": sgis, "vworld": vworld, "kakao": kakao}
    only = a.only.split(",") if a.only else list(checks)
    print("외부 API 스모크 — 응답은 fixtures/smoke/ 에 키를 가린 채 저장합니다.")
    r = Result()
    for name in only:
        try:
            checks[name](env, r)
        except Exception as e:  # noqa: BLE001 — 스모크는 모든 실패를 보고만 함
            r.add(name, False, f"{type(e).__name__}: {e}")
    failed = [x for x in r.rows if x[0] == "✕"]
    print(f"\n결과: 통과 {sum(x[0] == '✓' for x in r.rows)} · 실패 {len(failed)} · 건너뜀 {sum(x[0] == '–' for x in r.rows)}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
