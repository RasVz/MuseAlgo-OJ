#!/usr/bin/env python3
"""Exercise the checked-in gateway with disposable local Nginx stubs, no external requests."""
import json
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def main():
    name = 'musealgo-gateway-' + secrets.token_hex(4)
    with tempfile.TemporaryDirectory(prefix=name) as temporary:
        directory = Path(temporary)
        assets = directory / 'coursewares' / ('a' * 32)
        assets.mkdir(parents=True)
        (assets / 'index.html').write_text('synthetic private courseware')
        (directory / 'backend.conf').write_text('''server { listen 8080;
 location /coursewares/ {
  if ($http_cookie = "courseware_access=synthetic") { return 200; }
  return 401;
 }
}
''')
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0)); port = listener.getsockname()[1]
        def docker(*args):
            return subprocess.run(['docker', *args], check=True, capture_output=True, text=True).stdout.strip()
        created = []
        try:
            docker('network', 'create', name)
            created.append(name + '-backend')
            docker('run', '-d', '--pull=never', '--name', created[-1], '--network', name,
                   '--network-alias', 'backend', '-v', f'{directory}/backend.conf:/etc/nginx/conf.d/default.conf:ro', 'nginx:alpine')
            created.append(name + '-web')
            docker('run', '-d', '--pull=never', '--name', created[-1], '--network', name,
                   '-p', f'127.0.0.1:{port}:80', '-v', f'{ROOT}/deploy/nginx.conf:/etc/nginx/conf.d/default.conf:ro',
                   '-v', f'{directory}/coursewares:/var/www/coursewares:ro', 'nginx:alpine')
            for _ in range(60):
                try:
                    with HTTP.open(f'http://127.0.0.1:{port}/healthz', timeout=1): break
                except OSError: time.sleep(.2)
            else: raise AssertionError('gateway did not start')
            results = []
            for prefix in ('/coursewares/', '/api/coursewares/'):
                for cookie, expected in (('', 401), ('courseware_access=synthetic', 200)):
                    request = urllib.request.Request(f'http://127.0.0.1:{port}{prefix}' + 'a' * 32 + '/index.html',
                                                     headers={'Cookie': cookie})
                    try: response = HTTP.open(request, timeout=5)
                    except urllib.error.HTTPError as error: response = error
                    with response:
                        body = response.read().decode()
                        results.append({'prefix': prefix, 'authenticated': bool(cookie), 'status': response.status})
                        assert response.status == expected, results[-1]
                        assert 'synthetic private courseware' not in body
            print(json.dumps({'passed': results}))
        finally:
            for container in reversed(created):
                subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
            subprocess.run(['docker', 'network', 'rm', name], capture_output=True)

if __name__ == '__main__': main()
