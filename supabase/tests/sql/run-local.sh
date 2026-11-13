#!/usr/bin/env bash
set -euo pipefail
DORAN_SQL_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DORAN_TEST_CONTAINER="doran-sql-test-$$"
DORAN_TEST_IMAGE="public.ecr.aws/supabase/postgres:17.6.1.158"
trap 'docker rm -f "$DORAN_TEST_CONTAINER" >/dev/null 2>&1 || true' EXIT
# Isolated disposable database, no published ports and no persistent volumes.
docker run --rm -d --name "$DORAN_TEST_CONTAINER" -e POSTGRES_PASSWORD=doran-disposable-test "$DORAN_TEST_IMAGE" >/dev/null
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
for suite in "$DORAN_SQL_ROOT"/tests/sql/invariants.sql "$DORAN_SQL_ROOT"/tests/sql/advanced.sql "$DORAN_SQL_ROOT"/tests/sql/manual-context.sql "$DORAN_SQL_ROOT"/tests/sql/record-search.sql; do
  docker exec -i "$DORAN_TEST_CONTAINER" psql -U supabase_admin -d postgres -v ON_ERROR_STOP=1 < "$suite"
done
python3 "$DORAN_SQL_ROOT/tests/sql/concurrency.py" "$DORAN_TEST_CONTAINER"
