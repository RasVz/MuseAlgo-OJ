#!/usr/bin/env python3
"""Real Meilisearch functional checks, including explicit outage/recovery phases."""
import argparse
import json
import os
from pathlib import Path
import secrets
import time
import urllib.parse
import urllib.request
from modules_smoke import HTTP, Smoke

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=['normal', 'outage', 'recovery'])
    parser.add_argument('--label', default='search-after')
    args = parser.parse_args()
    s = Smoke('search-' + args.phase, args.label)
    s.login()
    meili = os.environ['OJ_TEST_MEILI'].rstrip('/')
    def direct(path, body=None):
        request = urllib.request.Request(meili + path,
                data=None if body is None else json.dumps(body).encode(),
                headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + os.environ['OJ_TEST_MEILI_KEY']})
        with HTTP.open(request, timeout=10) as r: return json.load(r)
    def settle():
        for _ in range(60):
            if not direct('/tasks?statuses=enqueued,processing&limit=1')['results']: break
            time.sleep(.25)
        else: raise AssertionError('Meilisearch tasks did not settle')
        failed = direct('/tasks?statuses=failed&limit=10')['results']
        assert not failed, failed
    def search(keyword, **filters):
        return s.ok('/problem/search?' + urllib.parse.urlencode({'keyword': keyword, 'size': 20, **filters}))
    def ids(page): return {p['id'] for p in page['records']}
    def create(title, public=True, difficulty=800):
        return s.ok('/problem/add', {'title': title, 'content': 'Synthetic module problem', 'inputDesc': 'none',
                         'outputDesc': 'none', 'difficulty': difficulty, 'isPublic': public,
                         'timeLimit': 1000, 'memoryLimit': 128, 'tags': '["Array"]'}, 'admin')
    fpath = s.out/'search-fixtures.json'
    try:
        if args.phase == 'normal':
            keyword = 'Orchard' + ''.join(chr(ord('a') + int(c,16)) for c in secrets.token_hex(4))
            first = create(keyword + ' Alpha')
            second = create(keyword + ' Beta', difficulty=1200)
            private = create(keyword + ' Secret', public=False)
            f = {'keyword': keyword, 'first': first, 'second': second, 'private': private}
            fpath.write_text(json.dumps(f))
            settle()
            s.result['real_index_stats'] = direct('/indexes/problems/stats')
            def exact():
                page = search(keyword)
                assert ids(page) == {first, second} and page['total'] == 2, page
            s.case('real index search returns public records and accurate total', exact)
            def typo():
                typo = keyword.replace('Orchard', 'Orchrd')
                page = search(typo)
                assert ids(page) == {first, second}, page
                assert direct('/indexes/problems/search', {'q': typo, 'filter': 'isPublic = true'})['hits']
            s.case('typo query proves real full-text path rather than SQL fallback', typo)
            def filters():
                assert ids(search(keyword, difficulty=800)) == {first}
                assert ids(search(keyword, minDifficulty=1000, maxDifficulty=1300)) == {second}
                assert not search(keyword, minDifficulty=1500)['records']
            s.case('exact and range difficulty filters', filters)
            def pagination():
                one, two = search(keyword, page=1, size=1), search(keyword, page=2, size=1)
                assert one['total'] == two['total'] == 2 and len(one['records']) == len(two['records']) == 1
                assert ids(one).isdisjoint(ids(two)) and ids(one) | ids(two) == {first, second}
                beyond = search(keyword, page=99, size=1)
                assert beyond['records'] == [] and beyond['total'] == 2, beyond
            s.case('search pagination preserves total and nonoverlapping results', pagination)
            s.case('student cannot rebuild search index', lambda: s.denied('/problem/admin/search/reindex', who='student01'))
            def update():
                s.ok('/problem/update', {'id': first, 'title': keyword + ' Updated', 'isPublic': True, 'difficulty': 800}, 'admin')
                settle()
                assert first in ids(search(keyword + ' Updated'))
                assert direct('/indexes/problems/documents/' + str(first))['title'] == keyword + ' Updated'
            s.case('problem update reaches real search index', update)
            def stale_guard():
                direct('/indexes/problems/documents', [{'id': private, 'title': keyword + ' Secret', 'difficulty': 800, 'isPublic': True}])
                settle()
                page = search(keyword)
                assert private not in ids(page), 'stale private index leaked a database-private row'
                assert page['total'] == len(page['records']) == 2, page
                s.result['stale_index_observation'] = {'database_visible_total': page['total'], 'visible_records': len(page['records'])}
            s.case('database privacy guard rejects deliberately stale public index entry', stale_guard)
            def reindex():
                s.ok('/problem/admin/search/reindex', who='admin'); settle()
                assert direct('/indexes/problems/documents/' + str(private))['isPublic'] is False
            s.case('admin rebuild repairs stale index', reindex)
            def delete():
                s.ok('/problem/delete/' + str(second), {}, 'admin'); settle()
                assert second not in ids(search(keyword))
                assert second not in {h['id'] for h in direct('/indexes/problems/search', {'q': keyword + ' Beta'})['hits']}
            s.case('delete removes database and index results', delete)
            f['title'] = keyword + ' Updated'; fpath.write_text(json.dumps(f))
            s.result['browser_fixture'] = {'keyword': keyword, 'title': f['title']}
        elif args.phase == 'outage':
            f = json.loads(fpath.read_text())
            def unavailable():
                try:
                    with HTTP.open(meili + '/health', timeout=2): pass
                except urllib.error.HTTPError as e:
                    raise AssertionError('Meilisearch still reachable: HTTP ' + str(e.code)) from e
                except (urllib.error.URLError, TimeoutError, ConnectionError):
                    return {'direct_health_unreachable': True}
                raise AssertionError('Meilisearch is still running; outage precondition failed')
            s.case('direct health probe confirms Meilisearch is stopped', unavailable)
            assert s.result['cases'][-1]['passed'], 'outage precondition failed'
            def fallback():
                for _ in range(4):
                    assert ids(search(f['keyword'])) == {f['first']}
            s.case('stopped Meilisearch falls back to SQL with private/deleted filtering', fallback)
            def write():
                f['outage_id'] = create(f['keyword'] + ' OutageWrite')
                fpath.write_text(json.dumps(f))
                assert f['outage_id'] in ids(search(f['keyword']))
            s.case('search outage does not reject problem writes', write)
        else:
            f = json.loads(fpath.read_text())
            assert direct('/health')['status'] == 'available'
            def restore():
                s.ok('/problem/admin/search/reindex', who='admin'); settle()
                page = search(f['keyword'].replace('Orchard', 'Orchrd'))
                assert ids(page) == {f['first'], f['outage_id']}, page
            s.case('service recovery plus explicit reindex restores full-text and outage writes', restore)
            s.result['browser_fixture'] = {'keyword': f['keyword'], 'title': f['title']}
    except Exception as e:
        s.result['fatal_error'] = str(e)[:1200]
        raise
    finally:
        passed = s.save(args.label)
    raise SystemExit(0 if passed else 1)

if __name__ == '__main__': main()
