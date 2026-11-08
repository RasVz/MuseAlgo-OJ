#!/usr/bin/env python3
"""Create synthetic backend JAR variants for isolated database maintenance tests.

The V62 fixture removes only migrations newer than V62. The legacy fixture removes
only DatabaseMaintenance.class so status can be tested against an old-style entrypoint.
Neither variant is a release artifact.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import zipfile

MAINTENANCE_CLASS = 'BOOT-INF/classes/com/musealgo/oj/DatabaseMaintenance.class'
PROPERTIES_LAUNCHER = 'org/springframework/boot/loader/launch/PropertiesLauncher.class'
MIGRATION_PATTERN = re.compile(r'^BOOT-INF/classes/db/migration/V(\d+)__[^/]+\.sql$')
IMAGE_PATTERN = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._/:@+-]*$')


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def migration_versions(entries):
    return {match.group(1): name for name in entries
            if (match := MIGRATION_PATTERN.fullmatch(name))}


def write_variant(source_jar, destination, exclude, required_entries):
    with zipfile.ZipFile(source_jar) as source:
        names = source.namelist()
        missing = sorted(set(required_entries) - set(names))
        if missing:
            raise ValueError('Source jar is missing required entries: ' + ', '.join(missing))
        with zipfile.ZipFile(destination, 'w') as target:
            kept = []
            removed = []
            for item in source.infolist():
                if exclude(item.filename):
                    removed.append(item.filename)
                    continue
                target.writestr(item, source.read(item.filename))
                kept.append(item.filename)
    return kept, removed


def build_variants(source_jar, output_dir, base_image):
    os.umask(0o077)
    source_jar = Path(source_jar).resolve()
    output_dir = Path(output_dir).resolve()
    if not source_jar.is_file() or not zipfile.is_zipfile(source_jar):
        raise ValueError('Source jar must be a readable Spring Boot executable jar')
    if not IMAGE_PATTERN.fullmatch(base_image):
        raise ValueError('Invalid base image reference')

    output_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    output_dir.chmod(0o700)
    v62_dir = output_dir / 'v62'
    legacy_dir = output_dir / 'legacy-maintenance'
    v62_dir.mkdir(mode=0o700)
    legacy_dir.mkdir(mode=0o700)
    v62_jar = v62_dir / 'app.jar'
    legacy_jar = legacy_dir / 'app.jar'

    with zipfile.ZipFile(source_jar) as source:
        names = source.namelist()
        if MAINTENANCE_CLASS not in names:
            raise ValueError('Source jar does not contain DatabaseMaintenance.class')
        if PROPERTIES_LAUNCHER not in names or 'META-INF/MANIFEST.MF' not in names:
            raise ValueError('Source jar is missing the Spring Boot launcher or manifest')
        if source.testzip() is not None:
            raise ValueError('Source jar has a corrupt entry')
        manifest = source.read('META-INF/MANIFEST.MF')
        if b'Main-Class: org.springframework.boot.loader.launch.JarLauncher' not in manifest:
            raise ValueError('Source jar does not declare the Spring Boot JarLauncher')
        migrations = migration_versions(names)
        if not migrations or '62' not in migrations:
            raise ValueError('Source jar must contain migrations V1 through V62')

    v62_kept, v62_removed = write_variant(
        source_jar,
        v62_jar,
        lambda name: (match := MIGRATION_PATTERN.fullmatch(name)) is not None and int(match.group(1)) > 62,
        (MAINTENANCE_CLASS, migrations['62']),
    )
    legacy_kept, legacy_removed = write_variant(
        source_jar,
        legacy_jar,
        lambda name: name == MAINTENANCE_CLASS,
        (MAINTENANCE_CLASS, migrations['62']),
    )

    # The fixture images inherit all runtime assets and user settings from the current
    # candidate. Only the packaged jar is replaced, and Docker labels mark the image synthetic.
    for directory, kind in ((v62_dir, 'v62-schema'), (legacy_dir, 'legacy-maintenance-entry')):
        dockerfile = (
            f'FROM {base_image}\n'
            f'LABEL org.musealgo.test-fixture="true" org.musealgo.test-fixture.kind="{kind}"\n'
            'COPY --chown=10001:10001 app.jar /app/app.jar\n'
        )
        (directory / 'Dockerfile').write_text(dockerfile, encoding='utf-8')

    # Verify the variants differ from the candidate only in their declared entries.
    with zipfile.ZipFile(source_jar) as original, zipfile.ZipFile(v62_jar) as v62, zipfile.ZipFile(legacy_jar) as legacy:
        original_names = set(original.namelist())
        v62_names = set(v62.namelist())
        legacy_names = set(legacy.namelist())
        if original_names - v62_names != set(v62_removed):
            raise ValueError('V62 fixture contains unexpected jar entry changes')
        if original_names - legacy_names != {MAINTENANCE_CLASS}:
            raise ValueError('Legacy fixture must remove only DatabaseMaintenance.class')
        for name in v62_names:
            if original.read(name) != v62.read(name):
                raise ValueError('V62 fixture altered retained entry: ' + name)
        for name in legacy_names:
            if original.read(name) != legacy.read(name):
                raise ValueError('Legacy fixture altered retained entry: ' + name)
        if MAINTENANCE_CLASS not in v62_names or MAINTENANCE_CLASS in legacy_names:
            raise ValueError('Fixture maintenance entry layout is invalid')
        remaining_versions = migration_versions(v62_names)
        if any(int(version) > 62 for version in remaining_versions):
            raise ValueError('V62 fixture still contains a migration newer than V62')

    report = {
        'synthetic_only': True,
        'source_jar_sha256': sha256(source_jar),
        'source_jar': str(source_jar),
        'v62': {
            'removed_entries': v62_removed,
            'migration_versions': sorted(map(int, migration_versions(v62_kept))),
            'jar_sha256': sha256(v62_jar),
            'context': str(v62_dir),
        },
        'legacy_maintenance': {
            'removed_entries': legacy_removed,
            'jar_sha256': sha256(legacy_jar),
            'context': str(legacy_dir),
        },
        'base_image': base_image,
    }
    (output_dir / 'fixture-manifest.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-jar', required=True)
    parser.add_argument('--output-dir', required=True)
    parser.add_argument('--base-image', required=True)
    args = parser.parse_args()
    print(json.dumps(build_variants(args.source_jar, args.output_dir, args.base_image), indent=2))


if __name__ == '__main__':
    main()
