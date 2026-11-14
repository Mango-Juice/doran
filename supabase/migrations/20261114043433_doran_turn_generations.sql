-- One durable user message can have multiple provider attempts. A stale attempt
-- must never finish, fail, or cancel a newer attempt with the same request ID.
alter table private.turns add column generation bigint not null default 1 check(generation>0);
comment on column private.turns.generation is 'Monotonic provider-attempt generation for one idempotent user turn';

do $$
declare definition text; old_expression text; new_expression text;
begin
 definition:=pg_get_functiondef('public.doran_command(uuid,text,jsonb)'::regprocedure);
 old_expression:='if t.status=''failed'' then';
 if position(old_expression in definition)=0 then raise exception 'Expected failed retry branch missing'; end if;
 definition:=replace(definition,old_expression,'if t.status in (''failed'',''cancelled'') or (t.status=''pending'' and t.created_at<=now()-interval ''90 seconds'') then');
 old_expression:='if exists(select 1 from private.turns where session_id=s.id and status=''pending'') then raise exception ''TURN_IN_PROGRESS''; end if;';
 if position(old_expression in definition)=0 then raise exception 'Expected duplicate retry pending guard missing'; end if;
 new_expression:='update private.turns set status=''failed'' where session_id=s.id and id<>t.id and status=''pending'' and created_at<=now()-interval ''90 seconds'';'||E'\n     '||
  'if exists(select 1 from private.turns where session_id=s.id and id<>t.id and status=''pending'') then raise exception ''TURN_IN_PROGRESS''; end if;';
 definition:=replace(definition,old_expression,new_expression);
 old_expression:='update private.turns set status=''pending'',policy_revision=p.policy_revision,created_at=now() where id=t.id returning * into t;';
 if position(old_expression in definition)=0 then raise exception 'Expected retry update missing'; end if;
 definition:=replace(definition,old_expression,'update private.turns set status=''pending'',generation=generation+1,assistant_text=null,policy_revision=p.policy_revision,created_at=now() where id=t.id returning * into t;');
 old_expression:='created_at>now()-interval ''60 seconds''';
 if position(old_expression in definition)=0 then raise exception 'Expected new turn timeout guard missing'; end if;
 definition:=replace(definition,old_expression,'created_at>now()-interval ''90 seconds''');
 execute definition;

 definition:=pg_get_functiondef('public.doran_worker(text,jsonb)'::regprocedure);
 old_expression:='v_queue record; v_id uuid; v_scan integer;';
 if position(old_expression in definition)=0 then raise exception 'Expected worker locals missing'; end if;
 definition:=replace(definition,old_expression,old_expression||' v_generation bigint;');
 old_expression:='elsif p_action in (''turns.validate'',''turns.complete'',''turns.fail'') then';
 if position(old_expression in definition)=0 then raise exception 'Expected turn worker action guard missing'; end if;
 new_expression:=old_expression||E'\n  '||
  'if jsonb_typeof(p_payload->''generation'') is distinct from ''number'' then raise exception ''INVALID_GENERATION''; end if;'||E'\n  '||
  'if (p_payload->>''generation'')::numeric<1 or (p_payload->>''generation'')::numeric>9223372036854775807 or trunc((p_payload->>''generation'')::numeric)<>(p_payload->>''generation'')::numeric then raise exception ''INVALID_GENERATION''; end if;'||E'\n  '||
  'v_generation:=(p_payload->>''generation'')::bigint;';
 definition:=replace(definition,old_expression,new_expression);
 old_expression:='if t.id is null then return jsonb_build_object(''valid'',false,''accepted'',false); end if;';
 if position(old_expression in definition)=0 then raise exception 'Expected turn lookup guard missing'; end if;
 definition:=replace(definition,old_expression,'if t.id is null or t.generation is distinct from v_generation then return jsonb_build_object(''valid'',false,''accepted'',false,''failed'',false); end if;');
 old_expression:='if p_action=''turns.fail'' then update private.turns set status=''failed'' where id=t.id and status=''pending''; return jsonb_build_object(''failed'',true); end if;';
 if position(old_expression in definition)=0 then raise exception 'Expected turn failure update missing'; end if;
 definition:=replace(definition,old_expression,'if p_action=''turns.fail'' then update private.turns set status=''failed'' where id=t.id and generation=v_generation and status=''pending'' returning id into v_id; return jsonb_build_object(''failed'',v_id is not null); end if;');
 old_expression:='select * into t from private.turns where id=t.id for update;';
 if position(old_expression in definition)=0 then raise exception 'Expected turn completion lock missing'; end if;
 definition:=replace(definition,old_expression,old_expression||E'\n  if t.id is null or t.generation is distinct from v_generation then return jsonb_build_object(''accepted'',false); end if;');
 old_expression:='update private.turns set status=''cancelled'' where id=t.id and status=''pending'';';
 if position(old_expression in definition)=0 then raise exception 'Expected invalid completion update missing'; end if;
 definition:=replace(definition,old_expression,'update private.turns set status=''cancelled'' where id=t.id and generation=v_generation and status=''pending'';');
 execute definition;
end $$;
