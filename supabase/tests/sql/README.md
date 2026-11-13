# Database checks

Run `bash supabase/tests/sql/run-local.sh` from the repository root with Docker running. The runner
starts a disposable Supabase PostgreSQL 17.6 container without published ports or persistent
volumes, applies all migrations, runs invariant and concurrency checks, then removes the container.
The standalone image's missing GoTrue `is_anonymous` column and `auth.jwt()` helper are added only
in this disposable test fixture. Do not apply that compatibility shim to hosted Supabase.

The SQL suites roll back all fixtures. They cover account/device isolation, two-sided pairing,
expiry and attempt limits, CAS writes, idempotent sessions and turns, retry dispatch limits, consent
and deletion invalidation, genuine user-only memory evidence, older-record retrieval, explicit
cross-record corrections, manual diary preservation, pgmq retry/leases and the global API budget.
The Python checks use multiple real database connections to verify budget contention and queue lock
handling. Scheduler tests roll back `pg_net` requests before commit, so no test mail or network
request is sent.

Hosted setup after migrations and Edge Function deployment:

```sql
select public.doran_worker(
  'worker.configure',
  '{"url":"https://YOUR_PROJECT_REF.supabase.co/functions/v1/doran-worker"}'::jsonb
);
```

Run this as a privileged deployment role. The project reference is exactly 20 lowercase alphanumeric
characters. A private random bearer is generated inside the database and is never returned. The
worker verifies it via the service-only `worker.authorize` RPC. Enqueued work wakes the worker with
a five-second debounce; Supabase Cron also checks every minute. Secrets, job payloads and raw
dialogue must not be copied to public build logs.

A successful local database run proves database behavior under these fixtures. It does not prove
deployed OAuth, OpenAI, microphone or mobile connectivity.
