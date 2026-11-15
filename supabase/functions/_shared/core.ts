export class ApiError extends Error {
  constructor(public code: string, public status = 400, public retryable = false) {
    super(code);
  }
}
export type Json = Record<string, any>;
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export function uuid(value: unknown): string {
  if (typeof value !== "string" || !UUID.test(value)) throw new ApiError("INVALID_REQUEST_ID");
  return value;
}
export function date(value: string): string {
  if (
    !/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(Date.parse(value)) ||
    new Date(value).toISOString().slice(0, 10) !== value
  ) {
    throw new ApiError("INVALID_DATE");
  }
  return value;
}
export function text(value: unknown, max = 12000): string {
  if (typeof value !== "string" || !value.trim() || value.length > max) {
    throw new ApiError("INVALID_TEXT");
  }
  return value.trim();
}
export function revision(value: unknown): number {
  if (!Number.isInteger(value) || Number(value) < 0) throw new ApiError("INVALID_VERSION");
  return Number(value);
}
const messages: Record<string, string> = {
  UNAUTHENTICATED: "로그인이 필요해요",
  FORBIDDEN: "이 작업을 할 수 있는 권한이 없어요",
  NOT_FOUND: "기록을 찾을 수 없어요",
  VERSION_CONFLICT: "다른 기기에서 변경된 내용이 있어요. 새로 확인해 주세요",
  AI_NOT_CONFIGURED: "대화 서비스 연결을 준비하고 있어요. 잠시 후 다시 시도해 주세요",
  SERVICE_NOT_CONFIGURED: "서비스 연결을 준비하고 있어요",
  BUDGET_EXCEEDED: "AI 이용 한도에 도달했어요. 저장한 기록은 계속 볼 수 있어요",
  RATE_LIMITED: "요청이 많아요. 잠시 후 다시 시도해 주세요",
  TURN_IN_PROGRESS: "이전 응답을 기다리고 있어요",
  SESSION_FINISHED: "이미 마친 대화예요. 새 대화를 시작해 주세요",
  EMPTY_SESSION: "아직 저장할 이야기가 없어요",
  CONSENT_REQUIRED: "모바일 앱에서 데이터 처리 안내를 확인해 주세요",
  DEVICE_NOT_PAIRED: "모바일 앱에서 차량을 연결해 주세요",
  PAIRING_EXPIRED: "연결 코드가 만료됐어요. 새 코드를 받아 주세요",
  PAIRING_NOT_FOUND: "연결 코드를 확인해 주세요",
  PAIRING_ALREADY_CLAIMED: "다른 기기에서 사용 중인 코드예요",
  AI_UNAVAILABLE: "응답을 받지 못했어요. 이야기는 안전하게 보관하고 있어요",
  STALE_CONTEXT: "기록이 변경되어 응답을 멈췄어요. 다시 이야기해 주세요",
  INTERNAL_ERROR: "처리하지 못했어요. 잠시 후 다시 시도해 주세요",
};
export function errorPayload(error: unknown): Json {
  const e = error instanceof ApiError ? error : new ApiError("INTERNAL_ERROR", 500, true);
  return {
    code: e.code,
    message: messages[e.code] ?? "입력 내용을 확인해 주세요",
    retryable: e.retryable,
  };
}
export function sanitizeContext(value: unknown, now = Date.now()): Json | null {
  if (!value || typeof value !== "object") return null;
  const c = value as Json;
  const observed = Date.parse(c.observed_at);
  if (
    !Number.isFinite(observed) || now - observed > 120000 || observed - now > 10000 ||
    c.source !== "pleos"
  ) return null;
  const result: Json = {
    passenger_source: ["pleos_seat", "user_assertion", "unknown"].includes(c.passenger_source)
      ? c.passenger_source
      : "unknown",
    source: "pleos",
    observed_at: new Date(observed).toISOString(),
    passenger_state: ["alone", "occupied", "unknown"].includes(c.passenger_state)
      ? c.passenger_state
      : "unknown",
  };
  for (const key of ["destination_eta_seconds", "trip_duration_seconds", "trip_distance_m"]) {
    if (typeof c[key] === "number" && Number.isFinite(c[key]) && c[key] >= 0 && c[key] <= 1e7) {
      result[key] = c[key];
    }
  }
  if (typeof c.arrived === "boolean") result.arrived = c.arrived;
  if (["pleos_navi_arrived", "pleos_vehicle_odometer"].includes(c.trip_duration_source)) {
    result.trip_duration_source = c.trip_duration_source;
  }
  if (typeof c.place_label === "string" && c.place_label.length <= 80) {
    result.place_label = c.place_label;
  }
  if (
    typeof c.destination_name === "string" && c.destination_name.trim().length > 0 &&
    c.destination_name.length <= 200
  ) {
    result.destination_name = c.destination_name.trim();
  }
  const b = c.trip_baseline;
  if (
    b && b.source === "local_confirmed_arrivals" && Number.isInteger(b.sample_count) &&
    b.sample_count >= 3 && b.sample_count <= 10000 &&
    Number.isFinite(b.median_duration_seconds) && b.median_duration_seconds >= 0 &&
    b.median_duration_seconds <= 86400 && Number.isInteger(b.median_arrival_minute) &&
    b.median_arrival_minute >= 0 && b.median_arrival_minute < 1440 &&
    Number.isFinite(Date.parse(b.latest_observed_at)) &&
    now - Date.parse(b.latest_observed_at) >= 0 &&
    now - Date.parse(b.latest_observed_at) <= 30 * 86400000
  ) {
    result.trip_baseline = {
      source: b.source,
      sample_count: b.sample_count,
      median_duration_seconds: b.median_duration_seconds,
      median_arrival_minute: b.median_arrival_minute,
      latest_observed_at: b.latest_observed_at,
    };
  }
  return result;
}
export function explicitlyRequestsHistory(utterance: string): boolean {
  // Occupancy unknown/occupied must fail closed: a negative, quote or hypothetical is not consent.
  if (/["“”'‘’]|않|말아|말고|하지|없|예를|만약|가정|라고|다면/.test(utterance)) return false;
  return /(?:지난|이전|과거|아까|전에).*(?:기록|대화|이야기).*(?:찾아(?:줘|주세요)|알려(?:줘|주세요)|보여(?:줘|주세요)|확인해(?:줘|주세요))/
    .test(utterance);
}
export function sentenceSplit(
  buffer: string,
  final = false,
): { sentences: string[]; rest: string } {
  const sentences: string[] = [];
  const regex = /[.!?。！？](?:[”"']?)(?:\s+|$)|\n+/g;
  let cursor = 0;
  for (const match of buffer.matchAll(regex)) {
    const end = match.index! + match[0].length;
    const part = buffer.slice(cursor, end).trim();
    if (part) sentences.push(part);
    cursor = end;
  }
  let rest = buffer.slice(cursor);
  if (final && rest.trim()) {
    sentences.push(rest.trim());
    rest = "";
  }
  return { sentences, rest };
}
export function settingsView(profile: Json): Json {
  return {
    vehicle_context_enabled: profile.consent_vehicle ?? false,
    consent_destination_name: profile.consent_destination_name ?? false,
    vehicle_memory_revision: profile.vehicle_memory_revision ?? 0,
    personalization_enabled: profile.personalization_enabled ?? true,
    conversation_suggestions: profile.suggestion_frequency ?? "off",
    conversation_style: profile.conversation_style ?? "balanced",
    consent_accepted: profile.consent_ai ?? false,
    version: profile.version ?? 1,
  };
}
export function dayView(value: Json | null, day: string): Json {
  const d = value ?? {};
  return {
    date: d.date ?? d.record_date ?? d.local_date ?? day,
    generated_text: d.generated_text ?? "",
    manual_text: d.manual_text ?? null,
    version: d.version ?? 0,
    source_revision: d.source_revision ?? 0,
    generated_revision: d.generated_revision ?? 0,
    has_new_generated: d.has_new_generated ?? (d.manual_text != null &&
      (d.generated_revision ?? 0) > (d.manual_base_revision ?? 0)),
    status: d.status ?? "empty",
    updated_at: d.updated_at ?? null,
  };
}
export async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((x) => x.toString(16).padStart(2, "0")).join("");
}
