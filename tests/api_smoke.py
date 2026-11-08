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
    student_id = student['user']['id']
    # T7 账号计数：记录提交前的 submit_count/accepted_count，结束后按差值断言（不依赖 seed 初始值）
    counter_before = call('/user/info/' + str(student_id), token=st)
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
    # T7 练习分析：本人 all/30d 契约（不依赖账号计数修复是否已上线，只断言真实提交能被统计到）
    practice_all = call('/profile/practice/me?range=all', token=st)
    overview = practice_all['overview']
    assert practice_all['range'] == 'all', practice_all
    assert practice_all['periodStart'] is None and practice_all['periodEnd'] is None, practice_all
    assert overview['totalSubmissions'] >= len(results), (overview, results)
    assert overview['acceptedSubmissions'] >= 1, overview
    assert 0 <= overview['acRate'] <= 100, overview
    assert any(item['problemId'] == 1 for item in practice_all['solvedProblems']), practice_all['solvedProblems']
    assert practice_all['listLimit'] == 50, practice_all
    assert len(practice_all['dimensions']) >= 5, practice_all['dimensions']
    assert len(practice_all['difficulty']) == 23, practice_all['difficulty']
    practice_recent = call('/profile/practice/me?range=30d', token=st)
    for field in ('range', 'periodStart', 'periodEnd', 'generatedAt', 'overview', 'dimensions',
                  'tags', 'difficulty', 'solvedProblems', 'triedProblems', 'listLimit'):
        assert field in practice_recent, (field, practice_recent)
    assert practice_recent['range'] == '30d', practice_recent
    assert practice_recent['periodStart'] and practice_recent['periodEnd'], practice_recent
    assert any(item['problemId'] == 1 for item in practice_recent['solvedProblems']), practice_recent['solvedProblems']
    assert practice_recent['overview']['totalSubmissions'] >= len(results), practice_recent['overview']
    # T7 账号计数：每提交一次 submit_count +1；首次 AC 某题 accepted_count +1。
    # 本次 smoke 对 student07 做 3 次提交（AC/WA/CE 同一个题），因此差值必须是 3 / 1。
    counter_after = call('/user/info/' + str(student_id), token=st)
    assert counter_after['submitCount'] - counter_before['submitCount'] == len(results), \
        (counter_before, counter_after)
    assert counter_after['acceptedCount'] - counter_before['acceptedCount'] == 1, \
        (counter_before, counter_after)
    # T8 单提交重判：重判本次 smoke 刚判完的练习提交（contest_id 为空，不触碰已固化历史），
    # 真实 Judge0 重新判题；seed 旧提交的 judge_phase 是历史默认值，不适合作为本场景样本。
    admin = login('admin')
    admin_token = admin['accessToken']
    target_submission = results[0]['id']
    existing = call('/submission/detail?id=' + str(target_submission), token=admin_token)
    assert existing['id'] == target_submission, existing
    rejudge = call('/admin/rejudge/submissions/' + str(target_submission),
                   {'reason': 'T8 integration rejudge verify'}, admin_token)
    assert rejudge['status'] == 'RUNNING', rejudge
    assert rejudge['totalItems'] == 1, rejudge
    op_id = rejudge['id']
    rejudge_detail = None
    deadline = time.time() + 120
    while time.time() < deadline:
        rejudge_detail = call('/admin/rejudge/operations/' + str(op_id), token=admin_token)
        if rejudge_detail['status'] in ('COMPLETED', 'FAILED', 'PARTIAL'):
            break
        time.sleep(2)
    assert rejudge_detail and rejudge_detail['status'] == 'COMPLETED', rejudge_detail
    rejudge_item = rejudge_detail['items'][0]
    assert rejudge_item['status'] == 'SUCCEEDED', rejudge_item
    assert rejudge_item['newResultJson'], rejudge_item
    assert rejudge_item['testcaseDigest'] and len(rejudge_item['testcaseDigest']) == 64, rejudge_item
    # T8-2a 批量 dry-run：范围预览只读，不产生写入
    batch_preview = call('/admin/rejudge/batch/preview',
                         {'scopeType': 'PROBLEM', 'problemId': 1}, admin_token)
    assert batch_preview['scopeType'] == 'PROBLEM', batch_preview
    assert batch_preview['matched'] >= 1, batch_preview
    assert batch_preview['limit'] == 200, batch_preview
    assert 'blockedPending' in batch_preview and 'blockedFinalized' in batch_preview, batch_preview
    evidence = {'class_id': cid, 'homework_id': homework, 'student_id': student['user']['id'],
                'class_name': name, 'homework_completed': True, 'submissions': results,
                'practice': {'all': practice_all['overview'], 'recent': practice_recent['overview']},
                'counters': {'before': counter_before, 'after': counter_after},
                'rejudge': {'operationId': op_id, 'status': rejudge_detail['status'],
                            'submissionId': target_submission, 'submissionBefore': {'status': existing.get('status'), 'score': existing.get('score')},
                            'item': rejudge_item},
                'batchPreview': batch_preview}
    output = Path(os.environ.get('OJ_TEST_OUTPUT_DIR', '/tmp/musealgo-ci-evidence'))
    output.mkdir(parents=True, exist_ok=True)
    (output/'api-smoke.json').write_text(json.dumps(evidence, indent=2)+'\n')
    print(json.dumps(evidence), flush=True)

if __name__ == '__main__':
    main()
