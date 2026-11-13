-- Retrieve source records as well as extracted facts. Both branches are owner-
-- scoped before ranking, and excluded records never enter the result candidates.
create index if not exists records_text_trgm_idx on public.records using gin(text extensions.gin_trgm_ops);
create function private.search_records(p_owner uuid,p_query text,p_limit integer default 5) returns jsonb
language sql stable security invoker set search_path='' as $$
 with query as (
  select left(btrim(coalesce(p_query,'')),256) as phrase,
   websearch_to_tsquery('simple',left(btrim(coalesce(p_query,'')),256)) as terms
 ), full_text as (
  select r.id,r.record_date,r.text,r.version,2+ts_rank_cd(to_tsvector('simple',r.text),q.terms) as rank
  from public.records r cross join query q
  where r.owner_id=p_owner and not r.excluded_from_personalization and q.phrase<>''
   and to_tsvector('simple',r.text) @@ q.terms
  order by rank desc,r.record_date desc,r.id limit 20
 ), fuzzy as (
  select r.id,r.record_date,r.text,r.version,extensions.word_similarity(q.phrase,r.text) as rank
  from public.records r cross join query q
  where r.owner_id=p_owner and not r.excluded_from_personalization and q.phrase<>''
   and r.text OPERATOR(extensions.%>) q.phrase
  order by rank desc,r.record_date desc,r.id limit 20
 ), candidates as (
  select * from full_text union all select * from fuzzy
 ), distinct_records as (
  select distinct on(id) * from candidates order by id,rank desc
 ), selected as (
  select * from distinct_records order by rank desc,record_date desc,id limit least(5,greatest(1,p_limit))
 )
 select coalesce(jsonb_agg(jsonb_build_object('id',id,'record_date',record_date,'text',left(text,2000),'version',version) order by rank desc,record_date desc,id),'[]'::jsonb) from selected
$$;
revoke execute on function private.search_records(uuid,text,integer) from public,anon,authenticated;
grant execute on function private.search_records(uuid,text,integer) to service_role;

do $$
declare definition text; old_expression text;
begin
 definition:=pg_get_functiondef('public.doran_worker(text,jsonb)'::regprocedure);
 old_expression:='return jsonb_build_object(''messages'',v_messages,''memories'',v_mem,''settings'',to_jsonb(p),''policy_revision'',p.policy_revision,''context_version'',p.policy_revision,''context'',v_context);';
 if position(old_expression in definition)=0 then raise exception 'Expected context response expression missing'; end if;
 definition:=replace(definition,old_expression,'return jsonb_build_object(''messages'',v_messages,''memories'',v_mem,''records'',case when p.personalization_enabled then private.search_records(s.owner_id,p_payload->>''query'',5) else ''[]''::jsonb end,''settings'',to_jsonb(p),''policy_revision'',p.policy_revision,''context_version'',p.policy_revision,''context'',v_context);');
 old_expression:='select m.* from public.memories m join public.records rr on rr.id=m.record_id';
 if position(old_expression in definition)=0 then raise exception 'Expected memory source lookup missing'; end if;
 definition:=replace(definition,old_expression,'select m.*,rr.record_date as source_record_date from public.memories m join public.records rr on rr.id=m.record_id');
 definition:=replace(definition,'''record_id'',z.record_id,''evidence'',z.evidence','''record_id'',z.record_id,''record_date'',z.source_record_date,''created_at'',z.created_at,''evidence'',z.evidence');
 old_expression:='select m.id,m.fact,m.kind,m.record_id,m.record_version from public.memories m join public.records rr on rr.id=m.record_id';
 if position(old_expression in definition)=0 then raise exception 'Expected correction snapshot lookup missing'; end if;
 definition:=replace(definition,old_expression,'select m.id,m.fact,m.kind,m.record_id,m.record_version,rr.record_date,m.created_at from public.memories m join public.records rr on rr.id=m.record_id');
 execute definition;
 definition:=pg_get_functiondef('public.doran_command(uuid,text,jsonb)'::regprocedure);
 old_expression:='if r.id is not null then return jsonb_build_object(''record'',private.record_json(r)); end if;';
 if position(old_expression in definition)=0 then raise exception 'Expected first session finish boundary missing'; end if;
 definition:=replace(definition,old_expression,old_expression||E'\n   if p_payload ? ''vehicle_context'' and p.consent_ai then\n    update public.sessions set vehicle_context=private.filtered_context(p_payload->''vehicle_context'',p.consent_vehicle),updated_at=now() where id=s.id returning * into s;\n   end if;');
 execute definition;
end $$;
