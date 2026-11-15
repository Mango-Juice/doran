#!/usr/bin/env bash
set -euo pipefail
DORAN_SQL_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DORAN_TEST_CONTAINER="doran-sql-test-$$"
DORAN_TEST_IMAGE="public.ecr.aws/supabase/postgres:17.6.1.158"
DORAN_TEST_STARTED=0
cleanup() {
  if [[ "$DORAN_TEST_STARTED" == 1 ]]; then
    python3 - "$DORAN_TEST_CONTAINER" <<'PY_CLEANUP'
import subprocess, sys
try:
    subprocess.run(['docker','rm','-f','-v',sys.argv[1]],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=12)
except subprocess.TimeoutExpired:
    print('Cleanup requires responsive Docker API for owned container: '+sys.argv[1],file=sys.stderr)
PY_CLEANUP
  fi
}
trap cleanup EXIT
# Do not pull images or restart Docker as a test side effect.
python3 - <<'PY_GATE'
import shutil, subprocess
if shutil.disk_usage('.').free < 1024**3:
    raise SystemExit('DISK_GATE: less than1GiB free; no container started')
try:
    result = subprocess.run(['docker','image','inspect','public.ecr.aws/supabase/postgres:17.6.1.158'],capture_output=True,timeout=12)
except subprocess.TimeoutExpired:
    raise SystemExit('DOCKER_GATE: image inspection timed out; no container started')
if result.returncode:
    raise SystemExit('DOCKER_GATE: required cached image unavailable; no pull attempted')
PY_GATE
# Isolated disposable database, no published ports and no persistent volumes.
DORAN_TEST_STARTED=1
docker run --pull=never --memory=768m --memory-swap=768m --cpus=1 --rm -d --name "$DORAN_TEST_CONTAINER" -e POSTGRES_PASSWORD=doran-disposable-test "$DORAN_TEST_IMAGE" >/dev/null
for attempt in $(seq 1 60); do
  if docker exec "$DORAN_TEST_CONTAINER" pg_isready -h 127.0.0.1 -U postgres >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$DORAN_TEST_CONTAINER" pg_isready -h 127.0.0.1 -U postgres
# The base image predates GoTrue's is_anonymous migration. Hosted Supabase already has it.
docker exec "$DORAN_TEST_CONTAINER" psql -U supabase_admin -d postgres -v ON_ERROR_STOP=1 -c 'alter table auth.users add column if not exists is_anonymous boolean not null default false;'
docker exec -i "$DORAN_TEST_CONTAINER" psql -U supabase_admin -d postgres -v ON_ERROR_STOP=1 <<'SQL'
do $block$
begin
 if to_regprocedure('auth.jwt()') is null then
  execute $definition$create function auth.jwt() returns jsonb language sql stable as $body$
   select coalesce(nullif(current_setting('request.jwt.claim',true),''),nullif(current_setting('request.jwt.claims',true),''))::jsonb
  $body$$definition$;
 end if;
end $block$;
SQL

for migration in "$DORAN_SQL_ROOT"/migrations/*.sql; do
  docker exec -i "$DORAN_TEST_CONTAINER" psql -U postgres -d postgres -v ON_ERROR_STOP=1 < "$migration"
done
for suite in "$DORAN_SQL_ROOT"/tests/sql/invariants.sql "$DORAN_SQL_ROOT"/tests/sql/advanced.sql "$DORAN_SQL_ROOT"/tests/sql/manual-context.sql "$DORAN_SQL_ROOT"/tests/sql/record-search.sql "$DORAN_SQL_ROOT"/tests/sql/turn-generations.sql "$DORAN_SQL_ROOT"/tests/sql/destination-opening.sql; do
  docker exec -i "$DORAN_TEST_CONTAINER" psql -U supabase_admin -d postgres -v ON_ERROR_STOP=1 < "$suite"
done
python3 "$DORAN_SQL_ROOT/tests/sql/concurrency.py" "$DORAN_TEST_CONTAINER"
