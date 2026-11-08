#!/usr/bin/env python3
"""Real HTTP admin queue/redrive smoke for the disposable CI compose stack.

This script creates only synthetic users, a class, a report job, and one FAILED
notification intent inside the throwaway MySQL container, then exercises the
admin-only list and the single allowed redrive over real HTTP. It never touches
existing business data and does not call any external notification provider.

Prerequisite: run against tests/compose.yml with the backend started by
tests/run_suite.py, which exports OJ_TEST_COMPOSE_JSON and OJ_TEST_API.
"""
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request

from api_smoke import BASE, HTTP, call, login, wait_health


REASON = '通知投递失败，重投一次核对站内提醒链路'


def raw_request(method, path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(
        BASE + path,
        headers=headers,
        data=None if body is None else json.dumps(body).encode(),
        method=method,
    )
    try:
        with HTTP.open(request, timeout=30) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def compose_mysql(script):
    raw = os.environ.get('OJ_TEST_COMPOSE_JSON')
    assert raw, 'notification_smoke.py requires OJ_TEST_COMPOSE_JSON from tests/run_suite.py'
    compose = json.loads(raw)
    command = compose + [
        'exec', '-T', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B oj_db',
    ]
    completed = subprocess.run(
        command, input=script.encode(), stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=True,
    )
    return completed.stdout.decode()


def cleanup_sql(suffix):
    like_user = f"ci-rni-%{suffix}%"
    like_class = f"ci-rni-class-{suffix}%"
    return f"""
DELETE FROM report_notification_intent
 WHERE job_id IN (SELECT id FROM report_job
                   WHERE class_id IN (SELECT id FROM `class` WHERE name LIKE '{like_class}'));
DELETE n FROM user_notification n
  JOIN `user` u ON u.id = n.user_id
 WHERE u.username LIKE '{like_user}';
DELETE FROM report_job
 WHERE class_id IN (SELECT id FROM `class` WHERE name LIKE '{like_class}');
DELETE FROM class_student
 WHERE class_id IN (SELECT id FROM `class` WHERE name LIKE '{like_class}');
DELETE FROM `class` WHERE name LIKE '{like_class}';
DELETE FROM `user` WHERE username LIKE '{like_user}';
"""


def seed_intent(suffix):
    script = f"""
INSERT INTO `user` (username, password, nickname, role, preferences)
VALUES ('ci-rni-teacher-{suffix}', 'test-only', 'ci-rni-teacher-{suffix}', 2,
        '{{"notifications":{{"interactions":true,"reports":true}}}}');
SET @teacher = LAST_INSERT_ID();
INSERT INTO `user` (username, password, nickname, role, preferences)
VALUES ('ci-rni-student-{suffix}', 'test-only', 'ci-rni-student-{suffix}', 1,
        '{{"notifications":{{"interactions":true,"reports":true}}}}');
SET @student = LAST_INSERT_ID();
INSERT INTO `class` (name, teacher_id, student_count)
VALUES ('ci-rni-class-{suffix}', @teacher, 1);
SET @class = LAST_INSERT_ID();
INSERT INTO class_student (class_id, student_id) VALUES (@class, @student);
INSERT INTO report_job
  (student_id, class_id, period_start, period_end, status, requester_id,
   active_requester_id, generation_no, terminal_revision, create_time)
VALUES (@student, @class, '2026-01-05', '2026-01-11', 'FAILED', @teacher,
        @teacher, 1, 1, CURRENT_TIMESTAMP(3));
SET @job = LAST_INSERT_ID();
INSERT INTO report_notification_intent
  (event_key, event_kind, job_id, class_id, student_id, recipient_id,
   generation_no, terminal_revision, source_error_code, status,
   attempts, max_attempts, redrive_round, next_attempt_at)
VALUES (CONCAT('REPORT_FAILED:', @job, ':1:1:', @teacher), 'REPORT_FAILED',
        @job, @class, @student, @teacher, 1, 1, 'EMPTY_RENDER', 'FAILED',
        5, 5, 0, NULL);
SET @intent = LAST_INSERT_ID();
SELECT @teacher, @student, @class, @job, @intent;
"""
    output = compose_mysql(script)
    line = next(line for line in reversed(output.strip().splitlines()) if line.strip())
    teacher, student, class_id, job_id, intent_id = (int(value) for value in line.split('\t'))
    return {
        'teacher': teacher,
        'student': student,
        'class_id': class_id,
        'job_id': job_id,
        'intent_id': intent_id,
    }


def main():
    wait_health()
    admin = login('admin')
    teacher = login('teacher01')
    admin_token = admin['accessToken']
    teacher_token = teacher['accessToken']
    admin_id = admin['user']['id']
    assert admin_id and admin_id > 0, admin

    suffix = str(time.time_ns())
    seeded = seed_intent(suffix)
    job_id = seeded['job_id']
    intent_id = seeded['intent_id']
    try:
        status_code, body = raw_request(
            'GET', f'/admin/report-notification-intents?jobId={job_id}', token=teacher_token)
        assert status_code == 403, (status_code, body)

        listed = call(f'/admin/report-notification-intents?status=FAILED&jobId={job_id}', token=admin_token)
        assert listed['total'] == 1, listed
        item = listed['items'][0]
        assert item['intentId'] == intent_id, (item, intent_id)
        assert item['eventKind'] == 'REPORT_FAILED', item
        assert item['status'] == 'FAILED', item
        assert item['attempts'] == 5 and item['maxAttempts'] == 5, item
        assert item['sourceErrorCode'] == 'EMPTY_RENDER', item
        assert item['lastErrorCode'] is None, item
        serialized = json.dumps(listed)
        assert 'ci-rni-student-' + suffix not in serialized, 'student identity leaked'
        assert '<html' not in serialized.lower(), 'report content leaked'

        status_code, body = raw_request(
            'POST', f'/admin/report-notification-intents/{intent_id}/redrive',
            {'reason': '太短'}, token=teacher_token)
        assert status_code == 403, (status_code, body)

        status_code, body = raw_request(
            'POST', f'/admin/report-notification-intents/{intent_id}/redrive',
            {'reason': '太短'}, token=admin_token)
        assert status_code == 200 and body.get('code') == 400, (status_code, body)

        redriven = call(
            f'/admin/report-notification-intents/{intent_id}/redrive',
            {'reason': REASON}, token=admin_token)
        assert redriven['status'] == 'PENDING', redriven
        assert redriven['attempts'] == 5, redriven
        assert redriven['maxAttempts'] == 10, redriven
        assert redriven['redriveRound'] == 1, redriven
        assert redriven['redriveAdminId'] == admin_id, redriven
        assert redriven['redriveReason'] == REASON, redriven
        assert redriven['redrivenAt'], redriven

        status_code, body = raw_request(
            'POST', f'/admin/report-notification-intents/{intent_id}/redrive',
            {'reason': REASON}, token=admin_token)
        assert status_code == 200 and body.get('code') == 409, (status_code, body)

        # The dispatcher may already have reclaimed the redriven row; only the
        # authoritative outbox audit and expanded budget are asserted here.
        after = call(f'/admin/report-notification-intents?jobId={job_id}', token=admin_token)
        assert after['total'] == 1, after
        audit = after['items'][0]
        assert audit['redriveRound'] == 1, audit
        assert audit['maxAttempts'] == 10, audit
        assert audit['attempts'] >= 5, audit
        assert audit['redriveAdminId'] == admin_id, audit
        assert audit['redriveReason'] == REASON, audit
        assert audit['redrivenAt'], audit

        evidence = {
            'intentId': intent_id,
            'jobId': job_id,
            'teacherUserId': seeded['teacher'],
            'studentUserId': seeded['student'],
            'classId': seeded['class_id'],
            'adminUserId': admin_id,
            'redriveReason': REASON,
            'firstRedrive': redriven,
            'postRedriveAudit': audit,
            'teacherListStatus': 403,
            'shortReasonCode': 400,
            'secondRedriveCode': 409,
        }
        output = Path(os.environ.get('OJ_TEST_OUTPUT_DIR', '/tmp/musealgo-ci-evidence'))
        output.mkdir(parents=True, exist_ok=True)
        (output / 'notification-smoke.json').write_text(
            json.dumps(evidence, indent=2, ensure_ascii=False) + '\n')
        print(json.dumps(evidence, ensure_ascii=False), flush=True)
    finally:
        compose_mysql(cleanup_sql(suffix))


if __name__ == '__main__':
    main()
