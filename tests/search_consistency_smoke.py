#!/usr/bin/env python3
"""Small real-MySQL/Meilisearch regression for stale index visibility and paging.

Only run against a disposable seeded stack. Deliberately adds stale index rows.
"""
import json
import os
import secrets
import time
import urllib.parse
import urllib.request
from modules_smoke import HTTP, Smoke

def main():
    s = Smoke('search-consistency', 'search-consistency')
    meili = os.environ['OJ_TEST_MEILI'].rstrip('/')
    def direct(path, body=None):
        req = urllib.request.Request(meili + path, data=None if body is None else json.dumps(body).encode(),
              headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + os.environ['OJ_TEST_MEILI_KEY']})
        with HTTP.open(req, timeout=10) as r: return json.load(r)
    def settle():
        for _ in range(60):
            if not direct('/tasks?statuses=enqueued,processing&limit=1')['results']: break
            time.sleep(.25)
        else: raise AssertionError('index task timeout')
        assert not direct('/tasks?statuses=failed&limit=10')['results']
    def search(q, **filters):
        return s.ok('/problem/search?' + urllib.parse.urlencode({'keyword': q, 'size': 20, **filters}))
    def create(title, public=True, difficulty=800):
        return s.ok('/problem/add', {'title': title, 'content': 'Synthetic search consistency fixture',
                    'difficulty': difficulty, 'isPublic': public, 'timeLimit': 1000, 'memoryLimit': 128}, 'admin')
    try:
        s.login()
        token = ''.join(chr(ord('a') + int(c, 16)) for c in secrets.token_hex(4))
        stem = 'Orchard' + token
        first = create(stem + ' Alpha')
        private = create(stem + ' Invisible' + token, False)
        second = create(stem + ' Beta')
        deleted = create(stem + ' Removed' + token)
        changed = create(stem + ' Changed' + token)
        s.ok('/problem/delete/' + str(deleted), {}, 'admin')
        s.ok('/problem/update', {'id': changed, 'difficulty': 1600}, 'admin')
        settle()
        missing = 9000000000000 + secrets.randbelow(1000000)
        stale = [
            {'id': private, 'title': stem + ' Invisible' + token, 'difficulty': 800, 'isPublic': True},
            {'id': deleted, 'title': stem + ' Removed' + token, 'difficulty': 800, 'isPublic': True},
            {'id': missing, 'title': stem + ' Missing' + token, 'difficulty': 800, 'isPublic': True},
            {'id': changed, 'title': stem + ' Changed' + token, 'difficulty': 800, 'isPublic': True}]
        direct('/indexes/problems/documents', stale); settle()
        query = stem.replace('Orchard', 'Orchrd')
        raw = direct('/indexes/problems/search', {'q': query, 'filter': 'isPublic = true', 'limit': 20})
        raw_ids = [h['id'] for h in raw['hits']]
        assert set(raw_ids) == {first, second, private, deleted, missing, changed}, raw
        expected = [i for i in raw_ids if i in {first, second, changed}]
        s.result['fixtures'] = {'public_ids': [first, second, changed], 'private': private, 'deleted': deleted,
                                'missing': missing, 'raw_index_order': raw_ids, 'expected_visible_order': expected}
        def cross_page():
            actual = []
            for n in range(1, 5):
                page = search(query, page=n, size=1)
                assert page['total'] == 3, page
                assert len(page['records']) == (1 if n <= 3 else 0), page
                actual.extend(p['id'] for p in page['records'])
            assert actual == expected, (actual, expected)
            s.result['cross_page'] = {'raw_total': len(raw_ids), 'reported_total': 3, 'returned_order': actual}
        s.case('private/deleted/missing hits on other pages do not count or create page holes', cross_page)
        def only_hidden(word):
            page = search(word + token)
            assert page['records'] == [] and page['total'] == 0, page
        s.case('private-only keyword has zero records and total', lambda: only_hidden('Invisible'))
        s.case('deleted-only keyword has zero records and total', lambda: only_hidden('Removed'))
        s.case('missing-row keyword has zero records and total', lambda: only_hidden('Missing'))
        def difficulty():
            for args in [{'difficulty': 800}, {'minDifficulty': 700, 'maxDifficulty': 900},
                         {'difficulty': 800, 'minDifficulty': 900, 'maxDifficulty': 1000}]:
                page = search(query, **args)
                assert page['total'] == 2 and {p['id'] for p in page['records']} == {first, second}, page
        s.case('current database difficulty excludes a stale difficulty hit, exact filter has precedence', difficulty)
        def beyond():
            page = search(query, page=99, size=1)
            assert page['records'] == [] and page['total'] == 3, page
        s.case('empty page keeps the database-visible total', beyond)
        def repair():
            lagged = search('Changed' + token, difficulty=1600)
            assert lagged['records'] == [] and lagged['total'] == 0, lagged
            s.ok('/problem/admin/search/reindex', who='admin'); settle()
            page = search(query)
            assert page['total'] == 3 and {p['id'] for p in page['records']} == {first, second, changed}, page
            assert private not in {p['id'] for p in page['records']}
            repaired = search('Changed' + token, difficulty=1600)
            assert repaired['total'] == 1 and [p['id'] for p in repaired['records']] == [changed], repaired
            s.result['index_lag_observation'] = {'reverse_difficulty_total_before_reindex': 0,
                    'after_reindex': 1, 'boundary': 'database validation cannot recover candidates excluded by stale index filters'}
        s.case('explicit rebuild preserves corrected count and visibility', repair)
    except Exception as e:
        s.result['fatal_error'] = str(e)[:1200]
        raise
    finally:
        passed = s.save('search-consistency')
    raise SystemExit(0 if passed else 1)

if __name__ == '__main__': main()
