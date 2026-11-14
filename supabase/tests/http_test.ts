// deno-lint-ignore-file require-await -- synchronous fakes implement asynchronous DB/AI interfaces
import { createHandler } from "../functions/doran-api/handler.ts";
import { ApiError, type Json } from "../functions/_shared/core.ts";
import type { Database } from "../functions/_shared/db.ts";
const id = "12345678-1234-4123-8123-123456789012";
function equal(a: unknown, b: unknown) {
  if (JSON.stringify(a) !== JSON.stringify(b)) {
    throw new Error(`${JSON.stringify(a)} != ${JSON.stringify(b)}`);
  }
}
function request(path: string, method = "GET", body?: Json, auth = true) {
  return new Request(`https://test.supabase.co/functions/v1/doran-api/v1/${path}`, {
    method,
    headers: auth ? { authorization: "Bearer valid", "content-type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
}
function db(action: (s: string, p: Json) => Json = () => ({})): Database {
  return {
    command: async (actor, a, p = {}) => {
      equal(actor, id);
      return action(a, p);
    },
    worker: async () => ({}),
    user: async () => ({ id, is_anonymous: false }),
    deleteUser: async () => {},
  };
}
Deno.test("unauthenticated HTTP rejected before RPC", async () => {
  let called = false;
  const h = createHandler(() =>
    db(() => {
      called = true;
      return {};
    })
  );
  equal((await h(request("records", "GET", undefined, false))).status, 401);
  equal(called, false);
});
Deno.test("unavailable AI returns honest503 without persisting turn", async () => {
  let called = false;
  const h = createHandler(() =>
    db(() => {
      called = true;
      return {};
    }), () => {
    throw new ApiError("AI_NOT_CONFIGURED", 503);
  });
  const r = await h(request(`sessions/${id}/turns`, "POST", { request_id: id, text: "안녕" }));
  equal(r.status, 503);
  equal(called, false);
});
Deno.test("flat SQL day output preserves manual edits", async () => {
  const h = createHandler(() =>
    db(() => ({
      date: "2026-11-14",
      manual_text: "내글",
      generated_text: "새글",
      has_new_generated: true,
      version: 3,
    }))
  );
  const r = await h(request("days/2026-11-14"));
  const body = await r.json();
  equal(body.manual_text, "내글");
  equal(body.has_new_generated, true);
});
Deno.test("pairing flat responses map into contract", async () => {
  const h = createHandler(() =>
    db((a) =>
      a === "pairings.create"
        ? { id, expires_at: "2026-11-14T01:00:00Z", status: "waiting" }
        : { id, expires_at: "x", status: "claimed", device_label: "차량" }
    )
  );
  const r = await h(request("pairings", "POST", { request_id: id }));
  const body = await r.json();
  equal(body.id, id);
  equal(body.code.length, 6);
  const claim = await h(request("pairings/claim", "POST", { code: "123456", request_id: id }));
  equal((await claim.json()).id, id);
});
Deno.test("finish passes queued speech without requiring AI key", async () => {
  let payload: Json = {};
  const h = createHandler(() =>
    db((a, p) => {
      equal(a, "sessions.finish");
      payload = p;
      return { record: { id, status: "pending" } };
    })
  );
  const r = await h(
    request(`sessions/${id}/finish`, "POST", {
      request_id: id,
      pending_turns: [{ request_id: id, text: "오늘 발표했어요" }],
    }),
  );
  equal(r.status, 200);
  equal(payload.pending_turns[0].text, "오늘 발표했어요");
});
Deno.test("delete only accepted with expected version and explicit scope", async () => {
  let payload: Json = {};
  const h = createHandler(() =>
    db((_, p) => {
      payload = p;
      return {};
    })
  );
  const r = await h(request(`records/${id}`, "DELETE", { request_id: id, version: 2 }));
  equal(r.status, 200);
  equal(payload.delete_manual, false);
  const bad = await h(request(`records/${id}`, "DELETE", { request_id: id }));
  equal(bad.status, 400);
});
Deno.test("server failures never reveal provider secrets", async () => {
  const h = createHandler(() =>
    db(() => {
      throw new Error("secret_key_private_user_transcript");
    })
  );
  const r = await h(request("records"));
  equal(r.status, 500);
  if ((await r.text()).includes("secret_key")) throw new Error("Secret leak");
});
Deno.test("data changed midstream suppresses sentence and completion", async () => {
  const database = db(() => ({
    turn: { id, status: "pending" },
    session: { version: 1 },
    owner_id: id,
    duplicate: false,
  }));
  database.worker = async (action) =>
    action === "context.get"
      ? { messages: [{ role: "user", content: "안녕" }], context: { passenger_state: "alone" } }
      : action === "turns.validate"
      ? { valid: false }
      : { reserved: true };
  const h = createHandler(() => database, () => ({
    stream: async (_, delta) => {
      await delta("민감한 과거 기록입니다.");
      return { input_tokens: 1, output_tokens: 1 };
    },
    summarize: async () => {
      throw new Error("unused");
    },
  }));
  const response = await h(
    request(`sessions/${id}/turns`, "POST", { request_id: id, text: "안녕" }),
  );
  const output = await response.text();
  if (output.includes("event: sentence") || output.includes("event: done")) {
    throw new Error("Stale spoken response leaked");
  }
  if (!output.includes("STALE_CONTEXT")) throw new Error("Missing stale error");
});

Deno.test("fixed public policy is available without auth or database", async () => {
  const h = createHandler(() => {
    throw new Error("must not initialize database");
  });
  for (const path of ["about", "privacy"]) {
    const r = await h(request(path, "GET", undefined, false));
    equal(r.status, 200);
    if (!(await r.text()).includes("도란")) throw new Error("Missing product policy");
  }
  equal((await h(request("settings", "GET", undefined, false))).status, 500);
});

for (const occupancy of ["occupied", "unknown"]) {
  Deno.test(`private records never enter outbound model input when ${occupancy}`, async () => {
    let outbound = "";
    const database = db(() => ({
      turn: { id, status: "pending" },
      session: { version: 1 },
      owner_id: id,
      duplicate: false,
    }));
    database.worker = async (action) =>
      action === "context.get"
        ? {
          messages: [{ role: "user", content: "가벼운 이야기를 나눠요" }],
          memories: [{ fact: "민감한 비공개 이직 계획" }],
          records: [{ text: "민감한 비공개 이직 계획 원문" }],
          context: {
            source: "pleos",
            observed_at: new Date().toISOString(),
            passenger_state: occupancy,
            passenger_source: "pleos_seat",
          },
        }
        : action === "turns.validate"
        ? { valid: true }
        : action === "turns.complete"
        ? { accepted: true, session_version: 2 }
        : { reserved: true };
    const h = createHandler(() => database, () => ({
      stream: async (input, delta) => {
        outbound = JSON.stringify(input);
        await delta("편하게 이야기해요.");
        return { input_tokens: 1, output_tokens: 1 };
      },
      summarize: async () => {
        throw new Error("unused");
      },
    }));
    const response = await h(
      request(`sessions/${id}/turns`, "POST", { request_id: id, text: "가벼운 이야기를 나눠요" }),
    );
    await response.text();
    if (outbound.includes("비공개") || outbound.includes("이직")) {
      throw new Error("Private source crossed model boundary");
    }
    if (!outbound.includes('"history_allowed":false'.replaceAll('"', '\\"'))) {
      throw new Error("Privacy context missing");
    }
  });
}

Deno.test("late cancelled dispatch cannot publish or fail its retry generation", async () => {
  let generation = 0;
  let currentStatus = "pending";
  const calls: { action: string; generation: unknown }[] = [];
  let releaseOld!: () => void;
  const oldProvider = new Promise<void>((resolve) => releaseOld = resolve);
  const database = db(() => ({
    turn: { id, status: "pending", generation: ++generation },
    session: { version: generation },
    owner_id: id,
    duplicate: false,
  }));
  database.worker = async (action, payload = {}) => {
    if (action === "context.get") return { messages: [{ role: "user", content: "합성 재시도" }] };
    if (action.startsWith("turns.")) {
      calls.push({ action, generation: payload.generation });
      const matches = payload.generation === generation;
      if (action === "turns.validate") return { valid: matches };
      if (action === "turns.complete") {
        if (matches) currentStatus = "complete";
        return { accepted: matches, session_version: generation };
      }
      if (action === "turns.fail") {
        if (matches) currentStatus = "failed";
        return { failed: matches };
      }
    }
    return { reserved: true, settled: true };
  };
  let providers = 0;
  const handler = createHandler(() => database, () => ({
    stream: async (_, delta) => {
      const dispatch = ++providers;
      if (dispatch === 1) await oldProvider;
      await delta(dispatch === 1 ? "취소된 옛 응답입니다." : "새 시도의 응답입니다.");
      return { input_tokens: 1, output_tokens: 1 };
    },
    summarize: async () => {
      throw new Error("unused");
    },
  }));
  const oldResponse = await handler(request(`sessions/${id}/turns`, "POST", {
    request_id: id,
    text: "합성 재시도",
  }));
  const oldBody = oldResponse.text();
  const retry = await handler(request(`sessions/${id}/turns`, "POST", {
    request_id: id,
    text: "합성 재시도",
  }));
  const retryBody = await retry.text();
  releaseOld();
  const cancelledBody = await oldBody;
  if (!retryBody.includes("event: done")) throw new Error("Retry did not complete");
  if (cancelledBody.includes("취소된 옛 응답") || cancelledBody.includes("event: done")) {
    throw new Error("Old generation response leaked");
  }
  equal(currentStatus, "complete");
  equal(calls.filter((c) => c.action === "turns.complete"), [{
    action: "turns.complete",
    generation: 2,
  }]);
  equal(calls.filter((c) => c.action === "turns.fail"), [{ action: "turns.fail", generation: 1 }]);
});

for (const failure of ["oversized_context", "budget_rejected"]) {
  Deno.test(`pre-stream ${failure} failure retains the dispatch generation`, async () => {
    const failures: Json[] = [];
    const database = db(() => ({
      turn: { id, status: "pending", generation: 7 },
      session: { version: 1 },
      owner_id: id,
      duplicate: false,
    }));
    database.worker = async (action, payload = {}) => {
      if (action === "context.get") {
        return {
          messages: [{
            role: "user",
            content: failure === "oversized_context" ? "a".repeat(100000) : "합성",
          }],
        };
      }
      if (action === "budget.reserve") throw new ApiError("BUDGET_EXHAUSTED", 429);
      if (action === "turns.fail") failures.push(payload);
      return {};
    };
    const handler = createHandler(() => database, () => ({
      stream: async () => {
        throw new Error("Provider must not be called");
      },
      summarize: async () => {
        throw new Error("unused");
      },
    }));
    const response = await handler(request(`sessions/${id}/turns`, "POST", {
      request_id: id,
      text: "합성",
    }));
    equal(response.status, failure === "oversized_context" ? 400 : 429);
    equal(failures, [{ turn_id: id, generation: 7 }]);
  });
}
