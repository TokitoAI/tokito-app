"""Inventory an extracted GenieX QAIRT release without altering its payload."""
import argparse
import hashlib
import json
from pathlib import Path


def build(root: Path, model_id: str) -> None:
    bundle = root / 'bundle'
    if not (bundle / 'metadata.json').is_file():
        raise ValueError('GenieX metadata.json is required')
    metadata = json.loads((bundle / 'metadata.json').read_text(encoding='utf-8'))
    if metadata.get('model_id') != model_id:
        raise ValueError('model-id must match the upstream GenieX metadata model_id')
    artifacts = []
    for file in sorted(bundle.rglob('*')):
        if file.is_symlink():
            raise ValueError('symlinks are not supported')
        if not file.is_file():
            continue
        digest = hashlib.sha256()
        with file.open('rb') as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(block)
        artifacts.append({'path': file.relative_to(root).as_posix(), 'size_bytes': file.stat().st_size, 'sha256': digest.hexdigest()})
    document = {'schema_version': 2, 'model_id': model_id, 'runtime_id': 'qairt', 'bundle_path': 'bundle', 'model_path': 'bundle/metadata.json', 'artifacts': artifacts}
    (root / 'dialogue-model.manifest.json').write_text(json.dumps(document, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--model-id', required=True)
    args = parser.parse_args()
    build(args.root, args.model_id)
