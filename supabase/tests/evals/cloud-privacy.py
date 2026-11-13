"""Real HTTP/AI/queue regression using a disposable Auth fixture, not Google UI or SDK."""
import json, pathlib, time, uuid, urllib.request, urllib.error, datetime, hashlib, os
private = pathlib.Path(os.environ['DORAN_QA_AUTH_FILE'])
qa = json.loads(private.read_text())
base = 'https://lokvjxxtnnzxnkjiehja.supabase.co/functions/v1/doran-api/v1/'
key = 'sb_publishable_5QiXYbosgqwkhzKXPfarDQ_eS3y_-2L'
output = pathlib.Path(__file__).with_name('cloud-privacy-20261114-results.json')
report = {'scope':'Real deployed HTTP, Supabase password QA owner and anonymous vehicle, OpenAI text API and pgmq worker; NOT Google UI, microphone or TTS', 'checks':[], 'script_sha256':hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()}
def record(name, value):
    report['checks'].append({'name':name,'value':value,'at':datetime.datetime.now(datetime.timezone.utc).isoformat()})
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n'); print(name,flush=True)
def call(who, route, method='GET', body=None, sse=False):
    token=qa[who+'_auth']['access_token']
    req=urllib.request.Request(base+route,data=json.dumps(body).encode() if body is not None else None,method=method,headers={'apikey':key,'Authorization':'Bearer '+token,'Content-Type':'application/json','Accept':'text/event-stream' if sse else 'application/json'})
    try:
        with urllib.request.urlopen(req,timeout=80) as r:
            raw=r.read().decode()
            if not sse:return json.loads(raw)
            events=[]
            for block in raw.split('\n\n'):
                lines=block.splitlines();kind=next((l[7:] for l in lines if l.startswith('event: ')),None);data=next((l[6:] for l in lines if l.startswith('data: ')),None)
                if kind and data:events.append({'event':kind,**json.loads(data)})
            errors=[e for e in events if e['event']=='error']
            if errors:raise RuntimeError('SSE '+json.dumps(errors,ensure_ascii=False))
            done=next((e for e in events if e['event']=='done'),None)
            if not done:raise RuntimeError('Missing done')
            return done
    except urllib.error.HTTPError as e:raise RuntimeError(str(e.code)+' '+e.read().decode()) from None
def req(**kwargs):return {'request_id':str(uuid.uuid4()),**kwargs}
def context():return {'source':'pleos','observed_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'passenger_state':'alone','passenger_source':'user_assertion'}
def conversation(text):
    session=call('car','sessions','POST',req(context=context()))
    response=call('car',f"sessions/{session['id']}/turns",'POST',req(text=text,context=context()),True)
    record('real_ai_turn',{'synthetic_user_text':text,'assistant_text':response['assistant_text']})
    saved=call('car',f"sessions/{session['id']}/finish",'POST',req(context=context()))
    record('source_saved',saved)
    return saved['record_id']
def ready(record_id):
    start=time.monotonic()
    while time.monotonic()-start<180:
        r=call('owner','records/'+record_id)
        day=call('owner','days/'+r['record_date'])
        if r['status']=='failed' or day['status']=='failed':raise RuntimeError('Queue failed '+str((r['status'],day['status'])))
        if r['status']=='ready' and day['status']=='ready':return r,day
        time.sleep(5)
    raise RuntimeError('Queue did not settle in180s')
try:
    settings=call('owner','settings')
    call('owner','settings','PATCH',req(version=settings['version'],consent_accepted=True,vehicle_context_enabled=True))
    call('car','devices/bootstrap','POST',{'label':'동승 정보 API 검증용 차량'})
    pair=call('car','pairings','POST',req())
    claim=call('owner','pairings/claim','POST',req(code=pair['code']))
    call('car','pairings/'+claim['id']+'/confirm','POST',req())
    session=call('car','sessions','POST',req(context=context()))
    private_text='가족에게 알리지 않은 이직 면접을 다음 달에 볼 예정이에요.'
    saved=call('car',f"sessions/{session['id']}/finish",'POST',req(pending_turns=[req(text=private_text)]))
    r,day=ready(saved['record_id'])
    memories=call('owner','memories')
    assert any('면접' in m['fact'] or '이직' in m['fact'] for m in memories['items'])
    record('sensitive_synthetic_source_really_stored',{'source':private_text,'memories':memories['items']})
    for occupancy in ['occupied','unknown']:
        ctx={**context(),'passenger_state':occupancy,'passenger_source':'pleos_seat'}
        session=call('car','sessions','POST',req(context=ctx))
        response=call('car',f"sessions/{session['id']}/turns",'POST',req(text='오늘은 가벼운 이야기를 나누고 싶어요.',context=ctx),True)
        assert not any(word in response['assistant_text'] for word in ['이직','면접','가족'])
        record('no_sensitive_topic_in_'+occupancy,{'assistant_text':response['assistant_text']})
    call('owner','account','DELETE',{'confirmation':'DELETE'})
    report['status']='passed';output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
except Exception as e:
    report['status']='failed';report['error']=str(e);output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print('FAILED:',str(e),flush=True);raise
