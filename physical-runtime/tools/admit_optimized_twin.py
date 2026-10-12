#!/usr/bin/env python3
"""Verify a gltfpack -kn -km -ke -noq export and rebind its asset manifest.

This does not grant physical/AR qualification. The source manifest's identity,
registration landmarks, and evidence flags are preserved, never inferred.
"""
import argparse
import hashlib
import json
from pathlib import Path

from glb_inspect import load_gltf_json, node_world_bounds


def verify(source, optimized, prefix):
    source_refs = {n['name'] for n in source['nodes'] if n.get('name', '').startswith(prefix)}
    target_refs = [n['name'] for n in optimized['nodes'] if n.get('name', '').startswith(prefix)]
    if source_refs != set(target_refs) or len(target_refs) != len(set(target_refs)):
        raise ValueError('Optimization changed or duplicated component identities')
    before, after = node_world_bounds(source), node_world_bounds(optimized)
    for name in source_refs:
        if (name in before) != (name in after):
            raise ValueError(f'Optimization changed renderability: {name}')
        if name in before and any(abs(a-b) > 0.000001 for a, b in zip(before[name], after[name])):
            raise ValueError(f'Optimization changed metric component bounds: {name}')
    primitives = sum(len(m['primitives']) for m in optimized.get('meshes', []))
    if primitives > 8192 or len(optimized['nodes']) > 8192:
        raise ValueError('Optimized asset still exceeds mobile renderer budget')
    return {'component_references': len(source_refs), 'draw_primitives': primitives}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--optimized', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output-manifest', type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
    if hashlib.sha256(args.source.read_bytes()).hexdigest() != manifest['glbSha256'].removeprefix('sha256:'):
        raise ValueError('Source manifest does not bind the input asset')
    evidence = verify(load_gltf_json(args.source), load_gltf_json(args.optimized), manifest['componentNodePrefix'])
    data = args.optimized.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    manifest.update(glbBytes=len(data), glbSha256='sha256:' + digest, assetId='tokito-selectable-' + digest[:32])
    args.output_manifest.write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    print(json.dumps({**evidence, 'glb_bytes': len(data), 'sha256': digest}))


if __name__ == '__main__':
    main()
