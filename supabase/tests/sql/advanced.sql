begin;
create function pg_temp.assert_true(ok boolean,label text) returns void language plpgsql as $$
begin if ok is distinct from true then raise exception 'FAILED: %',label; end if; raise notice 'PASS: %',label; end $$;
insert into auth.users(id,is_anonymous) values('30000000-0000-4000-8000-000000000001',false),('30000000-0000-4000-8000-000000000002',true);
set local role service_role;
do $$
declare owner constant uuid:='30000000-0000-4000-8000-000000000001'; car constant uuid:='30000000-0000-4000-8000-000000000002';
 d uuid;s uuid;r1 uuid;r2 uuid;old_memory uuid;search_memory uuid;v jsonb;j jsonb;rid uuid;tid uuid;request uuid:=gen_random_uuid();n int;mid bigint;
begin
 perform public.doran_command(owner,'profiles.get');
 perform public.doran_command(owner,'profiles.update','{"version":1,"consent_ai":true,"consent_vehicle":true}');
 v:=public.doran_command(car,'devices.bootstrap');d:=(v->'device'->>'id')::uuid;
 update public.devices set owner_id=owner,active=true where id=d;
 insert into public.sessions(owner_id,device_id,request_id) values(owner,d,gen_random_uuid()) returning id into s;
 insert into public.records(session_id,owner_id,record_date,source_text,status) values(s,owner,current_date,'출근 시각은 아홉 시다.','ready') returning id into r1;
 insert into public.memories(owner_id,record_id,record_version,kind,fact,evidence) values(owner,r1,1,'fact','출근 시각은 아홉 시','아홉 시') returning id into old_memory;
 insert into public.memories(owner_id,record_id,record_version,kind,fact,evidence,created_at) values(owner,r1,1,'interest','양자컴퓨터 논문을 읽는다','아홉 시',now()-interval '3 years') returning id into search_memory;
 for n in 1..40 loop insert into public.memories(owner_id,record_id,record_version,kind,fact,evidence) values(owner,r1,1,'fact','저녁에 우유를 마신다 '||n,'아홉 시'); end loop;
 v:=public.doran_worker('context.get',jsonb_build_object('owner_id',owner,'session_id',s,'query','양자컴퓨터 논문'));
 perform pg_temp.assert_true(exists(select 1 from jsonb_array_elements(v->'memories')m where m->>'id'=search_memory::text),'search retrieves relevant memory older than latest thirty');
 insert into public.sessions(owner_id,device_id,request_id) values(owner,d,gen_random_uuid()) returning id into s;
 insert into public.records(session_id,owner_id,record_date,source_text) values(s,owner,current_date,'이제 출근 시간이 열 시로 바뀌었다.') returning id into r2;
 insert into private.jobs(owner_id,kind,entity_id,source_revision) values(owner,'record_summary',r2,1);
 v:=public.doran_worker('jobs.claim'); j:=v->'items'->0;
 perform pg_temp.assert_true(exists(select 1 from jsonb_array_elements(j->'input'->'prior_memories')m where m->>'id'=old_memory::text),'summary receives relevant prior facts for explicit correction');
 perform public.doran_worker('jobs.complete',jsonb_build_object('job_id',j->>'id','lease_token',j->>'lease_token','policy_revision',j->>'policy_revision','result',jsonb_build_object('text','출근 시간이 열 시로 바뀌었다.','memories',jsonb_build_array(jsonb_build_object('kind','fact','fact','출근 시각은 열 시','evidence','이제 출근 시간이 열 시로 바뀌었다.','supersedes',jsonb_build_array(old_memory))))));
 perform pg_temp.assert_true((select not active from public.memories where id=old_memory),'explicit correction retires fact from older record');
 perform pg_temp.assert_true(exists(select 1 from public.memories where record_id=r2 and active and fact='출근 시각은 열 시'),'corrected fact retains new source evidence');
 -- Clean previous queue entries to isolate failure/retry delivery behavior.
 perform pgmq.purge_queue('doran_jobs'); delete from private.jobs;
 insert into private.jobs(owner_id,kind,entity_id,source_revision) values(owner,'record_summary',r2,1);
 for n in 1..3 loop
  v:=public.doran_worker('jobs.claim');j:=v->'items'->0;
  perform pg_temp.assert_true(j is not null,'retry attempt has a durable lease');
  perform public.doran_worker('jobs.fail',jsonb_build_object('job_id',j->>'id','lease_token',j->>'lease_token'));
  update private.jobs set available_at=now() where id=(j->>'id')::uuid;
  perform pgmq.set_vt('doran_jobs',(j->>'queue_message_id')::bigint,0);
 end loop;
 perform pg_temp.assert_true((select status='failed' from private.jobs where entity_id=r2),'three failed attempts stop automatic retry');
 perform public.doran_command(owner,'records.retry',jsonb_build_object('record_id',r2));
 v:=public.doran_worker('jobs.claim');j:=v->'items'->0;
 perform pg_temp.assert_true(j->>'entity_id'=r2::text,'manual retry re-enqueues previously removed pgmq message');
 -- A failed turn retry updates privacy context and consumes a dispatch rate unit.
 v:=public.doran_command(car,'sessions.create',jsonb_build_object('request_id',gen_random_uuid()));s:=(v->'session'->>'id')::uuid;
 v:=public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',s,'request_id',request,'text','첫 발화','vehicle_context',jsonb_build_object('source','pleos','observed_at',now(),'passenger_state','alone','passenger_source','pleos_seat')));tid:=(v->'turn'->>'id')::uuid;
 perform public.doran_worker('turns.fail',jsonb_build_object('turn_id',tid,'generation',(select generation from private.turns where id=tid)));
 v:=public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',s,'request_id',request,'text','첫 발화','vehicle_context',jsonb_build_object('source','pleos','observed_at',now(),'passenger_state','occupied','passenger_source','pleos_seat')));
 perform pg_temp.assert_true(v->'session'->'vehicle_context'->>'passenger_state'='occupied','failed retry replaces formerly alone context');
 perform public.doran_worker('turns.fail',jsonb_build_object('turn_id',tid,'generation',(select generation from private.turns where id=tid)));
 for n in 3..10 loop
  perform public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',s,'request_id',request,'text','첫 발화'));
  perform public.doran_worker('turns.fail',jsonb_build_object('turn_id',tid,'generation',(select generation from private.turns where id=tid)));
 end loop;
 begin perform public.doran_command(car,'sessions.turn',jsonb_build_object('session_id',s,'request_id',request,'text','첫 발화')); raise exception 'retry rate bypass'; exception when raise_exception then if sqlerrm<>'RATE_LIMITED' then raise; end if; end;
 perform pg_temp.assert_true(true,'failed retries consume per-user rate allowance');
 perform public.doran_command(owner,'account.delete');
 v:=public.doran_command(owner,'account.delete');
 perform pg_temp.assert_true((v->>'deleted')::boolean,'account deletion can retry Auth Admin cleanup after tombstone');
 v:=public.doran_worker('worker.authorize','{"token":"wrong"}');
 perform pg_temp.assert_true(not (v->>'valid')::boolean,'worker rejects untrusted bearer');
 v:=public.doran_worker('worker.authorize',jsonb_build_object('token',(select worker_token from private.worker_config where id=1)));
 perform pg_temp.assert_true((v->>'valid')::boolean,'private worker token authorizes scheduler');
 begin perform public.doran_worker('worker.configure','{"url":"https://example.com/exfiltrate"}'); raise exception 'unrestricted URL accepted'; exception when raise_exception then if sqlerrm<>'INVALID_WORKER_URL' then raise; end if; end;
 perform pg_temp.assert_true(true,'worker URL rejects external destinations');
 v:=public.doran_worker('worker.configure','{"url":"https://abcdefghijklmnopqrst.supabase.co/functions/v1/doran-worker"}');
 perform pg_temp.assert_true((v->>'configured')::boolean and not(v?'token'),'worker configuration does not expose private token');
 perform pg_temp.assert_true(private.wake_worker(),'scheduler can enqueue authenticated network request');
 perform pg_temp.assert_true(not private.wake_worker(),'job wake is debounced');
 -- pg_net dispatch is transaction-bound: the enclosing rollback prevents any outgoing test request.

end $$;
rollback;
