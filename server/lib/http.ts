import type { VercelRequest, VercelResponse } from "@vercel/node";
import { clientIp, take } from "./limit.js";

/** 앱만 부를 수 있게 공유 토큰 하나. 환경변수 APP_TOKEN 이 비어 있으면 검사하지 않는다(개발용). */
export function authorized(req: VercelRequest): boolean {
  const want = process.env.APP_TOKEN; if (!want) return true;
  return req.headers["x-app-token"] === want;
}

export function readJson(req: VercelRequest): unknown {
  const b = req.body; if (b == null) return null;
  return typeof b === "string" ? JSON.parse(b) : b;
}

export async function handle(req: VercelRequest, res: VercelResponse, fn: (body: any) => Promise<unknown>) {
  if (req.method !== "POST") return res.status(405).json({ error: "POST only" });
  if (!authorized(req)) return res.status(401).json({ error: "bad token" });
  const wait = take(clientIp(req.headers));
  if (wait != null) { res.setHeader("retry-after", String(wait)); return res.status(429).json({ error: "too many requests" }); }
  let body: unknown; try { body = readJson(req); } catch { return res.status(400).json({ error: "bad json" }); }
  if (!body) return res.status(400).json({ error: "empty body" });
  try {
    const t0 = Date.now(); const out = await fn(body);
    res.setHeader("x-elapsed-ms", String(Date.now() - t0));
    return res.status(200).json(out);
  } catch (e: any) {
    console.error("[relay]", e?.message ?? e);
    return res.status(502).json({ error: "upstream", message: String(e?.message ?? e) });
  }
}

/** 앱이 안 쓰는 PoC 주소(comment·weekly·chat). 환경변수 POC_ENDPOINTS=1 일 때만 연다 */
export function pocOnly(req: VercelRequest, res: VercelResponse, fn: (body: any) => Promise<unknown>) {
  if (process.env.POC_ENDPOINTS !== "1") return res.status(404).json({ error: "not found" });
  return handle(req, res, fn);
}
