-- Optional destination disclosure, explicitly enabled by the mobile owner.
alter table public.profiles add column consent_destination_name boolean not null default false;
alter table private.turns add column kind text not null default 'user' check(kind in ('user','opening'));
alter table private.turns alter column user_text drop not null;
alter table private.turns add constraint turns_text_kind_check check((kind='opening' and user_text is null) or (kind='user' and user_text is not null));
create unique index turns_one_opening_per_session on private.turns(session_id) where kind='opening';

create function private.filtered_context(p_context jsonb,p_vehicle_consent boolean,p_destination_consent boolean) returns jsonb
language plpgsql stable security invoker set search_path='' as $$
declare result jsonb;
begin
 result:=private.filtered_context(p_context,p_vehicle_consent);
 if coalesce(p_vehicle_consent,false) and coalesce(p_destination_consent,false) and result ? 'observed_at'
  and jsonb_typeof(p_context->'destination_name')='string' and length(btrim(p_context->>'destination_name')) between 1 and 200 then
  result:=result||jsonb_build_object('destination_name',btrim(p_context->>'destination_name'));
 end if;
 return result;
end $$;
revoke execute on function private.filtered_context(jsonb,boolean,boolean) from public,anon,authenticated;
grant execute on function private.filtered_context(jsonb,boolean,boolean) to service_role;

do $$
declare definition text; old_expression text;
begin
 definition:=pg_get_functiondef('public.doran_command(uuid,text,jsonb)'::regprocedure);
 old_expression:='consent_vehicle=coalesce((p_payload->>''consent_vehicle'')::boolean,consent_vehicle),';
 if position(old_expression in definition)=0 then raise exception 'Profile update expression missing'; end if;
 definition:=replace(definition,old_expression,old_expression||E'\n   consent_destination_name=coalesce((p_payload->>''consent_destination_name'')::boolean,consent_destination_name),');
 definition:=replace(definition,'private.filtered_context(coalesce(p_payload->''vehicle_context'',''{}''),p.consent_vehicle)','private.filtered_context(coalesce(p_payload->''vehicle_context'',''{}''),p.consent_vehicle,p.consent_ai and p.consent_destination_name)');
 old_expression:='if not p.consent_vehicle then';
 definition:=replace(definition,old_expression,E'if not (p.consent_ai and p.consent_vehicle and p.consent_destination_name) then\n   update public.sessions set vehicle_context=vehicle_context-''destination_name'' where owner_id=o;\n   update public.vehicle_events set payload=payload-''destination_name'' where owner_id=o;\n  end if;\n  '||old_expression);
 -- Opening uses the same ownership, session lock, generation and budget commit path as ordinary turns.
 definition:=replace(definition,'elsif p_action=''sessions.turn'' then','elsif p_action in (''sessions.turn'',''sessions.opening'') then');
 old_expression:='select * into t from private.turns where session_id=s.id and request_id=(p_payload->>''request_id'')::uuid;';
 if position(old_expression in definition)=0 then raise exception 'Turn lookup expression missing'; end if;
 definition:=replace(definition,old_expression,E'if p_action=''sessions.turn'' then update private.turns set status=''cancelled'' where session_id=s.id and kind=''opening'' and status=''pending''; end if;\n   select * into t from private.turns where session_id=s.id and ((p_action=''sessions.opening'' and kind=''opening'') or request_id=(p_payload->>''request_id'')::uuid);'||E'\n   if t.id is not null and ((p_action=''sessions.opening'')<>(t.kind=''opening'')) then raise exception ''IDEMPOTENCY_CONFLICT''; end if;');
 old_expression:='perform private.rate_turn(o);';
 -- Do not reopen a stale pending opening when the user has already begun a normal turn.
 definition:=replace(definition,old_expression,E'if p_action=''sessions.opening'' and exists(select 1 from public.messages where session_id=s.id and role=''user'') then raise exception ''SESSION_ALREADY_STARTED''; end if;\n   '||old_expression);
 old_expression:='insert into private.turns(session_id,request_id,user_text,policy_revision) values(s.id,(p_payload->>''request_id'')::uuid,p_payload->>''text'',p.policy_revision) returning * into t;';
 if position(old_expression in definition)=0 then raise exception 'Turn insert expression missing'; end if;
 definition:=replace(definition,old_expression,'insert into private.turns(session_id,request_id,user_text,policy_revision,kind) values(s.id,(p_payload->>''request_id'')::uuid,p_payload->>''text'',p.policy_revision,case when p_action=''sessions.opening'' then ''opening'' else ''user'' end) returning * into t;');
 old_expression:='insert into public.messages(session_id,owner_id,turn_id,role,content) values(s.id,o,t.id,''user'',t.user_text);';
 definition:=replace(definition,old_expression,'if t.kind=''user'' then '||old_expression||' end if;');
 execute definition;
 definition:=pg_get_functiondef('public.doran_worker(text,jsonb)'::regprocedure);
 definition:=replace(definition,'private.filtered_context(s.vehicle_context,p.consent_vehicle)','private.filtered_context(s.vehicle_context,p.consent_vehicle,p.consent_ai and p.consent_destination_name)');
 -- For opening retrieval, derive the query from the consent-filtered session, never request text.
 definition:=replace(definition,'p_payload->>''query''','case when coalesce((p_payload->>''opening'')::boolean,false) then private.filtered_context(s.vehicle_context,p.consent_vehicle,p.consent_ai and p.consent_destination_name)->>''destination_name'' else p_payload->>''query'' end');
 execute definition;
end $$;
