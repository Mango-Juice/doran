// Real provider evaluation. Supply a private dotenv path; never print its contents or secret.
import cases from "./korean_cases.json" with { type: "json" };
import { conversationInstructions, MODEL, openAI } from "../../functions/_shared/ai.ts";
const [keyFile, outputPath] = Deno.args;
if (!keyFile || !outputPath) throw new Error("Usage: provider-run.ts PRIVATE_DOTENV OUTPUT_JSON");
const env = await Deno.readTextFile(keyFile);
const match = env.match(/^\s*(?:export\s+)?OPENAI_API_KEY\s*=\s*(.+?)\s*$/m);
if (!match) throw new Error("OPENAI_API_KEY missing");
let secret = match[1];
if (
  (secret.startsWith('"') && secret.endsWith('"')) ||
  (secret.startsWith("'") && secret.endsWith("'"))
) secret = secret.slice(1, -1);
if (!secret.startsWith("sk-")) throw new Error("Invalid key format");
Deno.env.set("OPENAI_API_KEY", secret);
const ai = openAI();
const digest = async (value: string) =>
  [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)))].map(
    (x) => x.toString(16).padStart(2, "0"),
  ).join("");
const promptSha = await digest(conversationInstructions);
const runnerSha = await digest(await Deno.readTextFile(new URL(import.meta.url)));
const priorReservedUsd = Number(Deno.env.get("DORAN_EVAL_PRIOR_RESERVED_USD") ?? 0);
if (!Number.isFinite(priorReservedUsd) || priorReservedUsd < 0 || priorReservedUsd > 0.90) {
  throw new Error("Invalid prior evaluation reservation");
}
let reservedUsd = 0;
let actualConservativeUsd = 0;
const results = [];
const selectedIds = Deno.env.get("DORAN_EVAL_IDS")?.split(",");
for (
  const item of cases.filter((c) => !selectedIds || selectedIds.includes(c.id)).slice(
    0,
    Number(Deno.env.get("DORAN_EVAL_LIMIT") ?? cases.length),
  )
) {
  const prelude: Record<string, string> = {
    correct_01: "지금 회사 가는 길이에요",
    correct_02: "제 출근 시간은 아침 아홉 시예요",
    correct_03: "내일 발표가 있어요",
    correct_04: "발표가 걱정돼요",
    correct_05: "오늘 발표가 걱정됐어요",
  };
  const context = {
    now: "2026-11-14T09:00:00+09:00",
    timezone: "Asia/Seoul",
    now_kst: "now_kst" in item ? item.now_kst : "2026-11-14T09:00:00+09:00",
    history_allowed: item.id === "memory_01",
    vehicle: item.id === "context_04" ? { passenger_state: "occupied" } : null,
    memories: item.id === "memory_01"
      ? [{
        id: "fictional-memory",
        fact: "2026년11월13일 발표자료를 준비했다",
        record_date: "2026-11-13",
        evidence: "내일 할 발표의 자료를 오늘 준비했어요",
      }]
      : [],
    settings: { conversation_style: "balanced" },
  };
  const input = [
    { role: "developer", content: JSON.stringify(context) },
    ...(prelude[item.id]
      ? [{ role: "user", content: prelude[item.id] }, {
        role: "assistant",
        content: "이야기해 주셔서 고마워요.",
      }]
      : []),
    { role: "user", content: item.utterance },
  ];
  // Exact static conversation input plus 1KB framing margin, UTF8 byte upper bound and output cap768.
  const ceiling =
    (new TextEncoder().encode(JSON.stringify({ instructions: conversationInstructions, input }))
          .length * 2 + 1024 * 2 + 768 * 5) / 1e6;
  if (priorReservedUsd + reservedUsd + ceiling > 0.90) {
    throw new Error("Evaluation reservation exceeds $0.90 sub-budget; no call made");
  }
  reservedUsd += ceiling;
  const started = Date.now();
  let answer = "";
  try {
    const usage = await ai.stream(input, (delta) => {
      answer += delta;
      return Promise.resolve();
    }, AbortSignal.timeout(60000));
    actualConservativeUsd += (usage.input_tokens * 2 + usage.output_tokens * 5) / 1e6;
    results.push({ ...item, answer, usage, elapsed_ms: Date.now() - started, status: "completed" });
  } catch (error) {
    results.push({
      ...item,
      answer: "",
      elapsed_ms: Date.now() - started,
      status: "failed",
      error: error instanceof Error ? error.message : "error",
    });
  }
  await Deno.writeTextFile(
    outputPath,
    JSON.stringify(
      {
        model: MODEL,
        prompt_sha256: promptSha,
        runner_sha256: runnerSha,
        created_at: new Date().toISOString(),
        reservation_usd: reservedUsd,
        prior_reservation_usd: priorReservedUsd,
        conservative_budget_charge_usd: actualConservativeUsd,
        provider_calls: results.length,
        note:
          "Synthetic Korean text API evaluation. Does not verify microphone, TTS, SDK, or deployed cross-device flow.",
        results,
      },
      null,
      2,
    ),
  );
  console.log(`${item.id}: ${results.at(-1)?.status}`);
  if (results.at(-1)?.status !== "completed") break;
}
console.log(
  JSON.stringify({
    completed: results.filter((r) => r.status === "completed").length,
    reserved_usd: reservedUsd,
    conservative_budget_charge_usd: actualConservativeUsd,
  }),
);
