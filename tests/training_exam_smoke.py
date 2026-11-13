#!/usr/bin/env python3
"""Training exam integration checks, exclusively against a disposable local stack.

Usage: python3 tests/training_exam_smoke.py --base http://127.0.0.1:18083 \
  --mysql-container musealgo-training-mysql-20261113 --output /tmp/exam-http.json
Uses synthetic accounts and SQL submission fixtures; does not invoke Judge0 or AI.
"""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--base', required=True)
parser.add_argument('--mysql-container', required=True)
parser.add_argument('--output', required=True)
args = parser.parse_args()
assert urllib.parse.urlsplit(args.base).hostname in ('127.0.0.1', 'localhost')
assert args.mysql_container.startswith('musealgo-training-')
password = os.environ['OJ_TRAINING_TEST_DB_PASS']
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
checks = []

def sql(statement):
    proc = subprocess.run(['docker', 'exec', '-i', '-e', 'MYSQL_PWD=' + password,
                           args.mysql_container, 'mysql', '-uroot', '-N', '-B',
                           '--default-character-set=utf8mb4', 'oj_db'], input=statement,
                          text=True, capture_output=True, check=True)
    return proc.stdout.strip()

def request(path, data=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(args.base + path, headers=headers,
                                 data=None if data is None else json.dumps(data).encode())
    try:
        with http.open(req, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        return json.load(error)

def ok(path, data=None, token=None):
    result = request(path, data, token)
    assert result['code'] == 200, (path, result)
    return result.get('data')

def check(name, condition):
    assert condition, name
    checks.append({'name': name, 'passed': True})

prefix = 'exam' + str(time.time_ns())[-9:]
users = {}
for kind in ['admin', 'student', 'other']:
    username = prefix + kind
    ok('/auth/register', {'username': username, 'password': 'ExamTest12345',
                          'nickname': kind, 'email': username + '@example.test'})
    if kind == 'admin':
        sql("UPDATE user SET role=3 WHERE username='" + username + "'; "
            "INSERT IGNORE INTO sys_user_role(user_id,role_id) "
            "SELECT id,3 FROM user WHERE username='" + username + "';")
    login = ok('/auth/login', {'username': username, 'password': 'ExamTest12345'})
    users[kind] = (login['accessToken'], int(sql("SELECT id FROM user WHERE username='" + username + "'")))
admin, _ = users['admin']
student, uid = users['student']
other, _ = users['other']
pid = ok('/problem/add', {'title': prefix + ' coding', 'content': 'Synthetic statement',
    'inputDesc': 'a', 'outputDesc': 'a', 'isPublic': True, 'difficulty': 800,
    'timeLimit': 1000, 'memoryLimit': 128, 'samples': [{'input': '1', 'output': '1'}]}, admin)
payload = {'title': prefix + ' paper', 'category': 'MOCK', 'durationMinutes': 10,
    'totalScore': 100, 'isPublished': 1, 'problems': [{'problemId': pid, 'score': 80}],
    'questions': [{'questionType': 'JUDGE', 'statement': 'Synthetic true', 'answer': 'T',
                   'score': 20, 'options': [], 'explanation': 'Synthetic explanation'}]}
paper = ok('/exam-paper/save', payload, admin)
detail = ok('/exam-paper/' + str(paper), token=student)
qid = detail['questions'][0]['id']
check('student detail hides standard answers', not detail['questions'][0].get('answer'))
listed = ok('/exam-paper/list?keyword=' + urllib.parse.quote(prefix) + '&page=1&size=12', token=student)
check('list counts both objective and coding questions', listed['records'][0]['problemCount'] == 2)

with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    starts = list(pool.map(lambda _: ok(f'/exam-paper/{paper}/attempt/start', {}, student), range(4)))
check('concurrent start returns one running attempt', len({a['id'] for a in starts}) == 1)
attempt_id = starts[0]['id']
answer_payload = {'answers': [{'questionId': qid, 'answer': 'T'}]}
ok(f'/exam-paper/{paper}/submit-answers', answer_payload, student)
resume = ok(f'/exam-paper/{paper}/attempt', token=student)
check('resume returns own saved answers and grading', resume['answers'][0]['answer'] == 'T' and resume['results'][0]['score'] == 20)
check('other user cannot read this attempt', ok(f'/exam-paper/{paper}/attempt', token=other) is None)

# Both rows fall inside the exam window, but the contest score must be excluded.
sql(f"UPDATE exam_attempt SET start_time=DATE_SUB(NOW(),INTERVAL 2 SECOND) WHERE id={attempt_id}; "
    f"INSERT INTO submission(problem_id,user_id,score,status,judge_phase,create_time,contest_id) "
    f"VALUES({pid},{uid},50,2,'FINISHED',NOW(),NULL),({pid},{uid},100,1,'FINISHED',NOW(),999999),"
    f"({pid},{uid},100,1,'FINISHED',DATE_ADD(NOW(),INTERVAL 3 MINUTE),NULL);")
finish = ok(f'/exam-paper/{paper}/attempt/finish', {}, student)
check('coding uses the 80-point paper weight and excludes contest submissions', finish['codingScore'] == 40 and finish['totalScore'] == 60)
check('finish is idempotent', ok(f'/exam-paper/{paper}/attempt/finish', {}, student)['totalScore'] == 60)
check('submitted attempt rejects new answers', request(f'/exam-paper/{paper}/submit-answers', answer_payload, student)['code'] != 200)

ok('/exam-paper/save', {**payload, 'id': paper, 'description': 'metadata update'}, admin)
check('metadata save preserves questions and completed attempt', ok(f'/exam-paper/{paper}/attempt', token=student)['id'] == attempt_id and ok(f'/exam-paper/{paper}', token=student)['questions'][0]['id'] == qid)
changed = {**payload, 'id': paper, 'questions': [{**payload['questions'][0], 'score': 21}]}
check('attempted paper structure change is rejected', request('/exam-paper/save', changed, admin)['code'] != 200)
check('rejected save preserves final score', ok(f'/exam-paper/{paper}/attempt', token=student)['totalScore'] == 60)
copy = ok(f'/exam-paper/{paper}/copy', {}, admin)
check('copy is a new editable draft without attempts', ok(f'/exam-paper/{copy}', token=admin)['isPublished'] == 0 and not ok(f'/exam-paper/{copy}', token=admin)['hasAttempts'])

# DB-adjusted deadline is confined to the disposable synthetic attempt.
second = ok(f'/exam-paper/{paper}/attempt/start', {}, other)
sql(f"UPDATE exam_attempt SET end_time=DATE_SUB(NOW(), INTERVAL 1 SECOND) WHERE id={second['id']}")
check('expired attempt finalizes as TIMEOUT', ok(f'/exam-paper/{paper}/attempt', token=other)['status'] == 'TIMEOUT')
stats = ok(f'/exam-paper/{paper}/stats', token=admin)
check('unanswered objective question counts in correct-rate denominator', stats['questionStats'][0]['correctRate'] == 50)
check('students cannot read classwide grades', request(f'/exam-paper/{paper}/stats', token=student)['code'] != 200)
invalid = {**payload, 'id': None, 'durationMinutes': 0}
check('invalid duration rejected at HTTP boundary', request('/exam-paper/save', invalid, admin)['code'] != 200)
invalid = {**payload, 'id': None, 'questions': [{**payload['questions'][0], 'answer': 'A'}]}
check('invalid judge standard answer rejected', request('/exam-paper/save', invalid, admin)['code'] != 200)
# Pending judgement must not become a permanent zero on early or timed-out finish.
pending_attempt = ok(f'/exam-paper/{paper}/attempt/start', {}, student)
sql(f"UPDATE exam_attempt SET start_time=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id={pending_attempt['id']}; "
    f"INSERT INTO submission(problem_id,user_id,score,status,judge_phase,create_time) "
    f"VALUES({pid},{uid},0,0,'JUDGING',NOW());")
check('manual finish waits for pending coding judgement', request(f'/exam-paper/{paper}/attempt/finish', {}, student).get('errorCode') == 'EXAM_ATTEMPT_GRADING_PENDING')
sql(f"UPDATE exam_attempt SET end_time=DATE_SUB(NOW(),INTERVAL 1 SECOND),start_time=DATE_SUB(NOW(),INTERVAL 4 SECOND) WHERE id={pending_attempt['id']}; "
    f"UPDATE submission SET create_time=DATE_SUB(NOW(),INTERVAL 2 SECOND) WHERE user_id={uid} AND judge_phase='JUDGING';")
pending = ok(f'/exam-paper/{paper}/attempt', token=student)
check('timeout retains pending attempt with editing deadline zero', pending['status'] == 'RUNNING' and pending['remainingSeconds'] == 0 and pending['gradingPending'])
sql(f"UPDATE submission SET status=1,score=100,judge_phase='FINISHED' WHERE user_id={uid} AND judge_phase='JUDGING';")
settled = ok(f'/exam-paper/{paper}/attempt', token=student)
check('timeout retry waits then settles the completed coding score', settled['status'] == 'TIMEOUT' and settled['codingScore'] == 80)
check('attempted paper deletion is rejected without losing history', request(f'/exam-paper/delete/{paper}', {}, admin)['code'] != 200)
ok('/exam-paper/save', {**payload, 'id': paper, 'isPublished': 0}, admin)
check('unpublished paper still permits own review', ok(f'/exam-paper/{paper}', token=student)['id'] == paper)
check('unpublished paper denies strangers', request(f'/exam-paper/{paper}')['code'] != 200)
check('unpublished paper rejects a new start', request(f'/exam-paper/{paper}/attempt/start', {}, student)['code'] != 200)
Path(args.output).write_text(json.dumps({'checks': checks, 'count': len(checks),
    'fixtures': {'paperId': paper, 'copyId': copy},
    'scope': 'disposable MySQL/Redis + real HTTP; synthetic scoring fixtures, no Judge0 or AI'}, ensure_ascii=False, indent=2))
print(json.dumps({'passed': len(checks), 'output': args.output}, ensure_ascii=False))
