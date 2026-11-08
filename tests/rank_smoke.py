#!/usr/bin/env python3
"""Real HTTP/SSE rank convergence checks for the disposable CI compose stack.

Run with OJ_TEST_MQ_MODE=disabled after starting tests/compose.yml. This script
creates only synthetic contests and submissions in that disposable stack.
"""
import datetime as dt
import json
import os
from pathlib import Path
import queue
import threading
import time
import urllib.error
import urllib.request

from api_smoke import AC, BASE, HTTP, call, login, wait_health


INVALIDATION_WAIT_SECONDS = 20
JUDGE_WAIT_SECONDS = 180


def fmt(value):
    return value.strftime('%Y-%m-%d %H:%M:%S')


def create_contest(admin_token, mode, *, end_time):
    now = dt.datetime.now()
    name = f'ci-rank-{mode.lower()}-{time.time_ns()}'
    config = {
        'title': name,
        'mode': mode,
        'type': 'Public',
        'visible': 1,
        'startTime': fmt(now - dt.timedelta(minutes=2)),
        'endTime': fmt(end_time),
        'duration': 120,
    }
    contest = call('/contest/add', config, admin_token)
    contest_id = contest['id']
    call('/api/problem-contest/add', {
        'contestId': contest_id,
        'problemId': 1,
        'visible': 1,
        'score': 100,
    }, admin_token)
    return contest_id, config


def request_stream(path, token=None, timeout=8):
    headers = {'Accept': 'text/event-stream'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(BASE + path, headers=headers)
    try:
        response = HTTP.open(request, timeout=timeout)
    except urllib.error.HTTPError as error:
        body = error.read()
        return error.code, body, None

    try:
        first_event = read_next_event(response)
        return response.status, b'', first_event
    finally:
        response.close()


def read_next_event(response):
    event_name = 'message'
    data_lines = []
    while True:
        raw_line = response.readline()
        if not raw_line:
            return None
        line = raw_line.decode('utf-8').rstrip('\r\n')
        if line == '':
            if data_lines:
                return {'event': event_name or 'message', 'data': '\n'.join(data_lines)}
            event_name = 'message'
            data_lines = []
            continue
        if line.startswith(':'):
            continue
        field, separator, value = line.partition(':')
        if separator and value.startswith(' '):
            value = value[1:]
        if field == 'event':
            event_name = value
        elif field == 'data':
            data_lines.append(value)


class SseProbe:
    """A bounded-lifetime real HTTP client that records invalidation frames."""

    def __init__(self, path, token):
        self.path = path
        self.token = token
        self.headers_ready = threading.Event()
        self.connected = threading.Event()
        self.stop_requested = threading.Event()
        self.invalidations = queue.Queue()
        self.failures = queue.Queue()
        self.response = None
        self.status = None
        self.thread = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self.thread.start()
        if not self.headers_ready.wait(10):
            raise AssertionError(f'SSE connection did not produce connected: {self.path}')
        if self.status != 200:
            raise AssertionError(f'SSE connection returned HTTP {self.status}: {self.path}')
        if not self.connected.wait(10):
            raise AssertionError(f'SSE stream did not send connected event: {self.path}')
        return self

    def _run(self):
        request = urllib.request.Request(BASE + self.path, headers={
            'Accept': 'text/event-stream',
            'Authorization': 'Bearer ' + self.token,
        })
        try:
            with HTTP.open(request, timeout=60) as response:
                self.response = response
                self.status = response.status
                self.headers_ready.set()
                while not self.stop_requested.is_set():
                    event = read_next_event(response)
                    if event is None:
                        break
                    if event['event'] == 'connected':
                        self.connected.set()
                    elif event['event'] == 'ranking-invalidated':
                        self.invalidations.put(event['data'])
        except urllib.error.HTTPError as error:
            self.status = error.code
            self.failures.put(error)
            self.headers_ready.set()
        except Exception as error:  # surfaced to the caller through failures
            if not self.stop_requested.is_set():
                self.failures.put(error)
        finally:
            self.headers_ready.set()

    def drain(self):
        drained = 0
        while True:
            try:
                self.invalidations.get_nowait()
                drained += 1
            except queue.Empty:
                return drained

    def wait_invalidation(self, expected_board, timeout=INVALIDATION_WAIT_SECONDS):
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise AssertionError(f'No {expected_board} SSE invalidation within {timeout}s')
            try:
                failure = self.failures.get_nowait()
            except queue.Empty:
                failure = None
            if failure:
                raise AssertionError(f'SSE stream failed: {failure}') from failure
            try:
                board = self.invalidations.get(timeout=min(remaining, 0.2))
            except queue.Empty:
                continue
            if board != expected_board:
                raise AssertionError(f'Expected {expected_board} invalidation, got {board!r}')
            return board

    def close(self):
        self.stop_requested.set()
        if self.response is not None:
            self.response.close()
        self.thread.join(timeout=1)


def assert_connected(path, token):
    status, body, event = request_stream(path, token)
    assert status == 200, (path, status, body)
    assert event == {'event': 'connected', 'data': 'ok'}, (path, event)


def assert_denied(path, token, expected_status, *, bodyless=False):
    status, body, event = request_stream(path, token)
    assert status == expected_status, (path, status, body, event)
    if bodyless:
        assert body == b'', (path, body)
    assert event is None, (path, event)


def wait_for_judged(token, submission_id):
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


def assert_user_row(rows, user_id, participant_type=None):
    row = next((item for item in rows if item.get('userId') == user_id), None)
    assert row is not None, (user_id, rows)
    if participant_type:
        assert row.get('participantType') == participant_type, row
    return row


def main():
    mq_mode = os.environ.get('OJ_TEST_MQ_MODE', 'disabled')
    assert mq_mode == 'disabled', f'rank_smoke.py requires MQ disabled, got {mq_mode!r}'
    wait_health()
    admin = login('admin')
    student = login('student06')
    vp_student = login('student07')
    admin_token = admin['accessToken']
    student_token = student['accessToken']
    vp_token = vp_student['accessToken']
    student_id = student['user']['id']
    vp_student_id = vp_student['user']['id']

    now = dt.datetime.now()
    live_contest_id, live_config = create_contest(
        admin_token, 'ACM', end_time=now + dt.timedelta(minutes=8))
    oi_contest_id, _ = create_contest(
        admin_token, 'OI', end_time=now + dt.timedelta(minutes=8))
    routes = ('official', 'global')
    route_paths = {
        'official': f'/contest/{live_contest_id}/ranking/stream',
        'global': f'/contest/{live_contest_id}/ranking/global/stream',
    }
    oi_paths = {
        'official': f'/contest/{oi_contest_id}/ranking/stream',
        'global': f'/contest/{oi_contest_id}/ranking/global/stream',
    }

    controls = []
    for board in routes:
        assert_denied(route_paths[board], None, 401)
        controls.append({'board': board, 'anonymous': 401})
        assert_connected(route_paths[board], student_token)
        controls.append({'board': board, 'acm_student': 200})
        assert_denied(oi_paths[board], student_token, 403, bodyless=True)
        controls.append({'board': board, 'oi_student': 403, 'body_bytes': 0})
        assert_connected(oi_paths[board], admin_token)
        controls.append({'board': board, 'oi_admin': 200})

    official_probe = SseProbe(route_paths['official'], student_token).start()
    global_probe = SseProbe(route_paths['global'], student_token).start()
    vp_probe = None
    try:
        official_before = call(f'/contest/{live_contest_id}/rank/list', token=admin_token)
        global_before = call(f'/contest/{live_contest_id}/rank/global/list', token=admin_token)
        assert not any(row.get('userId') == student_id for row in official_before), official_before
        assert not any(row.get('userId') == student_id for row in global_before), global_before

        # Judge a real submission while MQ consumption is disabled. Clear any
        # earlier periodic frames, then require the independent resync timer.
        official_probe.drain()
        global_probe.drain()
        submission = call('/submission/submit', {
            'problemId': 1,
            'contestId': live_contest_id,
            'language': 'cpp',
            'code': AC,
        }, student_token)
        judged = wait_for_judged(student_token, submission['id'])
        assert judged.get('status') == 1
        official_probe.drain()
        global_probe.drain()
        official_probe.wait_invalidation('official')
        global_probe.wait_invalidation('global')
        official_after = call(f'/contest/{live_contest_id}/rank/list', token=admin_token)
        global_after = call(f'/contest/{live_contest_id}/rank/global/list', token=admin_token)
        assert_user_row(official_after, student_id, 'OFFICIAL')
        assert_user_row(global_after, student_id, 'OFFICIAL')

        # Finalize a real contest and then finish a real VP attempt. The final
        # official snapshot must stay byte-for-byte stable while the global GET
        # acquires a VP row after a separately subscribed global invalidation.
        live_config.update(id=live_contest_id, endTime=fmt(dt.datetime.now() - dt.timedelta(seconds=1)))
        call('/contest/update', live_config, admin_token)
        call(f'/contest/{live_contest_id}/rank/finalize', {}, admin_token)
        official_final_before_vp = call(f'/contest/{live_contest_id}/rank/final', token=admin_token)
        vp_probe = SseProbe(route_paths['global'], vp_token).start()
        call(f'/contest/{live_contest_id}/vp/start', {}, vp_token)
        time.sleep(11)
        vp_submission = call('/submission/submit', {
            'problemId': 1,
            'contestId': live_contest_id,
            'language': 'cpp',
            'code': AC,
        }, vp_token)
        wait_for_judged(vp_token, vp_submission['id'])
        vp_probe.drain()
        attempt = call(f'/contest/{live_contest_id}/vp/finish', {}, vp_token)
        if attempt.get('status') != 'FINISHED':
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                attempt = call(f'/contest/{live_contest_id}/vp', token=vp_token)
                if attempt.get('status') == 'FINISHED':
                    break
                time.sleep(1)
        assert attempt.get('status') == 'FINISHED', attempt
        # The direct VP transaction event may already have arrived; waiting for
        # the next periodic event proves convergence remains available offline.
        vp_probe.drain()
        vp_probe.wait_invalidation('global')
        global_after_vp = call(f'/contest/{live_contest_id}/rank/global/list', token=admin_token)
        official_final_after_vp = call(f'/contest/{live_contest_id}/rank/final', token=admin_token)
        assert_user_row(global_after_vp, vp_student_id, 'VP')
        assert official_final_after_vp == official_final_before_vp, (
            official_final_before_vp, official_final_after_vp)
    finally:
        official_probe.close()
        global_probe.close()
        if vp_probe is not None:
            vp_probe.close()

    evidence = {
        'mq_mode': mq_mode,
        'contest_ids': {'live': live_contest_id, 'oi': oi_contest_id},
        'stream_controls': controls,
        'mq_off': {
            'judged_submission_id': submission['id'],
            'official_rows_before': len(official_before),
            'official_rows_after': len(official_after),
            'global_rows_before': len(global_before),
            'global_rows_after': len(global_after),
            'official_invalidation': True,
            'global_invalidation': True,
        },
        'vp_finish': {
            'attempt_id': attempt.get('id'),
            'submission_id': vp_submission['id'],
            'global_vp_row': True,
            'official_final_unchanged': True,
            'global_resync_invalidation': True,
        },
    }
    output_dir = Path(os.environ.get('OJ_TEST_OUTPUT_DIR', '/tmp/musealgo-ci-evidence'))
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / 'rank-smoke.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence), flush=True)


if __name__ == '__main__':
    main()
