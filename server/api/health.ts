import type { VercelRequest, VercelResponse } from "@vercel/node";
import { MODEL } from "../lib/claude.js";
export default (_req: VercelRequest, res: VercelResponse) => res.status(200).json({ ok: true, model: MODEL, keyConfigured: Boolean(process.env.ANTHROPIC_API_KEY) });
