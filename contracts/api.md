# Doran API v1

Base: `${SUPABASE_URL}/functions/v1/doran-api/v1`. All requests include `apikey: PUBLISHABLE_KEY`
and `Authorization: Bearer <Supabase access token>`. Mobile signs in with Google/PKCE; vehicle uses
Supabase anonymous auth then bootstrap. Responses are direct JSON (no data envelope); errors are
`{ "error": { "code": "...", "message": "Korean user-safe message", "retryable": false } }`. UTC
timestamps ISO8601, diary dates YYYY-MM-DD Asia/Seoul. Mutable operations require UUID `request_id`
and version where stated. Generic error statuses 400 invalid, 401 unauthenticated, 403 denied, 404
not found, 409 version conflict, 429 quota, 503 missing service configuration. No production
mock AI or authentication bypass.

## Vehicle and pairing

- `POST /devices/bootstrap` `{label?:string}` →
  `{device:{id,owner_id:null|string,label,active},paired:boolean}`. Anonymous vehicle identity only.
  Device identity is auth.uid(), stored separately from owner.
- `GET /devices/current` → same shape. Revoked device remains unpaired.
- `POST /pairings` `{request_id}` → `{id,code,expires_at,status:"waiting"}`. Vehicle only, six
  digits, five-minute expiry; invalidate previous challenge. Code returned once.
- `GET /pairings/{id}` →
  `{id,expires_at,status:"waiting"|"claimed"|"paired"|"expired",owner_label?:string}`. Only
  initiating vehicle or claimant.
- `POST /pairings/claim` `{code,request_id}` → `{id,expires_at,status:"claimed",device_label}`.
  Google owner only; server records mobile approval.
- `POST /pairings/{id}/confirm` `{request_id}` → `{id,status:"paired",device_id}`. Initiating
  vehicle confirms the pending owner.
- `GET /devices` → `{items:[{id,label,active,created_at,last_seen_at}]}`. Owner's devices only.
- `DELETE /devices/{id}` → `{deleted:true}`. Owner only; revoke authorization immediately.

## Conversation

- `POST /sessions` `{request_id,context?:VehicleContext}` →
  `{id,version,status:"active",record_date}`. Paired vehicle only.
- `POST /sessions/{id}/turns` `{request_id,text,context?:VehicleContext}` → SSE
  (`Accept: text/event-stream`). `text` must contain a final recognized user utterance. One active
  turn per session. Retrying the same request ID and text never duplicates user messages.
  A completed turn replays its result; a failed or paused/cancelled turn starts a new provider
  attempt. A pending attempt returns `TURN_IN_PROGRESS` until it is at least90 seconds old,
  after which the same ID can be reclaimed. Changed text with the same ID is an idempotency error.
  A retry uses the current vehicle privacy context. Internal attempt generations reject late
  success/failure callbacks from earlier attempts; clients do not supply a generation.
- SSE `start`: `{turn_id}`; `delta`: `{text}`; `sentence`: `{text,index}` (only completed sentences
  for TTS); `done`: `{turn_id,assistant_text,session_version}`; `error`: `{code,message,retryable}`.
  Every event is `event: NAME\ndata: JSON\n\n`. Cancellation must stop local speech. HTTP errors
  before streaming use normal error envelope.
- `POST /sessions/{id}/finish`
  `{request_id,context?:VehicleContext,pending_turns?:[{request_id,text,context?}]}` →
  `{record_id,status:"pending"|"ready"}`. Save source text immediately; asynchronous AI processing.
  Idempotent. pending_turns (maximum20) are recognized offline/provider-failed utterances,
  deduplicated atomically without invoking AI.
- `POST /sessions/{id}/pause` `{request_id}` → `{id,status:"paused",version}`. Resume by retrying
  the preserved utterance with its original request ID or submitting the next turn; finish is terminal.

`VehicleContext`:
`{observed_at:ISO8601, passenger_state:"alone"|"occupied"|"unknown", source:"pleos", destination_eta_seconds?:number, arrived?:boolean, trip_duration_seconds?:number, trip_distance_m?:number, place_label?:string}`.
Optional capability fields omitted when unavailable. No raw coordinates or continuous routes in
cloud API. Stale context (>120s) is not used. Historical personal topics are not proactively
mentioned when passenger state is occupied/unknown. Device receives settings via `GET /settings`
before collecting optional vehicle context.

## Records and days (mobile owner only)

- `GET /records?date=YYYY-MM-DD&limit=20&cursor=...` → `{items:[Record],next_cursor:null|string}`.
  Date optional, newest first. Limit 1..50.
- `GET /records/{id}` → `Record`.
- `PATCH /records/{id}`
  `{request_id,version,text?:string,record_date?:date,excluded_from_personalization?:boolean}` →
  updated `Record`.
- `DELETE /records/{id}` JSON body `{request_id,version,delete_manual_diary?:boolean}` →
  `{deleted:true}`. Cascade source transcript/memories/index; regenerate affected AI day. Manual
  diary retained unless explicit true.
- `GET /days/{date}` → `Day` (empty date returns empty Day, not404).
- `PATCH /days/{date}` `{request_id,version,manual_text:string}` → `Day`. This saves only manual
  version.
- `POST /days/{date}/adopt` `{request_id,version}` → `Day` (explicitly adopt current generated
  version).
- `DELETE /days/{date}` `{request_id,version}` → `{deleted:true}`. Delete ALL that day's records and
  manual/generated diary; source sessions/messages/memories removed.
- `POST /records/{id}/retry` `{request_id}` → `{status:"pending"}`.

`Record`:
`{id,session_id,record_date,text,version,excluded_from_personalization,status:"pending"|"ready"|"failed",created_at,updated_at}`.
`text` is effective edited/generated/source fallback; optional `title` reserved, clients must not
require it.

`Day`:
`{date,generated_text:string,manual_text:string|null,version:number,source_revision:number,generated_revision:number,has_new_generated:boolean,status:"empty"|"pending"|"ready"|"failed",updated_at:string|null}`.
Display manual_text when non-null. has_new_generated indicates AI changed since manual
save/adoption. Version0 means no saved day.

## Preferences, memory and account

- `GET /settings` →
  `{vehicle_context_enabled:false,personalization_enabled:true,conversation_suggestions:"off"|"daily"|"trip",conversation_style:"balanced"|"listen"|"questions",consent_accepted:false,version:1}`.
  Paired car can read own owner's settings; owner can write.
- `PATCH /settings` `{request_id,version,...changedFields}` → full settings. Base text/cloud consent
  must be accepted before sessions. Consent revoke stops new turns. Vehicle context consent revoke
  invalidates stored vehicle-derived memories.
- `GET /memories` → `{items:[{id,fact,kind,record_id,created_at}]}`.
- `DELETE /memories/{id}` `{request_id}` → `{deleted:true}`; source record can be excluded to
  prevent subsequent recreation.
- `DELETE /account` `{confirmation:"DELETE"}` → `{deleted:true}`. Owner only; revoke devices, remove
  application data then auth user. UI must confirm destructive scope.

## Compatibility

Unknown optional fields may be ignored. Clients must not substitute fake AI output on503.
User-facing backend errors exclude provider responses, secrets and transcript bodies. UTF-8 Korean
supported throughout. Direct table writes are forbidden; mutations run through authenticated API and
service-only atomic RPCs.

### Vehicle provenance additions

`GET /settings` also includes `vehicle_memory_revision`: a destructive-data revision that advances
on source edit/exclusion/deletion and vehicle consent withdrawal, not ordinary new records. Vehicle
must clear encrypted local derived-place/baseline caches when it changes.

Context accepts `passenger_source: pleos_seat|user_assertion|unknown` to distinguish a parked user's
explicit session-only alone choice from supported seat occupancy. Known occupied state cannot be
overridden by the choice. `trip_duration_source: pleos_navi_arrived|pleos_vehicle_odometer`
distinguishes navigation-leg arrival metrics from ignition/session odometer metrics. Optional
`trip_baseline: {sample_count>=3,median_duration_seconds,median_arrival_minute,latest_observed_at,source:"local_confirmed_arrivals"}`
describes prior confirmed arrivals in last30days to the same local destination cluster. Only fresh
Navi arrival duration is comparable to this baseline; no cumulative odometer comparison or
lateness/emotion inference.

### Public documents

`GET /about` and `GET /privacy` are fixed read-only UTF-8 plain text without authentication. They
expose no account or deployment secrets; all other routes require authentication.
