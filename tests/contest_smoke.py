#!/usr/bin/env python3
"""Full ACM/OI -> delayed judging -> automatic settlement -> replay -> VP.
No mock judge results. Requires the disposable CI compose command in OJ_TEST_COMPOSE_JSON.
"""
import base64
import urllib.request
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import time
from api_smoke import AC, call, login, wait_health

COMPOSE = json.loads(os.environ['OJ_TEST_COMPOSE_JSON'])
MODE = os.environ.get('OJ_TEST_MQ_MODE','disabled')


def compose(*args):
    subprocess.run(COMPOSE+list(args), check=True, stdout=subprocess.DEVNULL)


def wait_result(token, sid):
    for _ in range(180):
        row=call('/submission/detail?id='+str(sid),token=token)
        if row.get('status') != 0 or row.get('judgePhase') == 'FAILED':
            assert row['status'] == 1, (sid,row.get('status'),row.get('judgePhase'))
            return row
        time.sleep(1)
    raise AssertionError('judge timeout')


def main():
    wait_health()
    admin=login('admin')['accessToken']
    student=login('student06')['accessToken']
    evidence=[]
    for mode in ('ACM','OI'):
        now=dt.datetime.now()
        fmt=lambda date:date.strftime('%Y-%m-%d %H:%M:%S')
        config={'title':f'ci-{MODE}-{mode}-{time.time_ns()}', 'mode':mode,'type':'Public','visible':1,
                'startTime':fmt(now-dt.timedelta(minutes=1)), 'endTime':fmt(now+dt.timedelta(minutes=5))}
        contest=call('/contest/add',config,admin);cid=contest['id']
        call('/api/problem-contest/add',{'contestId':cid,'problemId':1,'visible':1,'score':100},admin)
        # wait=true executes in the API process; suspending only the async worker does not delay judging.
        compose('pause','judge0-server','judge0-worker')
        try:
            submitted=call('/submission/submit',{'problemId':1,'contestId':cid,'language':'cpp','code':AC},student)
            sid=submitted['id']
            config.update(id=cid, endTime=fmt(dt.datetime.now()+dt.timedelta(seconds=2)))
            call('/contest/update',config,admin)
            time.sleep(3)  # End the contest while its real judge task is still pending.
            pending=call('/submission/detail?id='+str(sid),token=student)
            assert pending['status']==0 and pending.get('judgePhase')!='FAILED',pending
            assert call(f'/contest/{cid}/rank/final',token=admin)==[]
        finally:
            compose('unpause','judge0-server','judge0-worker')
        wait_result(student,sid)
        for _ in range(45):
            final=call(f'/contest/{cid}/rank/final',token=admin)
            if final:
                break
            time.sleep(1)
        assert len(final)==1, ('automatic settlement',mode)
        assert final[0]['totalScore']==(100 if mode=='OI' else 0),final
        assert final[0]['solvedCount']==(0 if mode=='OI' else 1),final
        for _ in range(2):
            call(f'/contest/{cid}/rank/finalize',{},admin)
            call(f'/contest/{cid}/rank/rebuild',{},admin)
        assert final==call(f'/contest/{cid}/rank/final',token=admin)
        # Real virtual participation does not alter the official ranking or Rating snapshot.
        vp=call(f'/contest/{cid}/vp/start',{},student)
        time.sleep(11)
        vps=call('/submission/submit',{'problemId':1,'contestId':cid,'language':'cpp','code':AC},student)
        call(f'/contest/{cid}/vp/finish',{},student)
        wait_result(student,vps['id'])
        for _ in range(30):
            result=call(f'/contest/{cid}/vp',token=student)
            if result['status']=='FINISHED':
                break
            time.sleep(1)
        assert result['status']=='FINISHED',result
        assert result['score']==(100 if mode=='OI' else 0),result
        assert final==call(f'/contest/{cid}/rank/final',token=admin)
        evidence.append({'mode':mode,'contest_id':cid,'submission_id':sid,'vp_submission_id':vps['id'],
                         'vp_attempt_id':vp['id'],'delayed_judge_waited':True,'rank':final})
        time.sleep(11)
    if MODE=='outage':
        compose('up','-d','--wait','rabbitmq')
        query="SELECT COUNT(*) FROM outbox_event WHERE event_type='submission.judged' AND status<>'PUBLISHED';"
        for _ in range(100):
            count=subprocess.run(COMPOSE+['exec','-T','mysql','sh','-c',
                                 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B oj_db'],
                                 input=query.encode(),stdout=subprocess.PIPE,check=True).stdout.strip()
            if count==b'0':break
            time.sleep(2)
        assert count==b'0',('outbox did not recover',count.decode())
        for item in evidence:
            assert item['rank']==call(f"/contest/{item['contest_id']}/rank/final",token=admin)
            item['mq_outage_backlog_drained']=True
    if MODE=='enabled':
        password=os.environ['OJ_CI_MQ_PASSWORD']
        url='http://127.0.0.1:'+os.environ['OJ_CI_MQ_ADMIN_PORT']+'/api/exchanges/%2F/amq.default/publish'
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        auth=base64.b64encode(('ci:'+password).encode()).decode()
        for item in reversed(evidence):
            for sid in (item['vp_submission_id'],item['submission_id'],item['submission_id']):
                payload={'properties':{},'routing_key':'oj.rank.update','payload_encoding':'string',
                         'payload':json.dumps({'submissionId':sid,'taskId':0,'judgedAt':dt.datetime.now().isoformat()})}
                request=urllib.request.Request(url,json.dumps(payload).encode(),{'Content-Type':'application/json','Authorization':'Basic '+auth})
                with opener.open(request,timeout=10) as response:assert json.load(response)['routed']
        time.sleep(3)
        for item in evidence:
            assert item['rank']==call(f"/contest/{item['contest_id']}/rank/final",token=admin)
            item['broker_duplicate_replay_safe']=True
    output=Path(os.environ['OJ_TEST_OUTPUT_DIR'])
    (output/f'contest-{MODE}.json').write_text(json.dumps(evidence,indent=2)+'\n')
    print(json.dumps({'mq':MODE,'contests_verified':len(evidence)}),flush=True)


if __name__=='__main__':
    main()
