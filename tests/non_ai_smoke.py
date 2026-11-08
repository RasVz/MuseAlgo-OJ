#!/usr/bin/env python3
"""Sequential non-AI business checks. Only use a disposable seeded test stack.

SQL fixtures require OJ_TEST_COMPOSE_JSON, exported by the isolated runner.
They are synthetic data, not evidence of real Judge0 execution or AI generation.
"""
import argparse
import base64
import io
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from modules_smoke import HTTP, Smoke

class NonAi(Smoke):
    def request(self, path, body=None, who=None, raw=None, content_type=None, method=None):
        headers = {'Content-Type': content_type or 'application/json'}
        if who: headers['Authorization'] = 'Bearer ' + self.sessions[who]['accessToken']
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with HTTP.open(req, timeout=45) as r: status, value = r.status, json.load(r)
        except urllib.error.HTTPError as e: status, value = e.code, json.load(e)
        if path != '/auth/login': self.result['requests'].append({'path':path,'method':req.get_method(),'http':status,'response':value})
        return value

    def reject(self, path, body=None, who=None, messages=(), codes=(400,401,403,404), **kwargs):
        r = self.request(path, body, who, **kwargs)
        assert r['code'] in codes or (r['code']==500 and r['message'] in messages), (path,r)
        assert r.get('data') is None, (path,r)
        assert r['message'] != '系统暂时不可用，请稍后重试', (path,r)
        return {'code':r['code'],'message':r['message']}

    def sql(self, query):
        raw = os.environ.get('OJ_TEST_COMPOSE_JSON')
        if not raw: raise RuntimeError('SQL fixtures require an explicit disposable compose command')
        command = json.loads(raw)
        assert command[0]=='docker' and '-p' in command and command[command.index('-p')+1].startswith('musealgo-nonai-')
        r = subprocess.run(command+['exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B --default-character-set=utf8mb4 oj_db'],
                           input=query,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
        assert r.returncode==0,r.stderr[:500]
        return r.stdout.strip()

    @staticmethod
    def text_sql(value):
        return "CONVERT(0x" + str(value).encode().hex() + " USING utf8mb4)"

    def upload(self, path, name, data, who='teacher01', fields=None):
        boundary='nonai-'+str(time.time_ns());parts=[]
        for key,value in (fields or {}).items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()+data+b'\r\n')
        parts.append(f'--{boundary}--\r\n'.encode())
        return self.request(path,who=who,raw=b''.join(parts),content_type='multipart/form-data; boundary='+boundary)

    def problem(self, suffix, public=False):
        return self.ok('/problem/add',{'title':self.prefix+suffix,'content':'Synthetic non-AI statement','inputDesc':'two integers',
                       'outputDesc':'sum','isPublic':public,'difficulty':800,'timeLimit':1000,'memoryLimit':128,
                       'samples':[{'input':'1 2','output':'3'}],'authorId':self.uid('student02')},'teacher01')

    def problems(self):
        pid=self.problem('-private');pub=self.problem('-public',True)
        self.result['fixtures']={'problemId':pid,'publicProblemId':pub}
        def identity():
            p=self.ok('/problem/'+str(pid),who='teacher01')
            assert p['authorId']==self.uid('teacher01') and p['samples'][0]['output']=='3',p
        self.case('problem creation preserves session owner and public samples',identity)
        self.case('student cannot create problems',lambda:self.reject('/problem/add',{'title':'bad'},'student01'))
        self.case('private statement denied to outsider',lambda:self.reject('/problem/'+str(pid),who='student02'))
        self.case('other teacher cannot edit private problem',lambda:self.reject('/problem/update',{'id':pid,'title':'bad'},'teacher02'))
        self.case('other teacher cannot delete private problem',lambda:self.reject('/problem/delete/'+str(pid),{},'teacher02'))
        self.case('student cannot read private judge cases',lambda:self.reject('/problem/case/list?problemId='+str(pub),who='student01'))
        archive=io.BytesIO()
        with zipfile.ZipFile(archive,'w') as z:
            for name,content in {'1.in':'1 2\n','1.out':'3\n','2.in':'4 5\n','2.out':'9\n'}.items():z.writestr(name,content)
        def import_zip():
            r=self.upload('/problem/case/upload','cases.zip',archive.getvalue(),fields={'problemId':pid})
            assert r['code']==200,r
            rows=self.ok('/problem/case/list?problemId='+str(pid),who='teacher01')
            assert len(rows)==2 and {r['input'].strip() for r in rows}=={'1 2','4 5'},rows
            assert sum(r['score'] for r in rows)==100,rows
            assert self.ok('/problem/'+str(pid),who='teacher01')['testCaseCount']==2
        self.case('ZIP import persists pairs, previews, scores and count',import_zip)
        self.case('other teacher cannot replace private testcases',lambda:self.reject('/problem/case/batch',{'problemId':pid,'cases':[{'input':'x','output':'y'}]},'teacher02'))
        def append():
            self.ok('/problem/case/append',{'problemId':pid,'cases':[{'input':'1 2\n','output':'3\n'},{'input':'6 7\n','output':'13\n'}]},'teacher01')
            rows=self.ok('/problem/case/list?problemId='+str(pid),who='teacher01')
            assert len(rows)==3 and sum(r['score'] for r in rows)==100,rows
            self.ok('/problem/case/delete/'+str(rows[0]['id']),{},'teacher01')
            rows=self.ok('/problem/case/list?problemId='+str(pid),who='teacher01')
            assert len(rows)==2 and sum(r['score'] for r in rows)==100,rows
        self.case('append deduplicates and delete recomputes testcase scores',append)
        def bad_zip():
            r=self.upload('/problem/case/upload','broken.zip',b'not a zip',fields={'problemId':pid})
            assert r['code'] in (400,500) and r['message']!='系统暂时不可用，请稍后重试',r
            assert len(self.ok('/problem/case/list?problemId='+str(pid),who='teacher01'))==2
        self.case('malformed archive fails without destroying existing testcases',bad_zip)
        fps=f'''<fps version="1.2"><item><title>{self.prefix}-fps</title><time_limit unit="s">2</time_limit><memory_limit unit="kb">65536</memory_limit><description><![CDATA[<p>sum</p>]]></description><input>integers</input><output>sum</output><sample_input>1 2</sample_input><sample_output>3</sample_output><test_input>1 2</test_input><test_output>3</test_output></item></fps>'''.encode()
        def import_fps():
            r=self.upload('/problem/import/fps','sample.fps',fps)
            assert r['code']==200 and r['data']['succeeded']==1 and r['data']['failed']==0,r
            fid=r['data']['items'][0]['problemId'];p=self.ok('/problem/'+str(fid),who='teacher01')
            assert p['timeLimit']==2000 and p['memoryLimit']==64 and p['isPublic'] is False,p
            assert len(self.ok('/problem/case/list?problemId='+str(fid),who='teacher01'))==1
            self.result['fixtures']['fpsProblemId']=fid
        self.case('FPS import creates private statement, limits, samples and real files',import_fps)
        def invalid_fps():
            r=self.upload('/problem/import/fps','broken.fps',b'<fps><item>')
            assert r['code']==400,r
        self.case('malformed FPS produces a business validation failure',invalid_fps)
        def edit():
            self.ok('/problem/update',{'id':pid,'title':self.prefix+'-edited','samples':[{'input':'2 3','output':'5'}]},'teacher01')
            p=self.ok('/problem/'+str(pid),who='teacher01');assert p['samples'][0]['output']=='5' and p['authorId']==self.uid('teacher01'),p
        self.case('problem editing updates samples and retains owner',edit)
        def clear():
            self.ok('/problem/case/clear',{'problemId':pid},'teacher01')
            assert self.ok('/problem/case/list?problemId='+str(pid),who='teacher01')==[]
            assert self.ok('/problem/'+str(pid),who='teacher01')['testCaseCount']==0
        self.case('clear removes testcase records and resets count',clear)
        self.case('public statement readable anonymously',lambda:self.ok('/problem/'+str(pub)))
        temp=self.problem('-delete')
        self.ok('/problem/delete/'+str(temp),{},'teacher01')
        self.case('deleted problem no longer readable',lambda:self.reject('/problem/'+str(temp),who='teacher01',messages=('题目不存在',)))

    def community(self):
        cid=self.classroom();post=self.post('-reported')
        self.result['fixtures']={'classId':cid,'postId':post}
        def report_flow():
            self.ok('/community/report',{'postId':str(post),'reason':'OTHER','detail':'Synthetic report'},'student02')
            self.reject('/community/report',{'postId':str(post),'reason':'OTHER'},'student02',messages=('你已举报过该内容，正在等待审核',))
            rows=self.ok('/community/moderation/reports',who='admin');rid=next(r['id'] for r in rows if r['postId']==post)
            self.ok('/community/moderation/reports/'+str(rid),who='admin')
            self.ok('/community/moderation/reports/'+str(rid),{'hide':True,'note':'synthetic decision'},'admin')
            assert self.ok('/community/moderation/reports/'+str(rid),who='admin')['status']=='RESOLVED'
            self.reject('/post/'+str(post),who='student02',messages=('文章不存在',))
            self.ok('/post/'+str(post),who='student01')
        self.case('report duplicate rejection, admin review, hiding and author access',report_flow)
        self.case('student cannot view reports or moderate posts',lambda:self.reject('/community/moderation/reports',who='student02'))
        def moderation():
            self.ok('/community/moderation/post/'+str(post),{'privacy':0,'isEssence':1},'admin')
            assert self.ok('/post/'+str(post),who='student02')['isEssence']==1
        self.case('admin can restore post visibility and editorial state',moderation)
        def notifications():
            rows=self.ok('/notification/list?scope=all',who='student01')
            note=next(r for r in rows if r.get('targetId')==post and r['type']=='MODERATION')
            assert not note['isRead'],note
            self.ok('/notification/'+str(note['id'])+'/read',{},'student02')
            rows=self.ok('/notification/list',who='student01');assert not next(r for r in rows if r['id']==note['id'])['isRead']
            self.ok('/notification/'+str(note['id'])+'/read',{},'student01')
            rows=self.ok('/notification/list',who='student01');assert next(r for r in rows if r['id']==note['id'])['isRead']
        self.case('notification persists, other user cannot mark it read, owner can',notifications)
        def read_all():
            self.ok('/notification/read-all',{},'student01')
            assert self.ok('/notification/unread-count',who='student01')==0
        self.case('read-all and unread count agree',read_all)
        self.case('notification list rejects anonymous',lambda:self.reject('/notification/list'))
        def tag_flow():
            name=self.prefix+'-tag'
            self.ok('/community/moderation/tags',{'name':name,'groupName':'Synthetic'},'admin')
            row=next(t for t in self.ok('/community/moderation/tags',who='admin') if t['name']==name)
            self.ok('/community/moderation/tags/'+str(row['id'])+'?enabled=false',{},'admin')
            assert not next(t for t in self.ok('/community/moderation/tags',who='admin') if t['id']==row['id'])['enabled']
        self.case('controlled tags can be created and disabled by admin',tag_flow)
        self.case('teacher cannot administer community tags',lambda:self.reject('/community/moderation/tags',who='teacher01'))
        def announcement():
            title=self.prefix+'-announcement'
            self.ok('/admin/announcements/save',{'title':title,'content':'Synthetic announcement','privacy':0},'admin')
            row=next(p for p in self.ok('/post/announcements') if p['title']==title)
            self.ok('/admin/announcements/save',{'id':row['id'],'title':title+' edited','content':'updated'},'admin')
            assert self.ok('/post/'+str(row['id']))['title']==title+' edited'
            self.ok('/admin/announcements/delete/'+str(row['id']),{},'admin')
            assert not any(p['id']==row['id'] for p in self.ok('/post/announcements'))
        self.case('announcement create/edit/delete follows public list',announcement)
        self.case('teacher cannot publish admin announcement',lambda:self.reject('/admin/announcements/save',{'title':'bad'},'teacher01'))
        classpost=self.post('-class',classId=cid)
        self.result['fixtures']['classPostId']=classpost
        self.case('class member and class teacher can read class post',lambda:[self.ok('/post/'+str(classpost),who=w) for w in ['student01','teacher01']])
        self.case('outsider cannot read class post detail',lambda:self.reject('/post/'+str(classpost),who='student02',messages=('文章不存在',)))
        self.case('anonymous cannot query class post directory',lambda:self.reject('/post/list?classId='+str(cid),messages=('文章不存在',)))
        self.case('outsider cannot query class post directory',lambda:self.reject('/post/list?classId='+str(cid),who='student02',messages=('文章不存在',)))
        def square():
            rows=self.ok('/post/list?keyword='+self.prefix)['records']
            assert classpost not in [r['id'] for r in rows],rows
        self.case('class-only posts are excluded from the public square',square)
        self.case('outsider cannot publish into another class',lambda:self.reject('/post/add',{'title':'forged class post','content':'forbidden','classId':cid,'type':3,'privacy':0},'student02'))
        self.case('outsider cannot comment on class-only posts',lambda:self.reject('/comment/add',{'entityId':classpost,'content':'forbidden'},'student02',messages=('文章不存在',)))
        self.case('outsider cannot react to class-only posts',lambda:self.reject(f'/post/{classpost}/like?active=true',{},'student02',messages=('文章不存在',)))
        self.case('outsider cannot report class content they cannot read',lambda:self.reject('/community/report',{'postId':str(classpost),'reason':'OTHER'},'student02',messages=('文章不存在',)))
        title=self.prefix+'-teacher-class'
        self.ok('/post/add',{'title':title,'content':'class teacher content','classId':cid,'type':3,'privacy':0,'tags':''},'teacher01')
        teacher_post=next(p['id'] for p in self.ok('/post/my?size=100',who='teacher01')['records'] if p['title']==title)
        self.ok('/favorite/add',{'targetId':teacher_post,'type':'post'},'student01')
        self.sql(f'DELETE FROM class_student WHERE class_id={cid} AND student_id={self.uid("student01")};')
        self.case('removed member loses class post access',lambda:self.reject('/post/'+str(teacher_post),who='student01',messages=('文章不存在',)))
        def removed_favorite():
            rows=self.ok('/favorite/posts/list?type=3',who='student01')
            assert teacher_post not in [r['id'] for r in rows],rows
        self.case('removed member no longer receives class post summary through favorites',removed_favorite)

    def business(self):
        """Real exam scoring, personal lists and administration; no judge/AI work."""
        pid=self.problem('-list',True)
        private=self.ok('/problem-list/add',{'title':self.prefix+'-private','privacy':0,'type':1,'creatorId':self.uid('student02')},'student01')
        public=self.ok('/problem-list/add',{'title':self.prefix+'-public','privacy':1,'type':1},'student01')
        self.result['fixtures']={'privateListId':private,'publicListId':public,'problemId':pid}
        def list_owner():
            p=self.ok('/problem-list/'+str(private),who='student01');assert p['creatorId']==self.uid('student01'),p
        self.case('personal list ownership is bound to the session',list_owner)
        self.case('private list detail rejects outsider',lambda:self.reject('/problem-list/'+str(private),who='student02'))
        def private_list_index():
            rows=self.ok('/problem-list/list?keyword='+self.prefix,who='student02')['records']
            assert private not in [r['id'] for r in rows],rows
        self.case('list directory excludes another user private list',private_list_index)
        def items():
            self.ok('/problem-list-item/add',{'problemListId':private,'problemDisplayId':str(pid)},'student01')
            self.reject('/problem-list-item/add',{'problemListId':private,'problemDisplayId':str(pid)},'student01',messages=('该题目已在题单中',))
            rows=self.ok('/problem-list-item/list?listId='+str(private),who='student01')
            assert len(rows)==1 and rows[0]['id']==pid,rows
            self.ok('/problem-list/update-order',{'listId':private,'problemIds':[pid]},'student01')
        self.case('owner adds problems idempotently and reorders them',items)
        self.case('private list items reject outsider',lambda:self.reject('/problem-list-item/list?listId='+str(private),who='student02'))
        self.case('outsider cannot add to another user list',lambda:self.reject('/problem-list-item/add',{'problemListId':private,'problemDisplayId':str(pid)},'student02',messages=('无权操作该题单','无权修改此题单')))
        self.case('outsider cannot reorder another user list',lambda:self.reject('/problem-list/update-order',{'listId':private,'problemIds':[pid]},'student02',messages=('无权操作该题单','无权修改此题单')))
        self.case('outsider cannot edit another user list',lambda:self.reject('/problem-list/update',{'id':private,'title':'bad'},'student02',messages=('无权修改',)))
        self.case('students cannot create official lists',lambda:self.reject('/problem-list/add',{'title':'bad','type':2},'student01',messages=('仅管理员可创建官方题单',)))
        self.case('public list remains readable',lambda:self.ok('/problem-list/'+str(public),who='student02'))
        def edit_list():
            self.ok('/problem-list/update',{'id':private,'title':self.prefix+' edited','creatorId':self.uid('student02'),'type':2},'student01')
            p=self.ok('/problem-list/'+str(private),who='student01');assert p['creatorId']==self.uid('student01') and p['type']==1,p
        self.case('list update preserves owner and type',edit_list)
        self.case('outsider cannot delete list',lambda:self.reject('/problem-list/delete/'+str(private),{},'student02',messages=('删除失败',)))
        self.case('owner can delete personal list',lambda:self.ok('/problem-list/delete/'+str(private),{},'student01'))
        self.case('owner can sort their personal list directory',lambda:self.ok('/problem-list/update-sort',{'type':1,'ids':[public]},'student01'))
        self.case('outsider cannot sort another owner list directory',lambda:self.reject('/problem-list/update-sort',{'type':1,'ids':[public]},'student02'))
        cid=self.classroom()
        class_list=self.ok('/problem-list/add',{'title':self.prefix+'-class-list','type':3,'classId':cid,'privacy':1},'teacher01')
        self.case('class list grants current student membership',lambda:self.ok('/problem-list/'+str(class_list),who='student01'))
        self.case('class list refuses outsider despite public flag',lambda:self.reject('/problem-list/'+str(class_list),who='student02'))
        self.case('student cannot create class lists',lambda:self.reject('/problem-list/add',{'title':'forged','type':3,'classId':cid},'student01'))
        self.ok('/favorite/add',{'targetId':class_list,'type':'problem_list'},'student01')
        self.sql(f'DELETE FROM class_student WHERE class_id={cid} AND student_id={self.uid("student01")};')
        def favorite_scope():
            self.reject('/problem-list/'+str(class_list),who='student01')
            for url in ['/favorite/problem_list/list','/favorite/problem_list/vo/list']:
                rows=self.ok(url,who='student01')['records']
                assert class_list not in [r.get('id',r.get('targetId')) for r in rows],rows
        self.case('removed class member loses both favorite list views',favorite_scope)


        options=[{'key':'A','text':'first'},{'key':'B','text':'second'},{'key':'C','text':'third'}]
        payload={'title':self.prefix+'-exam','description':'Synthetic objective exam','category':'MOCK','durationMinutes':10,'totalScore':30,'isPublished':0,'questions':[
            {'questionType':'SINGLE','statement':'choose A','options':options,'answer':'A','explanation':'first','score':10},
            {'questionType':'MULTIPLE','statement':'choose AC','options':options,'answer':'AC','explanation':'first and third','score':10},
            {'questionType':'JUDGE','statement':'true','answer':'T','explanation':'true','score':10}]}
        paper=self.ok('/exam-paper/save',payload,'admin');self.result['fixtures']['paperId']=paper
        self.case('draft exam is hidden from student',lambda:self.reject('/exam-paper/'+str(paper),who='student01'))
        self.case('teacher cannot administer exam papers',lambda:self.reject('/exam-paper/save',payload,'teacher01'))
        def publish():
            self.ok('/exam-paper/save',{**payload,'id':paper,'isPublished':1},'admin')
            p=self.ok('/exam-paper/'+str(paper),who='student01')
            assert len(p['questions'])==3 and all(not q.get('answer') and not q.get('explanation') for q in p['questions']),p
            return p
        self.case('published paper is visible without standard answers',publish)
        questions=self.ok('/exam-paper/'+str(paper),who='student01')['questions']
        answers={'answers':[{'questionId':q['id'],'answer':a} for q,a in zip(questions,['A','C,A','true'])]}
        self.case('answering before starting is rejected',lambda:self.reject(f'/exam-paper/{paper}/submit-answers',answers,'student01',messages=('请先开始作答，或当前试卷已交卷',)))
        attempt={}
        def start():
            attempt.update(self.ok(f'/exam-paper/{paper}/attempt/start',{},'student01'))
            again=self.ok(f'/exam-paper/{paper}/attempt/start',{},'student01')
            assert attempt['status']=='RUNNING' and attempt['id']==again['id'] and attempt['remainingSeconds']>0,again
        self.case('start attempt is idempotent while running',start)
        def grading():
            rows=self.ok(f'/exam-paper/{paper}/submit-answers',answers,'student01')
            assert len(rows)==3 and sum(r['score'] for r in rows)==30 and all(r['correct'] for r in rows),rows
            self.ok(f'/exam-paper/{paper}/submit-answers',answers,'student01')
            count=int(self.sql(f"SELECT COUNT(*) FROM exam_answer WHERE attempt_id={attempt['id']};"));assert count==3,count
        self.case('single/multiple/judge answers score correctly without duplicate rows',grading)
        self.case('another student has a separate empty attempt',lambda:self._equal(self.ok(f'/exam-paper/{paper}/attempt',who='student02'),None))
        def finish():
            a=self.ok(f'/exam-paper/{paper}/attempt/finish',{},'student01');assert a['status']=='SUBMITTED' and a['totalScore']==30,a
            b=self.ok(f'/exam-paper/{paper}/attempt/finish',{},'student01');assert b['id']==a['id'] and b['totalScore']==30,b
        self.case('finish persists score and repeated finish is idempotent',finish)
        self.case('submitted attempt refuses further answers',lambda:self.reject(f'/exam-paper/{paper}/submit-answers',answers,'student01',messages=('请先开始作答，或当前试卷已交卷',)))
        def expiry():
            a=self.ok(f'/exam-paper/{paper}/attempt/start',{},'student02')
            self.sql(f"UPDATE exam_attempt SET end_time=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id={a['id']};")
            a=self.ok(f'/exam-paper/{paper}/attempt',who='student02');assert a['status']=='TIMEOUT' and a['totalScore']==0,a
        self.case('expired attempt automatically finalizes with zero score',expiry)
        def stats():
            p=self.ok(f'/exam-paper/{paper}/attempts',who='admin');assert p['total']==2,p
            self.ok(f'/exam-paper/{paper}/stats',who='admin')
        self.case('admin exam results include submitted and expired attempts',stats)
        self.case('students cannot read classwide exam results',lambda:self.reject(f'/exam-paper/{paper}/attempts',who='student01'))
        def copy_paper():
            copy=self.ok(f'/exam-paper/{paper}/copy',{},'admin')
            p=self.ok('/exam-paper/'+str(copy),who='admin');assert len(p['questions'])==3,p
            self.ok('/exam-paper/delete/'+str(copy),{},'admin')
        self.case('exam copy preserves questions and can be deleted',copy_paper)

        old=self.ok('/user/info/'+str(self.uid('student02')),who='student02')
        def profile():
            self.ok('/user/profile',{'id':self.uid('student02'),'nickname':self.prefix+' nick','role':3,'isBanned':1},'student01',method='PUT')
            other=self.ok('/user/info/'+str(self.uid('student02')),who='student02');assert other['nickname']==old['nickname'],other
            me=self.ok('/user/info/'+str(self.uid('student01')),who='student01');assert me['nickname']==self.prefix+' nick' and me['role']==1,me
        self.case('profile update binds identity and ignores privileged fields',profile)
        def prefs():
            self.ok('/user/preferences',{'language':'java','fontSize':18,'profilePublic':False,'notifications':{'reports':False,'interactions':False,'homework':True,'contest':True}},'student01',method='PUT')
            p=self.ok('/user/preferences',who='student01');assert p['language']=='java' and p['fontSize']==18 and not p['notifications']['reports'],p
            self.reject('/user/info/'+str(self.uid('student01')))
            self.ok('/user/info/'+str(self.uid('student01')),who='student02')
            self.ok('/user/preferences',{'profilePublic':True},'student01',method='PUT')
        self.case('preferences persist and profile visibility follows anonymous/login contract',prefs)
        self.case('non-admin cannot list managed users',lambda:self.reject('/admin/users/list',who='teacher01'))
        name=self.prefix+'-account';password='SyntheticPass123!'
        self.ok('/admin/users/create',{'username':name,'password':password,'nickname':'Disposable account','role':1},'admin')
        rows=self.ok('/admin/users/list?keyword='+name,who='admin')['records'];account=rows[0]['id'];self.result['fixtures']['managedUserId']=account
        def managed():
            assert len(rows)==1 and 'password' not in rows[0],rows
            self.ok(f'/admin/users/{account}/role',{'role':2},'admin')
            assert self.ok('/admin/users/list?keyword='+name,who='admin')['records'][0]['role']==2
            self.ok(f'/admin/users/{account}/ban',{'banned':True},'admin')
            self.reject('/auth/login',{'username':name,'password':password},messages=('账号已被禁用',))
            self.ok(f'/admin/users/{account}/ban',{'banned':False},'admin')
            self.ok(f'/admin/users/{account}/reset-password',{'newPassword':'SyntheticReset456!'},'admin')
            self.ok('/auth/login',{'username':name,'password':'SyntheticReset456!'})
        self.case('admin create/role/ban/enable/reset applies to disposable account',managed)
        def audit():
            rows=self.ok('/admin/audit-logs/list?module=user&size=100',who='admin')['records']
            assert any(r['action']=='create_user' for r in rows) and any(r['action']=='reset_password' for r in rows),rows
            assert password not in json.dumps(rows), 'audit exposes plaintext password'
        self.case('admin mutations have audit records without plaintext passwords',audit)
        self.case('non-admin cannot read audit logs',lambda:self.reject('/admin/audit-logs/list',who='teacher01'))
        self.case('admin cannot disable own account',lambda:self.reject('/admin/users/'+str(self.uid('admin'))+'/ban',{'banned':True},'admin',messages=('不能禁用自己的账号',)))

    def boundaries(self):
        cid=self.classroom()
        lid=self.ok('/problem-list/add',{'title':self.prefix+'-class-list','type':3,'classId':cid,'privacy':1},'teacher01')
        post=self.post('-class-author',classId=cid)
        self.result['fixtures']={'classId':cid,'listId':lid,'postId':post}
        self.case('member class directory includes its class list',lambda:self._equal(self.ok(f'/class/{cid}/problem-list',who='student01')['total'],1))
        self.case('non-member class directory excludes all class lists',lambda:self._equal(self.ok(f'/class/{cid}/problem-list',who='student02')['total'],0))
        self.case('todo count rejects another student identity',lambda:self.reject('/problem-list/todo-count?userId='+str(self.uid('student01')),who='student02'))
        self.case('my-homework route rejects another student identity',lambda:self.reject('/class/homework/my?userId='+str(self.uid('student01')),who='student02'))
        self.sql(f'DELETE FROM class_student WHERE class_id={cid} AND student_id={self.uid("student01")};')
        self.case('removed member cannot edit their former class post',lambda:self.reject('/post/update',{'id':post,'title':'unauthorized edit','content':'changed'},'student01'))
        self.case('removed member class list query stays empty',lambda:self._equal(self.ok(f'/class/{cid}/problem-list',who='student01')['total'],0))

    def plagiarism(self):
        cid=self.classroom()
        invite=self.ok('/class/inviteCode?classId='+str(cid),who='teacher01')
        self.ok('/class/joinByCode',{'code':invite},'student02')
        pid=self.problem('-plagiarism',True)
        code='#include <iostream>\nusing namespace std; int main(){ long long first,second; cin>>first>>second; cout<<(first+second); return 0; }'
        def submission(problem,user,contest=None):
            c='NULL' if contest is None else str(contest)
            sid=int(self.sql(f"INSERT INTO submission(problem_id,user_id,language,status,score,judge_phase,contest_id) VALUES({problem},{user},'cpp',1,100,'FINISHED',{c}); SELECT LAST_INSERT_ID();"))
            self.sql(f'INSERT INTO submission_code(submission_id,code) VALUES({sid},{self.text_sql(code)});')
            return sid
        ids=[submission(pid,self.uid(w)) for w in ['student01','student02']]
        self.result['fixtures']={'classId':cid,'problemId':pid,'classSubmissionIds':ids,'submission_kind':'synthetic SQL fixtures; no judge execution'}
        def run_class():
            assert self.ok('/teacher/plagiarism/run',{'classId':cid,'problemId':pid},'teacher01')==1
            rows=self.ok('/teacher/plagiarism/reports?classId='+str(cid),who='teacher01')['records']
            assert len(rows)==1 and rows[0]['similarity']>=.85 and rows[0]['status']=='PENDING',rows
        self.case('real fingerprint engine persists a matching class submission pair',run_class)
        rid=int(self.sql(f'SELECT id FROM plagiarism_report WHERE class_id={cid} AND problem_id={pid} LIMIT 1;'))
        self.case('repeated class check is idempotent',lambda:self._equal(self.ok('/teacher/plagiarism/run',{'classId':cid,'problemId':pid},'teacher01'),0))
        self.case('student cannot trigger plagiarism',lambda:self.reject('/teacher/plagiarism/run',{'classId':cid,'problemId':pid},'student01'))
        self.case('other teacher cannot trigger class plagiarism',lambda:self.reject('/teacher/plagiarism/run',{'classId':cid,'problemId':pid},'teacher02'))
        self.case('explicit class report query enforces ownership',lambda:self.reject('/teacher/plagiarism/reports?classId='+str(cid),who='teacher02'))
        def unfiltered():
            rows=self.ok('/teacher/plagiarism/reports',who='teacher02')['records']
            assert rid not in [r['id'] for r in rows],rows
        self.case('unfiltered report list is limited to teacher-owned scopes',unfiltered)
        query='/teacher/plagiarism/codes?submissionIds='+','.join(map(str,ids))
        self.case('owner teacher reads the reported code pair',lambda:self._equal(len(self.ok(query,who='teacher01')),2))
        self.case('other teacher cannot read arbitrary reported source code',lambda:self.reject(query,who='teacher02'))
        self.case('student cannot read plagiarism source code',lambda:self.reject(query,who='student01'))
        def statuses():
            for status in ['REVIEWED','IGNORED','PENDING']:
                self.ok(f'/teacher/plagiarism/reports/{rid}/status',{'status':status},'teacher01')
                rows=self.ok('/teacher/plagiarism/reports?classId='+str(cid),who='teacher01')['records']
                assert rows[0]['status']==status,rows
        self.case('report dispositions are reversible and persisted',statuses)
        self.case('other teacher cannot change report disposition',lambda:self.reject(f'/teacher/plagiarism/reports/{rid}/status',{'status':'REVIEWED'},'teacher02'))
        self.case('invalid plagiarism status is rejected',lambda:self.reject(f'/teacher/plagiarism/reports/{rid}/status',{'status':'INVALID'},'teacher01'))
        import datetime as dt
        now=dt.datetime.now();fmt=lambda t:t.strftime('%Y-%m-%d %H:%M:%S')
        contest=self.ok('/contest/add',{'title':self.prefix+'-plag-contest','mode':'ACM','type':'Public','visible':1,
                'startTime':fmt(now-dt.timedelta(minutes=1)),'endTime':fmt(now+dt.timedelta(hours=1))},'teacher01')['id']
        cp=self.problem('-contest-plag',True);contest_ids=[submission(cp,self.uid(w),contest) for w in ['student01','student02']]
        self.result['fixtures'].update(contestId=contest,contestSubmissionIds=contest_ids)
        self.case('real contest fingerprint check persists report',lambda:self._equal(self.ok(f'/teacher/plagiarism/contest/{contest}/run',{},'teacher01'),1))
        self.case('repeated contest check is idempotent',lambda:self._equal(self.ok(f'/teacher/plagiarism/contest/{contest}/run',{},'teacher01'),0))
        def contest_filter():
            rows=self.ok('/teacher/plagiarism/reports?contestId='+str(contest),who='teacher01')['records']
            assert len(rows)==1 and rows[0]['contestId']==contest,rows
        self.case('contest report filter and contest ID are preserved',contest_filter)
        self.case('other teacher cannot query contest reports',lambda:self.reject('/teacher/plagiarism/reports?contestId='+str(contest),who='teacher02'))
        self.case('other teacher cannot run contest check',lambda:self.reject(f'/teacher/plagiarism/contest/{contest}/run',{},'teacher02'))
        self.sql(f'UPDATE class SET teacher_id={self.uid("teacher02")} WHERE id={cid};')
        self.case('previous class teacher loses source access after transfer',lambda:self.reject(query,who='teacher01'))
        self.case('successor class teacher can view report source',lambda:self._equal(len(self.ok(query,who='teacher02')),2))

def main():
    p=argparse.ArgumentParser();p.add_argument('phase',choices=['problems','community','plagiarism','business','boundaries']);p.add_argument('--label',default=None);args=p.parse_args()
    label=args.label or args.phase;s=NonAi(args.phase,label)
    try:s.login();getattr(s,args.phase)()
    except Exception as e:s.result['fatal_error']=str(e)[:1200];raise
    finally:passed=s.save(label)
    raise SystemExit(0 if passed else 1)

if __name__=='__main__':main()
