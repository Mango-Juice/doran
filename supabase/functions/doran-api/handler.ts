import { ABOUT, PRIVACY } from "../_shared/public_documents.ts";
import {
  ApiError,
  date,
  dayView,
  errorPayload,
  explicitlyRequestsHistory,
  type Json,
  revision,
  sanitizeContext,
  sentenceSplit,
  settingsView,
  sha256,
  text,
  uuid,
} from "../_shared/core.ts";
import { accountedCost, type AI, estimatedCost, openAI } from "../_shared/ai.ts";
import { type Database, database } from "../_shared/db.ts";
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "GET,POST,PATCH,DELETE,OPTIONS",
};
function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      ...cors,
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}
export function createHandler(getDb: () => Database = database, getAI: () => AI = openAI) {
  return async (req: Request): Promise<Response> => {
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    try {
      const publicPath = new URL(req.url).pathname.split("/v1/").pop();
      if (req.method === "GET" && (publicPath === "about" || publicPath === "privacy")) {
        return new Response(publicPath === "about" ? ABOUT : PRIVACY, {
          headers: {
            ...cors,
            "Content-Type": "text/plain; charset=utf-8",
            "Cache-Control": "public, max-age=300",
          },
        });
      }
      const db = getDb();
      const bearer = req.headers.get("authorization")?.match(/^Bearer (.+)$/i)?.[1];
      if (!bearer) throw new ApiError("UNAUTHENTICATED", 401);
      const user = await db.user(bearer);
      const path = new URL(req.url).pathname.split("/v1/").pop()!.split("/").filter(Boolean);
      const url = new URL(req.url);
      const method = req.method;
      let body: Json = {};
      if (method !== "GET") {
        const raw = await req.text();
        if (raw.length > 40000) throw new ApiError("INVALID_REQUEST");
        try {
          body = raw ? JSON.parse(raw) : {};
        } catch {
          throw new ApiError("INVALID_REQUEST");
        }
        if (!body || Array.isArray(body) || typeof body !== "object") {
          throw new ApiError("INVALID_REQUEST");
        }
      }
      const cmd = (action: string, payload: Json = {}) => db.command(user.id, action, payload);
      const requestId = () => uuid(body.request_id);
      const id = (position = 1) => uuid(path[position]);
      if (path[0] === "devices") {
        if (path[1] === "bootstrap" && method === "POST") {
          const r = await cmd("devices.bootstrap", {
            label: typeof body.label === "string" ? body.label.slice(0, 50) : "도란 차량",
          });
          return json({ device: r.device, paired: !!r.device?.owner_id && r.device?.active });
        }
        if (path[1] === "current" && method === "GET") {
          const r = await cmd("devices.current");
          return json({ device: r.device, paired: !!r.device?.owner_id && r.device?.active });
        }
        if (path.length === 1 && method === "GET") return json(await cmd("devices.list"));
        if (path.length === 2 && method === "DELETE") {
          await cmd("devices.revoke", { device_id: id() });
          return json({ deleted: true });
        }
      }
      if (path[0] === "pairings") {
        if (path.length === 1 && method === "POST") {
          requestId();
          const bytes = crypto.getRandomValues(new Uint32Array(1));
          const code = String(bytes[0] % 1000000).padStart(6, "0");
          const r = await cmd("pairings.create", { code_hash: await sha256(code) });
          const p = r.pairing ?? r;
          return json({ id: p.id, code, expires_at: p.expires_at, status: "waiting" });
        }
        if (path[1] === "claim" && method === "POST") {
          requestId();
          if (typeof body.code !== "string" || !/^\d{6}$/.test(body.code)) {
            throw new ApiError("PAIRING_NOT_FOUND", 404);
          }
          const r = await cmd("pairings.claim", { code_hash: await sha256(body.code) });
          return json({
            id: (r.pairing ?? r).id,
            expires_at: (r.pairing ?? r).expires_at,
            status: "claimed",
            device_label: r.device_label ?? "도란 차량",
          });
        }
        if (path.length === 2 && method === "GET") {
          const r = await cmd("pairings.status", { pairing_id: id() });
          return json({ ...(r.pairing ?? r), owner_label: r.owner_display_name ?? r.owner_label });
        }
        if (path[2] === "confirm" && method === "POST") {
          requestId();
          const r = await cmd("pairings.confirm", { pairing_id: id() });
          return json({ id: path[1], status: "paired", device_id: r.device?.id ?? r.device_id });
        }
      }
      if (path[0] === "settings") {
        if (method === "GET") {
          const r = await cmd("profiles.get");
          return json(settingsView(r.profile));
        }
        if (method === "PATCH") {
          requestId();
          const payload: Json = { version: revision(body.version) };
          const fields: Record<string, string> = {
            vehicle_context_enabled: "consent_vehicle",
            consent_destination_name: "consent_destination_name",
            personalization_enabled: "personalization_enabled",
            consent_accepted: "consent_ai",
          };
          for (const [from, to] of Object.entries(fields)) {
            if (from in body) {
              if (typeof body[from] !== "boolean") throw new ApiError("INVALID_REQUEST");
              payload[to] = body[from];
            }
          }
          if (body.conversation_suggestions !== undefined) {
            if (!["off", "daily", "trip"].includes(body.conversation_suggestions)) {
              throw new ApiError("INVALID_REQUEST");
            }
            payload.suggestion_frequency = body.conversation_suggestions;
          }
          if (body.conversation_style !== undefined) {
            if (!["balanced", "listen", "questions"].includes(body.conversation_style)) {
              throw new ApiError("INVALID_REQUEST");
            }
            payload.conversation_style = body.conversation_style;
          }
          const r = await cmd("profiles.update", payload);
          return json(settingsView(r.profile));
        }
      }
      if (path[0] === "sessions") {
        if (path.length === 1 && method === "POST") {
          const r = await cmd("sessions.create", {
            request_id: requestId(),
            vehicle_context: sanitizeContext(body.context),
          });
          return json({ ...r.session, record_date: r.session.record_date ?? r.session.local_date });
        }
        if (path[2] === "pause" && method === "POST") {
          requestId();
          const r = await cmd("sessions.pause", { session_id: id() });
          return json(r.session ?? r);
        }
        if (path[2] === "finish" && method === "POST") {
          requestId();
          const pending = body.pending_turns ?? [];
          if (!Array.isArray(pending) || pending.length > 20) throw new ApiError("INVALID_REQUEST");
          const r = await cmd("sessions.finish", {
            session_id: id(),
            vehicle_context: sanitizeContext(body.context),
            pending_turns: pending.map((t: Json) => ({
              request_id: uuid(t.request_id),
              text: text(t.text, 6000),
              vehicle_context: sanitizeContext(t.context),
            })),
          });
          return json({ record_id: r.record.id, status: r.record.status });
        }
        if (["turns", "opening"].includes(path[2]) && method === "POST") {
          const opening = path[2] === "opening";
          const ai = getAI(); // Do not append a user turn if deployment has no AI credentials.
          const utterance = opening ? "" : text(body.text, 6000);
          const turn = await cmd(opening ? "sessions.opening" : "sessions.turn", {
            session_id: id(),
            request_id: requestId(),
            ...(opening ? {} : { text: utterance }),
            vehicle_context: sanitizeContext(body.context),
          });
          // Migration rollout: old SQL returns no generation, which is its initial attempt (1).
          // The captured value must stay attached to this dispatch even if the same turn is retried.
          const generation = Number(turn.turn.generation ?? 1);
          if (turn.duplicate) {
            if (!["complete", "completed"].includes(turn.turn.status)) {
              throw new ApiError("TURN_IN_PROGRESS", 409, true);
            }
            return replay(
              turn.turn.id,
              turn.turn.assistant_text ?? turn.turn.text ?? "",
              turn.session.version,
            );
          }
          try {
            const context = await db.worker("context.get", {
              owner_id: turn.owner_id,
              session_id: path[1],
              query: utterance,
              opening,
            });
            context.context = sanitizeContext(context.context);
            const historyAllowed = context.context?.passenger_state === "alone" ||
              explicitlyRequestsHistory(utterance);
            const contextData = {
              now: new Date().toISOString(),
              now_kst: new Date(Date.now() + 9 * 3600000).toISOString().replace("Z", "+09:00"),
              timezone: "Asia/Seoul",
              settings: settingsView(context.settings ?? {}),
              vehicle: context.context,
              memories: historyAllowed ? context.memories : [],
              records: historyAllowed ? context.records ?? [] : [],
              history_allowed: historyAllowed,
            };
            const input: Json[] = [
              { role: "developer", content: JSON.stringify(contextData) },
              ...(context.messages ?? []).slice(-24).map((m: Json) => ({
                role: m.role,
                content: m.content ?? m.text,
              })),
            ];
            if (opening) {
              input.push({
                role: "developer",
                content:
                  "사용자가 대화 시작을 눌렀습니다. 지금의 목적지와 관련 있는 사용자의 이전 설명이 있으면 연결해서 짧은 첫 질문 하나를 건네세요. 관련 근거가 없으면 일반적인 질문을 하세요. 목적지 이름만으로 회사, 업종, 약속 목적이나 완료를 단정하지 마세요. 사용자 발화를 만들어내지 마세요.",
              });
            }
            if (new TextEncoder().encode(JSON.stringify(input)).length > 96000) {
              throw new ApiError("INVALID_TEXT");
            }
            const reservation = crypto.randomUUID();
            const reserve = estimatedCost(input, 768);
            await db.worker("budget.reserve", { request_id: reservation, max_cost_usd: reserve });
            return streamTurn(req, db, ai, input, turn.turn.id, generation, reservation, reserve);
          } catch (error) {
            await db.worker("turns.fail", { turn_id: turn.turn.id, generation }).catch(() => {});
            throw error;
          }
        }
      }
      if (path[0] === "records") {
        if (path.length === 1 && method === "GET") {
          const limit = Math.min(
            50,
            Math.max(1, Number(url.searchParams.get("limit") ?? 20) || 20),
          );
          const cursor = url.searchParams.get("cursor")?.split("|");
          if (cursor && (cursor.length !== 2 || !Number.isFinite(Date.parse(cursor[0])))) {
            throw new ApiError("INVALID_CURSOR");
          }
          const r = await cmd("records.list", {
            date: url.searchParams.has("date") ? date(url.searchParams.get("date")!) : undefined,
            limit: limit + 1,
            before: cursor?.[0],
            before_id: cursor ? uuid(cursor[1]) : undefined,
          });
          const items = r.items ?? [];
          return json({
            items: items.slice(0, limit),
            next_cursor: items.length > limit
              ? `${items[limit - 1].created_at}|${items[limit - 1].id}`
              : null,
          });
        }
        if (path.length === 2 && method === "GET") {
          const r = await cmd("records.get", { record_id: id() });
          return json(r.record ?? r);
        }
        if (path.length === 2 && method === "PATCH") {
          const payload: Json = {
            record_id: id(),
            version: revision(body.version),
            request_id: requestId(),
          };
          if (body.text !== undefined) payload.text = text(body.text);
          if (body.record_date !== undefined) payload.local_date = date(body.record_date);
          if (body.excluded_from_personalization !== undefined) {
            if (typeof body.excluded_from_personalization !== "boolean") {
              throw new ApiError("INVALID_REQUEST");
            }
            payload.excluded = body.excluded_from_personalization;
          }
          const r = await cmd("records.update", payload);
          return json(r.record ?? r);
        }
        if (path.length === 2 && method === "DELETE") {
          requestId();
          await cmd("records.delete", {
            record_id: id(),
            version: revision(body.version),
            delete_manual: body.delete_manual_diary === true,
          });
          return json({ deleted: true });
        }
        if (path[2] === "retry" && method === "POST") {
          requestId();
          await cmd("records.retry", { record_id: id() });
          return json({ status: "pending" });
        }
      }
      if (path[0] === "days" && path[1]) {
        const local_date = date(path[1]);
        if (path.length === 2 && method === "GET") {
          const r = await cmd("days.get", { local_date });
          return json(dayView(r.day ?? r, local_date));
        }
        if (
          (path.length === 2 && method === "PATCH") || (path[2] === "adopt" && method === "POST")
        ) {
          requestId();
          if (
            method === "PATCH" &&
            (typeof body.manual_text !== "string" || body.manual_text.length > 20000)
          ) throw new ApiError("INVALID_TEXT");
          const r = await cmd("days.update", {
            local_date,
            version: revision(body.version),
            ...(method === "PATCH" ? { manual_text: body.manual_text } : { use_generated: true }),
          });
          return json(dayView(r.day ?? r, local_date));
        }
        if (path.length === 2 && method === "DELETE") {
          requestId();
          await cmd("days.deleteall", { local_date, version: revision(body.version) });
          return json({ deleted: true });
        }
      }
      if (path[0] === "memories") {
        if (path.length === 1 && method === "GET") return json(await cmd("memories.list"));
        if (path.length === 2 && method === "DELETE") {
          requestId();
          await cmd("memories.delete", { memory_id: id() });
          return json({ deleted: true });
        }
      }
      if (path[0] === "account" && method === "DELETE") {
        if (body.confirmation !== "DELETE") throw new ApiError("INVALID_REQUEST");
        await cmd("account.delete");
        await db.deleteUser(user.id);
        return json({ deleted: true });
      }
      throw new ApiError("NOT_FOUND", 404);
    } catch (error) {
      const e = error instanceof ApiError ? error : new ApiError("INTERNAL_ERROR", 500, true);
      return json({ error: errorPayload(e) }, e.status);
    }
  };
}
const encoder = new TextEncoder();
const event = (name: string, data: Json) =>
  encoder.encode(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`);
function replay(turn: string, reply: string, version: number): Response {
  return new Response(
    new ReadableStream({
      start(c) {
        c.enqueue(event("start", { turn_id: turn }));
        sentenceSplit(reply, true).sentences.forEach((text, index) =>
          c.enqueue(event("sentence", { text, index }))
        );
        c.enqueue(
          event("done", { turn_id: turn, assistant_text: reply, session_version: version }),
        );
        c.close();
      },
    }),
    { headers: { ...cors, "Content-Type": "text/event-stream", "Cache-Control": "no-store" } },
  );
}
function streamTurn(
  req: Request,
  db: Database,
  ai: AI,
  input: Json[],
  turnId: string,
  generation: number,
  reservation: string,
  reserved: number,
): Response {
  const abort = new AbortController();
  req.signal.addEventListener("abort", () => abort.abort(), { once: true });
  const timeout = setTimeout(() => abort.abort(), 60000);
  let closed = false;
  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      const emit = (name: string, data: Json) => {
        if (!closed && !abort.signal.aborted) controller.enqueue(event(name, data));
      };
      let full = "", pending = "", index = 0;
      let billed = reserved;
      emit("start", { turn_id: turnId });
      try {
        const usage = await ai.stream(input, async (delta) => {
          full += delta;
          pending += delta;
          if (full.length > 16000) throw new ApiError("AI_UNAVAILABLE", 503);
          const parsed = sentenceSplit(pending);
          pending = parsed.rest;
          if (parsed.sentences.length) {
            const guard = await db.worker("turns.validate", { turn_id: turnId, generation });
            if (!guard.valid) throw new ApiError("STALE_CONTEXT", 409);
          }
          for (const sentence of parsed.sentences) {
            emit("delta", { text: sentence + " " });
            emit("sentence", { text: sentence, index: index++ });
          }
        }, abort.signal);
        billed = Math.min(reserved, accountedCost(usage));
        const complete = await db.worker("turns.complete", {
          turn_id: turnId,
          generation,
          text: full,
        });
        if (!complete.accepted) throw new ApiError("STALE_CONTEXT", 409);
        for (const sentence of sentenceSplit(pending, true).sentences) {
          emit("delta", { text: sentence });
          emit("sentence", { text: sentence, index: index++ });
        }
        emit("done", {
          turn_id: turnId,
          assistant_text: full,
          session_version: complete.session_version,
        });
      } catch (error) {
        await db.worker("turns.fail", { turn_id: turnId, generation }).catch(() => {});
        emit("error", errorPayload(error));
      } finally {
        clearTimeout(timeout);
        await db.worker("budget.settle", { request_id: reservation, actual_cost_usd: billed })
          .catch(() => {});
        if (!closed) {
          closed = true;
          controller.close();
        }
      }
    },
    cancel() {
      closed = true;
      abort.abort();
      clearTimeout(timeout);
    },
  });
  return new Response(stream, {
    headers: {
      ...cors,
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Accel-Buffering": "no",
    },
  });
}
