-- Doran: clients can read their own rows; all writes use service-only RPCs.
create schema if not exists extensions;
create extension if not exists pg_trgm with schema extensions;
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;
grant usage on schema private,extensions to service_role;

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null default '도란 사용자',
  consent_ai boolean not null default false,
  consent_vehicle boolean not null default false,
  personalization_enabled boolean not null default true,
  suggestion_frequency text not null default 'off' check (suggestion_frequency in ('off','daily','trip')),
  conversation_style text not null default 'balanced' check (conversation_style in ('balanced','listen','questions')),
  version bigint not null default 1,
  policy_revision bigint not null default 1,
  vehicle_memory_revision bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create table public.devices (
  id uuid primary key default gen_random_uuid(),
  auth_user_id uuid not null unique references auth.users(id) on delete cascade,
  owner_id uuid references public.profiles(id) on delete cascade,
  label text not null default '도란 차량',
  active boolean not null default false,
  created_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now()
);
create index devices_owner_idx on public.devices(owner_id);
create table private.pairing_challenges (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references public.devices(id) on delete cascade,
  code_hash text not null unique,
  pending_owner_id uuid references public.profiles(id) on delete cascade,
  status text not null default 'waiting' check(status in ('waiting','claimed','paired','expired')),
  expires_at timestamptz not null default now()+interval '5 minutes',
  created_at timestamptz not null default now()
);
create index pairing_device_idx on private.pairing_challenges(device_id,created_at desc);
create table private.pairing_attempts(actor_id uuid not null references auth.users(id) on delete cascade, created_at timestamptz not null default now());
create index pairing_attempts_actor_idx on private.pairing_attempts(actor_id,created_at desc);
create table public.sessions (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(id) on delete cascade,
  device_id uuid not null references public.devices(id) on delete cascade,
  request_id uuid not null,
  record_date date not null default (now() at time zone 'Asia/Seoul')::date,
  status text not null default 'active' check(status in ('active','paused','finished')),
  version bigint not null default 1,
  vehicle_context jsonb not null default '{}',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(device_id,request_id)
);
create index sessions_owner_idx on public.sessions(owner_id,created_at desc);
create table private.turns (
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null references public.sessions(id) on delete cascade,
  request_id uuid not null,
  user_text text not null check(length(user_text) between 1 and 6000),
  assistant_text text,
  status text not null default 'pending' check(status in ('pending','complete','failed','cancelled')),
  policy_revision bigint not null,
  created_at timestamptz not null default now(),
  unique(session_id,request_id)
);
create index turns_session_idx on private.turns(session_id,created_at);
create table private.turn_dispatches (
 id bigint generated always as identity primary key,
 owner_id uuid not null references public.profiles(id) on delete cascade,
 created_at timestamptz not null default now()
);
create index turn_dispatches_owner_idx on private.turn_dispatches(owner_id,created_at desc);
create table public.messages (
  sequence bigint generated always as identity,
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null references public.sessions(id) on delete cascade,
  owner_id uuid not null references public.profiles(id) on delete cascade,
  turn_id uuid not null references private.turns(id) on delete cascade,
  role text not null check(role in ('user','assistant')),
  content text not null,
  created_at timestamptz not null default now(),
  unique(turn_id,role)
);
create index messages_session_idx on public.messages(session_id,created_at);
create index messages_owner_idx on public.messages(owner_id);
create table public.records (
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null unique references public.sessions(id) on delete cascade,
  owner_id uuid not null references public.profiles(id) on delete cascade,
  record_date date not null,
  source_text text not null,
  generated_text text,
  edited_text text,
  text text generated always as (coalesce(edited_text,generated_text,source_text)) stored,
  version bigint not null default 1,
  excluded_from_personalization boolean not null default false,
  status text not null default 'pending' check(status in ('pending','ready','failed')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index records_owner_date_idx on public.records(owner_id,record_date desc,created_at desc,id);
create index records_search_idx on public.records using gin(to_tsvector('simple',text));
create table public.days (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(id) on delete cascade,
  date date not null,
  generated_text text not null default '',
  manual_text text,
  manual_base_revision bigint not null default 0,
  source_revision bigint not null default 0,
  generated_revision bigint not null default 0,
  version bigint not null default 1,
  has_new_generated boolean generated always as (manual_text is not null and generated_revision > manual_base_revision) stored,
  status text not null default 'pending' check(status in ('empty','pending','ready','failed')),
  updated_at timestamptz not null default now(),
  unique(owner_id,date)
);
create table private.diary_versions (
  id uuid primary key default gen_random_uuid(), owner_id uuid not null references public.profiles(id) on delete cascade,
  day_id uuid not null references public.days(id) on delete cascade, manual_text text not null, created_at timestamptz not null default now()
);
create table public.memories (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(id) on delete cascade,
  record_id uuid not null references public.records(id) on delete cascade,
  record_version bigint not null,
  kind text not null check(kind in ('interest','preference','event','fact')),
  fact text not null,
  evidence text not null,
  active boolean not null default true,
  version bigint not null default 1,
  created_at timestamptz not null default now()
);
create index memories_owner_idx on public.memories(owner_id,active,created_at desc);
create index memories_fact_search_idx on public.memories using gin(fact extensions.gin_trgm_ops);
create index memories_record_idx on public.memories(record_id);
create table public.vehicle_events (
  id uuid primary key default gen_random_uuid(),owner_id uuid not null references public.profiles(id) on delete cascade,
  session_id uuid references public.sessions(id) on delete cascade,
  payload jsonb not null, created_at timestamptz not null default now()
);
create index vehicle_events_owner_idx on public.vehicle_events(owner_id,created_at desc);
create table private.jobs (
  id uuid primary key default gen_random_uuid(),owner_id uuid not null references public.profiles(id) on delete cascade,
  kind text not null check(kind in ('record_summary','day_summary')),entity_id uuid not null,source_revision bigint not null,
  status text not null default 'queued' check(status in ('queued','running','done','failed','obsolete')),
  attempts integer not null default 0, available_at timestamptz not null default now(),lease_token uuid,lease_until timestamptz,
  snapshot_memory_ids uuid[] not null default '{}',
  created_at timestamptz not null default now(),unique(kind,entity_id,source_revision)
);
create index jobs_pending_idx on private.jobs(available_at) where status in ('queued','running');
create table private.budget (
  id integer primary key check(id=1),limit_usd numeric(12,6) not null default 30,
  committed_usd numeric(12,6) not null default 0 check(committed_usd>=0)
);
insert into private.budget(id) values(1);
create table private.usage_reservations (
  request_id uuid primary key,maximum_usd numeric(12,6) not null check(maximum_usd>0),
  actual_usd numeric(12,6),created_at timestamptz not null default now(),settled_at timestamptz
);
create table private.worker_config (
 id integer primary key check(id=1),
 service_url text,
 worker_token text not null default replace(gen_random_uuid()::text||gen_random_uuid()::text,'-',''),
 last_called_at timestamptz
);
insert into private.worker_config(id) values(1);
create table private.deleted_accounts(id uuid primary key,created_at timestamptz not null default now());

-- Auth table ownership belongs to GoTrue. A narrowly scoped, private definer
-- reads only account existence/type. It is executable only by the trusted API role.
create function private.actor_anonymous(p_actor uuid) returns boolean
language plpgsql stable security definer set search_path='' as $$
begin
 if auth.uid() is not null and auth.uid()<>p_actor then raise exception 'FORBIDDEN'; end if;
 return (select is_anonymous from auth.users where id=p_actor);
end $$;
-- Helpers are invoker functions. They can only be called by the trusted API role.
create function private.owner(p_actor uuid,p_require_mobile boolean default false) returns uuid
language plpgsql security invoker set search_path='' as $$
declare v_owner uuid; v_anon boolean;
begin
  v_anon:=private.actor_anonymous(p_actor);
  if v_anon is null or exists(select 1 from private.deleted_accounts where id=p_actor) then raise exception 'UNAUTHENTICATED'; end if;
  if not v_anon then
    insert into public.profiles(id) values(p_actor) on conflict do nothing;
    return p_actor;
  end if;
  if p_require_mobile then raise exception 'FORBIDDEN'; end if;
  select owner_id into v_owner from public.devices where auth_user_id=p_actor and active;
  if v_owner is null then raise exception 'DEVICE_UNPAIRED'; end if;
  return v_owner;
end $$;
create function private.rate_turn(p_owner uuid) returns void
language plpgsql security invoker set search_path='' as $$
begin
 if (select count(*) from private.turn_dispatches where owner_id=p_owner and created_at>now()-interval '1 minute')>=10 then raise exception 'RATE_LIMITED'; end if;
 if (select count(*) from private.turn_dispatches where owner_id=p_owner and created_at>now()-interval '24 hours')>=200 then raise exception 'DAILY_LIMIT'; end if;
 insert into private.turn_dispatches(owner_id) values(p_owner);
end $$;
create function private.bump_day(p_owner uuid,p_date date) returns void
language plpgsql security invoker set search_path='' as $$
declare d public.days;
begin
 insert into public.days(owner_id,date,source_revision) values(p_owner,p_date,1)
 on conflict(owner_id,date) do update set source_revision=public.days.source_revision+1,version=public.days.version+1,status='pending',updated_at=now()
 returning * into d;
 insert into private.jobs(owner_id,kind,entity_id,source_revision) values(p_owner,'day_summary',d.id,d.source_revision) on conflict do nothing;
end $$;
create function private.record_json(r public.records) returns jsonb language sql immutable set search_path='' as $$
 select jsonb_build_object('id',r.id,'session_id',r.session_id,'record_date',r.record_date,'text',r.text,'version',r.version,'excluded_from_personalization',r.excluded_from_personalization,'status',r.status,'created_at',r.created_at,'updated_at',r.updated_at)
$$;
create function private.day_json(d public.days) returns jsonb language sql immutable set search_path='' as $$
 select to_jsonb(d)-'id'-'owner_id'-'manual_base_revision'
$$;

create function public.doran_command(p_actor uuid,p_action text,p_payload jsonb default '{}') returns jsonb
language plpgsql security invoker set search_path='' as $$
declare
 o uuid; d public.devices; c private.pairing_challenges; s public.sessions; t private.turns; r public.records; y public.days; p public.profiles; m public.memories;
 v_id uuid; v_date date; v_text text; v_items jsonb; v_count integer; v_anon boolean; v_old_date date; v_pending jsonb;
begin
 -- The caller must be service_role; even database authenticated calls cannot spoof actor.
 if current_user not in ('service_role','postgres','supabase_admin') then raise exception 'FORBIDDEN'; end if;
 v_anon:=private.actor_anonymous(p_actor);
 if p_action='account.delete' and v_anon is not null and not v_anon and exists(select 1 from private.deleted_accounts where id=p_actor) then return jsonb_build_object('deleted',true); end if;
 if v_anon is null or exists(select 1 from private.deleted_accounts where id=p_actor) then raise exception 'UNAUTHENTICATED'; end if;
 if p_action in ('devices.bootstrap','devices.current','pairings.create') then
  if not v_anon then raise exception 'FORBIDDEN'; end if;
  if p_action='devices.bootstrap' then
   insert into public.devices(auth_user_id,label) values(p_actor,left(coalesce(nullif(p_payload->>'label',''),'도란 차량'),60)) on conflict(auth_user_id) do update set last_seen_at=now();
  end if;
  select * into d from public.devices where auth_user_id=p_actor for update;
  if d.id is null then raise exception 'DEVICE_NOT_FOUND'; end if;
  if p_action in ('devices.bootstrap','devices.current') then return jsonb_build_object('device',to_jsonb(d)-'auth_user_id','paired',d.active and d.owner_id is not null); end if;
  if d.active then raise exception 'ALREADY_PAIRED'; end if;
  if (select count(*) from private.pairing_challenges where device_id=d.id and created_at>now()-interval '1 hour')>=12 then raise exception 'RATE_LIMITED'; end if;
  if length(coalesce(p_payload->>'code_hash',''))<>64 then raise exception 'INVALID_CODE_HASH'; end if;
  update private.pairing_challenges set status='expired' where device_id=d.id and status in ('waiting','claimed');
  insert into private.pairing_challenges(device_id,code_hash) values(d.id,p_payload->>'code_hash') returning * into c;
  return jsonb_build_object('id',c.id,'expires_at',c.expires_at,'status',c.status);
 end if;
 if p_action in ('pairings.status','pairings.confirm') then
  select * into c from private.pairing_challenges where id=(p_payload->>'pairing_id')::uuid;
  if c.id is null then raise exception 'NOT_FOUND'; end if;
  select * into d from public.devices where id=c.device_id for update;
  select * into c from private.pairing_challenges where id=c.id for update;
  if d.auth_user_id<>p_actor and c.pending_owner_id is distinct from p_actor then raise exception 'FORBIDDEN'; end if;
  if c.expires_at<=now() and c.status<>'paired' then update private.pairing_challenges set status='expired' where id=c.id; c.status:='expired'; end if;
  if p_action='pairings.confirm' then
   if d.auth_user_id<>p_actor then raise exception 'FORBIDDEN'; end if;
   if c.status='paired' and d.active and d.owner_id=c.pending_owner_id then return jsonb_build_object('id',c.id,'status','paired','device_id',d.id); end if;
   if c.status<>'claimed' then return jsonb_build_object('error','PAIRING_NOT_CLAIMED'); end if;
   update public.devices set owner_id=c.pending_owner_id,active=true,last_seen_at=now() where id=d.id;
   update private.pairing_challenges set status='paired' where id=c.id;
   return jsonb_build_object('id',c.id,'status','paired','device_id',d.id);
  end if;
  return jsonb_build_object('id',c.id,'expires_at',c.expires_at,'status',c.status,'owner_label',(select display_name from public.profiles where id=c.pending_owner_id));
 end if;
 o:=private.owner(p_actor,p_action not like 'sessions.%' and p_action<>'profiles.get');
 select * into p from public.profiles where id=o for update;
 if p_action='pairings.claim' then
  if (select count(*) from private.pairing_attempts where actor_id=p_actor and created_at>now()-interval '5 minutes')>=5 then return jsonb_build_object('error','RATE_LIMITED'); end if;
  insert into private.pairing_attempts(actor_id) values(p_actor);
  select * into c from private.pairing_challenges where code_hash=p_payload->>'code_hash' and expires_at>now() and status in ('waiting','claimed') for update;
  if c.id is null then return jsonb_build_object('error','PAIRING_NOT_FOUND'); end if;
  if c.pending_owner_id is not null and c.pending_owner_id<>o then return jsonb_build_object('error','PAIRING_NOT_FOUND'); end if;
  update private.pairing_challenges set pending_owner_id=o,status='claimed' where id=c.id;
  return jsonb_build_object('id',c.id,'expires_at',c.expires_at,'status','claimed','device_label',(select label from public.devices where id=c.device_id));
 elsif p_action='profiles.get' then return jsonb_build_object('profile',to_jsonb(p));
 elsif p_action='profiles.update' then
  if p.version is distinct from (p_payload->>'version')::bigint then raise exception 'VERSION_CONFLICT'; end if;
  update public.profiles set
   consent_ai=coalesce((p_payload->>'consent_ai')::boolean,consent_ai),
   consent_vehicle=coalesce((p_payload->>'consent_vehicle')::boolean,consent_vehicle),
   personalization_enabled=coalesce((p_payload->>'personalization_enabled')::boolean,personalization_enabled),
   suggestion_frequency=coalesce(p_payload->>'suggestion_frequency',suggestion_frequency),
   conversation_style=coalesce(p_payload->>'conversation_style',conversation_style),
   display_name=left(coalesce(p_payload->>'display_name',display_name),60),
   version=version+1,policy_revision=policy_revision+1,vehicle_memory_revision=vehicle_memory_revision+case when consent_vehicle and p_payload->>'consent_vehicle'='false' then 1 else 0 end,updated_at=now() where id=o returning * into p;
  if not p.consent_vehicle then
   delete from public.vehicle_events where owner_id=o;
   update public.sessions set vehicle_context='{}' where owner_id=o;
  end if;
  if not p.consent_ai then
   update private.turns set status='cancelled' where session_id in (select id from public.sessions where owner_id=o) and status='pending';
   update public.sessions set status='paused',version=version+1 where owner_id=o and status='active';
  end if;
  return jsonb_build_object('profile',to_jsonb(p));
 elsif p_action='devices.list' then
  select coalesce(jsonb_agg(to_jsonb(z)-'auth_user_id'-'owner_id'),'[]') into v_items from (select * from public.devices where owner_id=o order by created_at desc)z;
  return jsonb_build_object('items',v_items);
 elsif p_action='devices.revoke' then
  update public.devices set active=false where id=(p_payload->>'device_id')::uuid and owner_id=o returning * into d;
  if d.id is null then raise exception 'NOT_FOUND'; end if;
  update public.sessions set status='paused',version=version+1 where device_id=d.id and status='active';
  update private.turns set status='cancelled' where session_id in(select id from public.sessions where device_id=d.id) and status='pending';
  return jsonb_build_object('deleted',true);
 elsif p_action='sessions.create' then
  if not v_anon then raise exception 'FORBIDDEN'; end if;
  if not p.consent_ai then raise exception 'CONSENT_REQUIRED'; end if;
  select * into d from public.devices where auth_user_id=p_actor and active;
  insert into public.sessions(owner_id,device_id,request_id,vehicle_context) values(o,d.id,(p_payload->>'request_id')::uuid,case when p.consent_vehicle then coalesce(p_payload->'vehicle_context','{}') else '{}' end)
   on conflict(device_id,request_id) do nothing;
  select * into s from public.sessions where device_id=d.id and request_id=(p_payload->>'request_id')::uuid;
  return jsonb_build_object('session',to_jsonb(s));
 elsif p_action like 'sessions.%' then
  select * into s from public.sessions where id=(p_payload->>'session_id')::uuid and owner_id=o for update;
  if s.id is null then raise exception 'NOT_FOUND'; end if;
  if v_anon and not exists(select 1 from public.devices where id=s.device_id and auth_user_id=p_actor and active) then raise exception 'FORBIDDEN'; end if;
  if p_action='sessions.get' then
   select coalesce(jsonb_agg(to_jsonb(z) order by z.sequence),'[]') into v_items from public.messages z where session_id=s.id;
   return jsonb_build_object('session',to_jsonb(s),'messages',v_items);
  elsif p_action='sessions.pause' then
   if s.status='finished' then raise exception 'SESSION_FINISHED'; end if;
   update public.sessions set status='paused',version=version+1,updated_at=now() where id=s.id returning * into s;
   update private.turns set status='cancelled' where session_id=s.id and status='pending';
   return to_jsonb(s);
  elsif p_action='sessions.turn' then
   if not v_anon then raise exception 'FORBIDDEN'; end if;
   if not p.consent_ai then raise exception 'CONSENT_REQUIRED'; end if;
   if s.status='finished' then raise exception 'SESSION_FINISHED'; end if;
   select * into t from private.turns where session_id=s.id and request_id=(p_payload->>'request_id')::uuid;
   if t.id is not null then
    if t.user_text is distinct from p_payload->>'text' then raise exception 'IDEMPOTENCY_CONFLICT'; end if;
    if t.status='failed' then
     perform private.rate_turn(o);
     if exists(select 1 from private.turns where session_id=s.id and status='pending') then raise exception 'TURN_IN_PROGRESS'; end if;
     update private.turns set status='pending',policy_revision=p.policy_revision,created_at=now() where id=t.id returning * into t;
     update public.sessions set status='active',version=version+1,updated_at=now(),vehicle_context=case when p.consent_vehicle then coalesce(p_payload->'vehicle_context','{}') else '{}' end where id=s.id returning * into s;
     return jsonb_build_object('turn',to_jsonb(t),'session',to_jsonb(s),'duplicate',false,'owner_id',o,'policy_revision',p.policy_revision);
    end if;
    if t.policy_revision<>p.policy_revision then raise exception 'STALE_CONTEXT'; end if;
    return jsonb_build_object('turn',to_jsonb(t),'session',to_jsonb(s),'duplicate',true,'owner_id',o,'policy_revision',p.policy_revision);
   end if;
   perform private.rate_turn(o);
   if exists(select 1 from private.turns where session_id=s.id and status='pending' and created_at>now()-interval '60 seconds') then raise exception 'TURN_IN_PROGRESS'; end if;
   update private.turns set status='failed' where session_id=s.id and status='pending';
   insert into private.turns(session_id,request_id,user_text,policy_revision) values(s.id,(p_payload->>'request_id')::uuid,p_payload->>'text',p.policy_revision) returning * into t;
   insert into public.messages(session_id,owner_id,turn_id,role,content) values(s.id,o,t.id,'user',t.user_text);
   update public.sessions set status='active',version=version+1,updated_at=now(),vehicle_context=case when p.consent_vehicle then coalesce(p_payload->'vehicle_context','{}') else '{}' end where id=s.id returning * into s;
   return jsonb_build_object('turn',to_jsonb(t),'session',to_jsonb(s),'duplicate',false,'owner_id',o,'policy_revision',p.policy_revision);
  elsif p_action='sessions.finish' then
   select * into r from public.records where session_id=s.id;
   if r.id is not null then return jsonb_build_object('record',private.record_json(r)); end if;
   if jsonb_array_length(coalesce(p_payload->'pending_turns','[]'))>20 then raise exception 'TOO_MANY_PENDING_TURNS'; end if;
   for v_pending in select value from jsonb_array_elements(coalesce(p_payload->'pending_turns','[]')) loop
    select * into t from private.turns where session_id=s.id and request_id=(v_pending->>'request_id')::uuid;
    if t.id is not null and t.user_text is distinct from v_pending->>'text' then raise exception 'IDEMPOTENCY_CONFLICT'; end if;
    if t.id is null then
     insert into private.turns(session_id,request_id,user_text,policy_revision,status) values(s.id,(v_pending->>'request_id')::uuid,v_pending->>'text',p.policy_revision,'cancelled') returning * into t;
     insert into public.messages(session_id,owner_id,turn_id,role,content) values(s.id,o,t.id,'user',t.user_text);
    end if;
   end loop;
   select coalesce(string_agg(content,E'\n' order by sequence),'') into v_text from public.messages where session_id=s.id and role='user';
   if length(v_text)=0 then raise exception 'EMPTY_SESSION'; end if;
   update public.sessions set status='finished',version=version+1,updated_at=now() where id=s.id;
   update private.turns set status='cancelled' where session_id=s.id and status='pending';
   insert into public.records(session_id,owner_id,record_date,source_text) values(s.id,o,s.record_date,v_text) returning * into r;
   insert into private.jobs(owner_id,kind,entity_id,source_revision) values(o,'record_summary',r.id,r.version);
   perform private.bump_day(o,r.record_date);
   return jsonb_build_object('record',private.record_json(r));
  end if;
 elsif p_action='records.list' then
  select coalesce(jsonb_agg(private.record_json(z) order by z.created_at desc,z.id desc),'[]') into v_items from (
   select * from public.records where owner_id=o and ((p_payload->>'date') is null or record_date=(p_payload->>'date')::date)
    and ((p_payload->>'before') is null or (created_at,id)<((p_payload->>'before')::timestamptz,coalesce((p_payload->>'before_id')::uuid,'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)))
   order by created_at desc,id desc limit least(51,greatest(1,coalesce((p_payload->>'limit')::int,20)))
  )z;
  return jsonb_build_object('items',v_items);
 elsif p_action like 'records.%' then
  select * into r from public.records where id=(p_payload->>'record_id')::uuid and owner_id=o for update;
  if r.id is null then raise exception 'NOT_FOUND'; end if;
  if p_action='records.get' then return private.record_json(r); end if;
  if p_action='records.retry' then
   update public.records set status='pending',updated_at=now() where id=r.id;
   insert into private.jobs(owner_id,kind,entity_id,source_revision) values(o,'record_summary',r.id,r.version)
    on conflict(kind,entity_id,source_revision) do update set status='queued',attempts=0,available_at=now(),lease_token=null,lease_until=null where private.jobs.status<>'running';
   update private.jobs set queue_message_id=(select pgmq.send('doran_jobs',jsonb_build_object('job_id',id))) where kind='record_summary' and entity_id=r.id and source_revision=r.version and status='queued';
   return jsonb_build_object('status','pending');
  end if;
  if r.version is distinct from (p_payload->>'version')::bigint then raise exception 'VERSION_CONFLICT'; end if;
  v_old_date:=r.record_date;
  delete from public.memories where record_id=r.id;
  update public.profiles set policy_revision=policy_revision+1,vehicle_memory_revision=vehicle_memory_revision+1 where id=o;
  if p_action='records.update' then
   update public.records set edited_text=coalesce(p_payload->>'text',edited_text),
    record_date=coalesce((p_payload->>'local_date')::date,record_date),
    excluded_from_personalization=coalesce((p_payload->>'excluded')::boolean,excluded_from_personalization),
    version=version+1,status='pending',updated_at=now() where id=r.id returning * into r;
   insert into private.jobs(owner_id,kind,entity_id,source_revision) values(o,'record_summary',r.id,r.version);
   perform private.bump_day(o,v_old_date);
   if r.record_date<>v_old_date then perform private.bump_day(o,r.record_date); end if;
   return private.record_json(r);
  elsif p_action='records.delete' then
   delete from private.jobs where entity_id=r.id;
   delete from public.sessions where id=r.session_id;
   if coalesce((p_payload->>'delete_manual')::boolean,false) then
    delete from private.diary_versions where day_id in(select id from public.days where owner_id=o and date=v_old_date);
    update public.days set manual_text=null where owner_id=o and date=v_old_date;
   end if;
   -- Remove old generated text immediately so deleted source is not visible during regeneration.
   update public.days set generated_text='',generated_revision=0 where owner_id=o and date=v_old_date;
   perform private.bump_day(o,v_old_date);
   return jsonb_build_object('deleted',true);
  end if;
 elsif p_action='days.list' then
  select coalesce(jsonb_agg(private.day_json(z) order by z.date desc),'[]') into v_items from (select * from public.days where owner_id=o order by date desc limit least(50,coalesce((p_payload->>'limit')::int,20)))z;
  return jsonb_build_object('items',v_items);
 elsif p_action like 'days.%' then
  v_date:=(p_payload->>'local_date')::date;
  select * into y from public.days where owner_id=o and date=v_date for update;
  if p_action='days.get' then
   if y.id is null then return jsonb_build_object('date',v_date,'generated_text','','manual_text',null,'version',0,'source_revision',0,'generated_revision',0,'has_new_generated',false,'status','empty','updated_at',null); end if;
   return private.day_json(y);
  end if;
  if y.id is null then
   if coalesce((p_payload->>'version')::bigint,-1)<>0 or p_action<>'days.update' then raise exception 'VERSION_CONFLICT'; end if;
   insert into public.days(owner_id,date,status) values(o,v_date,'empty') returning * into y;
  elsif y.version is distinct from (p_payload->>'version')::bigint then raise exception 'VERSION_CONFLICT'; end if;
  if p_action in ('days.update','days.adopt') then
   if y.manual_text is not null then insert into private.diary_versions(owner_id,day_id,manual_text) values(o,y.id,y.manual_text); end if;
   update public.days set manual_text=case when p_action='days.adopt' or coalesce((p_payload->>'use_generated')::boolean,false) then generated_text else p_payload->>'manual_text' end,
    manual_base_revision=generated_revision,version=version+1,updated_at=now() where id=y.id returning * into y;
   return private.day_json(y);
  elsif p_action='days.deleteall' then
   delete from private.jobs where owner_id=o and (entity_id=y.id or entity_id in(select id from public.records where owner_id=o and record_date=v_date));
   delete from public.sessions where id in(select session_id from public.records where owner_id=o and record_date=v_date);
   delete from public.days where id=y.id;
   update public.profiles set policy_revision=policy_revision+1,vehicle_memory_revision=vehicle_memory_revision+1 where id=o;
   return jsonb_build_object('deleted',true);
  end if;
 elsif p_action='memories.list' then
  select coalesce(jsonb_agg(to_jsonb(z) order by z.created_at desc),'[]') into v_items from public.memories z where owner_id=o and active;
  return jsonb_build_object('items',v_items);
 elsif p_action in ('memories.update','memories.delete') then
  select * into m from public.memories where id=(p_payload->>'memory_id')::uuid and owner_id=o for update;
  if m.id is null then raise exception 'NOT_FOUND'; end if;
  if p_action='memories.delete' then update public.memories set active=false,version=version+1 where id=m.id;
  else
   if m.version is distinct from (p_payload->>'version')::bigint then raise exception 'VERSION_CONFLICT'; end if;
   update public.memories set fact=coalesce(p_payload->>'fact',fact),active=coalesce((p_payload->>'active')::boolean,active),version=version+1 where id=m.id returning * into m;
  end if;
  update public.profiles set policy_revision=policy_revision+1,vehicle_memory_revision=vehicle_memory_revision+1 where id=o;
  return case when p_action='memories.delete' then jsonb_build_object('deleted',true) else to_jsonb(m) end;
 elsif p_action='account.delete' then
  insert into private.deleted_accounts(id) values(o) on conflict do nothing;
  delete from public.profiles where id=o;
  return jsonb_build_object('deleted',true);
 end if;
 raise exception 'UNKNOWN_ACTION';
end $$;

create extension if not exists pgmq;
select pgmq.create('doran_jobs');
alter table private.jobs add column queue_message_id bigint;
create function private.enqueue_job() returns trigger language plpgsql security invoker set search_path='' as $$
begin
 update private.jobs set queue_message_id=(select pgmq.send('doran_jobs',jsonb_build_object('job_id',new.id))) where id=new.id;
 return new;
end $$;
create trigger jobs_enqueue after insert on private.jobs for each row execute function private.enqueue_job();

create function public.doran_worker(p_action text,p_payload jsonb default '{}') returns jsonb
language plpgsql security invoker set search_path='' as $$
declare
 b private.budget; u private.usage_reservations; t private.turns; s public.sessions; p public.profiles; j private.jobs; r public.records; y public.days;
 v_cost numeric(12,6); v_items jsonb:='[]'; v_input jsonb; v_mem jsonb; v_messages jsonb; v_context jsonb; v_valid boolean; v_text text; v_queue record; v_id uuid; v_scan integer;
begin
 if current_user not in ('service_role','postgres','supabase_admin') then raise exception 'FORBIDDEN'; end if;
 if p_action='worker.authorize' then
  return jsonb_build_object('valid',coalesce((select length(p_payload->>'token')=64 and worker_token=p_payload->>'token' from private.worker_config where id=1),false));
 elsif p_action='worker.configure' then
  if coalesce(p_payload->>'url','') !~ '^https://[a-z0-9]{20}\.supabase\.co/functions/v1/doran-worker$' then raise exception 'INVALID_WORKER_URL'; end if;
  update private.worker_config set service_url=p_payload->>'url' where id=1;
  return jsonb_build_object('configured',true);
 elsif p_action='budget.reserve' then
  v_cost:=(p_payload->>'max_cost_usd')::numeric;
  if v_cost is null or v_cost<=0 or v_cost>1 then raise exception 'INVALID_RESERVATION'; end if;
  select * into b from private.budget where id=1 for update;
  select * into u from private.usage_reservations where request_id=(p_payload->>'request_id')::uuid;
  if u.request_id is not null then
   if u.maximum_usd<>v_cost then raise exception 'IDEMPOTENCY_CONFLICT'; end if;
   return jsonb_build_object('reserved',true,'duplicate',true,'remaining_usd',b.limit_usd-b.committed_usd);
  end if;
  if b.committed_usd+v_cost>b.limit_usd then raise exception 'BUDGET_EXHAUSTED'; end if;
  insert into private.usage_reservations(request_id,maximum_usd) values((p_payload->>'request_id')::uuid,v_cost);
  update private.budget set committed_usd=committed_usd+v_cost where id=1;
  return jsonb_build_object('reserved',true,'duplicate',false,'remaining_usd',b.limit_usd-b.committed_usd-v_cost);
 elsif p_action='budget.settle' then
  select * into b from private.budget where id=1 for update;
  select * into u from private.usage_reservations where request_id=(p_payload->>'request_id')::uuid for update;
  if u.request_id is null then raise exception 'RESERVATION_NOT_FOUND'; end if;
  if u.settled_at is not null then return jsonb_build_object('settled',true); end if;
  v_cost:=(p_payload->>'actual_cost_usd')::numeric;
  if v_cost is null or v_cost<0 or v_cost>u.maximum_usd then raise exception 'INVALID_SETTLEMENT'; end if;
  update private.usage_reservations set actual_usd=v_cost,settled_at=now() where request_id=u.request_id;
  update private.budget set committed_usd=committed_usd-u.maximum_usd+v_cost where id=1;
  return jsonb_build_object('settled',true);
 elsif p_action in ('turns.validate','turns.complete','turns.fail') then
  select * into t from private.turns where id=(p_payload->>'turn_id')::uuid;
  if t.id is null then return jsonb_build_object('valid',false,'accepted',false); end if;
  select * into s from public.sessions where id=t.session_id;
  select * into p from public.profiles where id=s.owner_id;
  v_valid:=t.status='pending' and s.status='active' and p.consent_ai and p.policy_revision=t.policy_revision
   and exists(select 1 from public.devices where id=s.device_id and owner_id=s.owner_id and active)
   and not exists(select 1 from private.deleted_accounts where id=s.owner_id);
  if p_action='turns.validate' then return jsonb_build_object('valid',coalesce(v_valid,false)); end if;
  if p_action='turns.fail' then update private.turns set status='failed' where id=t.id and status='pending'; return jsonb_build_object('failed',true); end if;
  -- A profile lock serializes final publication with exclusion/deletion/settings changes.
  select * into p from public.profiles where id=s.owner_id for update;
  select * into s from public.sessions where id=t.session_id for update;
  select * into t from private.turns where id=t.id for update;
  if t.status<>'pending' or not coalesce(v_valid,false) or p.policy_revision<>t.policy_revision or s.status<>'active' then
   update private.turns set status='cancelled' where id=t.id and status='pending';
   return jsonb_build_object('accepted',false);
  end if;
  v_text:=p_payload->>'text';
  if v_text is null or length(v_text)>12000 then raise exception 'INVALID_RESPONSE'; end if;
  update private.turns set assistant_text=v_text,status='complete' where id=t.id;
  insert into public.messages(session_id,owner_id,turn_id,role,content) values(s.id,s.owner_id,t.id,'assistant',v_text) on conflict(turn_id,role) do nothing;
  update public.sessions set version=version+1,updated_at=now() where id=s.id returning * into s;
  return jsonb_build_object('accepted',true,'session_version',s.version);
 elsif p_action='context.get' then
  select * into s from public.sessions where id=(p_payload->>'session_id')::uuid and owner_id=(p_payload->>'owner_id')::uuid;
  if s.id is null then raise exception 'NOT_FOUND'; end if;
  select * into p from public.profiles where id=s.owner_id;
  if not p.consent_ai then raise exception 'CONSENT_REQUIRED'; end if;
  select coalesce(jsonb_agg(jsonb_build_object('role',z.role,'content',z.content) order by z.sequence),'[]') into v_messages from (select * from public.messages where session_id=s.id order by sequence desc limit 24)z;
  if p.personalization_enabled then
   select coalesce(jsonb_agg(jsonb_build_object('id',z.id,'fact',z.fact,'kind',z.kind,'record_id',z.record_id,'evidence',z.evidence)),'[]') into v_mem from (
    select m.* from public.memories m join public.records rr on rr.id=m.record_id
    where m.owner_id=s.owner_id and m.active and not rr.excluded_from_personalization and rr.version=m.record_version
    order by extensions.word_similarity(coalesce(p_payload->>'query',''),m.fact) desc,m.created_at desc limit 30
   )z;
  else v_mem:='[]'; end if;
  v_context:=case when p.consent_vehicle then s.vehicle_context else '{}' end;
  return jsonb_build_object('messages',v_messages,'memories',v_mem,'settings',to_jsonb(p),'policy_revision',p.policy_revision,'context_version',p.policy_revision,'context',v_context);
 elsif p_action='jobs.claim' then
  for v_scan in 1..25 loop
   select * into v_queue from pgmq.read('doran_jobs',120,1);
   if v_queue.msg_id is null then exit; end if;
   select * into j from private.jobs where id=(v_queue.message->>'job_id')::uuid for update skip locked;
   if j.id is null then
    if not exists(select 1 from private.jobs where id=(v_queue.message->>'job_id')::uuid) then perform pgmq.delete('doran_jobs',v_queue.msg_id);
    else perform pgmq.set_vt('doran_jobs',v_queue.msg_id,2); end if;
    continue;
   end if;
   if j.queue_message_id<>v_queue.msg_id or j.status in ('done','failed','obsolete') then perform pgmq.delete('doran_jobs',v_queue.msg_id); continue; end if;
   select * into p from public.profiles where id=j.owner_id;
   if not coalesce(p.consent_ai,false) then perform pgmq.set_vt('doran_jobs',v_queue.msg_id,60); continue; end if;
   if j.available_at>now() then perform pgmq.set_vt('doran_jobs',v_queue.msg_id,greatest(1,ceil(extract(epoch from j.available_at-now()))::int)); continue; end if;
   if j.attempts>=3 then
    update private.jobs set status='failed' where id=j.id;
    if j.kind='record_summary' then update public.records set status='failed' where id=j.entity_id and version=j.source_revision;
    else update public.days set status='failed' where id=j.entity_id and source_revision=j.source_revision; end if;
    perform pgmq.delete('doran_jobs',v_queue.msg_id); continue;
   end if;
   if j.kind='record_summary' then
    select * into r from public.records where id=j.entity_id;
    v_valid:=r.id is not null and r.version=j.source_revision;
    select coalesce(jsonb_agg(to_jsonb(mm)),'[]') into v_mem from (
     select m.id,m.fact,m.kind,m.record_id,m.record_version from public.memories m join public.records rr on rr.id=m.record_id
     where m.owner_id=j.owner_id and m.active and p.personalization_enabled and not r.excluded_from_personalization and rr.version=m.record_version and not rr.excluded_from_personalization
     order by extensions.word_similarity(r.text,m.fact) desc,m.created_at desc limit 30
    )mm;
    update private.jobs set snapshot_memory_ids=array(select (value->>'id')::uuid from jsonb_array_elements(v_mem)) where id=j.id;
    v_input:=jsonb_build_object('record',to_jsonb(r),'prior_memories',v_mem,'messages',(select coalesce(jsonb_agg(jsonb_build_object('role',role,'content',content) order by sequence),'[]') from public.messages where session_id=r.session_id and role='user'));
   else
    select * into y from public.days where id=j.entity_id;
    v_valid:=y.id is not null and y.source_revision=j.source_revision;
    v_input:=jsonb_build_object('date',y.date,'records',(select coalesce(jsonb_agg(private.record_json(rr) order by rr.created_at),'[]') from public.records rr where owner_id=j.owner_id and record_date=y.date));
   end if;
   if not coalesce(v_valid,false) then update private.jobs set status='obsolete' where id=j.id; perform pgmq.delete('doran_jobs',v_queue.msg_id); continue; end if;
   update private.jobs set status='running',attempts=attempts+1,lease_token=gen_random_uuid(),lease_until=now()+interval '120 seconds' where id=j.id returning * into j;
   v_items:=v_items||jsonb_build_array(to_jsonb(j)||jsonb_build_object('input',v_input,'policy_revision',p.policy_revision));
   if jsonb_array_length(v_items)>=least(5,greatest(1,coalesce((p_payload->>'limit')::int,1))) then exit; end if;
  end loop;
  return jsonb_build_object('items',v_items);
 elsif p_action='jobs.validate' then
  select * into j from private.jobs where id=(p_payload->>'job_id')::uuid;
  select * into p from public.profiles where id=j.owner_id;
  v_valid:=j.status='running' and j.lease_token=(p_payload->>'lease_token')::uuid and j.lease_until>now() and p.consent_ai
   and p.policy_revision=(p_payload->>'policy_revision')::bigint;
  if j.kind='record_summary' then v_valid:=v_valid and exists(select 1 from public.records where id=j.entity_id and version=j.source_revision);
  else v_valid:=v_valid and exists(select 1 from public.days where id=j.entity_id and source_revision=j.source_revision); end if;
  return jsonb_build_object('valid',coalesce(v_valid,false));
 elsif p_action in ('jobs.complete','jobs.fail') then
  select * into j from private.jobs where id=(p_payload->>'job_id')::uuid;
  if j.id is not null then
   select * into p from public.profiles where id=j.owner_id for update;
   select * into j from private.jobs where id=j.id for update;
  end if;
  if j.id is null or j.status<>'running' or j.lease_token is distinct from (p_payload->>'lease_token')::uuid or j.lease_until<now() then return jsonb_build_object('accepted',false,'retrying',false); end if;
  if p_action='jobs.fail' then
   update private.jobs set status=case when attempts>=3 then 'failed' else 'queued' end,
    available_at=now()+make_interval(secs=>case when attempts=1 then 10 else 60 end),lease_token=null,lease_until=null where id=j.id returning * into j;
   if j.status='failed' then
    perform pgmq.delete('doran_jobs',j.queue_message_id);
    if j.kind='record_summary' then update public.records set status='failed' where id=j.entity_id and version=j.source_revision;
    else update public.days set status='failed' where id=j.entity_id and source_revision=j.source_revision; end if;
   else perform pgmq.set_vt('doran_jobs',j.queue_message_id,case when j.attempts=1 then 10 else 60 end); end if;
   return jsonb_build_object('retrying',j.status='queued');
  end if;
  select * into p from public.profiles where id=j.owner_id for update;
  if p.id is null or not p.consent_ai then v_valid:=false;
  elsif p.policy_revision is distinct from (p_payload->>'policy_revision')::bigint then
   update private.jobs set status='queued',lease_token=null,lease_until=null,available_at=now() where id=j.id;
   perform pgmq.set_vt('doran_jobs',j.queue_message_id,1);
   return jsonb_build_object('accepted',false,'retrying',true);
  elsif j.kind='record_summary' then
   select * into r from public.records where id=j.entity_id for update;
   v_valid:=r.id is not null and r.version=j.source_revision;
   if v_valid then
    v_text:=p_payload->'result'->>'text';
    if v_text is null or length(v_text)>16000 then raise exception 'INVALID_RESULT'; end if;
    update public.records set generated_text=v_text,status='ready',updated_at=now() where id=r.id returning * into r;
    -- Suppressed facts survive regeneration for the same source version.
    delete from public.memories where record_id=r.id and active;
    if not r.excluded_from_personalization and p.personalization_enabled then
     for v_mem in select value from jsonb_array_elements(coalesce(p_payload->'result'->'memories','[]')) loop
      if length(coalesce(v_mem->>'fact','')) between 1 and 1000 and length(coalesce(v_mem->>'evidence',''))>0
       and position(v_mem->>'evidence' in coalesce(r.edited_text,r.source_text))>0
       and v_mem->>'kind' in ('interest','preference','event','fact')
       and not exists(select 1 from public.memories where record_id=r.id and fact=v_mem->>'fact' and not active) then
       if v_mem->>'evidence' ~ '(아니|바뀌|변경|정정|취소|이제|앞으로|대신)' then
        update public.memories m set active=false,version=m.version+1
        where m.owner_id=j.owner_id and m.active and m.id=any(j.snapshot_memory_ids)
         and m.id::text in(select value#>>'{}' from jsonb_array_elements(coalesce(v_mem->'supersedes','[]')))
         and exists(select 1 from public.records rr where rr.id=m.record_id and rr.version=m.record_version and not rr.excluded_from_personalization);
       end if;
       insert into public.memories(owner_id,record_id,record_version,kind,fact,evidence) values(r.owner_id,r.id,r.version,v_mem->>'kind',v_mem->>'fact',v_mem->>'evidence');
      end if;
     end loop;
    end if;
    perform private.bump_day(j.owner_id,r.record_date);
    update public.profiles set policy_revision=policy_revision+1 where id=j.owner_id;
   end if;
  else
   select * into y from public.days where id=j.entity_id for update;
   v_valid:=y.id is not null and y.source_revision=j.source_revision;
   if v_valid then
    v_text:=p_payload->'result'->>'text';
    if v_text is null or length(v_text)>24000 then raise exception 'INVALID_RESULT'; end if;
    update public.days set generated_text=v_text,generated_revision=j.source_revision,version=version+1,
     status=case when exists(select 1 from public.records where owner_id=j.owner_id and record_date=y.date) then 'ready' else 'empty' end,updated_at=now() where id=y.id;
   end if;
  end if;
  update private.jobs set status=case when coalesce(v_valid,false) then 'done' else 'obsolete' end,lease_token=null,lease_until=null where id=j.id;
  perform pgmq.delete('doran_jobs',j.queue_message_id);
  return jsonb_build_object('accepted',coalesce(v_valid,false));
 end if;
 raise exception 'UNKNOWN_ACTION';
end $$;

-- API role only; private support tables are not exposed through PostgREST.
-- Supabase Auth grants service_role read access to auth.users.
grant all on all tables in schema private to service_role;
grant all on all sequences in schema private to service_role;
grant usage,select on sequence public.messages_sequence_seq to service_role;

revoke all on all tables in schema private from public,anon,authenticated;
revoke all on all functions in schema private from public,anon,authenticated;
grant execute on all functions in schema private to service_role;
revoke all on schema pgmq from public,anon,authenticated;
grant usage on schema pgmq to service_role;
grant all on all tables in schema pgmq to service_role;
grant all on all sequences in schema pgmq to service_role;
grant execute on all functions in schema pgmq to service_role;
revoke execute on function public.doran_command(uuid,text,jsonb) from public,anon,authenticated;
revoke execute on function public.doran_worker(text,jsonb) from public,anon,authenticated;
grant execute on function public.doran_command(uuid,text,jsonb) to service_role;
grant execute on function public.doran_worker(text,jsonb) to service_role;

do $$
declare tab text;
begin
 foreach tab in array array['profiles','devices','sessions','messages','records','days','memories','vehicle_events'] loop
  execute format('alter table public.%I enable row level security',tab);
  execute format('revoke all on public.%I from public,anon,authenticated',tab);
  execute format('grant select on public.%I to authenticated',tab);
  execute format('grant all on public.%I to service_role',tab);
  execute format('create policy owner_read on public.%I for select to authenticated using (%I=(select auth.uid()))',tab,case when tab='profiles' then 'id' else 'owner_id' end);
 end loop;
end $$;
-- No direct owner reads of deleted accounts can remain: every data row cascades through profiles.

-- Only non-sensitive row changes are broadcast; default replica identity sends IDs on delete.
do $$
declare tab text;
begin
 if exists(select 1 from pg_publication where pubname='supabase_realtime') then
  foreach tab in array array['records','days','devices','profiles'] loop
   if not exists(select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename=tab) then
    execute format('alter publication supabase_realtime add table public.%I',tab);
   end if;
  end loop;
 end if;
end $$;
