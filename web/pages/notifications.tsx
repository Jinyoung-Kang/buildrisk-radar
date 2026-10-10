import Link from "next/link";
import { useRouter } from "next/router";
import { Fragment, useState, type FormEvent } from "react";
import Layout from "@/components/Layout";
import { Card, Empty, ErrorBox, Loading, Pager, PageTitle, Prose, Segmented, SeverityBadge } from "@/components/ui";
import { type RuleView, type Severity, type Subscription, type SubscriptionInput, type Watch, type Webhook } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { DEMO_READ_ONLY, showLoginLink } from "@/lib/domain/demo";
import { dt } from "@/lib/domain/format";
import { targetHref } from "@/lib/domain/nav";
import { describeSubscription, EMPTY_SUBSCRIPTION, subscriptionBody, toggleCode } from "@/lib/domain/notify";
import { notifyChanged } from "@/lib/notify";
import { useAction } from "@/lib/hooks/useAction";
import { useApi } from "@/lib/hooks/useApi";
import { useDemo } from "@/lib/hooks/useSiteConfig";
import * as alertApi from "@/lib/api/alert";
import * as notifyApi from "@/lib/api/notify";

const input = "w-full bg-raised border border-line rounded-md px-2 py-1.5 text-sm focus-ring";
const btn = "text-xs px-2.5 py-1 rounded-md border border-line hover:bg-line/40 focus-ring disabled:opacity-40";
const primary = "px-3 py-1.5 rounded-lg bg-accent text-white text-sm focus-ring disabled:opacity-50";
const SEV_OPTIONS: { value: Severity; label: string }[] = [
  { value: "LOW", label: "낮음 이상 (전부)" }, { value: "MEDIUM", label: "중간 이상" }, { value: "HIGH", label: "높음만" },
];
const DELIVERY_STATUS: Record<string, string> = { SENT: "보냄", PENDING: "대기", SENDING: "보내는 중", DEAD: "포기" };

/** 받은 알림함 */
function InboxCard() {
  const router = useRouter();
  const [view, setView] = useState<"all" | "unread">("all");
  const [page, setPage] = useState(0);
  const { data, error, reload } = useApi(notifyApi.inbox({ unreadOnly: view === "unread", page, size: 30 }));
  const action = useAction();
  const read = (body: { ids?: number[]; all?: boolean }) => action.run(() => notifyApi.markRead(body), () => { reload(); notifyChanged(); });
  return (
    <Card title="받은 알림" pad={false}
      sub={data ? `읽지 않음 ${data.unread}건 · 새 경보는 규칙 평가(매일 지표 계산 뒤)마다 구독과 맞춰 30초 안에 쌓입니다` : undefined}
      right={<div className="flex flex-wrap items-center gap-2">
        <Segmented label="보기" value={view} onChange={(v) => { setView(v); setPage(0); }}
          options={[{ value: "all", label: "전체" }, { value: "unread", label: "안 읽음" }]} />
        <button type="button" className={btn} disabled={!data?.unread} onClick={() => read({ all: true })}>모두 읽음</button>
      </div>}>
      <ErrorBox error={error} />
      {action.error && <p role="alert" className="text-xs text-crit px-4 py-2">{action.error.message}</p>}
      {!data ? <Loading /> : data.items.length === 0 ? (
        <Empty>{view === "unread" ? "읽지 않은 알림이 없습니다." : "알림이 없습니다. 구독을 만들면 새 경보가 여기에 쌓입니다."}</Empty>
      ) : (
        <>
          <ul className="divide-y divide-line">
            {data.items.map((n) => (
              <li key={n.inboxId} className={`px-4 py-3 flex gap-3 items-start ${n.readAt ? "" : "bg-accent/5"}`}>
                <span className={`mt-1.5 w-2 h-2 rounded-full shrink-0 ${n.readAt ? "bg-transparent" : "bg-accent"}`}
                  aria-label={n.readAt ? undefined : "읽지 않음"} role={n.readAt ? undefined : "img"} />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <SeverityBadge s={n.severity} />
                    <Link href={`/alerts?id=${n.alertId}`} className="font-medium hover:underline focus-ring rounded"
                      onClick={(e) => { if (!n.readAt) { e.preventDefault(); read({ ids: [n.inboxId] }).then(() => router.push(`/alerts?id=${n.alertId}`)); } }}>
                      {n.title}
                    </Link>
                    {n.kind === "REOPENED" && <span className="text-[11px] px-1.5 py-0.5 rounded border border-warn text-warn">다시 열림</span>}
                    {n.alertStatus === "CLOSED" && <span className="text-[11px] text-muted">지금은 닫힘</span>}
                  </div>
                  <div className="text-xs text-muted mt-0.5 flex flex-wrap gap-x-2">
                    <Link href={targetHref(n.targetType, n.targetKey)} className="hover:underline">{n.targetName ?? n.targetKey}</Link>
                    <span>{n.ruleCode} · {n.asOf}</span>
                    <span className="tabular">{dt(n.createdAt)}</span>
                  </div>
                </div>
                {!n.readAt && <button type="button" className={btn} onClick={() => read({ ids: [n.inboxId] })}>읽음</button>}
              </li>
            ))}
          </ul>
          <Pager total={data.total} page={page} size={data.size} onPage={setPage} />
        </>
      )}
    </Card>
  );
}

function SubscriptionForm({ initial, rules, webhooks, admin, onSaved, onCancel }: {
  initial: Subscription | null; rules: RuleView[]; webhooks: Webhook[]; admin: boolean; onSaved: () => void; onCancel: () => void;
}) {
  const [f, setF] = useState<SubscriptionInput>(initial ? { ...initial, ruleCodes: initial.ruleCodes ?? [] } : EMPTY_SUBSCRIPTION);
  const action = useAction();
  const set = (p: Partial<SubscriptionInput>) => setF((x) => ({ ...x, ...p }));
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const body = subscriptionBody(f);
    return action.run(() => (initial ? notifyApi.updateSubscription(initial.subscriptionId, body) : notifyApi.createSubscription(body)), onSaved);
  };
  const toggleRule = (code: string) => set({ ruleCodes: toggleCode(f.ruleCodes, code) });
  // 고를 수 있는 웹훅 = 켜진 것 + 지금 이 구독이 쓰는 것(꺼졌어도) — 없으면 선택 상자가 다른 웹훅을 보여 주며 꺼진 웹훅으로 저장됨 (VERIFICATION 149)
  const usable = webhooks.filter((w) => w.enabled || w.webhookId === f.webhookId);
  return (
    <form onSubmit={submit} className="space-y-3 border border-line rounded-lg p-3" aria-label={initial ? "구독 바꾸기" : "새 구독"}>
      <label className="block text-xs text-ink2">이름
        <input className={`${input} mt-1`} value={f.name} maxLength={60} required onChange={(e) => set({ name: e.target.value })} placeholder="예: PF · 재무 경보" />
      </label>
      <div className="grid grid-cols-2 gap-3">
        <label className="block text-xs text-ink2">심각도
          <select className={`${input} mt-1`} value={f.minSeverity} onChange={(e) => set({ minSeverity: e.target.value as Severity })}>
            {SEV_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>
        <label className="block text-xs text-ink2">대상
          <select className={`${input} mt-1`} value={f.targetType ?? ""} onChange={(e) => set({ targetType: (e.target.value || undefined) as SubscriptionInput["targetType"] })}>
            <option value="">기업 · 지역 모두</option><option value="COMPANY">기업만</option><option value="REGION">지역만</option>
          </select>
        </label>
      </div>
      <fieldset>
        <legend className="text-xs text-ink2">규칙 <span className="text-muted">(고르지 않으면 모든 규칙)</span></legend>
        <div className="mt-1 grid grid-cols-1 sm:grid-cols-2 gap-x-3 gap-y-1 max-h-40 overflow-y-auto">
          {rules.map((r) => (
            <label key={r.ruleCode} className="flex items-center gap-1.5 text-xs">
              <input type="checkbox" checked={f.ruleCodes?.includes(r.ruleCode) ?? false} onChange={() => toggleRule(r.ruleCode)} />
              <span className="tabular text-muted">{r.ruleCode}</span><span className="truncate" title={r.nameKo}>{r.nameKo}</span>
            </label>
          ))}
        </div>
      </fieldset>
      <label className="flex items-center gap-2 text-xs">
        <input type="checkbox" checked={f.watchOnly} onChange={(e) => set({ watchOnly: e.target.checked })} />
        관심 목록에 넣은 기업 · 지역만 <span className="text-muted">(기업 · 지역 화면의 ☆ 버튼)</span>
      </label>
      <fieldset>
        <legend className="text-xs text-ink2">받는 곳</legend>
        <div className="mt-1 flex flex-wrap gap-4 text-xs">
          <label className="flex items-center gap-1.5"><input type="radio" name="channel" checked={f.channel === "IN_APP"} onChange={() => set({ channel: "IN_APP" })} />알림함</label>
          <label className={`flex items-center gap-1.5 ${admin ? "" : "text-muted"}`}>
            <input type="radio" name="channel" disabled={!admin || usable.length === 0} checked={f.channel === "WEBHOOK"}
              onChange={() => set({ channel: "WEBHOOK", webhookId: f.webhookId ?? webhooks.find((w) => w.enabled)?.webhookId })} />
            웹훅 {admin ? (usable.length === 0 ? "(먼저 아래에서 웹훅 등록)" : "") : "(관리자만)"}
          </label>
        </div>
        {f.channel === "WEBHOOK" && (
          <select aria-label="웹훅" className={`${input} mt-2`} value={f.webhookId ?? ""} onChange={(e) => set({ webhookId: Number(e.target.value) })}>
            {usable.map((w) => <option key={w.webhookId} value={w.webhookId}>{w.name} · {w.urlHint}{w.enabled ? "" : " (꺼짐)"}</option>)}
          </select>
        )}
      </fieldset>
      {action.error && <p role="alert" className="text-xs text-crit">{action.error.message}</p>}
      <div className="flex gap-2">
        <button type="submit" className={primary} disabled={action.busy}>{initial ? "저장" : "구독 만들기"}</button>
        <button type="button" className={btn} onClick={onCancel}>취소</button>
      </div>
    </form>
  );
}

function SubscriptionsCard({ admin }: { admin: boolean }) {
  const subs = useApi(notifyApi.subscriptions());
  const rules = useApi(alertApi.rules());
  const hooks = useApi(admin ? notifyApi.webhooks() : null);
  const [editing, setEditing] = useState<Subscription | "new" | null>(null);
  const action = useAction();
  const act = (fn: () => Promise<unknown>) => action.run(fn, () => subs.reload());
  const ruleList = rules.data ?? [];
  return (
    <Card title="구독" sub="어떤 경보를 어디로 받을지 — 규칙 평가 뒤 새로 생기거나 다시 열린 경보만 (다음 분기로 이어지기만 한 경보는 알리지 않음)"
      right={editing === null && <button type="button" className={btn} onClick={() => setEditing("new")}>+ 새 구독</button>}>
      <ErrorBox error={subs.error ?? rules.error ?? hooks.error} />
      {action.error && <p role="alert" className="text-xs text-crit mb-2">{action.error.message}</p>}
      {editing !== null && (
        <div className="mb-3">
          {/* key: 다른 구독을 고르면 폼 상태를 새로 — 없으면 앞서 고치던 구독의 값이 남은 채 이 구독으로 저장됨 (VERIFICATION 123) */}
          <SubscriptionForm key={editing === "new" ? "new" : editing.subscriptionId} initial={editing === "new" ? null : editing} rules={ruleList} webhooks={hooks.data?.items ?? []}
            admin={admin} onCancel={() => setEditing(null)} onSaved={() => { setEditing(null); subs.reload(); }} />
        </div>
      )}
      {!subs.data ? <Loading /> : subs.data.length === 0 ? <Empty>구독이 없습니다.</Empty> : (
        <ul className="space-y-2">
          {subs.data.map((s) => (
            <li key={s.subscriptionId} className={`border border-line rounded-lg px-3 py-2 ${s.enabled ? "" : "opacity-60"}`}>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="min-w-0">
                  <div className="text-sm font-medium">{s.name} <span className="text-xs text-muted font-normal">→ {s.channel === "IN_APP" ? "알림함" : `웹훅 ${s.webhookName ?? ""}`}</span></div>
                  <div className="text-xs text-muted">{describeSubscription(s, ruleList)}{s.enabled ? "" : " · 꺼짐"}</div>
                </div>
                <div className="flex gap-1.5">
                  <button type="button" className={btn} aria-pressed={s.enabled}
                    onClick={() => act(() => notifyApi.updateSubscription(s.subscriptionId, { ...s, enabled: !s.enabled }))}>{s.enabled ? "끄기" : "켜기"}</button>
                  <button type="button" className={btn} onClick={() => setEditing(s)}>바꾸기</button>
                  <button type="button" className={btn}
                    onClick={() => { if (window.confirm(`구독 '${s.name}'을 지울까요?`)) act(() => notifyApi.deleteSubscription(s.subscriptionId)); }}>지우기</button>
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function WatchCard() {
  const { data, error, reload } = useApi(notifyApi.watches());
  const action = useAction();
  const remove = (w: Watch) => action.run(() => notifyApi.unwatch(w.targetType, w.targetKey), reload);
  return (
    <Card title="관심 목록" sub="기업 · 지역 화면의 ☆ 버튼으로 넣습니다 — '관심 목록만' 구독이 이 목록으로 거릅니다">
      <ErrorBox error={error} />
      {action.error && <p role="alert" className="text-xs text-crit mb-2">{action.error.message}</p>}
      {!data ? <Loading /> : data.length === 0 ? <Empty>비어 있습니다.</Empty> : (
        <ul className="flex flex-wrap gap-2">
          {data.map((w) => (
            <li key={`${w.targetType}:${w.targetKey}`} className="inline-flex items-center gap-1 border border-line rounded-full pl-3 pr-1 py-0.5 text-xs">
              <Link href={targetHref(w.targetType, w.targetKey)} className="hover:underline">{w.name ?? w.targetKey}</Link>
              <span className="text-muted">{w.targetType === "COMPANY" ? "기업" : "지역"}</span>
              <button type="button" className="px-1.5 rounded-full hover:bg-line/60 focus-ring" aria-label={`${w.name ?? w.targetKey} 빼기`} onClick={() => remove(w)}>×</button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function Deliveries({ id }: { id: number }) {
  const { data, error } = useApi(notifyApi.webhookDeliveries(id));
  if (error) return <ErrorBox error={error} />;
  if (!data) return <Loading />;
  if (data.length === 0) return <p className="text-xs text-muted px-1 py-2">보낸 기록이 없습니다.</p>;
  return (
    <div className="table-wrap max-h-64">
      <table className="data-table text-xs">
        <thead><tr><th>경보</th><th>상태</th><th className="num">시도</th><th>마지막 오류</th><th>만든 때</th><th>다음 시도 · 보낸 때</th></tr></thead>
        <tbody>{data.map((d) => (
          <tr key={d.deliveryId}>
            <td><Link href={`/alerts?id=${d.alertId}`} className="hover:underline">{d.title}</Link>{d.kind === "REOPENED" && <span className="sub">다시 열림</span>}</td>
            <td className="whitespace-nowrap">{DELIVERY_STATUS[d.status] ?? d.status}</td>
            <td className="num">{d.attempts}</td>
            <td className="text-muted">{d.lastError ?? "–"}</td>
            <td className="tabular whitespace-nowrap">{dt(d.createdAt)}</td>
            <td className="tabular whitespace-nowrap">{d.status === "SENT" ? dt(d.sentAt) : d.status === "DEAD" ? "–" : dt(d.nextAt)}</td>
          </tr>))}</tbody>
      </table>
    </div>
  );
}

function WebhooksCard() {
  const { data, error, reload } = useApi(notifyApi.webhooks());
  const [form, setForm] = useState({ name: "", kind: "SLACK" as "SLACK" | "GENERIC", url: "", secret: "" });
  const action = useAction();
  const [note, setNote] = useState<string | null>(null);
  const [secret, setSecret] = useState<string | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  const act = (fn: () => Promise<unknown>, ok?: string) => {
    setNote(null);
    return action.run(fn, () => { if (ok) setNote(ok); reload(); });
  };
  const create = (e: FormEvent) => {
    e.preventDefault(); setNote(null); setSecret(null);
    return action.run(() => notifyApi.createWebhook({ name: form.name, kind: form.kind, url: form.url, secret: form.kind === "GENERIC" && form.secret ? form.secret : null }), (r) => {
      setSecret(r.secret ?? null);
      setForm({ name: "", kind: form.kind, url: "", secret: "" });
      setNote(`'${r.webhook.name}' 등록 — 주소는 암호화해 저장했고 화면에는 호스트만 보입니다.`);
      reload();
    });
  };
  const test = (w: Webhook) => {
    setNote(null);
    return action.run(() => notifyApi.testWebhook(w.webhookId), (r) => {
      if (r.ok) setNote(`'${w.name}' 시험 메시지 보냄 (HTTP ${r.status})`); else action.setError(new Error(`'${w.name}' 시험 실패 — ${r.error}`));
      reload();
    });
  };
  return (
    <Card title="웹훅 (관리자)" className="mt-5"
      sub="Slack Incoming Webhook 또는 서명된 JSON(GENERIC: X-BuildRisk-Signature = sha256=HMAC(비밀, 타임스탬프.본문)). https · 공인 주소만, 리다이렉트는 따라가지 않음. 실패하면 1분 · 5분 · 30분 · 2시간 · 12시간 뒤 다시 보내고 6번째에 포기">
      <ErrorBox error={error} />
      {data && !data.available && (
        <p role="alert" className="text-sm text-warn mb-3"><span className="block">NOTIFY_SECRET_KEY 가 설정되지 않아 웹훅을 쓸 수 없습니다 — <code>make env</code> 로 만든 뒤 api · worker 를 다시 시작하세요.</span><span className="block">알림함은 그대로 동작합니다.</span></p>
      )}
      {note && <p role="status" className="text-xs text-good mb-2">{note}</p>}
      {action.error && <p role="alert" className="text-xs text-crit mb-2">{action.error.message}</p>}
      {secret && (
        <div role="status" className="mb-3 border border-warn rounded-lg p-3 text-xs space-y-1">
          <p className="font-medium"><Prose text="서명 비밀 — 지금 한 번만 보여 줍니다. 받는 서버에 저장하세요." /></p>
          <div className="flex items-center gap-2">
            <code className="bg-page px-2 py-1 rounded break-all">{secret}</code>
            <button type="button" className={btn} onClick={() => navigator.clipboard?.writeText(secret)}>복사</button>
            <button type="button" className={btn} onClick={() => setSecret(null)}>닫기</button>
          </div>
        </div>
      )}
      {!data ? <Loading /> : data.items.length === 0 ? <Empty>등록한 웹훅이 없습니다.</Empty> : (
        <div className="table-wrap mb-4">
          <table className="data-table">
            <thead><tr><th>이름</th><th>형식</th><th>주소</th><th>마지막 결과</th><th className="num">대기 · 포기</th><th className="num">구독</th><th>동작</th></tr></thead>
            <tbody>{data.items.map((w) => (
              <Fragment key={w.webhookId}>
                <tr className={w.enabled ? "" : "opacity-60"}>
                  <td>{w.name}<span className="sub">{w.createdBy} · {dt(w.createdAt)}</span></td>
                  <td className="text-xs">{w.kind === "SLACK" ? "Slack" : "서명 JSON"}</td>
                  <td className="text-xs text-ink2">{w.urlHint}</td>
                  <td className="text-xs">{w.lastStatus ?? "–"}{w.lastSentAt && <span className="sub">{dt(w.lastSentAt)}</span>}</td>
                  <td className={`num ${w.dead > 0 ? "text-crit" : ""}`}>{w.pending} · {w.dead}</td>
                  <td className="num">{w.subscriptions}</td>
                  <td><div className="flex flex-wrap gap-1.5">
                    <button type="button" className={btn} disabled={!data.available} onClick={() => test(w)}>시험</button>
                    <button type="button" className={btn} aria-pressed={w.enabled} onClick={() => act(() => notifyApi.setWebhookEnabled(w.webhookId, !w.enabled))}>{w.enabled ? "끄기" : "켜기"}</button>
                    <button type="button" className={btn} aria-expanded={open === w.webhookId} onClick={() => setOpen(open === w.webhookId ? null : w.webhookId)}>전송 내역</button>
                    <button type="button" className={btn} onClick={() => { if (window.confirm(`웹훅 '${w.name}'과 이 웹훅으로 보내는 구독 ${w.subscriptions}개를 지울까요?`)) act(() => notifyApi.deleteWebhook(w.webhookId), "지웠습니다"); }}>지우기</button>
                  </div></td>
                </tr>
                {open === w.webhookId && <tr><td colSpan={7}><Deliveries id={w.webhookId} /></td></tr>}
              </Fragment>
            ))}</tbody>
          </table>
        </div>
      )}
      <form onSubmit={create} className="grid sm:grid-cols-[1fr_auto_2fr] gap-3 items-end" aria-label="웹훅 등록">
        <label className="block text-xs text-ink2">이름
          <input className={`${input} mt-1`} value={form.name} maxLength={60} required onChange={(e) => setForm({ ...form, name: e.target.value })} />
        </label>
        <label className="block text-xs text-ink2">형식
          <select className={`${input} mt-1`} value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value as "SLACK" | "GENERIC" })}>
            <option value="SLACK">Slack</option><option value="GENERIC">서명 JSON</option>
          </select>
        </label>
        <label className="block text-xs text-ink2">주소 (https)
          <input className={`${input} mt-1`} type="url" inputMode="url" autoComplete="off" spellCheck={false} required value={form.url}
            placeholder="https://hooks.slack.com/services/…" onChange={(e) => setForm({ ...form, url: e.target.value })} />
        </label>
        {form.kind === "GENERIC" && (
          <label className="block text-xs text-ink2 sm:col-span-3">서명 비밀 <span className="text-muted">(비우면 만들어 한 번 보여 줌, 16자 이상)</span>
            <input className={`${input} mt-1`} type="password" autoComplete="new-password" value={form.secret} onChange={(e) => setForm({ ...form, secret: e.target.value })} />
          </label>
        )}
        <div className="sm:col-span-3"><button type="submit" className={primary} disabled={action.busy || !data?.available}>웹훅 등록</button></div>
      </form>
    </Card>
  );
}

export default function NotificationsPage() {
  const { me, has, loading } = useAuth();
  const demo = useDemo();
  const { asPath } = useRouter();
  return (
    <Layout title="알림">
      <PageTitle title="알림" sub="구독한 경보가 새로 생기거나 다시 열리면 알림함과 웹훅으로 받습니다. 본인의 알림 · 구독만 보입니다." />
      {loading ? <Loading /> : !me?.authenticated ? (
        <Card><p className="text-sm">알림은 로그인한 사용자별로 받습니다. {demo && `${DEMO_READ_ONLY}.`}
          {showLoginLink(false, demo) && <Link href={`/login?next=${encodeURIComponent(asPath)}`} className="text-accent hover:underline">로그인</Link>}</p></Card>
      ) : (
        <>
          <div className="grid lg:grid-cols-[minmax(0,1fr)_minmax(0,26rem)] gap-5 items-start">
            <InboxCard />
            <div className="space-y-5"><SubscriptionsCard admin={has("ADMIN")} /><WatchCard /></div>
          </div>
          {has("ADMIN") && <WebhooksCard />}
        </>
      )}
    </Layout>
  );
}
