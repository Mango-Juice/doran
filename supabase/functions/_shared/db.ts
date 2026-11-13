import { ApiError, type Json } from "./core.ts";
export interface Database {
  command(actor: string, action: string, payload?: Json): Promise<Json>;
  worker(action: string, payload?: Json): Promise<Json>;
  user(token: string): Promise<Json>;
  deleteUser(id: string): Promise<void>;
}
export function database(): Database {
  const base = Deno.env.get("SUPABASE_URL");
  const secret = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? Deno.env.get("SUPABASE_SECRET_KEY");
  if (!base || !secret) throw new ApiError("SERVICE_NOT_CONFIGURED", 503);
  const headers = {
    apikey: secret,
    Authorization: `Bearer ${secret}`,
    "Content-Type": "application/json",
  };
  async function rpc(name: string, args: Json): Promise<Json> {
    const response = await fetch(`${base}/rest/v1/rpc/${name}`, {
      method: "POST",
      headers,
      body: JSON.stringify(args),
      signal: AbortSignal.timeout(15000),
    });
    const data = await response.json();
    if (!response.ok || data?.error) {
      const original = String(data.error ?? data.message ?? "INTERNAL_ERROR").split(/[ :\n]/)[0];
      const code = ({
        BUDGET_EXHAUSTED: "BUDGET_EXCEEDED",
        DAILY_LIMIT: "RATE_LIMITED",
        DEVICE_UNPAIRED: "DEVICE_NOT_PAIRED",
      } as Record<
        string,
        string
      >)[original] ?? original;
      const status = code === "UNAUTHENTICATED"
        ? 401
        : /NOT_FOUND/.test(code)
        ? 404
        : /CONFLICT|IN_PROGRESS|FINISHED|CLAIMED|STALE/.test(code)
        ? 409
        : /BUDGET|RATE_LIMIT/.test(code)
        ? 429
        : /FORBIDDEN|CONSENT|PAIRED|NOT_ANONYMOUS/.test(code)
        ? 403
        : /EXPIRED|INVALID|EMPTY_SESSION|TOO_MANY_PENDING/.test(code)
        ? 400
        : 500;
      throw new ApiError(/^[A-Z_]+$/.test(code) ? code : "INTERNAL_ERROR", status, status >= 500);
    }
    return data;
  }
  return {
    command: (actor, action, payload = {}) =>
      rpc("doran_command", { p_actor: actor, p_action: action, p_payload: payload }),
    worker: (action, payload = {}) => rpc("doran_worker", { p_action: action, p_payload: payload }),
    async user(token) {
      const response = await fetch(`${base}/auth/v1/user`, {
        headers: { ...headers, Authorization: `Bearer ${token}` },
        signal: AbortSignal.timeout(10000),
      });
      if (!response.ok) throw new ApiError("UNAUTHENTICATED", 401);
      const user = await response.json();
      if (!user.id) throw new ApiError("UNAUTHENTICATED", 401);
      return user;
    },
    async deleteUser(id) {
      const response = await fetch(`${base}/auth/v1/admin/users/${id}`, {
        method: "DELETE",
        headers,
        signal: AbortSignal.timeout(10000),
      });
      if (!response.ok && response.status !== 404) throw new ApiError("INTERNAL_ERROR", 500, true);
    },
  };
}
