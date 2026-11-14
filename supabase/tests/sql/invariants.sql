-- Run against a disposable Supabase database after applying migrations.
-- Every fixture is rolled back. Never run the standalone Auth compatibility shim on hosted databases.
begin;
create temporary table test_state(key text primary key,value jsonb);
grant all on test_state to service_role,authenticated;
create function pg_temp.assert_true(ok boolean,label text) returns void language plpgsql as $$
begin if ok is distinct from true then raise exception 'FAILED: %',label; end if; raise notice 'PASS: %',label; end $$;
insert into auth.users(id,is_anonymous) values
 ('10000000-0000-4000-8000-000000000001',false),('10000000-0000-4000-8000-000000000002',false),
 ('20000000-0000-4000-8000-000000000001',true),('20000000-0000-4000-8000-000000000002',true);
set local role service_role;
do $$
declare
 a constant uuid:='10000000-0000-4000-8000-000000000001'; b constant uuid:='10000000-0000-4000-8000-000000000002';
 car constant uuid:='20000000-0000-4000-8000-000000000001'; car2 constant uuid:='20000000-0000-4000-8000-000000000002';
 v jsonb; z jsonb; challenge uuid; sid uuid; tid uuid; rid uuid; request uuid:=gen_random_uuid(); n integer;
begin
 v:=public.doran_command(a,'profiles.get');
 perform pg_temp.assert_true((v->'profile'->>'consent_ai')::boolean=false,'new account requires explicit consent');
 perform public.doran_command(a,'profiles.update','{"version":1,"consent_ai":true}');
 perform public.doran_command(b,'profiles.get');
 begin perform public.doran_command(a,'profiles.update','{"consent_ai":true}'); raise exception 'missing CAS accepted'; exception when raise_exception then if sqlerrm<>'VERSION_CONFLICT' then raise; end if; end;
 perform pg_temp.assert_true(true,'missing version rejected');
 v:=public.doran_command(car,'devices.bootstrap');
 insert into test_state values('device',v->'device');
 perform pg_temp.assert_true((v->>'paired')::boolean=false,'anonymous vehicle begins unpaired');
 begin perform public.doran_command(a,'devices.bootstrap'); raise exception 'owner became device'; exception when raise_exception then if sqlerrm<>'FORBIDDEN' then raise; end if; end;
 perform pg_temp.assert_true(true,'owner cannot bootstrap car identity');
 v:=public.doran_command(car,'pairings.create',jsonb_build_object('code_hash',repeat('a',64)));
 challenge:=(v->>'id')::uuid;
 perform pg_temp.assert_true((v->>'expires_at')::timestamptz<=now()+interval '5 minutes','pair expires in five minutes');
 v:=public.doran_command(a,'pairings.claim',jsonb_build_object('code_hash',repeat('a',64)));
 perform pg_temp.assert_true(v->>'status'='claimed','mobile claim does not yet pair');
 v:=public.doran_command(car,'devices.current');
 perform pg_temp.assert_true((v->>'paired')::boolean=false,'vehicle approval required');
 v:=public.doran_command(car,'pairings.confirm',jsonb_build_object('pairing_id',challenge));
 perform pg_temp.assert_true(v->>'status'='paired','both devices approve association');
 begin perform public.doran_command(car2,'pairings.status',jsonb_build_object('pairing_id',challenge)); raise exception 'other car sees pairing'; exception when raise_exception then if sqlerrm<>'FORBIDDEN' then raise; end if; end;
 perform pg_temp.assert_true(true,'pairing status is actor scoped');
 for n in 1..5 loop perform public.doran_command(b,'pairings.claim',jsonb_build_object('code_hash',repeat('b',64))); end loop;
 v:=public.doran_command(b,'pairings.claim',jsonb_build_object('code_hash',repeat('b',64)));
 perform pg_temp.assert_true(v->>'error'='RATE_LIMITED','invalid pairing attempts persist rate limit');
 v:=public.doran_command(car,'sessions.create',jsonb_build_object('request_id',request));
 sid:=(v->'session'->>'id')::uuid;
 z:=public.doran_command(car,'sessions.create',jsonb_build_object('request_id',request));
 perform pg_temp.assert_true(v->'session'->>'id'=z->'session'->>'id','session create is idempotent');
 request:=gen_random_uuid();
 v:=public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',sid,'request_id',request,'text','오늘 발표를 준비했다.'));
 tid:=(v->'turn'->>'id')::uuid;
 z:=public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',sid,'request_id',request,'text','오늘 발표를 준비했다.'));
 perform pg_temp.assert_true((z->>'duplicate')::boolean,'replayed utterance is deduplicated');
 perform public.doran_worker('turns.fail',jsonb_build_object('turn_id',tid,'generation',(select generation from private.turns where id=tid)));
 z:=public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',sid,'request_id',request,'text','오늘 발표를 준비했다.'));
 perform pg_temp.assert_true(not (z->>'duplicate')::boolean and z->'turn'->>'id'=tid::text,'failed turn retries same source without duplicate');
 perform pg_temp.assert_true((select count(*)=1 from public.messages where session_id=sid),'retry stores user message exactly once');
 v:=public.doran_worker('turns.complete',jsonb_build_object('turn_id',tid,'generation',(select generation from private.turns where id=tid),'text','어떤 부분을 준비하셨나요?'));
 perform pg_temp.assert_true((v->>'accepted')::boolean,'current model response commits');
 v:=public.doran_command(car,'sessions.finish',jsonb_build_object('session_id',sid,'pending_turns',jsonb_build_array(jsonb_build_object('request_id',gen_random_uuid(),'text','회의는 내일이다.'))));
 rid:=(v->'record'->>'id')::uuid;
 insert into test_state values('record',v->'record'),('session',to_jsonb(sid));
 perform pg_temp.assert_true(position('회의는 내일이다.' in v->'record'->>'text')>0,'offline pending speech is included at finish');
 perform pg_temp.assert_true(position('어떤 부분' in v->'record'->>'text')=0,'assistant response is not source experience');
 begin perform public.doran_command(b,'records.get',jsonb_build_object('record_id',rid)); raise exception 'cross user read'; exception when raise_exception then if sqlerrm<>'NOT_FOUND' then raise; end if; end;
 perform pg_temp.assert_true(true,'RPC owner isolation');
 perform pg_temp.assert_true((select count(*)>0 from pgmq.q_doran_jobs),'durable pgmq jobs enqueued transactionally');
end $$;
reset role;
set local role authenticated;
select set_config('request.jwt.claim.sub','10000000-0000-4000-8000-000000000002',true);
select pg_temp.assert_true((select count(*)=0 from public.records),'RLS prevents another owner reading record');
select pg_temp.assert_true(not has_function_privilege('authenticated','public.doran_command(uuid,text,jsonb)','EXECUTE'),'mobile cannot invoke trusted mutation RPC');
select pg_temp.assert_true(not has_function_privilege('authenticated','public.doran_worker(text,jsonb)','EXECUTE'),'mobile cannot claim worker or budget');
select set_config('request.jwt.claim.sub','10000000-0000-4000-8000-000000000001',true);
select pg_temp.assert_true((select count(*)=1 from public.records),'RLS permits own record');
select pg_temp.assert_true(not has_table_privilege('authenticated','public.records','UPDATE'),'direct writes forbidden even to owned record');
select set_config('request.jwt.claim.sub','20000000-0000-4000-8000-000000000001',true);
select pg_temp.assert_true((select count(*)=0 from public.records),'paired car has no direct diary read access');
reset role;
select set_config('request.jwt.claim.sub','',true);
set local role service_role;
do $$
declare
 a constant uuid:='10000000-0000-4000-8000-000000000001'; car constant uuid:='20000000-0000-4000-8000-000000000001';
 r jsonb; v jsonb; z jsonb; job jsonb; y jsonb; request uuid; rid uuid; sid uuid; tid uuid; version bigint; n integer;
begin
 select value into r from test_state where key='record'; rid:=(r->>'id')::uuid;
 v:=public.doran_worker('jobs.claim','{"limit":5}');
 for job in select value from jsonb_array_elements(v->'items') loop
  if job->>'kind'='record_summary' then
   z:=public.doran_worker('jobs.complete',jsonb_build_object('job_id',job->>'id','lease_token',job->>'lease_token','policy_revision',job->>'policy_revision','result',jsonb_build_object('text','발표 준비를 했고 회의는 내일이다.','memories',jsonb_build_array(
    jsonb_build_object('kind','event','fact','발표 준비','evidence','발표를 준비했다'),
    jsonb_build_object('kind','fact','fact','허구의 감정','evidence','존재하지 않는 근거')))));
   perform pg_temp.assert_true((z->>'accepted')::boolean,'matching source job accepted');
  end if;
 end loop;
 perform pg_temp.assert_true((select count(*)=1 from public.memories where record_id=rid),'memory requires actual user evidence substring');
 y:=public.doran_command(a,'days.get',jsonb_build_object('local_date',r->>'record_date'));
 y:=public.doran_command(a,'days.update',jsonb_build_object('local_date',r->>'record_date','version',y->>'version','manual_text','내가 직접 쓴 일기'));
 v:=public.doran_worker('jobs.claim','{"limit":5}');
 for job in select value from jsonb_array_elements(v->'items') loop
  if job->>'kind'='day_summary' then perform public.doran_worker('jobs.complete',jsonb_build_object('job_id',job->>'id','lease_token',job->>'lease_token','policy_revision',job->>'policy_revision','result',jsonb_build_object('text','새 AI 일기'))); end if;
 end loop;
 y:=public.doran_command(a,'days.get',jsonb_build_object('local_date',r->>'record_date'));
 perform pg_temp.assert_true(y->>'manual_text'='내가 직접 쓴 일기','automatic generation preserves manual diary');
 perform pg_temp.assert_true((y->>'has_new_generated')::boolean,'new AI version is offered separately');
 v:=public.doran_command(car,'sessions.create',jsonb_build_object('request_id',gen_random_uuid())); sid:=(v->'session'->>'id')::uuid;
 v:=public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',sid,'request_id',gen_random_uuid(),'text','아까의 이야기를 이어가자.'));tid:=(v->'turn'->>'id')::uuid;
 r:=public.doran_command(a,'records.update',jsonb_build_object('record_id',rid,'version',r->>'version','excluded',true));
 v:=public.doran_worker('turns.validate',jsonb_build_object('turn_id',tid,'generation',(select generation from private.turns where id=tid)));
 perform pg_temp.assert_true(not (v->>'valid')::boolean,'exclusion invalidates in-flight conversation');
 v:=public.doran_worker('turns.complete',jsonb_build_object('turn_id',tid,'generation',(select generation from private.turns where id=tid),'text','이전 내용을 말하는 응답'));
 perform pg_temp.assert_true(not (v->>'accepted')::boolean,'stale conversation cannot commit');
 perform pg_temp.assert_true((select count(*)=0 from public.memories where record_id=rid),'exclusion removes derived memory');
 v:=public.doran_worker('jobs.claim','{"limit":5}');
 r:=public.doran_command(a,'records.update',jsonb_build_object('record_id',rid,'version',r->>'version','text','수정한 사실만 남긴다.'));
 for job in select value from jsonb_array_elements(v->'items') loop
  z:=public.doran_worker('jobs.complete',jsonb_build_object('job_id',job->>'id','lease_token',job->>'lease_token','policy_revision',job->>'policy_revision','result',jsonb_build_object('text','오래된 결과')));
  perform pg_temp.assert_true(not (z->>'accepted')::boolean,'stale record/day result rejected');
 end loop;
 v:=public.doran_command(a,'profiles.get');
 perform public.doran_command(a,'profiles.update',jsonb_build_object('version',v->'profile'->>'version','consent_ai',false));
 v:=public.doran_worker('jobs.claim','{"limit":5}');
 perform pg_temp.assert_true(jsonb_array_length(v->'items')=0,'consent revoke prevents worker from obtaining private source');
 perform public.doran_command(a,'records.delete',jsonb_build_object('record_id',rid,'version',r->>'version'));
 perform pg_temp.assert_true((select count(*)=0 from public.messages where session_id=(select (value#>>'{}')::uuid from test_state where key='session')),'record deletion cascades original transcript');
 y:=public.doran_command(a,'days.get',jsonb_build_object('local_date',r->>'record_date'));
 perform pg_temp.assert_true(y->>'generated_text'='' and y->>'manual_text'='내가 직접 쓴 일기','deleted source clears AI draft while explicit manual copy stays');
 -- Monetary reservations are serialized by the singleton budget row.
 for n in 1..30 loop perform public.doran_worker('budget.reserve',jsonb_build_object('request_id',gen_random_uuid(),'max_cost_usd',1)); end loop;
 begin perform public.doran_worker('budget.reserve',jsonb_build_object('request_id',gen_random_uuid(),'max_cost_usd',0.001)); raise exception 'budget overspend'; exception when raise_exception then if sqlerrm<>'BUDGET_EXHAUSTED' then raise; end if; end;
 perform pg_temp.assert_true((select committed_usd=30 from private.budget where id=1),'30 dollar reservation ceiling is enforced');
 select request_id into request from private.usage_reservations limit 1;
 perform public.doran_worker('budget.settle',jsonb_build_object('request_id',request,'actual_cost_usd',0.25));
 perform public.doran_worker('budget.settle',jsonb_build_object('request_id',request,'actual_cost_usd',0.25));
 perform pg_temp.assert_true((select committed_usd=29.25 from private.budget where id=1),'settlement refund is idempotent');
 perform public.doran_command(a,'devices.revoke',jsonb_build_object('device_id',(select value->>'id' from test_state where key='device')));
 begin perform public.doran_command(car,'profiles.get'); raise exception 'revoked device still accesses'; exception when raise_exception then if sqlerrm<>'DEVICE_UNPAIRED' then raise; end if; end;
 perform pg_temp.assert_true(true,'device revoke immediately removes API authorization');
 perform public.doran_command(a,'account.delete');
 begin perform public.doran_command(a,'profiles.get'); raise exception 'deleted account resurrected'; exception when raise_exception then if sqlerrm<>'UNAUTHENTICATED' then raise; end if; end;
 perform pg_temp.assert_true(true,'deleted account tombstone prevents valid-token resurrection');
end $$;
rollback;
