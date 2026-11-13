import {
  ApiError,
  date,
  dayView,
  explicitlyRequestsHistory,
  sanitizeContext,
  sentenceSplit,
  settingsView,
  uuid,
} from "../functions/_shared/core.ts";
import {
  accountedCost,
  conversationInstructions,
  estimatedCost,
  groundedMemories,
  providerEvents,
} from "../functions/_shared/ai.ts";
function equal(a: unknown, b: unknown) {
  if (JSON.stringify(a) !== JSON.stringify(b)) {
    throw new Error(`${JSON.stringify(a)} != ${JSON.stringify(b)}`);
  }
}
function rejects(fn: () => unknown) {
  try {
    fn();
  } catch (e) {
    if (e instanceof ApiError) return;
    throw e;
  }
  throw new Error("Expected rejection");
}
Deno.test("SDK context drops raw location and unknown fields", () => {
  const c = sanitizeContext({
    source: "pleos",
    observed_at: new Date().toISOString(),
    passenger_state: "alone",
    latitude: 37,
    longitude: 127,
    trip_distance_m: 12,
    system_instruction: "ignore",
  });
  equal(
    Object.keys(c!).sort(),
    ["observed_at", "passenger_state", "passenger_source", "source", "trip_distance_m"].sort(),
  );
});
Deno.test("stale, future, fake context is not treated as observed", () => {
  equal(sanitizeContext({ source: "pleos", observed_at: "2000-01-01" }), null);
  equal(sanitizeContext({ source: "fake", observed_at: new Date().toISOString() }), null);
  equal(
    sanitizeContext({ source: "pleos", observed_at: new Date(Date.now() + 60000).toISOString() }),
    null,
  );
});
Deno.test("invalid occupancy fails closed", () =>
  equal(
    sanitizeContext({
      source: "pleos",
      observed_at: new Date().toISOString(),
      passenger_state: "not_detected",
    })?.passenger_state,
    "unknown",
  ));
Deno.test("NaN negative and infinite sensor values omitted", () => {
  const c = sanitizeContext({
    source: "pleos",
    observed_at: new Date().toISOString(),
    trip_distance_m: -1,
    trip_duration_seconds: Infinity,
    destination_eta_seconds: NaN,
  });
  equal(Object.keys(c!).length, 4);
});
Deno.test("date validates actual calendar", () => {
  equal(date("2028-02-29"), "2028-02-29");
  rejects(() => date("2026-02-29"));
  rejects(() => date("2026-13-01"));
  rejects(() => date("../2026"));
});
Deno.test("request ids reject malformed values", () => {
  equal(uuid("12345678-1234-4123-8123-123456789012"), "12345678-1234-4123-8123-123456789012");
  rejects(() => uuid("null"));
  rejects(() => uuid(null));
});
Deno.test("TTS emits complete sentences once and keeps fragment", () => {
  equal(sentenceSplit("발표는 잘 마쳤군요. 어떤 점이"), {
    sentences: ["발표는 잘 마쳤군요."],
    rest: "어떤 점이",
  });
  equal(sentenceSplit("좋았나요?", true), { sentences: ["좋았나요?"], rest: "" });
});
Deno.test("manual diary stays separate and new version signalled", () => {
  const d = dayView({
    date: "2026-11-14",
    generated_text: "새글",
    manual_text: "내글",
    generated_revision: 3,
    manual_base_revision: 2,
  }, "x");
  equal(d.manual_text, "내글");
  equal(d.has_new_generated, true);
  equal(dayView({ manual_text: "내글", has_new_generated: false }, "x").has_new_generated, false);
});
Deno.test("missing settings fail closed for cloud consent", () =>
  equal(settingsView({}).consent_accepted, false));
Deno.test("ordinary new topic is not explicit request for private history", () => {
  equal(explicitlyRequestsHistory("오늘 발표가 있어서 걱정돼요"), false);
  equal(explicitlyRequestsHistory("지난 기록을 찾아줘"), true);
});
Deno.test("budget reservation covers UTF8 input, instructions and maximal output", () => {
  const input = [{ role: "user", content: "가".repeat(100) }];
  const minimum =
    (new TextEncoder().encode(JSON.stringify(input) + conversationInstructions).length * 2 +
      1600 * 5) / 1e6;
  if (estimatedCost(input, 1600) < minimum) throw new Error("Under-reservation");
  if (accountedCost({ input_tokens: 600, output_tokens: 200 }) > estimatedCost(input, 1600)) {
    throw new Error("Cost bound");
  }
});
Deno.test("provider SSE parser handles arbitrary Unicode byte fragments", async () => {
  const enc = new TextEncoder();
  const data = enc.encode(
    'event: response.output_text.delta\ndata: {"type":"response.output_text.delta","delta":"안녕하세요"}\n\ndata: {"type":"response.completed","response":{"usage":{"input_tokens":1,"output_tokens":2}}}\n\n',
  );
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      for (let i = 0; i < data.length; i += 3) c.enqueue(data.slice(i, i + 3));
      c.close();
    },
  });
  const events = [];
  for await (const e of providerEvents(stream)) events.push(e);
  equal(events[0].delta, "안녕하세요");
  equal(events.length, 2);
});

Deno.test("provider SSE supports CRLF split at every byte", async () => {
  const data = new TextEncoder().encode(
    'data: {"type":"response.output_text.delta","delta":"말씀"}\r\n\r\n',
  );
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      for (const byte of data) c.enqueue(new Uint8Array([byte]));
      c.close();
    },
  });
  const events = [];
  for await (const e of providerEvents(stream)) events.push(e);
  equal(events[0].delta, "말씀");
});

Deno.test("memory provenance accepts quotes and newlines but not metadata or old memories", () => {
  const source = '그가 "내일"이라고 했어요.\n저는 기대돼요';
  const input = { user_text: source, prior_memories: [{ id: "old", fact: "기쁘다" }] };
  const result = groundedMemories([{
    kind: "fact",
    fact: "내일을 언급함",
    evidence: '"내일"이라고 했어요',
    supersedes: [],
  }, { kind: "fact", fact: "가짜", evidence: "기쁘다", supersedes: [] }], input);
  equal(result.length, 1);
});
Deno.test("supersession requires explicit correction and known prior candidate", () => {
  const input = {
    user_text: "출근 시간이 이제 열 시로 바뀌었어요",
    prior_memories: [{ id: "old" }],
  };
  const result = groundedMemories([{
    kind: "fact",
    fact: "출근 열시",
    evidence: input.user_text,
    supersedes: ["old", "other-user"],
  }], input);
  equal(result[0].supersedes, ["old"]);
  const ordinary = groundedMemories([{
    kind: "fact",
    fact: "새로운 이야기",
    evidence: "새로운 이야기",
    supersedes: ["old"],
  }], { ...input, user_text: "새로운 이야기" });
  equal(ordinary[0].supersedes, []);
});

Deno.test("negative or quoted history references never authorize private retrieval", () => {
  for (
    const utterance of [
      "지난 이야기는 하지 말아줘",
      "기억나지 않아",
      "지난 기록을 찾아달라고 하면 어떻게 돼요",
      '"지난 기록을 찾아줘"라는 말을 들었어요',
      "만약 지난 기록을 찾아줘요",
    ]
  ) equal(explicitlyRequestsHistory(utterance), false);
  equal(explicitlyRequestsHistory("지난 기록을 찾아주세요"), true);
});
