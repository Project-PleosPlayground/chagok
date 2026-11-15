// POST /api/comment   body = SessionCommentPayload (core AiPayload.kt)   → { "comment": "두 문장" }
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { comment } from "../lib/claude.js";
import { pocOnly } from "../lib/http.js";
export default (req: VercelRequest, res: VercelResponse) => pocOnly(req, res, async (body) => ({ comment: await comment(body) }));
