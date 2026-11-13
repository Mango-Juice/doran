-- pg_net requests are committed with the source change; tokens stay in private SQL.
create extension if not exists pg_cron;
create extension if not exists pg_net with schema extensions;
create function private.wake_worker() returns boolean
language plpgsql security invoker set search_path='' as $$
declare cfg private.worker_config;
begin
 select * into cfg from private.worker_config where id=1 for update skip locked;
 if cfg.id is null or cfg.service_url is null then return false; end if;
 if cfg.last_called_at>clock_timestamp()-interval '5 seconds' then return false; end if;
 update private.worker_config set last_called_at=clock_timestamp() where id=1;
 perform net.http_post(
  url:=cfg.service_url,
  headers:=jsonb_build_object('Content-Type','application/json','Authorization','Bearer '||cfg.worker_token),
  body:='{}'::jsonb,
  timeout_milliseconds:=110000
 );
 return true;
end $$;
create function private.wake_worker_on_enqueue() returns trigger
language plpgsql security invoker set search_path='' as $$
begin
 perform private.wake_worker();
 return new;
end $$;
create trigger jobs_wake_worker after insert on private.jobs for each row execute function private.wake_worker_on_enqueue();
revoke execute on function private.wake_worker() from public,anon,authenticated;
revoke execute on function private.wake_worker_on_enqueue() from public,anon,authenticated;
grant execute on function private.wake_worker() to service_role;
grant execute on function private.wake_worker_on_enqueue() to service_role;
grant usage on schema net to service_role;
grant execute on function net.http_post(text,jsonb,jsonb,jsonb,integer) to service_role;
select cron.schedule('doran-worker','* * * * *','select private.wake_worker();');
