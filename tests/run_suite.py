#!/usr/bin/env python3
"""Disposable integration + real Judge0 + browser acceptance (Python3, JDK17, Node22+, Docker)."""
import argparse
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import tempfile
import time
import urllib.request

ROOT=Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));return sock.getsockname()[1]


def run_suite(output, extended=True):
    output.mkdir(parents=True,exist_ok=True)
    os.umask(0o077)
    env=os.environ.copy()
    env['TZ']='Asia/Shanghai'
    project='musealgo-ci-'+secrets.token_hex(5)
    for name in ('MYSQL','REDIS','JUDGE','MQ','MQ_ADMIN'):
        env['OJ_CI_'+name+'_PORT']=str(free_port())
    env.update(OJ_CI_DB_PASSWORD=secrets.token_hex(32),OJ_CI_MQ_PASSWORD=secrets.token_hex(32),JWT_SECRET=secrets.token_hex(48))
    processes=[]
    with tempfile.TemporaryDirectory(prefix='musealgo-ci-') as temporary:
        credentials=Path(temporary)/'test.env'
        credentials.write_text('\n'.join(f'{k}={v}' for k,v in env.items() if k.startswith('OJ_CI_') or k=='JWT_SECRET')+'\n')
        credentials.chmod(0o600)
        compose=['docker','compose','--env-file',str(credentials),'-p',project,'-f',str(ROOT/'tests/compose.yml'),'--profile','judge','--profile','mq']
        env.update(SPRING_DATASOURCE_URL=f"jdbc:mysql://127.0.0.1:{env['OJ_CI_MYSQL_PORT']}/oj_db?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                   OJ_DB_PASS=env['OJ_CI_DB_PASSWORD'],SPRING_DATA_REDIS_PORT=env['OJ_CI_REDIS_PORT'],
                   DEEPSEEK_API_KEY='',COS_SECRET_ID='',COS_SECRET_KEY='',
                   OJ_SEARCH_ENABLED='false',OJ_RABBITMQ_ENABLED='false',MANAGEMENT_HEALTH_RABBIT_ENABLED='false',
                   OJ_CONTEST_SETTLEMENT_INITIAL_DELAY_MS='86400000',OJ_TESTCASE_PATH=str(ROOT/'OJ-backend/db/demo-testcases'),
                   OJ_UPLOAD_DIR=str(output/'uploads'),OJ_SLIDEV_OUTPUT_DIR=str(output/'coursewares'),
                   OJ_TEST_OUTPUT_DIR=str(output),OJ_TEST_COMPOSE_JSON=json.dumps(compose),
                   JUDGE0_URLS=f"http://127.0.0.1:{env['OJ_CI_JUDGE_PORT']}")
        if os.uname().machine in ('arm64','aarch64'):
            env['JUDGE0_PER_PROCESS_MEMORY_LIMIT']='false'
        def run(name,args,cwd=ROOT,**kwargs):
            print(name,flush=True)
            with (output/(name+'.log')).open('wb') as log:
                subprocess.run(args,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT,check=True,**kwargs)
        def start(name,args,cwd=ROOT):
            log=(output/(name+'.log')).open('wb')
            proc=subprocess.Popen(args,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            processes.append(proc);log.close();return proc
        def stop(proc):
            if proc.poll() is None:
                os.killpg(proc.pid,signal.SIGTERM)
                try:proc.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid,signal.SIGKILL);proc.wait()
        try:
            run('operations-unit',['python3','-m','unittest','discover','-s','tests','-p','test_*.py'])
            run('services',compose+['up','-d'])
            run('services-ready',compose+['up','-d','--wait','mysql','redis','rabbitmq'])
            with (ROOT/'OJ-backend/db/seed.sql').open('rb') as seed:
                run('seed',compose+['exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot oj_db'],stdin=seed)
            run('backend-integration',['./mvnw','-B','package','-Pintegration'],ROOT/'OJ-backend')
            run('frontend-install',['npm','ci','--no-audit','--no-fund'],ROOT/'OJ-frontend')
            run('frontend-lint',['npm','run','lint:ci'],ROOT/'OJ-frontend')
            run('frontend-build',['npm','run','build'],ROOT/'OJ-frontend')
            api_port,web_port=free_port(),free_port()
            env.update(SERVER_PORT=str(api_port),OJ_TEST_API=f'http://127.0.0.1:{api_port}',
                       OJ_CONTEST_SETTLEMENT_INITIAL_DELAY_MS='1000',OJ_CONTEST_SETTLEMENT_INTERVAL_MS='1000')
            jar=ROOT/'OJ-backend/oj-admin/target/oj-admin-0.1.0-SNAPSHOT.jar'
            backend=start('backend-no-mq',['java','-jar',str(jar)])
            run('api-smoke',['python3',str(ROOT/'tests/api_smoke.py')])
            env['OJ_TEST_MQ_MODE']='disabled'
            if extended:
                run('contest-no-mq',['python3',str(ROOT/'tests/contest_smoke.py')])
            env.update(VITE_PROXY_TARGET=env['OJ_TEST_API'],OJ_TEST_WEB=f'http://127.0.0.1:{web_port}',OJ_TEST_EVIDENCE=str(output/'api-smoke.json'))
            front=start('frontend-web',['npm','run','preview','--','--strictPort','--host','127.0.0.1','--port',str(web_port)],ROOT/'OJ-frontend')
            http=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            for _ in range(60):
                try:
                    http.open(env['OJ_TEST_WEB'],timeout=2).close();break
                except OSError:time.sleep(1)
            run('browser',['npm','run','test:e2e'],ROOT/'OJ-frontend')
            stop(front);stop(backend)
            if not extended:
                print('Core integration, API and browser acceptance passed',flush=True)
                return
            env.update(OJ_RABBITMQ_ENABLED='true',OJ_RABBIT_HOST='127.0.0.1',OJ_RABBIT_PORT=env['OJ_CI_MQ_PORT'],
                       OJ_RABBIT_USER='ci',OJ_RABBIT_PASS=env['OJ_CI_MQ_PASSWORD'],OJ_TEST_MQ_MODE='enabled')
            backend=start('backend-mq',['java','-jar',str(jar)])
            run('contest-mq',['python3',str(ROOT/'tests/contest_smoke.py')])
            run('mq-outage-start',compose+['stop','rabbitmq'])
            env['OJ_TEST_MQ_MODE']='outage'
            run('contest-mq-outage',['python3',str(ROOT/'tests/contest_smoke.py')])
            run('mq-recovery',compose+['up','-d','--wait','rabbitmq'])
            print('Integration, real judging, browser and MQ recovery acceptance passed',flush=True)
        finally:
            for process in reversed(processes):stop(process)
            with (output/'containers.log').open('wb') as log:
                subprocess.run(compose+['logs','--no-color'],stdout=log,stderr=subprocess.STDOUT)
            run('cleanup',compose+['down','-v','--remove-orphans'])


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',default='/tmp/musealgo-ci-evidence')
    parser.add_argument('--core-only',action='store_true',help='Skip extended MQ fault matrix; still run integration, real API and browser checks')
    options=parser.parse_args()
    run_suite(Path(options.output).resolve(),extended=not options.core_only)
