/**
 * 호출 횟수 제한. APK 안 토큰이 새어도 AI 비용이 한 번에 나가지 않게 막는다 (11/15).
 * 서버 메모리로 센다. Vercel 이 인스턴스를 여러 개 띄우면 인스턴스마다 따로 세므로 정확한 상한은 아니다.
 * 돈의 최종 상한은 Anthropic 콘솔의 월 지출 한도가 맡는다.
 */
const PER_IP_HOUR = Number(process.env.RATE_PER_IP_HOUR ?? 30);
const GLOBAL_DAY = Number(process.env.RATE_GLOBAL_DAY ?? 500);
const HOUR = 3_600_000, DAY = 86_400_000;

const perIp = new Map<string, { start: number; n: number }>();
let global = { start: 0, n: 0 };

/** 받아도 되면 null, 넘었으면 다시 시도할 수 있을 때까지 남은 초 */
export function take(ip: string, now = Date.now()): number | null {
  if (now - global.start >= DAY) global = { start: now, n: 0 };
  if (global.n >= GLOBAL_DAY) return Math.ceil((global.start + DAY - now) / 1000);
  let w = perIp.get(ip);
  if (!w || now - w.start >= HOUR) { w = { start: now, n: 0 }; perIp.set(ip, w); }
  if (w.n >= PER_IP_HOUR) return Math.ceil((w.start + HOUR - now) / 1000);
  w.n++; global.n++;
  if (perIp.size > 5_000) for (const [k, v] of perIp) if (now - v.start >= HOUR) perIp.delete(k);
  return null;
}

export function clientIp(h: Record<string, string | string[] | undefined>): string {
  const v = h["x-real-ip"] ?? h["x-forwarded-for"];
  const s = Array.isArray(v) ? v[0] : v;
  return (s ?? "unknown").split(",")[0].trim();
}
