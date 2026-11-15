// POST /api/weekly   body = WeeklyPayload   → { "report": {...}, "missions": [...] }  (core AiResponses 가 그대로 파싱)
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { weekly } from "../lib/claude.js";
import { pocOnly } from "../lib/http.js";
export default (req: VercelRequest, res: VercelResponse) => pocOnly(req, res, async (body) => weekly(body));
