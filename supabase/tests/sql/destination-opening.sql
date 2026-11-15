begin;
create function pg_temp.assert_true(ok boolean,label text) returns void language plpgsql as $$
begin if ok is distinct from true then raise exception 'FAILED: %',label; end if; raise notice 'PASS: %',label; end $$;
insert into auth.users(id,is_anonymous) values('80000000-0000-4000-8000-000000000001',false),('80000000-0000-4000-8000-000000000002',true);
set local role service_role;
do $$
declare owner constant uuid:='80000000-0000-4000-8000-000000000001'; car constant uuid:='80000000-0000-4000-8000-000000000002';
 c jsonb:=jsonb_build_object('source','pleos','observed_at',now(),'passenger_state','alone','passenger_source','user_assertion','destination_name','목적지','latitude',37);
 v jsonb; z jsonb; s uuid; d uuid; tid uuid; rid uuid:=gen_random_uuid();
begin
 perform pg_temp.assert_true(not(private.filtered_context(c,true,false)?'destination_name'),'destination absent without separate consent');
 perform pg_temp.assert_true(not(private.filtered_context(c,false,true)?'destination_name'),'destination requires vehicle consent');
 perform pg_temp.assert_true(private.filtered_context(c,true,true)->>'destination_name'='목적지','separate consent admits name');
 perform pg_temp.assert_true(not(private.filtered_context(c,true,true)?'latitude'),'coordinates still excluded');
 perform public.doran_command(owner,'profiles.get');
 perform public.doran_command(owner,'profiles.update','{"version":1,"consent_ai":true,"consent_vehicle":true,"consent_destination_name":true}');
 v:=public.doran_command(car,'devices.bootstrap'); d:=(v->'device'->>'id')::uuid;
 update public.devices set owner_id=owner,active=true where id=d;
 v:=public.doran_command(car,'sessions.create',jsonb_build_object('request_id',gen_random_uuid(),'vehicle_context',c));s:=(v->'session'->>'id')::uuid;
 perform pg_temp.assert_true(v->'session'->'vehicle_context'->>'destination_name'='목적지','session stores name only under consent');
 v:=public.doran_command(car,'sessions.opening',jsonb_build_object('session_id',s,'request_id',rid,'vehicle_context',c));tid:=(v->'turn'->>'id')::uuid;
 perform pg_temp.assert_true(v->'turn'->>'kind'='opening' and v->'turn'->>'user_text' is null,'opening has no fake user text');
 perform pg_temp.assert_true(not exists(select 1 from public.messages where session_id=s),'opening inserts no user message');
 z:=public.doran_command(car,'sessions.opening',jsonb_build_object('session_id',s,'request_id',gen_random_uuid(),'vehicle_context',c));
 perform pg_temp.assert_true(z->'turn'->>'id'=tid::text and (z->>'duplicate')::boolean,'one opening per session even with another request id');
 v:=public.doran_worker('turns.complete',jsonb_build_object('turn_id',tid,'generation',1,'text','오늘은 어떠셨어요?'));
 perform pg_temp.assert_true((v->>'accepted')::boolean,'opening uses ordinary guarded commit');
 perform pg_temp.assert_true((select count(*)=1 and bool_and(role='assistant') from public.messages where session_id=s),'only assistant greeting persisted');
 v:=public.doran_worker('context.get',jsonb_build_object('owner_id',owner,'session_id',s,'opening',true));
 perform pg_temp.assert_true(v->'context'->>'destination_name'='목적지','opening retrieval retains permitted destination');
 perform public.doran_command(owner,'profiles.update','{"version":2,"consent_destination_name":false}');
 perform pg_temp.assert_true(not exists(select 1 from public.sessions where owner_id=owner and vehicle_context?'destination_name'),'revocation erases retained names');
 v:=public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',s,'request_id',gen_random_uuid(),'text','오늘은 좋았어요','vehicle_context',c));
 perform pg_temp.assert_true(not(v->'session'->'vehicle_context'?'destination_name'),'old client cannot bypass revoked consent');
end $$;
rollback;
