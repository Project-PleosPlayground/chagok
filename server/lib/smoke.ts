// 로컬 확인: npm run smoke  (.env 에 ANTHROPIC_API_KEY). ai-poc 샘플로 함수를 한 번씩 부른다.
//   npm run smoke -- drive-end   → 주행 끝 호출만 (앱과 같은 입력: request_count 0 → 리포트만)
import { readFileSync } from "node:fs";
import { comment, weekly, chat, driveEnd, MODEL } from "./claude.js";
const S = new URL("../../ai-poc/samples/", import.meta.url);
const sess = JSON.parse(readFileSync(new URL("session_evening.json", S), "utf8"));
const week = JSON.parse(readFileSync(new URL("week.json", S), "utf8"));
const de = JSON.parse(readFileSync(new URL("drive_end_evening.json", S), "utf8"));
const only = process.argv[2];
console.log("[model]", MODEL);

if (!only || only === "drive-end") {
  const t = Date.now(); const d = await driveEnd(de);
  console.log("[drive-end.report]", d.report, `(${Date.now() - t}ms)`);
  console.log("[drive-end.tip]", d.tip);
  console.log("[drive-end.panels]", d.panels);
  for (const m of d.missions) console.log("[drive-end.mission]", m.role, `${m.title} = ${m.condition} ${m.target} ${m.scope}`, "|", m.why);
}
if (!only) {
  const t0 = Date.now();
  console.log("[comment]", await comment(sess), `(${Date.now() - t0}ms)`);
  const t1 = Date.now(); const w = await weekly(week);
  console.log("[weekly.report]", w.report.headline, "|", w.report.insights.length, "insights", `(${Date.now() - t1}ms)`);
  console.log("[weekly.missions]", w.missions.map((m) => `${m.title}=${m.condition}≤${m.target}`).join(" / "));
  const t2 = Date.now(); console.log("[chat]", await chat(sess, "오늘 나 어땠어?"), `(${Date.now() - t2}ms)`);
}
