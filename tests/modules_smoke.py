#!/usr/bin/env python3
"""Sequential community/courseware/search checks against a disposable seeded stack.

OJ_TEST_API and OJ_TEST_OUTPUT_DIR are required. Never use real user data.
Search checks require a real Meilisearch instance; courseware builds real Slidev.
"""
import argparse
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request

HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))

class Smoke:
    def __init__(self, phase, label):
        self.base = os.environ['OJ_TEST_API'].rstrip('/')
        self.out = Path(os.environ['OJ_TEST_OUTPUT_DIR'])
        self.out.mkdir(parents=True, exist_ok=True)
        self.result = {'phase': phase, 'label': label, 'cases': [], 'requests': []}
        self.prefix = 'modules-' + str(time.time_ns())
        self.sessions = {}

    def request(self, path, body=None, who=None, raw=None, content_type=None):
        headers = {'Content-Type': content_type or 'application/json'}
        if who:
            headers['Authorization'] = 'Bearer ' + self.sessions[who]['accessToken']
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self.base + path, data=data, headers=headers)
        try:
            with HTTP.open(req, timeout=150) as r:
                status, value = r.status, json.load(r)
        except urllib.error.HTTPError as e:
            status, value = e.code, json.load(e)
        # Authentication responses contain tokens and are deliberately excluded.
        if path != '/auth/login':
            self.result['requests'].append({'path': path, 'http': status, 'response': value})
        return value

    def ok(self, path, body=None, who=None, **kwargs):
        r = self.request(path, body, who, **kwargs)
        assert r.get('code') == 200, (path, r)
        return r.get('data')

    def denied(self, path, body=None, who=None, **kwargs):
        r = self.request(path, body, who, **kwargs)
        assert r.get('code') in (400, 401, 403, 404, 500), (path, r)
        assert r.get('data') is None, (path, r)
        if r['code'] == 500:
            assert r.get('message') in {'不能关注自己', '文章不存在', '无权修改他人的动态', '无权删除他人的动态', '父评论不存在或不属于当前讨论区', '评论对象类型与内容类型不匹配', '该课件已添加到班级', '课件不存在'}, (path, r)
        return r

    def case(self, name, run):
        try:
            details = run()
            self.result['cases'].append({'name': name, 'passed': True, 'details': details})
            print('PASS', name, flush=True)
        except Exception as e:
            self.result['cases'].append({'name': name, 'passed': False, 'error': str(e)[:1200]})
            print('FAIL', name, str(e)[:160], flush=True)

    def login(self):
        for who in ('student01', 'student02', 'teacher01', 'teacher02', 'admin'):
            self.sessions[who] = self.ok('/auth/login', {'username': who, 'password': '123456'})

    def uid(self, who):
        return self.sessions[who]['user']['id']

    def post(self, suffix, privacy=0, **extra):
        title = self.prefix + suffix
        self.ok('/post/add', {'title': title, 'content': 'Synthetic functional content', 'type': 0,
                              'category': 'question', 'privacy': privacy, 'tags': '', **extra}, 'student01')
        records = self.ok('/post/my?size=100', who='student01')['records']
        return next(p['id'] for p in records if p['title'] == title)

    def classroom(self):
        self.ok('/class/create', {'name': self.prefix, 'teacherId': self.uid('teacher01'), 'description': 'Synthetic module tests'}, 'teacher01')
        cid = next(c['id'] for c in self.ok('/class/list?teacherId=' + str(self.uid('teacher01')), who='teacher01') if c['name'] == self.prefix)
        invite = self.ok('/class/inviteCode?classId=' + str(cid), who='teacher01')
        self.ok('/class/joinByCode', {'code': invite}, 'student01')
        return cid

    def community(self):
        pub = self.post('-public', authorId=self.uid('student02'), isOfficial=1, isTop=1)
        private = self.post('-private', privacy=1)
        other = self.post('-other')
        self.result['fixtures'] = {'public': pub, 'private': private, 'other': other, 'prefix': self.prefix}
        def publish_identity():
            p = self.ok('/post/' + str(pub))
            assert p['authorId'] == self.uid('student01') and p['isOfficial'] == 0 and p['isTop'] == 0, p
        self.case('publish uses authenticated author and controlled editorial flags', publish_identity)
        def create_counters():
            pid = self.post('-forged-counters', favorites=999, dislikes=999, score=999, isDeleted=1)
            p = self.ok('/post/' + str(pid), who='student01')
            assert p['favoriteCount'] == p['dislikeCount'] == 0
        self.case('publish cannot forge counters or soft-delete state', create_counters)
        self.case('anonymous cannot publish', lambda: self.denied('/post/add', {'title': 'anonymous', 'content': 'x'}))
        self.case('non-owner cannot edit', lambda: self.denied('/post/update', {'id': pub, 'title': 'forbidden'}, 'student02'))
        self.case('non-owner cannot delete', lambda: self.denied('/post/delete/' + str(pub), {}, 'student02'))
        def private_hidden():
            for who in (None, 'student02'):
                self.denied('/post/' + str(private), who=who)
                records = self.ok('/post/list?keyword=' + self.prefix, who=who)['records']
                assert private not in [p['id'] for p in records]
            self.ok('/post/' + str(private), who='student01')
        self.case('private post excluded from public list and detail', private_hidden)
        self.ok('/comment/add', {'entityId': private, 'entityType': 1, 'content': 'private comment marker'}, 'student01')
        private_comment = self.ok('/comment/list?entityId=' + str(private), who='student01')[0]['id']
        self.case('private comment cannot be liked by outsiders', lambda: self.denied('/comment/like?commentId=' + str(private_comment) + '&active=true', {}, 'student02'))
        self.case('private comments cannot be read by outsiders', lambda: self.denied('/comment/list?entityId=' + str(private), who='student02'))
        self.case('private comments cannot be written by outsiders', lambda: self.denied('/comment/add', {'entityId': private, 'entityType': 1, 'content': 'forbidden'}, 'student02'))
        self.case('private post cannot be liked by outsiders', lambda: self.denied('/post/' + str(private) + '/like?active=true', {}, 'student02'))
        self.case('private post cannot be collected by outsiders', lambda: self.denied('/favorite/add', {'targetId': private, 'type': 'post'}, 'student02'))
        def like_cycle():
            for _ in range(2):
                stats = self.ok(f'/post/{pub}/like?active=true', {}, 'student02')
                assert stats['likeCount'] == 1 and stats['isLiked'], stats
            stats = self.ok(f'/post/{pub}/dislike?active=true', {}, 'student02')
            assert stats['dislikeCount'] == 1, stats
            for _ in range(2):
                stats = self.ok(f'/post/{pub}/dislike?active=false', {}, 'student02')
                assert stats['dislikeCount'] == 0, stats
        self.case('like/dislike explicit repeats keep counts correct', like_cycle)
        def favorite_cycle():
            for _ in range(2): self.ok('/favorite/add', {'targetId': pub, 'type': 'post'}, 'student02')
            assert self.ok('/post/' + str(pub), who='student02')['favoriteCount'] == 1
            assert self.ok(f'/favorite/check?targetId={pub}&type=post', who='student02') is True
            assert any(p['id'] == pub for p in self.ok('/favorite/posts/list?type=0', who='student02'))
            for _ in range(2): self.ok('/favorite/remove', {'targetId': pub, 'type': 'post'}, 'student02')
            assert self.ok('/post/' + str(pub), who='student02')['favoriteCount'] == 0
        self.case('favorite repeat/remove and list are consistent', favorite_cycle)
        self.ok('/comment/add', {'entityId': pub, 'content': self.prefix + '-root'}, 'student02')
        comments = self.ok('/comment/list?entityId=' + str(pub))
        comment = next(c for c in comments if c['content'] == self.prefix + '-root')
        def reply():
            self.ok('/comment/add', {'entityId': pub, 'content': 'valid reply', 'parentId': comment['id'], 'replyCommentId': comment['id']}, 'student01')
            rows = self.ok('/comment/list?entityId=' + str(pub))
            row = next(c for c in rows if c['content'] == 'valid reply')
            assert row['replyUserId'] == self.uid('student02') and row['parentId'] == comment['id'], row
            assert self.ok('/post/' + str(pub))['commentCount'] == len(rows)
        self.case('comment and reply identities/counter are consistent', reply)
        self.case('cross-post parent is rejected', lambda: self.denied('/comment/add', {'entityId': other, 'content': 'bad parent', 'parentId': comment['id']}, 'student01'))
        self.case('wrong comment target type is rejected', lambda: self.denied('/comment/add', {'entityId': pub, 'entityType': 3, 'content': 'bad type'}, 'student02'))
        self.case('blank comment is rejected', lambda: self.denied('/comment/add', {'entityId': pub, 'content': ' '}, 'student02'))
        self.case('cannot follow oneself', lambda: self.denied('/user-relation/toggle', {'followedId': self.uid('student02')}, 'student02'))
        def follow_cycle():
            initial = self.ok('/user-relation/following/' + str(self.uid('student02')) + '?size=100', who='student02')['records']
            was_following = any(r['id'] == self.uid('teacher01') for r in initial)
            self.ok('/user-relation/toggle', {'followedId': self.uid('teacher01')}, 'student02')
            rows = self.ok('/user-relation/following/' + str(self.uid('student02')), who='student02')['records']
            assert any(r['id'] == self.uid('teacher01') for r in rows) != was_following, rows
            self.ok('/user-relation/toggle', {'followedId': self.uid('teacher01')}, 'student02')
        self.case('follow/unfollow and following list agree', follow_cycle)
        def privacy_transition():
            pid = self.post('-transition')
            self.ok('/favorite/add', {'targetId': pid, 'type': 'post'}, 'student02')
            self.ok('/post/update', {'id': pid, 'privacy': 1}, 'student01')
            rows = self.ok('/favorite/posts/list?type=0', who='student02')
            assert pid not in [row['id'] for row in rows], rows
        self.case('collected post becoming private disappears for outsider', privacy_transition)
        def owner_edit():
            self.ok('/post/update', {'id': other, 'title': self.prefix + '-edited', 'authorId': self.uid('student02'), 'likes': 999}, 'student01')
            p = self.ok('/post/' + str(other), who='student01')
            assert p['authorId'] == self.uid('student01') and p['title'].endswith('-edited') and p['likes'] != 999, p
        self.case('owner edit preserves author and system counters', owner_edit)
        def deleted():
            self.ok('/post/delete/' + str(pub), {}, 'student01')
            self.denied('/post/' + str(pub))
            self.denied('/comment/list?entityId=' + str(pub))
            self.denied('/post/' + str(pub) + '/like?active=true', {}, 'student02')
        self.case('deleted post unavailable to detail, comment and reaction', deleted)

    def upload(self, filename, content, who='teacher01'):
        boundary = 'module-upload-' + str(time.time_ns())
        raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: text/markdown\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
        return self.request('/courseware/upload-md', who=who, raw=raw, content_type='multipart/form-data; boundary=' + boundary)

    def courseware(self):
        cid = self.classroom()
        def upload_invalid():
            assert self.upload('slides.txt', b'# no')['code'] != 200
            assert self.upload('empty.md', b'')['code'] != 200
            assert self.upload('student.md', b'# no', 'student01')['code'] != 200
        self.case('invalid extension/empty file/student upload rejected', upload_invalid)
        markdown = b'---\ntheme: default\ntitle: Module Courseware\n---\n\n# Module Slide One\n\nSynthetic lesson content\n\n---\n\n# Module Slide Two\n\nSecond slide content\n'
        r = self.upload('module.md', markdown)
        assert r['code'] == 200, r
        url = r['data']
        self.result['slide_url'] = url
        self.case('real Slidev build returns loadable HTML', lambda: self._html(url))
        self.ok('/courseware/add', {'title': self.prefix, 'url': url, 'privacy': 0, 'teacherId': self.uid('student02')}, 'teacher01')
        cw = next(c for c in self.ok('/courseware/list?onlyMine=true&size=100', who='teacher01')['records'] if c['title'] == self.prefix)
        cwid = cw['id']
        self.result['fixtures'] = {'classId': cid, 'coursewareId': cwid, 'url': url}
        self.case('courseware author comes from session', lambda: self._equal(cw['teacherId'], self.uid('teacher01')))
        self.case('private courseware denies outsider', lambda: self.denied('/courseware/' + str(cwid), who='student02'))
        self.case('student cannot create courseware', lambda: self.denied('/courseware/add', {'title': 'forbidden'}, 'student01'))
        self.ok('/class-courseware/add', {'classId': cid, 'title': self.prefix, 'url': url}, 'teacher01')
        directory = self.ok('/class-courseware/list?classId=' + str(cid), who='student01')
        rel = next(c for c in directory if c['id'] == cwid)['relationId']
        self.case('visible class binding enables member detail', lambda: self.ok('/courseware/' + str(cwid), who='student01'))
        self.case('outsider cannot read class directory', lambda: self.denied('/class-courseware/list?classId=' + str(cid), who='student02'))
        self.case('student cannot change class courseware', lambda: self.denied('/class-courseware/update', {'id': rel, 'isVisible': False}, 'student01'))
        self.case('duplicate binding is rejected', lambda: self.denied('/class-courseware/add', {'classId': cid, 'title': self.prefix, 'url': url}, 'teacher01'))
        def hide():
            self.ok('/class-courseware/update', {'id': rel, 'isVisible': False}, 'teacher01')
            assert self.ok('/class-courseware/list?classId=' + str(cid) + '&isVisible=0', who='student01') == []
            self.denied('/courseware/' + str(cwid), who='student01')
            assert len(self.ok('/class-courseware/list?classId=' + str(cid) + '&isVisible=0', who='teacher01')) == 1
        self.case('hidden courseware unavailable to member API', hide)
        self.ok('/class-courseware/update', {'id': rel, 'isVisible': True, 'sort': 8}, 'teacher01')
        def edit():
            self.ok('/courseware/update', {'id': cwid, 'title': self.prefix + '-edited', 'teacherId': self.uid('admin')}, 'teacher01')
            assert self.ok('/courseware/' + str(cwid), who='teacher01')['teacherId'] == self.uid('teacher01')
            self.ok('/class-courseware/update-sort-batch', [{'id': rel, 'sort': 2}], 'teacher01')
            assert self.ok('/class-courseware/list?classId=' + str(cid), who='student01')[0]['sort'] == 2
        self.case('edit retains owner and teacher can reorder', edit)
        self.result['browser_fixture'] = {'classId': cid, 'coursewareId': cwid, 'url': url}
        self.case('other teacher cannot edit private courseware', lambda: self.denied('/courseware/update', {'id': cwid, 'title': 'forbidden'}, 'teacher02'))
        self.case('other teacher cannot delete private courseware', lambda: self.denied('/courseware/delete/' + str(cwid), {}, 'teacher02'))
        def public_and_remove():
            self.ok('/courseware/add', {'title': self.prefix + '-public', 'url': url + '?copy=public', 'privacy': 1}, 'teacher01')
            public = next(c for c in self.ok('/courseware/list?onlyMine=true&size=100', who='teacher01')['records'] if c['title'] == self.prefix + '-public')
            self.ok('/courseware/' + str(public['id']), who='student02')
            self.denied('/courseware/' + str(public['id']))
            self.ok('/class-courseware/add', {'classId': cid, 'title': public['title'], 'url': public['url']}, 'teacher01')
            rows = self.ok('/class-courseware/list?classId=' + str(cid), who='teacher01')
            other_rel = next(c['relationId'] for c in rows if c['id'] == public['id'])
            self.ok('/class-courseware/update-sort-batch', [{'id': other_rel, 'sort': 1}, {'id': rel, 'sort': 2}], 'teacher01')
            assert [r['id'] for r in self.ok('/class-courseware/list?classId=' + str(cid), who='student01')] == [public['id'], cwid]
            self.ok('/class-courseware/remove/' + str(rel), {}, 'teacher01')
            self.denied('/courseware/' + str(cwid), who='student01')
            assert cwid not in [r['id'] for r in self.ok('/class-courseware/list?classId=' + str(cid), who='student01')]
            self.ok('/courseware/delete/' + str(cwid), {}, 'teacher01')
            self.denied('/courseware/' + str(cwid), who='teacher01')
            self.result['browser_fixture'] = {'classId': cid, 'coursewareId': public['id'], 'url': public['url']}
        self.case('public visibility, multi-item order, removal and deletion', public_and_remove)

    def _equal(self, actual, expected):
        assert actual == expected, (actual, expected)

    def _html(self, url):
        with HTTP.open(self.base + url, timeout=15) as r:
            value = r.read().decode()
            assert r.status == 200 and '<html' in value and 'assets/' in value
        return {'url': url}

    def save(self, label):
        self.result['passed'] = all(c['passed'] for c in self.result['cases']) and bool(self.result['cases']) and 'fatal_error' not in self.result
        (self.out/(label + '.json')).write_text(json.dumps(self.result, ensure_ascii=False, indent=2))
        return self.result['passed']

def main():
    p = argparse.ArgumentParser()
    p.add_argument('phase', choices=['community', 'courseware'])
    p.add_argument('--label', default='smoke')
    args = p.parse_args()
    smoke = Smoke(args.phase, args.label)
    try:
        smoke.login()
        getattr(smoke, args.phase)()
    except Exception as e:
        smoke.result['fatal_error'] = str(e)[:1200]
        raise
    finally:
        passed = smoke.save(args.label)
    raise SystemExit(0 if passed else 1)

if __name__ == '__main__':
    main()
