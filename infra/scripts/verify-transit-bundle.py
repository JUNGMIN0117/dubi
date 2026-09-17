"""로컬 가공 결과의 전송 전 무결성 검사. 무결성 통과는 운영 승인과 다르다."""
import argparse
import hashlib
import json
from pathlib import Path


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1048576), b''):
            value.update(chunk)
    return value.hexdigest()


def verify(folder):
    root = folder.resolve()
    failures = []
    try:
        manifest = json.loads((root / 'complete.json').read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return dict(integrityVerified=False, productionApproved=False,
                    failures=['MISSING_OR_INVALID_COMPLETION_MANIFEST'])
    outputs = manifest.get('outputs')
    if not isinstance(outputs, dict) or not outputs:
        failures.append('OUTPUTS_MISSING')
    else:
        for name, expected in outputs.items():
            path = (root / name).resolve()
            if not path.is_relative_to(root) or path == root:
                failures.append('OUTPUT_PATH_ESCAPE')
            elif not path.is_file():
                failures.append('OUTPUT_FILE_MISSING:' + name)
            elif digest(path) != expected:
                failures.append('OUTPUT_CHECKSUM_MISMATCH:' + name)
    for field in ('sourceSha256', 'codeSha256'):
        value = manifest.get(field)
        if not isinstance(value, str) or len(value) != 64 or any(c not in '0123456789abcdef' for c in value):
            failures.append('INVALID_' + field)
    return dict(integrityVerified=not failures, productionApproved=False,
                failures=failures, blockedBy=manifest.get('blockedBy', []),
                result=manifest.get('result', {}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    args = parser.parse_args()
    result = verify(args.folder)
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if result['integrityVerified'] else 1)
