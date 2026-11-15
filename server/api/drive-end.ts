// POST /api/drive-end   body = DriveEndPayload (core AiPayload.kt: 세션 코멘트 페이로드 + missions 재료)
//   → { "report": "두세 문장", "missions": [ { title, condition, target, scope: "DAY", why, reward_points, role } ] }
// 주행 끝 한 번만 부른다. missions.request_count 가 0 이면 리포트만 돌려준다. core AiResponses.parseDriveEnd 가 파싱.
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { driveEnd } from "../lib/claude.js";
import { handle } from "../lib/http.js";
export default (req: VercelRequest, res: VercelResponse) => handle(req, res, async (body) => driveEnd(body));
