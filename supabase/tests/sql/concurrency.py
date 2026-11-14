"""Real concurrent PostgreSQL transactions; disposable local Docker container only."""
import concurrent.futures
import json
import subprocess
import sys
import time
import uuid

container = sys.argv[1]
if not container.startswith("doran-"):
    raise SystemExit("Refusing a non-Doran disposable container")


def sql(statement, *, administrator=False):
    role = "supabase_admin" if administrator else "postgres"
    return subprocess.run(
        ["docker", "exec", container, "psql", "-U", role, "-d", "postgres", "-At", "-v", "ON_ERROR_STOP=1", "-c", statement],
        text=True, capture_output=True, check=False,
    )


def reserve(key):
    return sql("set role service_role; select public.doran_worker('budget.reserve', "
               + "'{\"request_id\":\"" + key + "\",\"max_cost_usd\":0.01}'::jsonb);")


assert sql("update private.budget set committed_usd=29.95;").returncode == 0
with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
    results = list(pool.map(reserve, [str(uuid.uuid4()) for _ in range(20)]))
assert sum(r.returncode == 0 for r in results) == 5, [r.stderr for r in results]
assert all(r.returncode == 0 or "BUDGET_EXHAUSTED" in r.stderr for r in results)
assert sql("select committed_usd=30 from private.budget;").stdout.strip() == "t"
print("PASS: twenty concurrent reservations admit exactly remaining five, never exceed $30")
assert sql("delete from private.usage_reservations; update private.budget set committed_usd=0;").returncode == 0
key = str(uuid.uuid4())
with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
    results = list(pool.map(reserve, [key] * 10))
assert all(r.returncode == 0 for r in results)
assert sql("select committed_usd=0.01 from private.budget;").stdout.strip() == "t"
print("PASS: ten concurrent idempotent reservation replays charge exactly once")

owner, job = str(uuid.uuid4()), str(uuid.uuid4())
setup = f"""
insert into auth.users(id,is_anonymous) values('{owner}',false);
insert into public.profiles(id,consent_ai) values('{owner}',true);
insert into private.jobs(id,owner_id,kind,entity_id,source_revision)
values('{job}','{owner}','record_summary',gen_random_uuid(),1);
"""
result = sql(setup, administrator=True)
assert result.returncode == 0, result.stderr
lock = subprocess.Popen(
    ["docker", "exec", container, "psql", "-U", "postgres", "-d", "postgres", "-At", "-c",
     f"begin; select id from private.jobs where id='{job}' for update; select pg_sleep(5); commit; -- doran_lock_test"],
    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
)
for _ in range(20):
    state = sql("select exists(select 1 from pg_stat_activity where query like '%-- doran_lock_test' and wait_event='PgSleep');")
    if state.stdout.strip() == "t":
        break
    time.sleep(0.1)
else:
    raise AssertionError("Row-lock fixture did not start")
result = sql("set role service_role; select public.doran_worker('jobs.claim','{\"limit\":1}');")
assert result.returncode == 0, result.stderr
assert sql(f"select count(*)=1 from pgmq.q_doran_jobs where message->>'job_id'='{job}';").stdout.strip() == "t"
lock.communicate(timeout=10)
assert lock.returncode == 0
print("PASS: SKIP LOCKED contention retains queue message instead of losing job")
assert sql(f"delete from auth.users where id='{owner}'; delete from private.usage_reservations; update private.budget set committed_usd=0;", administrator=True).returncode == 0

# Force the original completion to read generation1, then wait on the profile
# lock while the lock-owning connection retries the same turn as generation2.
owner, car, device, session, turn, request = [str(uuid.uuid4()) for _ in range(6)]
setup = f"""
insert into auth.users(id,is_anonymous) values('{owner}',false),('{car}',true);
insert into public.profiles(id,consent_ai) values('{owner}',true);
insert into public.devices(id,auth_user_id,owner_id,active) values('{device}','{car}','{owner}',true);
insert into public.sessions(id,owner_id,device_id,request_id) values('{session}','{owner}','{device}',gen_random_uuid());
insert into private.turns(id,session_id,request_id,user_text,policy_revision,created_at)
values('{turn}','{session}','{request}','합성 경합 시험 발화',1,now()-interval '91 seconds');
insert into public.messages(session_id,owner_id,turn_id,role,content)
values('{session}','{owner}','{turn}','user','합성 경합 시험 발화');
"""
r = sql(setup, administrator=True)
assert r.returncode == 0, r.stderr
blocker_name = "doran-gen-block-" + uuid.uuid4().hex[:12]
waiter_name = "doran-gen-wait-" + uuid.uuid4().hex[:12]
blocker = subprocess.Popen(
    ["docker", "exec", "-i", "-e", f"PGAPPNAME={blocker_name}", container,
     "psql", "-U", "postgres", "-d", "postgres", "-At", "-v", "ON_ERROR_STOP=1"],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
)
waiter = None
try:
    blocker.stdin.write(f"begin; set local role service_role; select id from public.profiles where id='{owner}' for update;\n")
    blocker.stdin.flush()
    for _ in range(40):
        observed = sql(f"select exists(select 1 from pg_stat_activity where application_name='{blocker_name}' and state='idle in transaction');")
        if observed.stdout.strip() == "t":
            break
        time.sleep(0.1)
    else:
        raise AssertionError("Generation blocker did not acquire profile lock")
    complete_payload = json.dumps({"turn_id": turn, "generation": 1, "text": "이전 생성의 늦은 응답"}, ensure_ascii=False)
    waiter = subprocess.Popen(
        ["docker", "exec", "-e", f"PGAPPNAME={waiter_name}", container,
         "psql", "-U", "postgres", "-d", "postgres", "-At", "-v", "ON_ERROR_STOP=1", "-c",
         "set role service_role; select public.doran_worker('turns.complete','" + complete_payload + "'::jsonb);"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    for _ in range(40):
        observed = sql(f"select exists(select 1 from pg_stat_activity where application_name='{waiter_name}' and wait_event_type='Lock');")
        if observed.stdout.strip() == "t":
            break
        time.sleep(0.1)
    else:
        raise AssertionError("Generation1 completion never reached its profile lock wait")
    retry_payload = json.dumps({"session_id": session, "request_id": request, "text": "합성 경합 시험 발화"}, ensure_ascii=False)
    blocker.stdin.write("select public.doran_command('" + car + "','sessions.turn','" + retry_payload + "'::jsonb); commit;\n")
    blocker.stdin.flush()
    blocker_out, blocker_err = blocker.communicate(timeout=15)
    assert blocker.returncode == 0, blocker_err
    waiter_out, waiter_err = waiter.communicate(timeout=15)
    assert waiter.returncode == 0, waiter_err
    response = next(json.loads(line) for line in waiter_out.splitlines() if line.startswith("{"))
    assert response["accepted"] is False, response
    assert sql(f"select generation=2 and status='pending' and assistant_text is null from private.turns where id='{turn}';").stdout.strip() == "t"
    assert sql(f"select count(*)=1 from public.messages where turn_id='{turn}' and role='user';").stdout.strip() == "t"
    assert sql(f"select count(*)=0 from public.messages where turn_id='{turn}' and role='assistant';").stdout.strip() == "t"
    print("PASS: generation1 completion waits on profile, generation2 retry commits, stale completion cannot cancel or complete it")
finally:
    for proc in [blocker, waiter]:
        if proc is not None and proc.poll() is None:
            proc.kill()
            proc.communicate(timeout=5)
    sql(f"delete from auth.users where id in ('{owner}','{car}');", administrator=True)
