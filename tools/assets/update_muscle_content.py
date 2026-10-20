#!/usr/bin/env python3
"""Refresh learning metadata without rebuilding or altering either display GLB."""
import hashlib
import json
from pathlib import Path
from muscle_learning import apply_learning

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ASSETS = ROOT/'app/src/main/assets'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def atomic_json(path, data):
    temporary = path.with_suffix(path.suffix+'.tmp')
    temporary.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    temporary.replace(path)


def main():
    metadata_path = ASSETS/'anatomy.json'
    metadata = json.loads(metadata_path.read_text())
    invariant_fields = ['id','nameZh','nameEn','system','region','layer','nodeName','bounds']
    invariant = [{key:s[key] for key in invariant_fields} for s in metadata['structures']]
    models = {str(p.relative_to(ROOT)):digest(p) for p in sorted((ASSETS/'models').glob('body-*.glb'))}
    coverage = apply_learning(metadata['structures'])
    assert invariant == [{key:s[key] for key in invariant_fields} for s in metadata['structures']]
    before_idempotent = json.dumps(metadata,ensure_ascii=False)
    assert apply_learning(metadata['structures']) == coverage
    assert json.dumps(metadata,ensure_ascii=False) == before_idempotent
    atomic_json(metadata_path,metadata)
    assert models == {p:digest(ROOT/p) for p in models}
    manifest_path = ROOT/'manifests/model-build.json'
    manifest = json.loads(manifest_path.read_text())
    manifest['anatomyMetadata'] = {'path':str(metadata_path.relative_to(ROOT)), 'sha256':digest(metadata_path)}
    manifest['muscleContentCoverage'] = coverage
    pipeline = set(item['path'] for item in manifest['pipelineFiles'])
    pipeline.update(str((HERE/p).relative_to(ROOT)) for p in ['muscle_learning.py','muscle-learning.tsv','update_muscle_content.py'])
    manifest['pipelineFiles'] = [{'path':p,'sha256':digest(ROOT/p)} for p in sorted(pipeline)]
    manifest['licenseFiles'] = [{'path':str(p.relative_to(ROOT)),'sha256':digest(p)}
                              for p in sorted((ROOT/'licenses').glob('*')) if p.is_file()]
    atomic_json(manifest_path,manifest)
    report = {'schemaVersion':1,'modelId':metadata['modelId'],**coverage,
              'anatomyMetadata':manifest['anatomyMetadata'],
              'displayGlbUnchanged':True,'displayGlbSha256':models,
              'metadataUpdateIdempotent':True,
              'contentInputs': [item for item in manifest['pipelineFiles'] if any(
                  part in item['path'] for part in ['muscle','chinese-names'])]}
    atomic_json(ROOT/'manifests/model-muscle-content.json',report)
    print(json.dumps({key:report[key] for key in ['muscleSystemNodes','completeOriginInsertionFunction',
          'completeSourceReferences','coveragePercent','countsByStructureType',
          'distinctEnglishLearningFamilies','anatomyMetadata','displayGlbUnchanged']},ensure_ascii=False,indent=2))


if __name__ == '__main__':
    main()
