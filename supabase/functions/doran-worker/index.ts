import { accountedCost, estimatedCost, openAI } from "../_shared/ai.ts";
import { ApiError, errorPayload } from "../_shared/core.ts";
import { database } from "../_shared/db.ts";
Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response(null, { status: 405 });
  try {
    const db = database();
    const token = req.headers.get("authorization")?.match(/^Bearer (.+)$/i)?.[1];
    if (!token || token.length > 256) throw new ApiError("FORBIDDEN", 403);
    const secret = Deno.env.get("DORAN_WORKER_SECRET");
    const authorized = secret
      ? token === secret
      : (await db.worker("worker.authorize", { token })).valid === true;
    if (!authorized) throw new ApiError("FORBIDDEN", 403);
    const ai = openAI();
    const jobs = await db.worker("jobs.claim", { limit: 1 });
    const results = [];
    for (const job of jobs.items ?? []) {
      const input = job.kind === "record_summary"
        ? {
          user_text: job.input.record.edited_text ?? job.input.record.source_text,
          record_date: job.input.record.record_date,
          prior_memories: job.input.prior_memories ?? [],
        }
        : {
          date: job.input.date,
          records: job.input.records.map((r: Record<string, unknown>) => ({
            text: r.text,
            record_date: r.record_date,
          })),
        };
      const reservation = crypto.randomUUID();
      const reserved = estimatedCost(input, 1600);
      let held = false;
      let billed = reserved;
      try {
        if (new TextEncoder().encode(JSON.stringify(input)).length > 256000) {
          throw new ApiError("INVALID_TEXT");
        }
        await db.worker("budget.reserve", { request_id: reservation, max_cost_usd: reserved });
        held = true;
        const guard = await db.worker("jobs.validate", {
          job_id: job.id,
          lease_token: job.lease_token,
          policy_revision: job.policy_revision,
        });
        if (!guard.valid) {
          billed = 0;
          throw new ApiError("STALE_CONTEXT", 409);
        }
        const emptyDay = job.kind === "day_summary" && "records" in input &&
          input.records.length === 0;
        const generated = emptyDay
          ? { result: { text: "", memories: [] }, usage: { input_tokens: 0, output_tokens: 0 } }
          : await ai.summarize(input, job.kind, AbortSignal.timeout(90000));
        billed = Math.min(reserved, accountedCost(generated.usage));
        const saved = await db.worker("jobs.complete", {
          job_id: job.id,
          lease_token: job.lease_token,
          result: generated.result,
          policy_revision: job.policy_revision,
        });
        results.push({ id: job.id, accepted: saved.accepted });
      } catch (error) {
        await db.worker("jobs.fail", { job_id: job.id, lease_token: job.lease_token });
        results.push({ id: job.id, error: errorPayload(error).code });
      } finally {
        if (held) {
          await db.worker("budget.settle", { request_id: reservation, actual_cost_usd: billed });
        }
      }
    }
    return Response.json({ processed: results });
  } catch (error) {
    return Response.json({ error: errorPayload(error) }, {
      status: error instanceof ApiError ? error.status : 500,
    });
  }
});
