-- Regression for stale callbacks racing retries of the same durable utterance.
-- All account/data fixtures are confined to this transaction and rolled back.
begin;
create function pg_temp.assert_true(ok boolean,label text) returns void language plpgsql as $$
begin if ok is distinct from true then raise exception 'FAILED: %',label; end if; raise notice 'PASS: %',label; end $$;
insert into auth.users(id,is_anonymous) values('60000000-0000-4000-8000-000000000001',false),('60000000-0000-4000-8000-000000000002',true);
set local role service_role;
do $$
declare owner constant uuid:='60000000-0000-4000-8000-000000000001'; car constant uuid:='60000000-0000-4000-8000-000000000002';
 d uuid;s uuid;t uuid;request uuid:=gen_random_uuid();v jsonb;z jsonb;g bigint;old_g bigint;
 context jsonb:=jsonb_build_object('source','pleos','observed_at',now(),'passenger_state','alone','passenger_source','user_assertion');
begin
 perform public.doran_command(owner,'profiles.get');
 perform public.doran_command(owner,'profiles.update','{"version":1,"consent_ai":true}');
 v:=public.doran_command(car,'devices.bootstrap');d:=(v->'device'->>'id')::uuid;
 update public.devices set owner_id=owner,active=true where id=d;
 v:=public.doran_command(car,'sessions.create',jsonb_build_object('request_id',gen_random_uuid()));s:=(v->'session'->>'id')::uuid;
 v:=public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',s,'request_id',request,'text','발표 준비를 했다.','vehicle_context',context));t:=(v->'turn'->>'id')::uuid;g:=(v->'turn'->>'generation')::bigint;
 perform pg_temp.assert_true(g=1,'first provider attempt starts at generation one');
 begin perform public.doran_worker('turns.validate',jsonb_build_object('turn_id',t)); raise exception 'missing generation accepted'; exception when raise_exception then if sqlerrm<>'INVALID_GENERATION' then raise; end if; end;
 perform pg_temp.assert_true(true,'generation is mandatory on provider callbacks');
 begin perform public.doran_worker('turns.fail',jsonb_build_object('turn_id',t,'generation',1.2)); raise exception 'fractional generation accepted'; exception when raise_exception then if sqlerrm<>'INVALID_GENERATION' then raise; end if; end;
 perform pg_temp.assert_true(true,'fractional provider generation is rejected');
 perform public.doran_command(car,'sessions.pause',jsonb_build_object('session_id',s));
 old_g:=g;
 v:=public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',s,'request_id',request,'text','발표 준비를 했다.','vehicle_context',context));g:=(v->'turn'->>'generation')::bigint;
 perform pg_temp.assert_true(g=old_g+1 and v->'turn'->>'id'=t::text and not (v->>'duplicate')::boolean,'pause/resume restarts the same turn with a new generation');
 perform pg_temp.assert_true(v->'session'->>'status'='active','explicit retry resumes paused session');
 perform pg_temp.assert_true((select count(*)=1 from public.messages where session_id=s and role='user'),'pause/resume never duplicates source utterance');
 v:=public.doran_worker('turns.validate',jsonb_build_object('turn_id',t,'generation',old_g));
 perform pg_temp.assert_true(not (v->>'valid')::boolean,'old generation is not valid for streaming');
 v:=public.doran_worker('turns.complete',jsonb_build_object('turn_id',t,'generation',old_g,'text','종료된 요청의 늦은 응답'));
 perform pg_temp.assert_true(not (v->>'accepted')::boolean,'old provider completion is discarded');
 v:=public.doran_worker('turns.fail',jsonb_build_object('turn_id',t,'generation',old_g));
 perform pg_temp.assert_true(not (v->>'failed')::boolean,'old provider failure cannot fail the retry');
 perform pg_temp.assert_true((select generation=g and status='pending' from private.turns where id=t),'old callbacks leave current generation pending');
 -- Failed retries also advance generation and refresh the current privacy choice.
 perform public.doran_worker('turns.fail',jsonb_build_object('turn_id',t,'generation',g)); old_g:=g;
 v:=public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',s,'request_id',request,'text','발표 준비를 했다.','vehicle_context','{}'::jsonb));g:=(v->'turn'->>'generation')::bigint;
 perform pg_temp.assert_true(g=old_g+1 and v->'session'->'vehicle_context'='{}','failed retry advances generation and clears stale privacy context');
 -- A provider which disappeared without a callback becomes reclaimable after90s.
 update private.turns set created_at=now()-interval '91 seconds' where id=t;old_g:=g;
 v:=public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',s,'request_id',request,'text','발표 준비를 했다.','vehicle_context',context));g:=(v->'turn'->>'generation')::bigint;
 perform pg_temp.assert_true(g=old_g+1 and not(v->>'duplicate')::boolean,'stale pending attempt is reclaimed using same request id');
 v:=public.doran_worker('turns.fail',jsonb_build_object('turn_id',t,'generation',old_g));
 perform pg_temp.assert_true(not(v->>'failed')::boolean,'late failure after stale reclaim is harmless');
 v:=public.doran_worker('turns.complete',jsonb_build_object('turn_id',t,'generation',g,'text','발표에서 어떤 점을 준비하셨나요?'));
 perform pg_temp.assert_true((v->>'accepted')::boolean,'current generation completion is accepted');
 v:=public.doran_worker('turns.fail',jsonb_build_object('turn_id',t,'generation',g));
 perform pg_temp.assert_true(not(v->>'failed')::boolean,'even matching late failure cannot overwrite completion');
 v:=public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',s,'request_id',request,'text','발표 준비를 했다.'));
 perform pg_temp.assert_true((v->>'duplicate')::boolean and (v->'turn'->>'generation')::bigint=g,'completed replay retains its generation');
 perform pg_temp.assert_true((select count(*)=1 from public.messages where session_id=s and role='user'),'all retries still have exactly one user message');
 perform pg_temp.assert_true((select count(*)=1 from public.messages where session_id=s and role='assistant'),'only current provider response is persisted');
 perform public.doran_command(car,'sessions.finish',jsonb_build_object('session_id',s));
 begin perform public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',s,'request_id',request,'text','발표 준비를 했다.')); raise exception 'finished session reopened'; exception when raise_exception then if sqlerrm<>'SESSION_FINISHED' then raise; end if; end;
 perform pg_temp.assert_true(true,'finished session remains terminal');
 -- A new request observes the same90s boundary as retries.
 v:=public.doran_command(car,'sessions.create',jsonb_build_object('request_id',gen_random_uuid()));s:=(v->'session'->>'id')::uuid;
 v:=public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',s,'request_id',gen_random_uuid(),'text','응답을 기다리는 발화'));t:=(v->'turn'->>'id')::uuid;
 update private.turns set created_at=now()-interval '70 seconds' where id=t;
 begin perform public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',s,'request_id',gen_random_uuid(),'text','너무 이른 새 요청')); raise exception '70 second attempt replaced'; exception when raise_exception then if sqlerrm<>'TURN_IN_PROGRESS' then raise; end if; end;
 perform pg_temp.assert_true(true,'new request cannot replace a70second attempt');
 update private.turns set created_at=now()-interval '91 seconds' where id=t;
 z:=public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',s,'request_id',gen_random_uuid(),'text','새 요청으로 이어갈 발화'));
 perform pg_temp.assert_true(z->'turn'->>'id'<>t::text and (select status='failed' from private.turns where id=t),'new request can replace an expired90second attempt');
end $$;
rollback;
