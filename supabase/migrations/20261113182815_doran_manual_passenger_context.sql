-- A parked user's explicit privacy choice is not consent to collect vehicle sensors.
-- This boundary also drops unknown fields even when vehicle data is permitted.
create function private.filtered_context(p_context jsonb,p_vehicle_consent boolean) returns jsonb
language plpgsql stable security invoker set search_path='' as $$
declare
 result jsonb; observed timestamptz; state text; passenger_source text; k text; b jsonb; latest timestamptz;
begin
 if jsonb_typeof(p_context) is distinct from 'object' or p_context->>'source' is distinct from 'pleos' then return '{}'::jsonb; end if;
 begin observed:=(p_context->>'observed_at')::timestamptz; exception when others then return '{}'::jsonb; end;
 if observed is null or observed<now()-interval '120 seconds' or observed>now()+interval '10 seconds' then return '{}'::jsonb; end if;
 state:=case when p_context->>'passenger_state' in ('alone','occupied','unknown') then p_context->>'passenger_state' else 'unknown' end;
 passenger_source:=case when p_context->>'passenger_source' in ('pleos_seat','user_assertion','unknown') then p_context->>'passenger_source' else 'unknown' end;
 if not coalesce(p_vehicle_consent,false) and (state<>'alone' or passenger_source<>'user_assertion') then return '{}'::jsonb; end if;
 result:=jsonb_build_object('source','pleos','observed_at',observed,'passenger_state',state,'passenger_source',passenger_source);
 if not coalesce(p_vehicle_consent,false) then return result; end if;
 foreach k in array array['destination_eta_seconds','trip_duration_seconds','trip_distance_m'] loop
  if jsonb_typeof(p_context->k)='number' and (p_context->>k)::numeric between 0 and 10000000 then result:=result||jsonb_build_object(k,p_context->k); end if;
 end loop;
 if jsonb_typeof(p_context->'arrived')='boolean' then result:=result||jsonb_build_object('arrived',p_context->'arrived'); end if;
 if p_context->>'trip_duration_source' in ('pleos_navi_arrived','pleos_vehicle_odometer') then result:=result||jsonb_build_object('trip_duration_source',p_context->'trip_duration_source'); end if;
 if jsonb_typeof(p_context->'place_label')='string' and length(p_context->>'place_label')<=80 then result:=result||jsonb_build_object('place_label',p_context->'place_label'); end if;
 b:=p_context->'trip_baseline';
 if jsonb_typeof(b)='object' and b->>'source'='local_confirmed_arrivals' then
  begin
   latest:=(b->>'latest_observed_at')::timestamptz;
   if jsonb_typeof(b->'sample_count')='number' and (b->>'sample_count')::numeric between 3 and 10000
    and trunc((b->>'sample_count')::numeric)=(b->>'sample_count')::numeric
    and jsonb_typeof(b->'median_duration_seconds')='number' and (b->>'median_duration_seconds')::numeric between 0 and 86400
    and jsonb_typeof(b->'median_arrival_minute')='number' and (b->>'median_arrival_minute')::numeric between 0 and 1439
    and trunc((b->>'median_arrival_minute')::numeric)=(b->>'median_arrival_minute')::numeric
    and latest between now()-interval '30 days' and now() then
    result:=result||jsonb_build_object('trip_baseline',jsonb_build_object('source','local_confirmed_arrivals','sample_count',b->'sample_count','median_duration_seconds',b->'median_duration_seconds','median_arrival_minute',b->'median_arrival_minute','latest_observed_at',latest));
   end if;
  exception when others then null;
  end;
 end if;
 return result;
end $$;
revoke execute on function private.filtered_context(jsonb,boolean) from public,anon,authenticated;
grant execute on function private.filtered_context(jsonb,boolean) to service_role;

-- Recreate installed RPCs with only these reviewed substitutions. Assert each
-- original expression exists so drift causes a migration failure, not a partial fix.
do $$
declare definition text; old_expression text;
begin
 definition:=pg_get_functiondef('public.doran_command(uuid,text,jsonb)'::regprocedure);
 old_expression:='case when p.consent_vehicle then coalesce(p_payload->''vehicle_context'',''{}'') else ''{}'' end';
 if position(old_expression in definition)=0 then raise exception 'Expected session context expression missing'; end if;
 definition:=replace(definition,old_expression,'private.filtered_context(coalesce(p_payload->''vehicle_context'',''{}''),p.consent_vehicle)');
 old_expression:='update public.sessions set vehicle_context=''{}'' where owner_id=o;';
 if position(old_expression in definition)=0 then raise exception 'Expected consent-revoke context expression missing'; end if;
 definition:=replace(definition,old_expression,'update public.sessions set vehicle_context=private.filtered_context(vehicle_context,false) where owner_id=o;');
 execute definition;
 definition:=pg_get_functiondef('public.doran_worker(text,jsonb)'::regprocedure);
 old_expression:='case when p.consent_vehicle then s.vehicle_context else ''{}'' end';
 if position(old_expression in definition)=0 then raise exception 'Expected worker context expression missing'; end if;
 definition:=replace(definition,old_expression,'private.filtered_context(s.vehicle_context,p.consent_vehicle)');
 execute definition;
end $$;

-- Express the permanent-user condition directly for the security advisor too.
do $$
declare tab text;
begin
 foreach tab in array array['profiles','devices','sessions','messages','records','days','memories','vehicle_events'] loop
  execute format('alter policy owner_read on public.%I using (%I=(select auth.uid()) and coalesce((select (auth.jwt()->>''is_anonymous'')::boolean),false)=false)',tab,case when tab='profiles' then 'id' else 'owner_id' end);
 end loop;
end $$;
create index if not exists diary_versions_day_idx on private.diary_versions(day_id);
create index if not exists diary_versions_owner_idx on private.diary_versions(owner_id);
create index if not exists jobs_owner_idx on private.jobs(owner_id);
create index if not exists pairing_pending_owner_idx on private.pairing_challenges(pending_owner_id);
create index if not exists vehicle_events_session_idx on public.vehicle_events(session_id);
alter table private.pairing_attempts add column id bigint generated always as identity primary key;
grant usage,select on sequence private.pairing_attempts_id_seq to service_role;
