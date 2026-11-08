#!/usr/bin/env python3
"""Sample-based CSV, participantCount and VP-reference acceptance.

Run only against the disposable tests/compose.yml stack with the seeded accounts
and real Judge0. The script creates synthetic contests/registrations/VP sessions
and one synthetic user; it never touches production data or external services.

Gate command (same disposable stack as rank_smoke.py):
    OJ_TEST_API=http://127.0.0.1:<port> OJ_TEST_OUTPUT_DIR=<evidence-dir> \
        python3 tests/rank_csv_smoke.py

Run it standalone or at least 60 seconds after rank_smoke.py so the login rate
limit window (10/minute/IP) is not shared. MQ is not required for CSV checks.
"""
import csv
import datetime as dt
import io
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request

from api_smoke import AC, BASE, HTTP, call, login, wait_health

BOM = b'\xef\xbb\xbf'
OFFICIAL_HEADER = ['名次', '用户', '解题数', '罚时', '总分', 'Rating前', 'Rating后', '奖项']
GLOBAL_HEADER = ['名次', '用户', '类型', '解题数', '罚时', '总分', 'Rating前', 'Rating后', '奖项']
JUDGE_WAIT_SECONDS = 180


def fmt(value):
    return value.strftime('%Y-%m-%d %H:%M:%S')


def request(method, path, body=None, token=None, timeout=30):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = None if body is None else json.dumps(body).encode('utf-8')
    req = urllib.request.Request(BASE + path, headers=headers, data=data, method=method)
    try:
        with HTTP.open(req, timeout=timeout) as response:
            return response.status, response.read(), dict(response.headers)
    except urllib.error.HTTPError as error:
        return error.code, error.read(), dict(error.headers)


def json_result(method, path, body=None, token=None, expected_http=200):
    status, raw, _ = request(method, path, body, token)
    try:
        result = json.loads(raw)
    except ValueError as error:
        raise AssertionError((path, status, raw[:300])) from error
    if expected_http is not None:
        assert status == expected_http, (path, status, result)
    return result


def contest_detail(admin_token, contest_id):
    return call(f'/contest/{contest_id}', token=admin_token)


def create_contest(admin_token, *, mode, category='FORMAL', level_code=None, review=0, title_prefix='ci-rank-csv'):
    """Create a running contest ending in the future, so formal submissions are accepted."""
    now = dt.datetime.now()
    config = {
        'title': f'{title_prefix}-{mode.lower()}-{time.time_ns()}',
        'description': 'synthetic CSV/participantCount acceptance sample',
        'mode': mode,
        'category': category,
        'type': 'Public',
        'visible': 1,
        'startTime': fmt(now - dt.timedelta(minutes=30)),
        'endTime': fmt(now + dt.timedelta(minutes=5)),
        'registrationReview': review,
        'levelCode': level_code,
    }
    contest = call('/contest/add', config, admin_token)
    contest_id = contest['id']
    call('/api/problem-contest/add',
         {'contestId': contest_id, 'problemId': 1, 'visible': 1, 'score': 100}, admin_token)
    config['id'] = contest_id
    return contest_id, config


def end_contest(admin_token, contest_id, config):
    """End slightly in the future, then wait: submissions already made stay inside the window."""
    updated = dict(config)
    updated['endTime'] = fmt(dt.datetime.now() + dt.timedelta(seconds=1))
    call('/contest/update', updated, admin_token)
    time.sleep(3)


def finalize(admin_token, contest_id):
    """Finalize is idempotent; tolerate the scheduler winning and earlier contests settling."""
    last = None
    for _ in range(120):
        status, raw, _ = request('POST', f'/contest/{contest_id}/rank/finalize', {}, admin_token)
        try:
            result = json.loads(raw)
        except ValueError:
            raise AssertionError((contest_id, status, raw[:300]))
        if status == 200 and result.get('code') == 200:
            return result
        last = (status, result)
        message = str(result.get('message') or '')
        if any(marker in message for marker in ('尚未结束', '尚未结算', '正在判题')):
            time.sleep(2)
            continue
        raise AssertionError((contest_id, last))
    raise AssertionError((contest_id, 'finalize timeout', last))


def wait_judged(token, submission_id):
    deadline = time.monotonic() + JUDGE_WAIT_SECONDS
    while time.monotonic() < deadline:
        detail = call('/submission/detail?id=' + str(submission_id), token=token)
        status = detail.get('status')
        # 刚创建的任务可能尚未回填 status（judgePhase=SUBMITTED）；只有终态才断言。
        if detail.get('judgePhase') == 'FAILED' or status in (1, 2, 3):
            assert status == 1, (submission_id, detail)
            return detail
        time.sleep(1)
    raise AssertionError(f'Judge timed out for submission {submission_id}')


def submit_ac(token, contest_id, reader_token=None):
    print(f'[rank-csv] submit contest={contest_id} reader={"admin" if reader_token else "owner"}',
          flush=True)
    submitted = call('/submission/submit', {
        'problemId': 1,
        'contestId': contest_id,
        'language': 'cpp',
        'code': AC,
    }, token)
    return submitted['id'], wait_judged(reader_token or token, submitted['id'])


def export_csv(admin_token, contest_id, *, global_board):
    path = f'/contest/{contest_id}/rank/global/export' if global_board else f'/contest/{contest_id}/rank/export'
    status, raw, _ = request('GET', path, token=admin_token)
    assert status == 200, (path, status, raw[:200])
    assert raw.startswith(BOM), (path, raw[:3])
    text = raw.decode('utf-8-sig')
    rows = list(csv.reader(io.StringIO(text)))
    expected_header = GLOBAL_HEADER if global_board else OFFICIAL_HEADER
    assert rows and rows[0] == expected_header, (path, rows[:1])
    return {'rows': rows[1:], 'text': text}


def run_vp(token, contest_id, reader_token=None):
    started = call(f'/contest/{contest_id}/vp/start', {}, token)
    assert started.get('status') == 'RUNNING', started
    submission_id, judged = submit_ac(token, contest_id, reader_token)
    assert judged.get('status') == 1, judged
    attempt = call(f'/contest/{contest_id}/vp/finish', {}, token)
    deadline = time.monotonic() + 60
    while attempt.get('status') != 'FINISHED' and time.monotonic() < deadline:
        time.sleep(1)
        attempt = call(f'/contest/{contest_id}/vp', token=token)
    assert attempt.get('status') == 'FINISHED', attempt
    return attempt, submission_id


def main():
    wait_health()
    admin = login('admin')
    admin_token = admin['accessToken']

    students = {}
    for name in ('student01', 'student02', 'student03'):
        session = login(name)
        students[name] = session
    vp_student = login('student06')

    student01 = students['student01']
    student02 = students['student02']
    student03 = students['student03']
    student01_info = call(f"/user/info/{student01['user']['id']}", token=admin_token)
    student02_info = call(f"/user/info/{student02['user']['id']}", token=admin_token)
    infos = {'student01': student01_info, 'student02': student02_info}
    ratings = {
        'student01': int(student01_info.get('rating') or 0),
        'student02': int(student02_info.get('rating') or 0),
    }
    star_name = max(ratings, key=ratings.get)
    official2_name = min(ratings, key=ratings.get)
    assert ratings[star_name] > ratings[official2_name], (
        'synthetic sample needs distinct ratings to separate STARRED from OFFICIAL', ratings)
    star = students[star_name]
    official2 = students[official2_name]

    # A synthetic user with CSV-hostile nickname proves escaping without mutating seed profiles.
    suffix = str(time.time_ns())[-12:]
    synthetic_username = 'ci_rank_' + suffix
    synthetic_nickname = 'CI, "排名"'
    call('/auth/register', {
        'username': synthetic_username,
        'password': '123456',
        'nickname': synthetic_nickname,
        'email': synthetic_username + '@ci.invalid',
    }, None)
    synthetic = login(synthetic_username)

    # Restricted level: its Rating max (upper exclusive) makes only the highest seed rating STARRED.
    level = call('/admin/contest-levels', {
        'code': 'CI_RANK_' + suffix,
        'name': 'CI rank csv level',
        'minRating': None,
        'maxRating': ratings[star_name],
        'enabled': True,
        'sortOrder': 0,
    }, admin_token)

    # --- Main OI contest: ties, a STARRED row, a PENDING registration and two VPs. ---
    main_id, main_config = create_contest(
        admin_token, mode='OI', level_code=level['code'], review=1, title_prefix='ci-rank-main')
    for session in (synthetic, star, official2, student03):
        call(f'/contest/register/{main_id}', {}, session['accessToken'])
    registrations = call(f'/contest/{main_id}/registrations?page=1&size=20', token=admin_token)['records']
    by_user = {row['userId']: row for row in registrations}
    assert len(registrations) == 4, registrations
    assert by_user[synthetic['user']['id']]['status'] == 'PENDING', by_user
    assert by_user[star['user']['id']]['eligible'] == 0, by_user
    assert by_user[official2['user']['id']]['eligible'] == 1, by_user
    for session in (synthetic, star, official2):
        call(f"/contest/{main_id}/registrations/{session['user']['id']}/review",
             {'approve': True, 'reason': None}, admin_token)
    reviewed = call(f'/contest/{main_id}/registrations?page=1&size=20', token=admin_token)['records']
    reviewed_by_user = {row['userId']: row for row in reviewed}
    pending_user_id = student03['user']['id']
    assert reviewed_by_user[pending_user_id]['status'] == 'PENDING', reviewed_by_user
    for session in (synthetic, star, official2):
        assert reviewed_by_user[session['user']['id']]['status'] == 'APPROVED', reviewed_by_user
    assert contest_detail(admin_token, main_id)['participantCount'] == 4

    main_submissions = []
    for session in (synthetic, official2, star):
        # OI 赛中学生会看到脱敏详情；管理员读取真实判题终态。
        submission_id, judged = submit_ac(session['accessToken'], main_id, admin_token)
        assert judged.get('status') == 1, judged
        main_submissions.append(submission_id)
    end_contest(admin_token, main_id, main_config)
    finalize(admin_token, main_id)
    assert contest_detail(admin_token, main_id)['rankFinalizedAt'], 'main contest not finalized'

    official_csv = export_csv(admin_token, main_id, global_board=False)
    official_ranks = [row[0] for row in official_csv['rows']]
    official_names = {row[1] for row in official_csv['rows']}
    assert len(official_csv['rows']) == 2, official_csv
    assert official_ranks == ['1', '1'], official_ranks
    assert official_names == {synthetic_nickname, infos[official2_name]['nickname']}, official_names
    assert '"CI, ""排名"""' in official_csv['text'], official_csv['text']
    assert all(row[7] == '' for row in official_csv['rows']), official_csv['rows']

    official_final_before_vp = call(f'/contest/{main_id}/rank/final', token=admin_token)
    vp_attempts = []
    for session in (student03, vp_student):
        attempt, submission_id = run_vp(session['accessToken'], main_id, admin_token)
        assert attempt.get('rankBasis') == 'OFFICIAL_REFERENCE', attempt
        # Two OFFICIAL rows both scored 100; the VP reference rank is 1/3, not a global position.
        assert attempt.get('rankNum') == 1, attempt
        assert attempt.get('totalParticipants') == 3, attempt
        assert attempt.get('score') == 100, attempt
        vp_attempts.append({'attempt': attempt, 'submissionId': submission_id})
    assert call(f'/contest/{main_id}/rank/final', token=admin_token) == official_final_before_vp
    assert contest_detail(admin_token, main_id)['participantCount'] == 4, 'VP must not change participantCount'

    global_csv = export_csv(admin_token, main_id, global_board=True)
    global_rows = global_csv['rows']
    assert len(global_rows) == 5, global_rows
    assert [row[0] for row in global_rows] == ['1', '2', '3', '4', '5'], global_rows
    types = [row[2] for row in global_rows]
    assert types.count('OFFICIAL') == 2 and types.count('STARRED') == 1 and types.count('VP') == 2, types
    for row in global_rows:
        assert row[8] == '', ('award must stay manual/empty', row)
        if row[2] == 'VP':
            assert row[6] == '' and row[7] == '', ('VP must not receive Rating', row)

    # --- Entertainment contest: official board/CSV empty, global CSV keeps participants. ---
    fun_id, fun_config = create_contest(admin_token, mode='ACM', category='ENTERTAINMENT',
                                        title_prefix='ci-rank-fun')
    fun_submission, fun_judged = submit_ac(synthetic['accessToken'], fun_id, admin_token)
    assert fun_judged.get('status') == 1, fun_judged
    end_contest(admin_token, fun_id, fun_config)
    finalize(admin_token, fun_id)
    assert contest_detail(admin_token, fun_id)['participantCount'] == 1
    assert export_csv(admin_token, fun_id, global_board=False)['rows'] == []
    fun_global = export_csv(admin_token, fun_id, global_board=True)
    assert len(fun_global['rows']) == 1 and fun_global['rows'][0][2] == 'OFFICIAL', fun_global
    assert fun_global['rows'][0][6] == '' and fun_global['rows'][0][7] == '', fun_global

    # --- Empty board: both CSVs contain headers only. ---
    empty_id, empty_config = create_contest(admin_token, mode='ACM', title_prefix='ci-rank-empty')
    end_contest(admin_token, empty_id, empty_config)
    finalize(admin_token, empty_id)
    assert contest_detail(admin_token, empty_id)['participantCount'] == 0
    assert export_csv(admin_token, empty_id, global_board=False)['rows'] == []
    assert export_csv(admin_token, empty_id, global_board=True)['rows'] == []

    # --- Running OI: student board access and CSV export are denied; admin export works. ---
    oi_id, oi_config = create_contest(admin_token, mode='OI', title_prefix='ci-rank-oi-live')
    for board in ('list', 'global/list'):
        hidden = json_result('GET', f'/contest/{oi_id}/rank/{board}', token=student02['accessToken'])
        assert hidden.get('code') == 403, (board, hidden)
    admin_official = json_result('GET', f'/contest/{oi_id}/rank/list', token=admin_token)
    admin_global = json_result('GET', f'/contest/{oi_id}/rank/global/list', token=admin_token)
    assert admin_official.get('code') == 200 and admin_global.get('code') == 200, (admin_official, admin_global)
    for path in (f'/contest/{oi_id}/rank/export', f'/contest/{oi_id}/rank/global/export'):
        status, raw, _ = request('GET', path, token=student02['accessToken'])
        assert status == 403, (path, status, raw[:200])
        assert not raw.startswith(BOM), (path, 'CSV leaked to student', raw[:3])
    assert export_csv(admin_token, oi_id, global_board=False)['rows'] == []
    assert export_csv(admin_token, oi_id, global_board=True)['rows'] == []
    # Clean up the synthetic live contest so a later Gate step is not blocked by an unsettled row.
    end_contest(admin_token, oi_id, oi_config)
    finalize(admin_token, oi_id)

    evidence = {
        'synthetic_only': True,
        'contest_ids': {
            'main_oi': main_id,
            'entertainment': fun_id,
            'empty': empty_id,
            'running_oi': oi_id,
        },
        'synthetic_user': {'username': synthetic_username, 'nickname': synthetic_nickname},
        'main': {
            'participant_count': 4,
            'registration_statuses': {
                'synthetic': 'APPROVED',
                'starred': star_name,
                'official2': official2_name,
                'pending': 'student03',
            },
            'official_csv_rows': official_csv['rows'],
            'official_tie_ranks': official_ranks,
            'global_csv_rows': global_rows,
            'global_types': types,
            'submissions': main_submissions,
            'vp_attempts': vp_attempts,
            'official_snapshot_unchanged_by_vp': True,
        },
        'entertainment': {
            'participant_count': 1,
            'submission_id': fun_submission,
            'official_csv_rows': 0,
            'global_rows': len(fun_global['rows']),
            'rating_columns_blank': True,
        },
        'empty_board': {'official_rows': 0, 'global_rows': 0, 'header_only': True},
        'running_oi': {
            'student_board_code': 403,
            'student_csv_status': 403,
            'admin_csv_header_only': True,
        },
        'awards': 'manual only; no automatic award values observed',
    }
    output = Path(os.environ.get('OJ_TEST_OUTPUT_DIR', '/tmp/musealgo-ci-evidence'))
    output.mkdir(parents=True, exist_ok=True)
    (output / 'rank-csv-smoke.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n',
                                                encoding='utf-8')
    print(json.dumps({'csv_rows': {'official': len(official_csv['rows']), 'global': len(global_rows)},
                      'vp_attempts': len(vp_attempts), 'contest_ids': evidence['contest_ids']},
                     ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
