-- Targeted migration-4 regression: fixtures are rolled back; no provider calls.
begin;
create function pg_temp.assert_true(ok boolean,label text) returns void language plpgsql as $$
begin if ok is distinct from true then raise exception 'FAILED: %',label; end if; raise notice 'PASS: %',label; end $$;
insert into auth.users(id,is_anonymous) values('50000000-0000-4000-8000-000000000001',false),('50000000-0000-4000-8000-000000000002',true),('50000000-0000-4000-8000-000000000003',false);
set local role service_role;
do $$
declare owner constant uuid:='50000000-0000-4000-8000-000000000001'; car constant uuid:='50000000-0000-4000-8000-000000000002'; other_owner constant uuid:='50000000-0000-4000-8000-000000000003';
 d uuid;s uuid;r uuid;v jsonb;found_record uuid;memory uuid;request uuid:=gen_random_uuid();n integer;ctx jsonb;
begin
 perform public.doran_command(owner,'profiles.get'); perform public.doran_command(other_owner,'profiles.get');
 perform public.doran_command(owner,'profiles.update','{"version":1,"consent_ai":true,"consent_vehicle":true}');
 v:=public.doran_command(car,'devices.bootstrap');d:=(v->'device'->>'id')::uuid;
 update public.devices set owner_id=owner,active=true where id=d;
 for n in 1..12 loop
  insert into public.sessions(owner_id,device_id,request_id) values(owner,d,gen_random_uuid()) returning id into s;
  insert into public.records(session_id,owner_id,record_date,source_text,status) values(s,owner,'2026-10-01'::date+n,'제주 여행에서 바다를 보았다. '||repeat('기록 ',900),'ready') returning id into r;
  if n=1 then found_record:=r; end if;
 end loop;
 insert into public.memories(owner_id,record_id,record_version,kind,fact,evidence) values(owner,found_record,1,'event','내일 발표가 있다','제주 여행') returning id into memory;
 v:=public.doran_worker('context.get',jsonb_build_object('owner_id',owner,'session_id',s,'query','제주 여행'));
 perform pg_temp.assert_true(jsonb_array_length(v->'records')=5,'source record search returns at most five results');
 perform pg_temp.assert_true(not exists(select 1 from jsonb_array_elements(v->'records')x where length(x->>'text')>2000),'retrieved source snippets are capped at 2000 characters');
 perform pg_temp.assert_true(exists(select 1 from jsonb_array_elements(v->'memories')x where x->>'id'=memory::text and x->>'record_date'='2026-10-02' and x?'created_at'),'memory includes source day and extraction timestamp');
 update public.records set excluded_from_personalization=true where owner_id=owner;
 v:=public.doran_worker('context.get',jsonb_build_object('owner_id',owner,'session_id',s,'query','제주 여행'));
 perform pg_temp.assert_true(jsonb_array_length(v->'records')=0,'excluded source records cannot be retrieved');
 v:=private.search_records(other_owner,'제주 여행');
 perform pg_temp.assert_true(jsonb_array_length(v)=0,'record search never crosses owners');
 v:=public.doran_command(car,'sessions.create',jsonb_build_object('request_id',gen_random_uuid()));s:=(v->'session'->>'id')::uuid;
 v:=public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',s,'request_id',request,'text','발표 준비를 했다.'));
 perform public.doran_worker('turns.fail',jsonb_build_object('turn_id',v->'turn'->>'id'));
 ctx:=jsonb_build_object('source','pleos','observed_at',now(),'passenger_state','alone','passenger_source','user_assertion','arrived',true,'trip_duration_seconds',900,'trip_duration_source','pleos_navi_arrived');
 v:=public.doran_command(car,'sessions.finish',jsonb_build_object('session_id',s,'vehicle_context',ctx));
 perform pg_temp.assert_true(v->'record'->>'text'='발표 준비를 했다.','arrival context does not fabricate a user utterance');
 v:=public.doran_command(car,'sessions.get',jsonb_build_object('session_id',s));
 perform pg_temp.assert_true((v->'session'->'vehicle_context'->>'arrived')::boolean and v->'session'->'vehicle_context'->>'trip_duration_seconds'='900','finish persists arrival received after last speech turn');
 perform public.doran_command(car,'sessions.finish',jsonb_build_object('session_id',s,'vehicle_context','{}'::jsonb));
 v:=public.doran_command(car,'sessions.get',jsonb_build_object('session_id',s));
 perform pg_temp.assert_true((v->'session'->'vehicle_context'->>'arrived')::boolean,'idempotent finish cannot replace the saved context');
end $$;
rollback;
