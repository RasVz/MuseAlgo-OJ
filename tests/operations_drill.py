#!/usr/bin/env python3
"""Local release/backup/recovery exercise. Touches only newly generated musealgo-drill-* projects."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from database_reference_smoke import check_reference_data

ROOT=Path(__file__).resolve().parents[1]
HTTP=urllib.request.build_opener(urllib.request.ProxyHandler({}))


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));return sock.getsockname()[1]


def canonical_digest(value):
    encoded=json.dumps(value,sort_keys=True,separators=(',',':')).encode()
    return hashlib.sha256(encoded).hexdigest()


def main(args):
    os.umask(0o077)
    output=Path(args.output).resolve();output.mkdir(parents=True,exist_ok=True,mode=0o700);output.chmod(0o700)
    latest_schema=max(int(p.name.split('__')[0][1:]) for p in (ROOT/'OJ-Backend/oj-admin/src/main/resources/db/migration').glob('V*__*.sql'))
    base_env=os.environ.copy()
    # Shell variables override --env-file in Compose. Never inherit another stack's secrets/ports.
    for name in re.findall(r'\$\{([A-Za-z_][A-Za-z0-9_]*)', (ROOT/'docker-compose.prod.yml').read_text()):
        base_env.pop(name,None)
    projects=[]
    with tempfile.TemporaryDirectory(prefix='musealgo-drill-') as temporary:
        base=Path(temporary)
        def environment(name, backend_image=None):
            project='musealgo-drill-'+name+'-'+secrets.token_hex(4);projects.append(project)
            values={'OJ_DB_ROOT_PASS':secrets.token_hex(32),'OJ_DB_MIGRATION_PASS':secrets.token_hex(32),
                    'OJ_DB_PASS':secrets.token_hex(32),'JWT_SECRET':secrets.token_hex(48),
                    'BACKEND_IMAGE':backend_image or args.backend_image,'FRONTEND_IMAGE':args.frontend_image,
                    'BACKEND_PORT':str(port()),'WEB_PORT':str(port()),'WEB_BIND':'127.0.0.1','JAVA_OPTS':'-Xmx512m'}
            file=base/(project+'.env');write_env(file,values)
            return project,values,file
        def write_env(file,values):
            file.write_text('\n'.join(f'{k}={v}' for k,v in values.items())+'\n');file.chmod(0o600)
        def compose(project,file,*parts,**kwargs):
            command=['docker','compose','--env-file',str(file),'-p',project,'-f',str(ROOT/'docker-compose.prod.yml')]+list(parts)
            return subprocess.run(command,env=base_env,check=True,**kwargs)
        def sql(project,file,query):
            return compose(project,file,'exec','-T','mysql','sh','-c',
                           'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B oj_db',
                           input=query.encode(),stdout=subprocess.PIPE).stdout.decode().strip()
        def run_ops(project,file,*parts,compose_file=None):
            command=['python3',str(ROOT/'scripts/ops.py'),'--project',project,'--env-file',str(file)]
            if compose_file: command+=['--compose',str(compose_file)]
            command+=list(parts)
            return subprocess.run(command,cwd=ROOT,env=base_env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        def ops(project,file,*parts,compose_file=None):
            result=run_ops(project,file,*parts,compose_file=compose_file)
            if result.returncode:
                raise subprocess.CalledProcessError(result.returncode,'ops.py',output=result.stdout,stderr=result.stderr)
            return result.stdout.decode()
        def database_summary(project,file):
            def concat(query):
                return sql(project,file,'SET SESSION group_concat_max_len=1048576; '+query)
            material={
                'history':concat("SELECT GROUP_CONCAT(CONCAT(version,':',success,':',COALESCE(checksum,'NULL')) ORDER BY installed_rank SEPARATOR ',') FROM flyway_schema_history;"),
                'roles':concat("SELECT GROUP_CONCAT(CONCAT(id,':',HEX(code),':',HEX(name)) ORDER BY id SEPARATOR ',') FROM sys_role;"),
                'permissions':concat("SELECT GROUP_CONCAT(CONCAT(id,':',HEX(code)) ORDER BY id SEPARATOR ',') FROM sys_permission;"),
                'role_permissions':concat("SELECT GROUP_CONCAT(CONCAT(role_id,':',permission_id) ORDER BY role_id,permission_id SEPARATOR ',') FROM sys_role_permission;"),
                'role_permission_codes':concat("SELECT GROUP_CONCAT(CONCAT(HEX(r.code),':',HEX(p.code)) ORDER BY r.code,p.code SEPARATOR ',') FROM sys_role_permission rp JOIN sys_role r ON r.id=rp.role_id JOIN sys_permission p ON p.id=rp.permission_id;"),
                'user_roles':concat("SELECT GROUP_CONCAT(CONCAT(user_id,':',role_id) ORDER BY user_id,role_id SEPARATOR ',') FROM sys_user_role;"),
                'users':concat("SELECT GROUP_CONCAT(CONCAT(id,':',HEX(username),':',HEX(nickname),':',COALESCE(role,-1),':',COALESCE(is_banned,-1),':',COALESCE(is_deleted,-1)) ORDER BY id SEPARATOR ',') FROM user;"),
                'submissions':concat("SELECT GROUP_CONCAT(CONCAT(id,':',user_id,':',problem_id,':',status) ORDER BY id SEPARATOR ',') FROM submission;")}
            return {'history_rows':sql(project,file,'SELECT COUNT(*) FROM flyway_schema_history;'),
                    'role_rows':sql(project,file,'SELECT COUNT(*) FROM sys_role;'),
                    'permission_rows':sql(project,file,'SELECT COUNT(*) FROM sys_permission;'),
                    'grant_rows':sql(project,file,'SELECT COUNT(*) FROM sys_role_permission;'),
                    'user_role_rows':sql(project,file,'SELECT COUNT(*) FROM sys_user_role;'),
                    'user_rows':sql(project,file,'SELECT COUNT(*) FROM user;'),
                    'submission_rows':sql(project,file,'SELECT COUNT(*) FROM submission;'),
                    'digest':canonical_digest(material)}
        def service_rows(project,file):
            raw=compose(project,file,'ps','--format','json',stdout=subprocess.PIPE).stdout.decode().strip()
            if not raw:return []
            return json.loads(raw) if raw.startswith('[') else [json.loads(line) for line in raw.splitlines() if line.strip()]
        def app_state(project,file):
            rows=service_rows(project,file)
            return {row.get('Service'):row.get('State') for row in rows
                    if row.get('Service') in ('backend','frontend','report-worker')}
        def running_image_ids(project,file):
            raw=compose(project,file,'ps','--format','json',stdout=subprocess.PIPE).stdout.decode().strip()
            rows=json.loads(raw) if raw.startswith('[') else [json.loads(line) for line in raw.splitlines() if line.strip()]
            result={}
            for service in ('backend','frontend','report-worker'):
                matches=[row for row in rows if row.get('Service')==service and row.get('State')=='running']
                assert len(matches)==1 and matches[0].get('ID'),(service,matches)
                inspected=subprocess.run(['docker','inspect','--format={{.Image}}',matches[0]['ID']],env=base_env,
                    check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True).stdout.strip()
                result[service]=inspected
            return result
        def effective_images(project,file,compose_path,image_ids):
            override=base/(project+'-pinned-images.yml')
            override.write_text(json.dumps({'services':{name:{'image':image_ids[name]}
                for name in image_ids}}));override.chmod(0o600)
            command=['docker','compose','--env-file',str(file),'-p',project,'-f',str(compose_path),
                     '-f',str(override),'config','--format','json']
            parsed=json.loads(subprocess.run(command,env=base_env,check=True,stdout=subprocess.PIPE).stdout)
            return {name:parsed['services'][name]['image'] for name in image_ids}
        def status(url):
            try:
                with HTTP.open(url,timeout=5) as response:return response.status,response.read()
            except urllib.error.HTTPError as error:return error.code,error.read()
        def api_call(url,method='GET',payload=None,token=None):
            headers={'Content-Type':'application/json'}
            if token:headers['Authorization']='Bearer '+token
            body=json.dumps(payload).encode() if payload is not None else None
            request=urllib.request.Request(url,data=body,headers=headers,method=method)
            try:
                with HTTP.open(request,timeout=10) as response:return response.status,response.read()
            except urllib.error.HTTPError as error:return error.code,error.read()
        def normalized_http(url):
            code,body=status(url)
            try:
                payload=json.loads(body)
                body_digest=canonical_digest(payload)
                kind='json'
            except (json.JSONDecodeError,UnicodeDecodeError):
                normalized=b' '.join(body.split())
                body_digest=hashlib.sha256(normalized).hexdigest()
                kind='text'
            return {'status':code,'body_kind':kind,'body_sha256':body_digest,'body_bytes':len(body)}
        evidence={}
        source,values,file=environment('source')
        try:
            # Fresh install validates split migration/runtime credentials and gateway boundaries.
            compose(source,file,'up','-d','--wait')
            zone=compose(source,file,'exec','-T','backend','date','+%z',stdout=subprocess.PIPE).stdout.strip()
            assert zone==b'+0800',zone
            assert sql(source,file,"SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;")==str(latest_schema)
            result=subprocess.run(['docker','compose','--env-file',str(file),'-p',source,'-f',str(ROOT/'docker-compose.prod.yml'),
                                   'exec','-T','mysql','sh','-c','MYSQL_PWD="$OJ_DB_PASS" mysql -umusealgo_app oj_db -e "CREATE TABLE forbidden_ddl(id INT)"'],
                                  env=base_env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            assert result.returncode!=0 and b'denied' in result.stderr.lower()
            runtime_ddl={'returncode':result.returncode,'denial':result.stderr.decode(errors='replace').strip()[:240]}
            web='http://127.0.0.1:'+values['WEB_PORT']
            assert status(web+'/')[0]==200
            gateway_probes=[]
            for path in ('/api/actuator/prometheus','/api/actuator/health','/api/doc.html','/api/v3/api-docs'):
                observed=normalized_http(web+path)
                assert observed['status'] in (403,404),(path,observed)
                gateway_probes.append({'path':path,**observed})
            backend='http://127.0.0.1:'+values['BACKEND_PORT']
            direct_probes=[]
            for path in ('/actuator/prometheus','/doc.html'):
                observed=normalized_http(backend+path)
                assert observed['status']==401,(path,observed)
                direct_probes.append({'path':path,**observed})
            assert sql(source,file,"SELECT GROUP_CONCAT(CONCAT(id,':',code) ORDER BY id) FROM sys_role;")=="1:STUDENT,2:TEACHER,3:ADMIN"
            evidence['reference_data']=check_reference_data(lambda query: sql(source,file,query), ROOT)
            read_before=database_summary(source,file)
            services_before=app_state(source,file)
            report=json.loads(ops(source,file,'status','--json'))
            read_after=database_summary(source,file)
            services_after=app_state(source,file)
            assert report['ready'] and report['current']==str(latest_schema),report
            assert read_before==read_after,(read_before,read_after)
            assert services_before==services_after,(services_before,services_after)
            resolved_config=json.loads(compose(source,file,'config','--format','json',stdout=subprocess.PIPE).stdout)
            custom_compose=base/(source+'-hardcoded-compose.yml')
            custom_compose.write_text(json.dumps(resolved_config));custom_compose.chmod(0o600)
            custom_before=database_summary(source,file)
            custom_result=run_ops(source,file,'status','--json',compose_file=custom_compose)
            assert custom_result.returncode==0,custom_result.stderr.decode()
            custom_report=json.loads(custom_result.stdout)
            pinned_config=effective_images(source,file,custom_compose,custom_report['image_ids'])
            custom_after=database_summary(source,file)
            assert custom_report['current']==str(latest_schema) and custom_before==custom_after
            assert pinned_config==custom_report['image_ids'],(pinned_config,custom_report['image_ids'])
            evidence['readonly_status']={'current':report['current'],'target':report['target'],
                'image_ids':report['image_ids'],'database_before':read_before,'database_after':read_after,
                'application_state_before':services_before,'application_state_after':services_after}
            evidence['custom_compose_status']={'status_report':custom_report,'effective_config_images':pinned_config,
                'database_before':custom_before,'database_after':custom_after}
            evidence['http_boundary']={'gateway':gateway_probes,'direct_backend':direct_probes}
            evidence['fresh_install']={'schema':latest_schema,'migration_history_rows':read_after['history_rows'],
                'runtime_ddl':runtime_ddl,'gateway_internal_routes':gateway_probes,
                'direct_internal_routes':direct_probes,'roles':sql(source,file,"SELECT GROUP_CONCAT(CONCAT(id,':',code) ORDER BY id) FROM sys_role;")}
            # Exercise actual authentication and role checks in both compatibility modes.
            # The only target is this random, disposable drill project; no mutation endpoint is called.
            seed_sql=(ROOT/'OJ-Backend/db/seed.sql').read_text()
            password_match=re.search(r"\(1,\s*'admin',\s*'([^']+)'",seed_sql)
            assert password_match,'Public seed admin bcrypt hash not found'
            auth_password_hash=password_match.group(1)
            auth_suffix=secrets.token_hex(5)
            auth_admin='authadmin_'+auth_suffix
            auth_student='authstudent_'+auth_suffix
            assert sql(source,file,f"SELECT COUNT(*) FROM user WHERE username IN ('{auth_admin}','{auth_student}');")== '0'
            sql(source,file,"INSERT INTO user(username,password,nickname,role) VALUES "
                f"('{auth_admin}','{auth_password_hash}','drill admin',3),"
                f"('{auth_student}','{auth_password_hash}','drill student',1);")
            auth_admin_id=int(sql(source,file,f"SELECT id FROM user WHERE username='{auth_admin}';"))
            auth_student_id=int(sql(source,file,f"SELECT id FROM user WHERE username='{auth_student}';"))
            sql(source,file,f"INSERT INTO sys_user_role(user_id,role_id) VALUES ({auth_admin_id},3),({auth_student_id},1);")
            def auth_role_columns():
                return sql(source,file,"SELECT GROUP_CONCAT(CONCAT(CASE id "
                    f"WHEN {auth_admin_id} THEN 'admin' WHEN {auth_student_id} THEN 'student' END,':',role) "
                    "ORDER BY id) FROM user WHERE id IN ("
                    f"{auth_admin_id},{auth_student_id});")
            def auth_role_links():
                return sql(source,file,"SELECT GROUP_CONCAT(CONCAT(CASE ur.user_id "
                    f"WHEN {auth_admin_id} THEN 'admin' WHEN {auth_student_id} THEN 'student' END,':',r.code) "
                    "ORDER BY ur.user_id) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id "
                    f"WHERE ur.user_id IN ({auth_admin_id},{auth_student_id});")
            def login_and_probe(username,expected_status):
                login_status,login_body=api_call(backend+'/auth/login','POST',
                    {'username':username,'password':'123456'})
                login_payload=json.loads(login_body)
                assert login_status==200 and login_payload.get('code')==200,{
                    'http_status':login_status,'business_code':login_payload.get('code')}
                access_token=login_payload['data']['accessToken']
                probe_status,probe_body=api_call(backend+'/admin/rejudge/operations',token=access_token)
                probe_payload=json.loads(probe_body)
                observed={'http_status':probe_status,'business_code':probe_payload.get('code')}
                assert probe_status==expected_status and observed['business_code']==expected_status,observed
                # Never retain credentials or include the response payload in evidence.
                del access_token,login_payload,login_body,probe_body,probe_payload
                return observed
            def normalized_compose(*parts,**kwargs):
                command=['docker','compose','--env-file',str(file),'-p',source,
                    '-f',str(ROOT/'docker-compose.prod.yml'),'-f',str(auth_override)]+list(parts)
                return subprocess.run(command,env=base_env,check=True,**kwargs)
            auth_evidence={}
            auth_cleanup_error=None
            auth_override=base/(source+'-normalized-rbac.yml')
            try:
                legacy_flag=compose(source,file,'exec','-T','backend','sh','-c',
                    'printf %s "${OJ_NORMALIZED_RBAC:-false}"',stdout=subprocess.PIPE).stdout.decode().strip().lower()
                assert legacy_flag in ('false','0',''),legacy_flag
                assert auth_role_columns()=='admin:3,student:1',auth_role_columns()
                assert auth_role_links()=='admin:ADMIN,student:STUDENT',auth_role_links()
                auth_evidence['legacy_mode']={'container_flag':legacy_flag or 'false',
                    'legacy_role_columns':auth_role_columns(),'normalized_associations':auth_role_links(),
                    'admin':login_and_probe(auth_admin,200),'student':login_and_probe(auth_student,403)}

                auth_override.write_text(json.dumps({'services':{'backend':{'environment':{'OJ_NORMALIZED_RBAC':'true'}}}}))
                auth_override.chmod(0o600)
                normalized_config=normalized_compose('config','--format','json',stdout=subprocess.PIPE).stdout
                normalized_config=json.loads(normalized_config)
                assert str(normalized_config['services']['backend']['environment'].get('OJ_NORMALIZED_RBAC')).lower()=='true'
                normalized_compose('up','-d','--no-deps','--force-recreate','--wait','backend')
                normalized_flag=normalized_compose('exec','-T','backend','sh','-c',
                    'printf %s "${OJ_NORMALIZED_RBAC:-false}"',stdout=subprocess.PIPE).stdout.decode().strip().lower()
                assert normalized_flag=='true',normalized_flag
                assert auth_role_links()=='admin:ADMIN,student:STUDENT',auth_role_links()
                sql(source,file,f"UPDATE user SET role=CASE id WHEN {auth_admin_id} THEN 1 "
                    f"WHEN {auth_student_id} THEN 3 END WHERE id IN ({auth_admin_id},{auth_student_id});")
                assert auth_role_columns()=='admin:1,student:3',auth_role_columns()
                auth_evidence['normalized_mode']={'compose_environment':'true','container_flag':normalized_flag,
                    'legacy_role_columns':auth_role_columns(),'normalized_associations':auth_role_links(),
                    'admin':login_and_probe(auth_admin,200),'student':login_and_probe(auth_student,403)}
            finally:
                try:
                    # Recreate from the normal project config before deleting the fixture users.
                    compose(source,file,'up','-d','--no-deps','--force-recreate','--wait','backend')
                    default_flag=compose(source,file,'exec','-T','backend','sh','-c',
                        'printf %s "${OJ_NORMALIZED_RBAC:-false}"',stdout=subprocess.PIPE).stdout.decode().strip().lower()
                    assert default_flag in ('false','0',''),default_flag
                    sql(source,file,f"DELETE FROM sys_user_role WHERE user_id IN ({auth_admin_id},{auth_student_id}); "
                        f"DELETE FROM user WHERE id IN ({auth_admin_id},{auth_student_id});")
                    assert sql(source,file,f"SELECT COUNT(*) FROM user WHERE id IN ({auth_admin_id},{auth_student_id});")== '0'
                    auth_evidence['cleanup']={'default_mode_restored':True,'synthetic_users_removed':True}
                except Exception as error:
                    auth_cleanup_error=error
                finally:
                    auth_override.unlink(missing_ok=True)
            if auth_cleanup_error:
                raise RuntimeError('HTTP role matrix cleanup failed in disposable source project') from auth_cleanup_error
            evidence['http_role_matrix']=auth_evidence
            # The local maintenance lock must reject a concurrent apply before touching Docker or SQL.
            lock_directory=ROOT.parent/'outputs'/'operations'
            lock_directory.mkdir(parents=True,exist_ok=True,mode=0o700);lock_directory.chmod(0o700)
            lock_path=lock_directory/(hashlib.sha256(source.encode()).hexdigest()[:24]+'.lock')
            with lock_path.open('a') as lock:
                os.fchmod(lock.fileno(),0o600)
                fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
                lock_before=database_summary(source,file)
                lock_result=run_ops(source,file,'upgrade','--apply')
                lock_after=database_summary(source,file)
            assert lock_result.returncode==1 and '已有维护操作' in lock_result.stderr.decode(),lock_result.stderr.decode()
            assert lock_before==lock_after,(lock_before,lock_after)
            evidence['maintenance_lock']={'second_writer_rejected':True,'database_before':lock_before,
                                          'database_after':lock_after}
            legacy_image=args.legacy_maintenance_image
            legacy_env=base/(source+'-legacy.env')
            legacy_values={**values,'BACKEND_IMAGE':legacy_image}
            write_env(legacy_env,legacy_values)
            legacy_before=database_summary(source,file)
            legacy_result=run_ops(source,legacy_env,'status','--json')
            legacy_after=database_summary(source,file)
            assert legacy_result.returncode==1,legacy_result.stdout.decode()
            legacy_detail=legacy_result.stderr.decode()
            log_match=re.search(r'诊断日志：([^\r\n]+)',legacy_detail)
            diagnostic=Path(log_match.group(1)).read_text(errors='replace') if log_match else ''
            assert 'DatabaseMaintenance' in diagnostic and ('ClassNotFound' in diagnostic or 'Could not find or load' in diagnostic),diagnostic
            assert legacy_before==legacy_after,(legacy_before,legacy_after)
            entrypoint_error=next((line.strip() for line in diagnostic.splitlines()
                if 'DatabaseMaintenance' in line and ('ClassNotFound' in line or 'Could not find or load' in line)), '')
            evidence['legacy_status']={'image':legacy_image,'returncode':legacy_result.returncode,
                'entrypoint_error':entrypoint_error,'database_before':legacy_before,'database_after':legacy_after}
            sql(source,file,"INSERT INTO user(username,password,nickname) VALUES ('drill-user','not-a-login','drill');")
            compose(source,file,'exec','-T','backend','sh','-c',
                    'printf testcase-proof > /data/testcases/drill.txt; printf upload-proof > /data/uploads/drill.txt; printf courseware-proof > /app/OJ-Coursewares/drill.txt')
            before=sql(source,file,"SELECT username,nickname FROM user WHERE username='drill-user';")
            data_before_recreate=database_summary(source,file)
            # Recreate app containers: writable data must stay in volumes.
            compose(source,file,'up','-d','--force-recreate','--wait','backend','frontend')
            assert before==sql(source,file,"SELECT username,nickname FROM user WHERE username='drill-user';")
            data_after_recreate=database_summary(source,file)
            assert data_before_recreate==data_after_recreate
            files=compose(source,file,'exec','-T','backend','sha256sum','/data/testcases/drill.txt','/data/uploads/drill.txt','/app/OJ-Coursewares/drill.txt',stdout=subprocess.PIPE).stdout
            evidence['container_recreate']={'database_before':data_before_recreate,'database_after':data_after_recreate,
                'volume_hashes':files.decode().splitlines()}
            backup=output/'backups'/('snapshot-'+secrets.token_hex(4))
            before_backup=app_state(source,file)
            assert before_backup.get('report-worker')=='running',before_backup
            backup_result=json.loads(ops(source,file,'backup','--output',str(backup)))
            verification=ops(source,file,'verify','--source',str(backup)).strip()
            health=json.loads(ops(source,file,'check','--backup-root',str(backup.parent)))
            manifest=json.loads((backup/'manifest.json').read_text())
            assert backup_result['verified'] and verification=='Backup checksums and archive members verified'
            assert set(manifest['files'])=={'database.sql','testcases.tar.gz','uploads.tar.gz','coursewares.tar.gz'}
            assert 'report-worker' in manifest['running_services'],manifest
            assert manifest['images']['report-worker']==report['image_ids']['report-worker'],manifest
            assert app_state(source,file).get('report-worker')=='running'
            assert health['ok'],health
            evidence['backup']={'path':str(backup),'manifest_sha256':hashlib.sha256((backup/'manifest.json').read_bytes()).hexdigest(),
                'file_sha256':manifest['files'],'image_ids':manifest['images'],'verify_output':verification,
                'health_report':health}
            # Negative monitoring test without a real application failure.
            sql(source,file,"INSERT INTO submission(problem_id,user_id,language,status,judge_phase) SELECT 1,id,'cpp',0,'JUDGING' FROM user WHERE username='drill-user'; INSERT INTO judge_task(submission_id,status,locked_at,attempt_count) SELECT MAX(id),'PROCESSING',NOW()-INTERVAL 20 MINUTE,1 FROM submission;")
            probe=run_ops(source,file,'check','--backup-root',str(backup.parent))
            probe_report=json.loads(probe.stdout)
            assert probe.returncode==1 and probe_report['stalled_judges']>=1,probe_report
            evidence['alert_negative']={'returncode':probe.returncode,'report':probe_report}
            sql(source,file,"DELETE FROM judge_task; DELETE FROM submission;")
            # Restore to a new project using different credentials and ports.
            restored,restored_values,restored_file=environment('restore')
            restore_source_summary=database_summary(source,file)
            print(ops(restored,restored_file,'restore','--source',str(backup),'--confirm-empty-target'),flush=True)
            assert app_state(restored,restored_file).get('report-worker')=='running'
            assert before==sql(restored,restored_file,"SELECT username,nickname FROM user WHERE username='drill-user';")
            restore_target_summary=database_summary(restored,restored_file)
            assert restore_source_summary==restore_target_summary,(restore_source_summary,restore_target_summary)
            after=compose(restored,restored_file,'exec','-T','backend','sha256sum','/data/testcases/drill.txt','/data/uploads/drill.txt','/app/OJ-Coursewares/drill.txt',stdout=subprocess.PIPE).stdout
            assert files==after
            evidence['restore']={'source_database':restore_source_summary,'restored_database':restore_target_summary,
                'source_volume_hashes':files.decode().splitlines(),'restored_volume_hashes':after.decode().splitlines(),
                'different_credentials':values['OJ_DB_PASS']!=restored_values['OJ_DB_PASS']}
            # Older manifests had neither image identities nor worker state. A manually
            # matched image/schema pair remains restorable, without starting a worker
            # whose original state and image are unknown.
            legacy_backup=output/'backups'/('legacy-no-images-'+secrets.token_hex(4))
            shutil.copytree(backup,legacy_backup)
            legacy_manifest_path=legacy_backup/'manifest.json'
            legacy_manifest=json.loads(legacy_manifest_path.read_text())
            legacy_manifest.pop('images',None)
            legacy_manifest.pop('maintenance_image_ids',None)
            legacy_manifest.pop('running_services',None)
            legacy_manifest_path.write_text(json.dumps(legacy_manifest,indent=2)+'\n')
            legacy_project,_,legacy_file=environment('legacy-no-images')
            legacy_result=json.loads(ops(legacy_project,legacy_file,'restore','--source',str(legacy_backup),
                                         '--confirm-empty-target','--skip-migrations'))
            legacy_state=app_state(legacy_project,legacy_file)
            assert legacy_state.get('backend')=='running' and legacy_state.get('frontend')=='running',legacy_state
            assert legacy_state.get('report-worker')!='running',legacy_state
            legacy_database=database_summary(legacy_project,legacy_file)
            assert legacy_database==restore_source_summary,(legacy_database,restore_source_summary)
            legacy_hashes=compose(legacy_project,legacy_file,'exec','-T','backend','sha256sum',
                '/data/testcases/drill.txt','/data/uploads/drill.txt','/app/OJ-Coursewares/drill.txt',stdout=subprocess.PIPE).stdout
            assert legacy_hashes==files
            evidence['legacy_no_images_restore']={'result':legacy_result,'application_state':legacy_state,
                'restored_database':legacy_database,'volume_hashes':legacy_hashes.decode().splitlines(),
                'worker_left_stopped_without_recorded_state':True}
            compose(legacy_project,legacy_file,'stop','backend','frontend')
            # Compatible binary rollback: old image runs with Flyway disabled, current migrator is retained.
            if args.previous_backend_image:
                override=base/'rollback.yml';override.write_text('services:\n  backend:\n    image: '+args.previous_backend_image+'\n')
                command=['docker','compose','--env-file',str(file),'-p',source,'-f',str(ROOT/'docker-compose.prod.yml'),'-f',str(override)]
                subprocess.run(command+['up','-d','--wait','backend'],env=base_env,check=True)
                assert before==sql(source,file,"SELECT username,nickname FROM user WHERE username='drill-user';")
                rollback_health=json.loads(status(backend+'/actuator/health')[1])
                assert rollback_health['status']=='UP'
                compose(source,file,'up','-d','--wait','backend')
                evidence['compatible_binary_rollback']={'image':args.previous_backend_image,'health':rollback_health,
                    'preserved_user':before}
            compose(source,file,'stop','backend','frontend','report-worker')
            compose(restored,restored_file,'stop','backend','frontend','report-worker')
            # V53->current migrations upgrade on a new DB, using the exact schema baseline and current migration entry.
            upgrade,upgrade_values,upgrade_file=environment('upgrade')
            compose(upgrade,upgrade_file,'up','-d','--wait','mysql','redis')
            assert sql(upgrade,upgrade_file,"SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;")=='53'
            sql(upgrade,upgrade_file,"INSERT INTO user(username,password,nickname) VALUES ('upgrade-user','not-a-login','upgrade-proof');")
            old_backup=output/'backups'/('v53-'+secrets.token_hex(4))
            old_backup_env=upgrade_file
            if args.previous_backend_image:
                old_values={**upgrade_values,'BACKEND_IMAGE':args.previous_backend_image}
                old_backup_env=base/(upgrade+'-previous.env');write_env(old_backup_env,old_values)
            print(ops(upgrade,old_backup_env,'backup','--output',str(old_backup)),flush=True)
            preview_before=database_summary(upgrade,upgrade_file)
            preview_services_before=app_state(upgrade,upgrade_file)
            plan=json.loads(ops(upgrade,upgrade_file,'upgrade'))
            preview_after=database_summary(upgrade,upgrade_file)
            preview_services_after=app_state(upgrade,upgrade_file)
            assert plan['preview'] and len(plan['pending'])>0,plan
            assert preview_before==preview_after,(preview_before,preview_after)
            assert preview_services_before==preview_services_after,(preview_services_before,preview_services_after)
            evidence['upgrade_preview']={'pending':plan['pending'],'current':plan['current'],'target':plan['target'],
                'image_ids':plan['image_ids'],'database_before':preview_before,'database_after':preview_after,
                'application_state_before':preview_services_before,'application_state_after':preview_services_after}
            # A path that cannot contain the automatic backup must fail before migration DDL.
            backup_blocker=base/'backup-root-is-a-file';backup_blocker.write_text('blocker')
            before_backup_failure=database_summary(upgrade,upgrade_file)
            backup_failure=run_ops(upgrade,upgrade_file,'upgrade','--apply','--backup-root',str(backup_blocker))
            after_backup_failure=database_summary(upgrade,upgrade_file)
            assert backup_failure.returncode==1 and '一致备份' in backup_failure.stderr.decode(),backup_failure.stderr.decode()
            assert before_backup_failure==after_backup_failure,(before_backup_failure,after_backup_failure)
            evidence['backup_failure']={'rejected_before_migration':True,'returncode':backup_failure.returncode,
                'database_before':before_backup_failure,'database_after':after_backup_failure}
            # A corrupted copy of the verified backup must be rejected before restore creates target resources.
            corrupt_backup=output/'backups'/('corrupt-'+secrets.token_hex(4))
            shutil.copytree(old_backup,corrupt_backup)
            with (corrupt_backup/'database.sql').open('ab') as stream:stream.write(b'\n-- deliberate corruption\n')
            corrupt_project,corrupt_values,corrupt_file=environment('corrupt-restore')
            source_before_corrupt_restore=database_summary(upgrade,upgrade_file)
            corrupt_restore=run_ops(corrupt_project,corrupt_file,'restore','--source',str(corrupt_backup),'--confirm-empty-target')
            source_after_corrupt_restore=database_summary(upgrade,upgrade_file)
            target_volumes=subprocess.run(['docker','volume','ls','--filter',
                'label=com.docker.compose.project='+corrupt_project,'-q'],env=base_env,check=True,
                stdout=subprocess.PIPE).stdout.decode().strip()
            assert corrupt_restore.returncode==1 and 'checksum mismatch' in corrupt_restore.stderr.decode(),corrupt_restore.stderr.decode()
            assert source_before_corrupt_restore==source_after_corrupt_restore
            assert not target_volumes,target_volumes
            evidence['corrupt_backup_rejected']={'returncode':corrupt_restore.returncode,
                'source_database_before':source_before_corrupt_restore,'source_database_after':source_after_corrupt_restore,
                'target_volumes_created':target_volumes}
            # 历史校验失败须在停站/备份/DDL前被发现；只在本轮临时库注入损坏再还原原值。
            checksum=sql(upgrade,upgrade_file,"SELECT checksum FROM flyway_schema_history WHERE version='7';")
            valid_history_summary=database_summary(upgrade,upgrade_file)
            sql(upgrade,upgrade_file,"UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='7';")
            damaged_history_summary=database_summary(upgrade,upgrade_file)
            rejected=run_ops(upgrade,upgrade_file,'upgrade','--apply')
            after_rejection=database_summary(upgrade,upgrade_file)
            assert rejected.returncode==1 and '操作未完成' in rejected.stderr.decode(),rejected.stderr.decode()
            assert after_rejection==damaged_history_summary,(damaged_history_summary,after_rejection)
            assert sql(upgrade,upgrade_file,"SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;")=='53'
            sql(upgrade,upgrade_file,"UPDATE flyway_schema_history SET checksum="+checksum+" WHERE version='7';")
            apply_result=run_ops(upgrade,upgrade_file,'upgrade','--apply','--backup-root',str(output/'automatic-backups'))
            assert apply_result.returncode==0,apply_result.stderr.decode()
            upgraded=json.loads(apply_result.stdout)
            preflight_lines=apply_result.stderr.decode().splitlines()
            preflight=[json.loads(line) for line in preflight_lines if line.strip()]
            assert len(preflight)==1 and preflight[0]['image_ids']==upgraded['image_ids'],preflight_lines
            assert upgraded['ok'] and upgraded['changed']
            upgrade_manifest=json.loads((Path(upgraded['backup'])/'manifest.json').read_text())
            assert upgrade_manifest['maintenance_image_ids']==upgraded['image_ids'],upgrade_manifest
            upgraded_runtime_ids=running_image_ids(upgrade,upgrade_file)
            assert upgraded_runtime_ids=={name:upgraded['image_ids'][name] for name in ('backend','frontend','report-worker')},upgraded_runtime_ids
            assert effective_images(upgrade,upgrade_file,ROOT/'docker-compose.prod.yml',upgraded['image_ids'])==upgraded['image_ids']
            upgraded_summary=database_summary(upgrade,upgrade_file)
            repeat=json.loads(ops(upgrade,upgrade_file,'upgrade','--apply','--backup-root',str(output/'automatic-backups')))
            assert repeat['ok'] and not repeat['changed']
            repeated_summary=database_summary(upgrade,upgrade_file)
            assert upgraded_summary==repeated_summary,(upgraded_summary,repeated_summary)
            evidence['guarded_upgrade']={'preview_database_before':preview_before,'preview_database_after':preview_after,
                'automatic_backup':upgraded['backup'],'backup_manifest':upgrade_manifest,
                'runtime_image_ids':upgraded_runtime_ids,'effective_compose_images':effective_images(upgrade,upgrade_file,ROOT/'docker-compose.prod.yml',upgraded['image_ids']),
                'preflight_stderr':preflight[0],
                'bad_checksum_database_before':damaged_history_summary,'bad_checksum_database_after':after_rejection,
                'history_before_injected_damage':valid_history_summary,'repeat_noop':repeat,
                'database_after_upgrade':upgraded_summary,'database_after_repeat':repeated_summary}
            assert sql(upgrade,upgrade_file,"SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;")==str(latest_schema)
            assert sql(upgrade,upgrade_file,"SELECT nickname FROM user WHERE username='upgrade-user';")=='upgrade-proof'
            evidence['upgrade']={'from':53,'to':latest_schema,'database_after':upgraded_summary,
                'business_record':sql(upgrade,upgrade_file,"SELECT nickname FROM user WHERE username='upgrade-user';"),
                'history_latest':sql(upgrade,upgrade_file,"SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;")}
            if args.previous_backend_image:
                legacy,legacy_values,legacy_file=environment('schema-rollback',args.previous_backend_image)
                print(ops(legacy,legacy_file,'restore','--source',str(old_backup),'--confirm-empty-target','--skip-migrations'),flush=True)
                assert sql(legacy,legacy_file,"SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;")=='53'
                legacy_record=sql(legacy,legacy_file,"SELECT nickname FROM user WHERE username='upgrade-user';")
                assert legacy_record=='upgrade-proof'
                evidence['schema_rollback']={'schema':sql(legacy,legacy_file,"SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;"),
                    'image':args.previous_backend_image,'business_record':legacy_record}
            # A known pre-V63 image provides an exact V62 fixture; the current target applies V63 and every later migration.
            v62,v62_values,v62_file=environment('v62-seed',args.v62_backend_image)
            # V62 夹具镜像早于报告 worker 拆分，没有 readiness 探针；旧版本只能启动当时
            # 存在的服务集合，不能用当前 compose 隐式补起旧 worker。
            compose(v62,v62_file,'up','--pull','never','-d','--wait','mysql','redis','backend','frontend')
            v62_rows_before=service_rows(v62,v62_file)
            v62_running_before=sorted(row.get('Service') for row in v62_rows_before if row.get('State')=='running')
            assert v62_running_before==['backend','frontend','mysql','redis'],v62_rows_before
            assert not any(row.get('Service')=='report-worker' for row in v62_rows_before),v62_rows_before
            v62_services_before={row.get('Service'):{'state':row.get('State'),'health':row.get('Health')}
                                 for row in v62_rows_before}
            v62_seed=sql(v62,v62_file,"SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;")
            assert v62_seed=='62',{'expected':'62','observed':v62_seed,'v62_backend_image':args.v62_backend_image}
            sql(v62,v62_file,"INSERT INTO user(username,password,nickname) VALUES ('v62-user','not-a-login','v62-proof');")
            compose(v62,v62_file,'exec','-T','backend','sh','-c',
                'printf v62-testcase > /data/testcases/v62.txt; printf v62-upload > /data/uploads/v62.txt; printf v62-courseware > /app/OJ-Coursewares/v62.txt')
            v62_volume_hashes=compose(v62,v62_file,'exec','-T','backend','sha256sum','/data/testcases/v62.txt','/data/uploads/v62.txt','/app/OJ-Coursewares/v62.txt',stdout=subprocess.PIPE).stdout.decode().splitlines()
            v62_roles_before=sql(v62,v62_file,"SELECT GROUP_CONCAT(CONCAT(id,':',code) ORDER BY id) FROM sys_role;")
            assert v62_roles_before=='1:STUDENT,2:TEACHER,3:ADMIN',v62_roles_before
            v62_feature_codes_before=sql(v62,v62_file,"SELECT GROUP_CONCAT(code ORDER BY code) FROM sys_permission WHERE code IN ('judge:rejudge','report:generate','report:read');")
            assert v62_feature_codes_before=='judge:rejudge,report:generate,report:read',v62_feature_codes_before
            compose(v62,v62_file,'stop','--timeout','60','backend','frontend')
            assert sql(v62,v62_file,'SELECT COUNT(*) FROM sys_user_role;')=='0','V62 fixture unexpectedly has user-role links'
            sql(v62,v62_file,'DELETE FROM sys_role_permission; DELETE FROM sys_role; '
                "DELETE FROM sys_permission WHERE code IN ('problem:manage','contest:manage','class:member:manage','submission:read:private');")
            assert sql(v62,v62_file,'SELECT COUNT(*) FROM sys_role;')=='0'
            assert sql(v62,v62_file,'SELECT COUNT(*) FROM sys_role_permission;')=='0'
            assert sql(v62,v62_file,"SELECT COUNT(*) FROM sys_permission WHERE code IN ('problem:manage','contest:manage','class:member:manage','submission:read:private');")=='0'
            assert sql(v62,v62_file,"SELECT GROUP_CONCAT(code ORDER BY code) FROM sys_permission WHERE code IN ('judge:rejudge','report:generate','report:read');")==v62_feature_codes_before
            v62_empty_role_database=database_summary(v62,v62_file)
            v62_users_before=sql(v62,v62_file,'SELECT GROUP_CONCAT(username ORDER BY id) FROM user;')
            assert v62_users_before=='v62-user',v62_users_before
            current_values={**v62_values,'BACKEND_IMAGE':args.backend_image,'FRONTEND_IMAGE':args.frontend_image}
            current_file=base/(v62+'-current-maintenance.env');write_env(current_file,current_values)
            v62_status_before=database_summary(v62,current_file)
            v62_preview_services=app_state(v62,v62_file)
            v62_status=json.loads(ops(v62,current_file,'status','--json'))
            v62_status_after=database_summary(v62,current_file)
            assert v62_status['current']=='62' and v62_status['target']==str(latest_schema),v62_status
            expected_v62_pending=[str(version) for version in range(63,latest_schema+1)]
            assert [item['version'] for item in v62_status['pending']]==expected_v62_pending,v62_status
            assert v62_status_before==v62_status_after==v62_empty_role_database
            v62_preview=json.loads(ops(v62,current_file,'upgrade'))
            assert v62_preview['preview'] and [item['version'] for item in v62_preview['pending']]==expected_v62_pending,v62_preview
            assert database_summary(v62,current_file)==v62_status_after
            v62_backup_root=output/'v62-automatic-backups'
            v62_apply=run_ops(v62,current_file,'upgrade','--apply','--backup-root',str(v62_backup_root))
            assert v62_apply.returncode==0,v62_apply.stderr.decode()
            v62_result=json.loads(v62_apply.stdout)
            v62_preflight=[json.loads(line) for line in v62_apply.stderr.decode().splitlines() if line.strip()]
            assert len(v62_preflight)==1 and v62_preflight[0]['current']=='62',v62_preflight
            assert v62_result['ok'] and v62_result['changed'] and v62_result['current']=='62' and v62_result['target']==str(latest_schema),v62_result
            v62_manifest=json.loads((Path(v62_result['backup'])/'manifest.json').read_text())
            assert v62_manifest['database_version']=='62',v62_manifest
            assert v62_manifest['maintenance_image_ids']==v62_result['image_ids'],v62_manifest
            # 升级命令自身必须在迁移后启动新 worker；不由演练额外补启。
            v62_rows_after=service_rows(v62,current_file)
            v62_workers=[row for row in v62_rows_after if row.get('Service')=='report-worker']
            assert len(v62_workers)==1,v62_rows_after
            v62_worker=v62_workers[0]
            assert v62_worker.get('State')=='running' and v62_worker.get('Health')=='healthy',v62_worker
            v62_running_after=sorted(row.get('Service') for row in v62_rows_after if row.get('State')=='running')
            assert v62_running_after==['backend','frontend','mysql','redis','report-worker'],v62_rows_after
            v62_worker_image_id=subprocess.run(['docker','inspect','--format={{.Image}}',v62_worker['ID']],
                env=base_env,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True).stdout.strip()
            assert v62_worker_image_id==v62_result['image_ids']['backend'],(v62_worker_image_id,v62_result['image_ids'])
            v62_services_after={row.get('Service'):{'state':row.get('State'),'health':row.get('Health')}
                                for row in v62_rows_after}
            v62_runtime_ids=running_image_ids(v62,current_file)
            assert v62_runtime_ids=={name:v62_result['image_ids'][name] for name in ('backend','frontend','report-worker')},v62_runtime_ids
            v62_effective_images=effective_images(v62,current_file,ROOT/'docker-compose.prod.yml',v62_result['image_ids'])
            assert v62_effective_images==v62_result['image_ids'],v62_effective_images
            v62_roles_after=sql(v62,current_file,"SELECT GROUP_CONCAT(CONCAT(id,':',code) ORDER BY id) FROM sys_role;")
            v62_grants_after=sql(v62,current_file,"SELECT GROUP_CONCAT(CONCAT(r.code,':',p.code) ORDER BY r.code,p.code) FROM sys_role_permission rp JOIN sys_role r ON r.id=rp.role_id JOIN sys_permission p ON p.id=rp.permission_id;")
            expected_v62_grants=','.join(sorted(
                [f'ADMIN:{code}' for code in ('problem:manage','contest:manage','class:member:manage','submission:read:private','judge:rejudge','report:generate','report:read')]+
                [f'TEACHER:{code}' for code in ('problem:manage','contest:manage','class:member:manage','report:generate','report:read')]))
            v62_users_after=sql(v62,current_file,'SELECT GROUP_CONCAT(username ORDER BY id) FROM user;')
            assert v62_roles_after=='1:STUDENT,2:TEACHER,3:ADMIN',v62_roles_after
            assert v62_grants_after==expected_v62_grants,(expected_v62_grants,v62_grants_after)
            assert v62_users_after==v62_users_before and sql(v62,current_file,"SELECT COUNT(*) FROM user WHERE username LIKE 'demo%';")=='0'
            v62_upgraded=database_summary(v62,current_file)
            v62_repeated=json.loads(ops(v62,current_file,'upgrade','--apply','--backup-root',str(v62_backup_root)))
            assert v62_repeated['ok'] and not v62_repeated['changed'],v62_repeated
            assert database_summary(v62,current_file)==v62_upgraded
            v62_restore,v62_restore_values,v62_restore_file=environment('v62-restore',args.backend_image)
            print(ops(v62_restore,v62_restore_file,'restore','--source',v62_result['backup'],'--confirm-empty-target'),flush=True)
            v62_restored_summary=database_summary(v62_restore,v62_restore_file)
            assert v62_restored_summary==v62_upgraded,(v62_upgraded,v62_restored_summary)
            v62_restored_hashes=compose(v62_restore,v62_restore_file,'exec','-T','backend','sha256sum','/data/testcases/v62.txt','/data/uploads/v62.txt','/app/OJ-Coursewares/v62.txt',stdout=subprocess.PIPE).stdout.decode().splitlines()
            assert v62_restored_hashes==v62_volume_hashes,(v62_volume_hashes,v62_restored_hashes)
            evidence['v62_to_current']={'seed_image':args.v62_backend_image,'current_image_ids':v62_status['image_ids'],
                'seed_history_max':v62_seed,'status':v62_status,'preview':v62_preview,
                'services_before_upgrade':v62_services_before,'services_after_worker_start':v62_services_after,
                'report_worker_image_id':v62_worker_image_id,
                'database_before_status':v62_status_before,'database_after_status':v62_status_after,
                'roles_before_removal':v62_roles_before,'feature_permissions_preserved':v62_feature_codes_before,
                'database_at_empty_role_fixture':v62_empty_role_database,'users_before':v62_users_before,
                'roles_after_current':v62_roles_after,'default_grants_after_current':v62_grants_after,'users_after':v62_users_after,
                'application_state':v62_preview_services,'preflight_stderr':v62_preflight[0],
                'apply_result':v62_result,'backup_manifest':v62_manifest,'repeat_noop':v62_repeated,
                'actual_runtime_image_ids':v62_runtime_ids,'effective_compose_images':v62_effective_images,
                'database_after_upgrade':v62_upgraded,'database_after_backup_restore':v62_restored_summary,
                'volume_hashes_before_upgrade':v62_volume_hashes,'volume_hashes_after_restore':v62_restored_hashes}
            (output/'operations-drill.json').write_text(json.dumps(evidence,indent=2)+'\n')
            print(json.dumps(evidence),flush=True)
        finally:
            cleanup_errors=[]
            for project in reversed(projects):
                try:
                    credential_file=base/(project+'.env')
                    with (output/(project+'.log')).open('wb') as log:
                        compose(project,credential_file,'logs','--no-color',stdout=log,stderr=subprocess.STDOUT)
                    compose(project,credential_file,'down','-v','--remove-orphans')
                except subprocess.CalledProcessError:
                    print('Cleanup failed for '+project,flush=True)
                    cleanup_errors.append(project)
            if cleanup_errors:
                raise RuntimeError('Some exercise resources need cleanup: '+', '.join(cleanup_errors))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend-image',required=True)
    parser.add_argument('--frontend-image',required=True)
    parser.add_argument('--previous-backend-image')
    parser.add_argument('--v62-backend-image',required=True,
                        help='合成V62后端镜像：仅移除V63及以后迁移，用于V62→当前目标验证')
    parser.add_argument('--legacy-maintenance-image',required=True,
                        help='合成旧维护入口镜像：仅移除DatabaseMaintenance.class，用于验证status fail-closed')
    parser.add_argument('--output',default='/tmp/musealgo-operations-evidence')
    main(parser.parse_args())
