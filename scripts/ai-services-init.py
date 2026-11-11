#!/usr/bin/env python3
"""Initialize AI master-key mounting for compose.dev.yml without overwriting keys.

Run from the repository: python3 scripts/ai-services-init.py --start
Production keeps its documented secret provisioning process.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TARGET = '/run/secrets/oj_ai_master_key'


def run(command, *, env=None):
    result = subprocess.run(command, cwd=ROOT, env=env, text=True, capture_output=True)
    if result.returncode:
        # Docker errors can include configuration; never copy raw output into logs.
        raise RuntimeError('部署检查未完成，请确认 Docker 与开发栈 MySQL 可用；未生成或替换密钥。')
    return result.stdout.strip()


def compose(*args):
    return ['docker', 'compose', '-f', 'compose.dev.yml', *args]


def host_mount_path(source):
    path = Path(source)
    # Docker Desktop may report its VM path instead of the macOS host path.
    if sys.platform == 'darwin' and source.startswith('/host_mnt/') and not path.exists():
        translated = Path(source[len('/host_mnt'):])
        if translated.exists():
            return translated
    return path


def mounted_key():
    container = run(compose('ps', '-q', 'backend'))
    if not container:
        return None
    mounts = json.loads(run(['docker', 'inspect', '--format', '{{json .Mounts}}', container]))
    for mount in mounts:
        if mount.get('Destination') == TARGET:
            if mount.get('RW'):
                raise RuntimeError('现有主密钥挂载可写，请先按文档改为只读。')
            return host_mount_path(mount['Source'])
    return None


def require_no_ciphertext():
    # Includes soft-deleted services: their ciphertext still requires the original key.
    query = 'SELECT COUNT(*) FROM ai_service_config WHERE api_key_encrypted IS NOT NULL AND api_key_encrypted <> ""'
    value = run(compose('exec', '-T', 'mysql', 'sh', '-c',
                        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B "$MYSQL_DATABASE" -e "$1"',
                        'ai-key-check', query))
    if value != '0':
        raise RuntimeError('数据库已有加密服务密钥或状态不明。请恢复原主密钥备份，禁止重新生成。')


def validate_key(path):
    if path.is_symlink() or not path.is_file():
        raise RuntimeError('主密钥必须是普通文件，不允许符号链接。')
    info = path.stat()
    if info.st_size > 4096 or stat.S_IMODE(info.st_mode) & 0o077:
        raise RuntimeError('主密钥大小或权限不正确；请限制为文件所有者可读写（600/400）。')
    try:
        decoded = base64.b64decode(path.read_bytes().strip(), validate=True)
    except (ValueError, OSError):
        raise RuntimeError('主密钥文件不可读或格式无效；请恢复正确文件。') from None
    if len(decoded) != 32:
        raise RuntimeError('主密钥必须为 Base64 编码的 32 字节。')


def initialize(path, check_empty=require_no_ciphertext):
    if path.is_symlink():
        raise RuntimeError('拒绝主密钥符号链接。')
    if path.exists():
        validate_key(path)
        return False
    check_empty()
    if any(parent.is_symlink() for parent in path.parents):
        raise RuntimeError('拒绝通过符号链接目录创建主密钥。')
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    # O_EXCL prevents concurrent runs from replacing an existing key.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as output:
        output.write(base64.b64encode(secrets.token_bytes(32)) + b'\n')
        output.flush()
        os.fsync(output.fileno())
    validate_key(path)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--key-file', type=Path, help='已有/首次生成的主密钥路径；默认复用当前挂载')
    parser.add_argument('--start', action='store_true', help='初始化后应用只读挂载并启动 backend')
    args = parser.parse_args()
    os.umask(0o077)
    existing = mounted_key()
    configured = os.environ.get('OJ_AI_MASTER_KEY_FILE_HOST')
    requested = args.key_file or (Path(configured) if configured else None)
    if existing and requested and existing.absolute() != requested.absolute():
        raise RuntimeError('指定路径与当前主密钥挂载不同；初始化命令不执行密钥轮换。')
    path = (existing or requested or ROOT / 'deploy/dev-secrets/oj_ai_master_key').absolute()
    if existing and not path.exists():
        raise RuntimeError('当前挂载的主密钥丢失，请恢复原文件，禁止补造。')
    created = initialize(path)
    print('主密钥已首次生成，请将其独立安全备份。' if created else '已复用原主密钥，未修改文件。')
    if args.start:
        env = dict(os.environ, OJ_AI_MASTER_KEY_FILE_HOST=str(path))
        run(['docker', 'compose', '-f', 'compose.dev.yml', '-f', 'deploy/compose.dev.ai-service-secret.yml',
             'up', '-d', '--wait', '--wait-timeout', '180', 'backend'], env=env)
        run(compose('exec', '-T', 'backend', 'sh', '-c', 'test -r /run/secrets/oj_ai_master_key'))
        print('后端已启动且主密钥文件可读；请刷新 AI 服务页查看就绪状态。')
    else:
        print('未启动服务；添加 --start 可应用只读挂载并等待后端就绪。')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError) as error:
        print(f'初始化未完成：{error}', file=sys.stderr)
        sys.exit(1)
