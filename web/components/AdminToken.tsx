import { useEffect, useState } from "react";
import { adminToken } from "@/lib/api";

/** 관리 작업(규칙 변경·배치 실행·매핑 추가)에 쓰는 X-Admin-Token — .env 의 ADMIN_TOKEN 값을 붙여 넣습니다. */
export default function AdminToken() {
  const [v, setV] = useState("");
  const [saved, setSaved] = useState(false);
  useEffect(() => { const t = adminToken.get(); setV(t); setSaved(!!t); }, []);
  return (
    <form className="flex items-center gap-2 text-xs" onSubmit={(e) => { e.preventDefault(); adminToken.set(v.trim()); setSaved(!!v.trim()); }}>
      <label htmlFor="admin-token" className="text-ink2">관리 토큰</label>
      <input id="admin-token" type="password" value={v} onChange={(e) => { setV(e.target.value); setSaved(false); }}
        placeholder=".env 의 ADMIN_TOKEN" className="bg-raised border border-line rounded-md px-2 py-1 w-52 focus-ring" autoComplete="off" />
      <button className="px-2 py-1 rounded-md bg-accent text-white focus-ring">저장</button>
      {saved && <span className="text-good">✓ 이 탭에 저장됨</span>}
    </form>
  );
}
