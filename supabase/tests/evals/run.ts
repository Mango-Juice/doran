import cases from "./korean_cases.json" with { type: "json" };
const token = Deno.env.get("DORAN_EVAL_CAR_TOKEN");
const host = Deno.env.get("SUPABASE_URL");
const key = Deno.env.get("SUPABASE_PUBLISHABLE_KEY");
if (!token || !host || !key) {
  throw new Error(
    "Set evaluation vehicle token, Supabase URL and publishable key in private environment",
  );
}
const headers = {
  authorization: `Bearer ${token}`,
  apikey: key,
  "content-type": "application/json",
};
const base = `${host}/functions/v1/doran-api/v1`;
for (const item of cases) {
  const start = Date.now();
  const opened = await fetch(`${base}/sessions`, {
    method: "POST",
    headers,
    body: JSON.stringify({ request_id: crypto.randomUUID() }),
  });
  if (!opened.ok) throw new Error(`Session failed ${opened.status}`);
  const session = await opened.json();
  const response = await fetch(`${base}/sessions/${session.id}/turns`, {
    method: "POST",
    headers,
    body: JSON.stringify({ request_id: crypto.randomUUID(), text: item.utterance }),
  });
  const result = await response.text();
  console.log(
    JSON.stringify({
      id: item.id,
      http_status: response.status,
      elapsed_ms: Date.now() - start,
      criterion: item.criterion,
      output: result,
    }),
  );
  if (!response.ok) throw new Error(`Evaluation stopped at ${item.id}; no provider substitutions`);
  await fetch(`${base}/sessions/${session.id}/finish`, {
    method: "POST",
    headers,
    body: JSON.stringify({ request_id: crypto.randomUUID() }),
  });
  // Respect ten turns per minute per account; this is an evaluation throttle, not an app delay.
  await new Promise((resolve) => setTimeout(resolve, 6500));
}
