#!/usr/bin/env python3
"""Local release/backup/recovery exercise. Touches only newly generated musealgo-drill-* projects."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
HTTP=urllib.request.build_opener(urllib.request.ProxyHandler({}))


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));return sock.getsockname()[1]


def main(args):
    output=Path(args.output).resolve();output.mkdir(parents=True,exist_ok=True)
    os.umask(0o077)
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
            file=base/(project+'.env');file.write_text('\n'.join(f'{k}={v}' for k,v in values.items())+'\n');file.chmod(0o600)
            return project,values,file
        def compose(project,file,*parts,**kwargs):
            command=['docker','compose','--env-file',str(file),'-p',project,'-f',str(ROOT/'docker-compose.prod.yml')]+list(parts)
            return subprocess.run(command,env=base_env,check=True,**kwargs)
        def sql(project,file,query):
            return compose(project,file,'exec','-T','mysql','sh','-c',
                           'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B oj_db',
                           input=query.encode(),stdout=subprocess.PIPE).stdout.decode().strip()
        def ops(project,file,*parts):
            return subprocess.run(['python3',str(ROOT/'scripts/ops.py'),'--project',project,'--env-file',str(file)]+list(parts),
                                  cwd=ROOT,env=base_env,check=True,stdout=subprocess.PIPE).stdout.decode()
        def status(url):
            try:
                with HTTP.open(url,timeout=5) as response:return response.status,response.read()
            except urllib.error.HTTPError as error:return error.code,error.read()
        evidence={}
        source,values,file=environment('source')
        try:
            # Fresh install validates split migration/runtime credentials and gateway boundaries.
            compose(source,file,'up','-d','--wait')
            zone=compose(source,file,'exec','-T','backend','date','+%z',stdout=subprocess.PIPE).stdout.strip()
            assert zone==b'+0800',zone
            assert sql(source,file,"SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;")=='54'
            result=subprocess.run(['docker','compose','--env-file',str(file),'-p',source,'-f',str(ROOT/'docker-compose.prod.yml'),
                                   'exec','-T','mysql','sh','-c','MYSQL_PWD="$OJ_DB_PASS" mysql -umusealgo_app oj_db -e "CREATE TABLE forbidden_ddl(id INT)"'],
                                  env=base_env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            assert result.returncode!=0 and b'denied' in result.stderr.lower()
            web='http://127.0.0.1:'+values['WEB_PORT']
            assert status(web+'/')[0]==200
            for path in ('/api/actuator/prometheus','/api/actuator/health','/api/doc.html','/api/v3/api-docs'):
                assert status(web+path)[0] in (403,404),path
            backend='http://127.0.0.1:'+values['BACKEND_PORT']
            assert status(backend+'/actuator/prometheus')[0]==401
            assert status(backend+'/doc.html')[0]==401
            evidence['fresh_install']={'schema':54,'runtime_ddl_denied':True,'gateway_internal_routes_blocked':True,'direct_internal_routes_require_auth':True}
            sql(source,file,"INSERT INTO user(username,password,nickname) VALUES ('drill-user','not-a-login','drill');")
            compose(source,file,'exec','-T','backend','sh','-c',
                    'printf testcase-proof > /data/testcases/drill.txt; printf upload-proof > /data/uploads/drill.txt; printf courseware-proof > /app/OJ-Coursewares/drill.txt')
            before=sql(source,file,"SELECT username,nickname FROM user WHERE username='drill-user';")
            # Recreate app containers: writable data must stay in volumes.
            compose(source,file,'up','-d','--force-recreate','--wait','backend','frontend')
            assert before==sql(source,file,"SELECT username,nickname FROM user WHERE username='drill-user';")
            files=compose(source,file,'exec','-T','backend','sha256sum','/data/testcases/drill.txt','/data/uploads/drill.txt','/app/OJ-Coursewares/drill.txt',stdout=subprocess.PIPE).stdout
            backup=output/'backups'/('snapshot-'+secrets.token_hex(4))
            print(ops(source,file,'backup','--output',str(backup)),flush=True)
            print(ops(source,file,'verify','--source',str(backup)),flush=True)
            print(ops(source,file,'check','--backup-root',str(backup.parent)),flush=True)
            evidence['backup']={'manifest_sha256':hashlib.sha256((backup/'manifest.json').read_bytes()).hexdigest(),'all_four_parts_verified':True,'probe_healthy':True}
            # Negative monitoring test without a real application failure.
            sql(source,file,"INSERT INTO submission(problem_id,user_id,language,status,judge_phase) SELECT 1,id,'cpp',0,'JUDGING' FROM user WHERE username='drill-user'; INSERT INTO judge_task(submission_id,status,locked_at,attempt_count) SELECT MAX(id),'PROCESSING',NOW()-INTERVAL 20 MINUTE,1 FROM submission;")
            probe=subprocess.run(['python3',str(ROOT/'scripts/ops.py'),'--project',source,'--env-file',str(file),'check','--backup-root',str(backup.parent)],env=base_env,stdout=subprocess.PIPE)
            assert probe.returncode==1 and json.loads(probe.stdout)['stalled_judges']>=1
            evidence['alert_negative']=True
            sql(source,file,"DELETE FROM judge_task; DELETE FROM submission;")
            # Restore to a new project using different credentials and ports.
            restored,restored_values,restored_file=environment('restore')
            print(ops(restored,restored_file,'restore','--source',str(backup),'--confirm-empty-target'),flush=True)
            assert before==sql(restored,restored_file,"SELECT username,nickname FROM user WHERE username='drill-user';")
            after=compose(restored,restored_file,'exec','-T','backend','sha256sum','/data/testcases/drill.txt','/data/uploads/drill.txt','/app/OJ-Coursewares/drill.txt',stdout=subprocess.PIPE).stdout
            assert files==after
            evidence['restore']={'database_equal':True,'three_volume_hashes_equal':True,'different_credentials':True}
            # Compatible binary rollback: old image runs with Flyway disabled, current migrator is retained.
            if args.previous_backend_image:
                override=base/'rollback.yml';override.write_text('services:\n  backend:\n    image: '+args.previous_backend_image+'\n')
                command=['docker','compose','--env-file',str(file),'-p',source,'-f',str(ROOT/'docker-compose.prod.yml'),'-f',str(override)]
                subprocess.run(command+['up','-d','--wait','backend'],env=base_env,check=True)
                assert before==sql(source,file,"SELECT username,nickname FROM user WHERE username='drill-user';")
                assert json.loads(status(backend+'/actuator/health')[1])['status']=='UP'
                compose(source,file,'up','-d','--wait','backend')
                evidence['compatible_binary_rollback']=True
            compose(source,file,'stop','backend','frontend')
            compose(restored,restored_file,'stop','backend','frontend')
            # V53->V54 upgrade on a new DB, using the exact schema baseline and current migration entry.
            upgrade,upgrade_values,upgrade_file=environment('upgrade')
            compose(upgrade,upgrade_file,'up','-d','--wait','mysql','redis')
            assert sql(upgrade,upgrade_file,"SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;")=='53'
            sql(upgrade,upgrade_file,"INSERT INTO user(username,password,nickname) VALUES ('upgrade-user','not-a-login','upgrade-proof');")
            old_backup=output/'backups'/('v53-'+secrets.token_hex(4))
            print(ops(upgrade,upgrade_file,'backup','--output',str(old_backup)),flush=True)
            compose(upgrade,upgrade_file,'run','--rm','migrate')
            compose(upgrade,upgrade_file,'run','--rm','migrate')
            assert sql(upgrade,upgrade_file,"SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;")=='54'
            assert sql(upgrade,upgrade_file,"SELECT nickname FROM user WHERE username='upgrade-user';")=='upgrade-proof'
            evidence['upgrade']={'from':53,'to':54,'repeated_migration_safe':True,'data_preserved':True}
            if args.previous_backend_image:
                legacy,legacy_values,legacy_file=environment('schema-rollback',args.previous_backend_image)
                print(ops(legacy,legacy_file,'restore','--source',str(old_backup),'--confirm-empty-target','--skip-migrations'),flush=True)
                assert sql(legacy,legacy_file,"SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;")=='53'
                assert sql(legacy,legacy_file,"SELECT nickname FROM user WHERE username='upgrade-user';")=='upgrade-proof'
                evidence['schema_rollback']={'schema':53,'old_binary_ready':True,'old_data_preserved':True}
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
    parser.add_argument('--output',default='/tmp/musealgo-operations-evidence')
    main(parser.parse_args())
