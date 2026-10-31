#!/usr/bin/env python3
"""校验迁移命名、连续性、SHA-256清单及可信Git基线。"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / 'OJ-Backend/oj-admin/src/main/resources/db/migration'
MIGRATION_REL = 'OJ-Backend/oj-admin/src/main/resources/db/migration'
LOCK = DIRECTORY.parent / 'migration-checksums.json'
LOCK_REL = 'OJ-Backend/oj-admin/src/main/resources/db/migration-checksums.json'
MIGRATION_NAME = re.compile(r'^V([1-9][0-9]*)__([a-z0-9]+(?:_[a-z0-9]+)*)\.sql$')
SHA256 = re.compile(r'^[0-9a-f]{64}$')


def _version(name):
    match = MIGRATION_NAME.fullmatch(name)
    if not match:
        raise ValueError(f'迁移文件名不符合V<版本>__lower_snake.sql约定：{name}')
    return int(match.group(1))


def _discover(directory):
    if not directory.is_dir():
        raise ValueError(f'迁移目录不存在：{directory}')
    files = {}
    versions = {}
    for path in directory.iterdir():
        if path.is_dir():
            raise ValueError(f'迁移目录只支持平铺文件，不能包含子目录：{path.name}')
        if path.suffix.lower() != '.sql' and not path.name.startswith('V'):
            continue
        if not path.is_file():
            raise ValueError(f'迁移目录中存在非普通文件：{path.name}')
        version = _version(path.name)
        if version in versions:
            raise ValueError(f'迁移编号重复：{versions[version]}、{path.name}')
        versions[version] = path.name
        files[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
    if not files:
        raise ValueError('迁移目录为空')
    maximum = max(versions)
    expected = set(range(1, maximum + 1))
    missing = sorted(expected - set(versions))
    if missing:
        raise ValueError('迁移版本不连续，缺少：' + ', '.join(f'V{n}' for n in missing))
    return files


def _read_lock(path):
    if not path.is_file():
        return None
    try:
        value = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f'SHA-256清单无法读取：{error}') from error
    if not isinstance(value, dict):
        raise ValueError('SHA-256清单必须是文件名到摘要的JSON对象')
    for name, digest in value.items():
        _version(name)
        if not isinstance(digest, str) or not SHA256.fullmatch(digest):
            raise ValueError(f'SHA-256清单项格式错误：{name}')
    return value


def _git(repo, *args):
    try:
        return subprocess.check_output(['git', *args], cwd=repo, stderr=subprocess.PIPE)
    except subprocess.CalledProcessError as error:
        detail = error.stderr.decode(errors='replace').strip()
        raise ValueError(f'无法读取可信Git基线（{args[-1]}）：{detail}') from error


def _trusted_snapshot(repo, ref):
    commit = _git(repo, 'rev-parse', '--verify', f'{ref}^{{commit}}').decode().strip()
    tree = _git(repo, 'ls-tree', '-r', '--name-only', commit, '--', MIGRATION_REL).decode().splitlines()
    migration_dir = PurePosixPath(MIGRATION_REL)
    trusted_files = {}
    for relative in tree:
        path = PurePosixPath(relative)
        if path.parent != migration_dir:
            raise ValueError(f'可信Git基线中的迁移目录包含子目录：{relative}')
        if path.suffix.lower() == '.sql' or path.name.startswith('V'):
            _version(path.name)
            trusted_files[path.name] = hashlib.sha256(_git(repo, 'show', f'{commit}:{relative}')).hexdigest()
    if trusted_files:
        _assert_contiguous(trusted_files)

    lock_result = subprocess.run(
        ['git', 'cat-file', '-e', f'{commit}:{LOCK_REL}'], cwd=repo,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    trusted_lock = None
    if lock_result.returncode == 0:
        raw = _git(repo, 'show', f'{commit}:{LOCK_REL}')
        try:
            trusted_lock = json.loads(raw)
        except json.JSONDecodeError as error:
            raise ValueError('可信基线中的SHA-256清单不是有效JSON') from error
        if not isinstance(trusted_lock, dict):
            raise ValueError('可信基线中的SHA-256清单格式错误')
        for name, digest in trusted_lock.items():
            _version(name)
            if not isinstance(digest, str) or not SHA256.fullmatch(digest):
                raise ValueError(f'可信基线清单项格式错误：{name}')
        if trusted_lock != trusted_files:
            raise ValueError('可信基线中的迁移文件与SHA-256清单不一致')
    return commit, trusted_files, trusted_lock


def _assert_contiguous(files):
    versions = {_version(name) for name in files}
    if versions:
        missing = sorted(set(range(1, max(versions) + 1)) - versions)
        if missing:
            raise ValueError('迁移版本不连续，缺少：' + ', '.join(f'V{n}' for n in missing))


def check(directory=DIRECTORY, lock=LOCK, record_new=False, trusted_base=None, repo=ROOT):
    """校验工作树；--record-new仅登记未登记的新迁移，不能更新已有摘要。"""
    current = _discover(Path(directory))
    frozen = _read_lock(Path(lock))
    if record_new and not trusted_base:
        raise ValueError('--record-new必须同时指定--trusted-base，以保护Git基线中的历史迁移')

    trusted_files = {}
    baseline_max = 0
    trusted_commit = None
    if trusted_base:
        trusted_commit, trusted_files, trusted_lock = _trusted_snapshot(Path(repo), trusted_base)
        baseline_max = max((_version(name) for name in trusted_files), default=0)

        # Git基线里的迁移字节是独立可信点。旧清单存在时还要求它完整覆盖基线。
        for name, digest in trusted_files.items():
            if name not in current or current[name] != digest:
                raise ValueError(f'可信Git基线中的历史迁移被修改或删除：{name}')
        if trusted_lock is not None:
            for name, digest in trusted_files.items():
                if frozen is None or frozen.get(name) != digest:
                    raise ValueError(f'当前SHA-256清单改动了可信历史项：{name}')

    if frozen is None:
        if not record_new:
            raise ValueError(f'SHA-256清单不存在：{lock}')
        frozen = dict(trusted_files)

    for name, digest in frozen.items():
        if name not in current:
            raise ValueError(f'迁移文件缺失：{name}')
        version = _version(name)
        if trusted_base and name in trusted_files and digest != trusted_files[name]:
            raise ValueError(f'当前SHA-256清单改动了可信历史项：{name}')
        if version <= baseline_max and trusted_base and name not in trusted_files:
            raise ValueError(f'不能在可信Git基线的历史版本中插入迁移：{name}')
        if version <= max((_version(item) for item in trusted_files), default=0) and name not in trusted_files:
            raise ValueError(f'不能在可信Git基线历史中插入迁移：{name}')
        if digest != current[name]:
            raise ValueError(f'历史迁移被修改或SHA-256不匹配：{name}；应还原原文件并新增迁移。')

    new_files = {name: digest for name, digest in current.items() if name not in frozen}
    if trusted_base:
        for name, digest in current.items():
            if name in trusted_files and digest != trusted_files[name]:
                raise ValueError(f'可信Git基线中的历史迁移被修改：{name}')
            if name not in trusted_files and _version(name) <= baseline_max:
                raise ValueError(f'不能在已发布的迁移历史中插入迁移：{name}')

    if new_files and not record_new:
        raise ValueError('新增迁移尚未登记；登记前请检查SQL并提供可信基线：python3 scripts/check_migrations.py --trusted-base <git-ref> --record-new')

    if record_new:
        # --record-new只追加从未登记的文件，不改写任何已有登记项。
        revised = dict(frozen)
        revised.update(new_files)
        ordered = dict(sorted(revised.items(), key=lambda item: _version(item[0])))
        Path(lock).write_text(json.dumps(ordered, indent=2) + '\n')
        frozen = ordered

    if set(frozen) != set(current):
        missing = sorted(set(current) - set(frozen), key=_version)
        extra = sorted(set(frozen) - set(current), key=_version)
        raise ValueError(f'迁移与SHA-256清单覆盖范围不同；未登记={missing}，多余={extra}')
    return len(current)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--record-new', action='store_true')
    parser.add_argument('--trusted-base', help='受信Git提交或ref；CI应传PR目标/推送前提交')
    options = parser.parse_args()
    try:
        count = check(record_new=options.record_new, trusted_base=options.trusted_base)
        print(f'迁移历史检查通过：{count}个文件')
    except (ValueError, OSError) as error:
        parser.exit(1, str(error) + '\n')
