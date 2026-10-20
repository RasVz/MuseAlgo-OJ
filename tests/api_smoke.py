#!/usr/bin/env python3
"""Real API + Judge0 acceptance. Run only against the disposable tests/compose.yml stack."""
import datetime as dt
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request

BASE = os.environ.get('OJ_TEST_API', 'http://127.0.0.1:18020')
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
AC = '#include <iostream>\n#include <vector>\n#include <unordered_map>\nint main(){int n,t;std::cin>>n;std::vector<int>a(n);for(int &x:a)std::cin>>x;std::cin>>t;std::unordered_map<int,int>s;for(int i=0;i<n;++i){auto p=s.find(t-a[i]);if(p!=s.end()){std::cout<<p->second<<" "<<i;return 0;}s[a[i]]=i;}}'

def call(path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + path, headers=headers,
                                 data=None if body is None else json.dumps(body).encode())
    try:
        with HTTP.open(req, timeout=30) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        result = json.load(error)
    assert result.get('code') == 200, (path, result.get('code'), result.get('message'))
    return result['data']

def login(username):
    return call('/auth/login', {'username': username, 'password': '123456'})

def wait_health():
    for _ in range(120):
        try:
            with HTTP.open(BASE + '/actuator/health', timeout=3) as response:
                if json.load(response)['status'] == 'UP':
                    return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise AssertionError('backend readiness timeout')

def judged(token, code, expected):
    submitted = call('/submission/submit', {'problemId': 1, 'language': 'cpp', 'code': code}, token)
    sid = submitted['id']
    for _ in range(180):
        detail = call('/submission/detail?id=' + str(sid), token=token)
        if detail.get('status') != 0 or detail.get('judgePhase') == 'FAILED':
            break
        time.sleep(1)
    assert detail['status'] == expected, (sid, detail.get('status'), detail.get('judgePhase'))
    assert detail['score'] == (100 if expected == 1 else 0), (sid, detail.get('score'))
    return {'id': sid, 'status': detail['status'], 'score': detail['score'], 'phase': detail['judgePhase']}

def main():
    wait_health()
    teacher = login('teacher01')
    student = login('student07')
    tt, st = teacher['accessToken'], student['accessToken']
    tid = teacher['user']['id']
    name = 'ci-class-' + str(time.time_ns())
    call('/class/create', {'name': name, 'teacherId': tid, 'description': 'isolated acceptance'}, tt)
    classes = call('/class/list?teacherId=' + str(tid), token=tt)
    cid = next(c['id'] for c in classes if c['name'] == name)
    invite = call('/class/inviteCode?classId=' + str(cid), token=tt)
    call('/class/joinByCode', {'code': invite}, st)
    now = dt.datetime.now()
    fmt = lambda date: date.strftime('%Y-%m-%d %H:%M:%S')
    homework = call('/problem-list/add', {'title': name + '-homework', 'type': 4, 'classId': cid,
                                         'privacy': 0, 'startTime': fmt(now-dt.timedelta(minutes=1)),
                                         'endTime': fmt(now+dt.timedelta(hours=1))}, tt)
    assert call('/problem-list/'+str(homework),token=st)['type']==4
    call('/problem-list-item/add', {'problemListId': homework, 'problemDisplayId': '1'}, tt)
    records = call('/problem-list/my-homeworks?size=100', token=st)['records']
    assert any(row['id'] == homework and row['problemCount'] == 1 for row in records)
    call('/problem/1', token=st)
    results = [judged(st, AC, 1)]
    records = call('/problem-list/my-homeworks?size=100', token=st)['records']
    assert next(row for row in records if row['id'] == homework)['solvedCount'] == 1
    overview = call(f'/class/{cid}/homework/{homework}/status/overview', token=tt)
    assert overview['summary']['finalAcCount'] == 1, overview['summary']
    assert overview['summary']['activeStudentCount'] == 1, overview['summary']
    time.sleep(11)
    results.append(judged(st, '#include <iostream>\nint main(){std::cout<<0;}', 2))
    time.sleep(11)
    results.append(judged(st, 'int main( {', 3))
    evidence = {'class_id': cid, 'homework_id': homework, 'student_id': student['user']['id'],
                'class_name': name, 'homework_completed': True, 'submissions': results}
    output = Path(os.environ.get('OJ_TEST_OUTPUT_DIR', '/tmp/musealgo-ci-evidence'))
    output.mkdir(parents=True, exist_ok=True)
    (output/'api-smoke.json').write_text(json.dumps(evidence, indent=2)+'\n')
    print(json.dumps(evidence), flush=True)

if __name__ == '__main__':
    main()
