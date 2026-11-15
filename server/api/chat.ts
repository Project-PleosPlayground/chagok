// POST /api/chat   body = { "context": SessionCommentPayload, "text": "오늘 나 어땠어?", "history": [...] }   → { "reply": "..." }
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { chat } from "../lib/claude.js";
import { pocOnly } from "../lib/http.js";
export default (req: VercelRequest, res: VercelResponse) => pocOnly(req, res, async (body) => ({ reply: await chat(body.context, String(body.text ?? ""), body.history ?? []) }));
