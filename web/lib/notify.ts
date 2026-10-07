/** 알림 수가 바뀌었음을 머리말 종 아이콘에 알림 (읽음 표시 · 새로 고침 뒤) */
export const NOTIFY_EVENT = "br:notify-changed";
export function notifyChanged() {
  if (typeof window !== "undefined") window.dispatchEvent(new Event(NOTIFY_EVENT));
}
