"""Real HTTP/AI/queue regression using a disposable Auth fixture, not Google UI or SDK."""
import json, pathlib, time, uuid, urllib.request, urllib.error, datetime, hashlib, os
private = pathlib.Path(os.environ['DORAN_QA_AUTH_FILE'])
qa = json.loads(private.read_text())
base = 'https://lokvjxxtnnzxnkjiehja.supabase.co/functions/v1/doran-api/v1/'
key = 'sb_publishable_5QiXYbosgqwkhzKXPfarDQ_eS3y_-2L'
output = pathlib.Path(__file__).with_name('cloud-20261114-results.json')
report = {'scope':'Real deployed HTTP, Supabase password QA owner and anonymous vehicle, OpenAI text API and pgmq worker; NOT Google UI, microphone or TTS', 'checks':[], 'script_sha256':hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()}
def record(name, value):
    report['checks'].append({'name':name,'value':value,'at':datetime.datetime.now(datetime.timezone.utc).isoformat()})
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2)); print(name,flush=True)
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
    settings=call('owner','settings','PATCH',req(version=settings['version'],consent_accepted=True,vehicle_context_enabled=False))
    record('consent_accepted_without_vehicle_sensors',settings)
    call('car','devices/bootstrap','POST',{'label':'API 검증용 가상 차량'})
    pair=call('car','pairings','POST',req())
    claim=call('owner','pairings/claim','POST',req(code=pair['code']))
    confirmed=call('car','pairings/'+claim['id']+'/confirm','POST',req())
    record('pairing_both_sides',{'status':confirmed['status']})
    first=conversation('오늘 발표 자료를 준비했어요. 발표 시작 시각은 오후 세 시예요.')
    r,day=ready(first);record('record_and_daily_generated',{'record':r['text'],'day':day['generated_text']})
    manual='내가 직접 다듬은 문장입니다. 이 문장을 유지합니다.'
    day=call('owner','days/'+r['record_date'],'PATCH',req(version=day['version'],manual_text=manual))
    second=conversation('발표는 오후 세 시가 아니라 오후 네 시로 바뀌었어요.')
    r2,day2=ready(second)
    assert day2['manual_text']==manual and day2['has_new_generated'], 'Manual diary not protected'
    record('manual_preserved_with_new_draft',{'manual_text':day2['manual_text'],'new_generated':day2['generated_text'],'has_new_generated':day2['has_new_generated']})
    memories=call('owner','memories');record('cross_record_memory_after_correction',memories)
    r=call('owner','records/'+first)
    call('owner','records/'+first,'PATCH',req(version=r['version'],excluded_from_personalization=True))
    memories=call('owner','memories');assert not any(m['record_id']==first for m in memories['items'])
    record('exclude_immediate_memory_removal',True)
    r=call('owner','records/'+first)
    call('owner','records/'+first,'DELETE',req(version=r['version'],delete_manual_diary=False))
    day3=call('owner','days/'+r['record_date']);assert day3['manual_text']==manual
    record('source_delete_retains_chosen_manual',True)
    call('owner','account','DELETE',{'confirmation':'DELETE'})
    try:call('owner','settings');raise AssertionError('Deleted owner still authorized')
    except RuntimeError as e:assert str(e).startswith('401')
    record('account_delete_revokes_access',True)
    report['status']='passed';output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
except Exception as e:
    report['status']='failed';report['error']=str(e);output.write_text(json.dumps(report,ensure_ascii=False,indent=2));print('FAILED:',str(e),flush=True);raise
