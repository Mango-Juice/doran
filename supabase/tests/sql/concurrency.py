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
