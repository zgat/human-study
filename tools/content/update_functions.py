#!/usr/bin/env python3
"""Apply curated, source-linked traditional-use notes without touching point geometry.

The curated input contains short factual summaries, never source books or procedures.
Historical descriptions, teaching descriptions and combination-use context are distinct.
"""
from pathlib import Path
import collections
import hashlib
import json

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'app/src/main/assets/content'
INPUT = Path(__file__).with_name('function_notes.json')
FUNCTION_KEYS = {'traditionalIndicationsZh', 'functionSourceRefs', 'functionReviewStatus', 'functionSourceIds'}
SUMMARY = 'source-summarized-pending-medical-review'
CONTEXT = 'context-only-pending-indication-review'
REFERENCE = 'reference-only-pending-medical-review'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def point_fingerprint(points):
    clean = [{k: v for k, v in p.items() if k not in FUNCTION_KEYS} for p in points]
    return sha(json.dumps(clean, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode())

def validate_notes(notes, points):
    index = {p['id']: p for p in points}
    assert len(index) == 413
    assert len(notes) == len({n['pointId'] for n in notes}) == 413
    assert {n['pointId'] for n in notes} == set(index)
    for note in notes:
        assert note['pointNameZh'] == index[note['pointId']]['nameZh'], note['pointId']
        assert note['sourceId'] and note['sourceTitleZh'] and note['sourceUrl'].startswith('https://')
        assert note['locatorZh'] and note['summaryZh'] and len(note['summaryZh']) <= 130
        assert note['reviewStatus'] in {SUMMARY, CONTEXT, REFERENCE}
        assert not any(term in note['summaryZh'] for term in
                       ['针刺', '直刺', '斜刺', '点刺', '艾灸', '分钟', '壮', '立愈', '即有子', '无所不疗'])
        if note['pointNameZh'] != note['sourcePointNameZh']:
            assert note.get('nameMappingStatus') or note['pointId'] == 'ex-hn12-hn13'
    states = collections.Counter(n['reviewStatus'] for n in notes)
    assert states == {SUMMARY: 408, CONTEXT: 4, REFERENCE: 1}, states

def make_sources(notes):
    # Hashes refer to the actual public documents consulted; source documents stay outside the APK.
    source_dir = Path(__file__).with_name('sources')
    ppt_index_path = source_dir / 'function-shutcm/index.json'
    classic_index_path = source_dir / 'function-ctext/index.json'
    ppt_index = {str(int(x['file'].split('.')[0])): x for x in json.loads(ppt_index_path.read_text())} if ppt_index_path.exists() else {}
    classic_index = {x['chapter']: x for x in json.loads(classic_index_path.read_text())} if classic_index_path.exists() else {}
    sources = {}
    for note in notes:
        sid = note['sourceId']
        if sid == 'gb12346-2021' or sid in sources:
            continue
        entry = {'id': sid, 'title': note['sourceTitleZh'],
                 'url': note['sourceUrl'].split('#')[0], 'retrievedDate': '2026-10-20',
                 'usageZh': '仅使用逐穴简短事实摘要；未分发课件、原网页或操作方法。',
                 'contentType': note['sourceKind'], 'professionalReviewStatus': 'pending'}
        if sid.startswith('shutcm-acupoint-'):
            item = ppt_index.get(sid.rsplit('-', 1)[-1])
            entry.update(publisher='上海中医药大学',
                         licenseStatus='copyrighted-public-course-no-open-license-confirmed',
                         extractionZh='仅核对当前幻灯片；不读取PPT中残留的旧版或已删文本。')
            if item:
                entry.update(sha256=item['sha256'], bytes=item['bytes'])
        elif note['sourceKind'] == 'public-domain-classic':
            entry.update(licenseStatus='public-domain-original; electronic-transcription-license-not-assumed',
                         transcriptionReviewZh='公开电子转录，尚需与扫描底本逐字复核；摘要中的历史病证不得直接当作现代疗效结论。')
            if sid.startswith('zjdc-') and sid.rsplit('-', 1)[-1] in classic_index:
                item = classic_index[sid.rsplit('-', 1)[-1]]
                entry.update(sha256=item['sha256'], bytes=item['bytes'], originalAuthorZh='明·杨继洲')
            elif sid == 'keiketsu-kaige-224855':
                file = source_dir / 'function-ctext/224855.html'
                entry['originalAuthorZh'] = '日本江户·原昌克'
                if file.exists():
                    entry.update(sha256=sha(file.read_bytes()), bytes=file.stat().st_size)
            elif sid == 'zjdc-wikisource-9':
                file = source_dir / 'zhenjiudacheng-卷九.html'
                entry['originalAuthorZh'] = '明·杨继洲'
                if file.exists():
                    entry.update(sha256=sha(file.read_bytes()), bytes=file.stat().st_size)
        else:
            entry.update(licenseStatus='public-reading-no-open-license-confirmed',
                         retrievalZh='公开网页、期刊或摘要中逐条核对；只记录事实摘要，不转载全文。')
        sources[sid] = entry
    return list(sources.values())

def apply_to_catalogue():
    curated = json.loads(INPUT.read_text())
    notes = curated['notes']
    path = OUT / 'acupoints.json'
    data = json.loads(path.read_text())
    validate_notes(notes, data['points'])
    before = point_fingerprint(data['points'])
    index = {note['pointId']: note for note in notes}
    for point in data['points']:
        note = index[point['id']]
        point['traditionalIndicationsZh'] = note['summaryZh']
        point['functionSourceRefs'] = [f"{note['sourceTitleZh']} · {note['locatorZh']}\n{note['sourceUrl']}"]
        point['functionSourceIds'] = [note['sourceId']]
        point['functionReviewStatus'] = note['reviewStatus']
    assert point_fingerprint(data['points']) == before
    data['functionContentNoticeZh'] = curated['intendedUseZh']
    data['functionCoverage'] = {'describedEntries': 413, 'traditionalIndicationSummaries': 408,
                               'contextOnlyEntries': 4, 'referenceOnlyEntries': 1,
                               'professionalReviewedEntries': 0}
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    sources_path = OUT / 'sources.json'
    registry = json.loads(sources_path.read_text())
    previous = {entry['id']: entry for entry in registry['sources']}
    for entry in curated.get('sources', make_sources(notes)):
        # Retain pinned source hashes if rebuilding from an exported source tree without downloaded originals.
        old = previous.get(entry['id'], {})
        previous[entry['id']] = {**old, **entry}
    registry['sources'] = list(previous.values())
    sources_path.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + '\n')
    (ROOT / 'manifests/content-sources.json').write_text(json.dumps(registry, ensure_ascii=False, indent=2) + '\n')
    report = {
        'schemaVersion': 1, 'notesInput': str(INPUT.relative_to(ROOT)),
        'notesSha256': sha(INPUT.read_bytes()), 'checksPassed': True,
        **data['functionCoverage'],
        'bySourceKind': dict(collections.Counter(n['sourceKind'] for n in notes)),
        'contextOnly': [{'pointId': n['pointId'], 'nameZh': n['pointNameZh'], 'reasonZh': n['summaryZh']}
                        for n in notes if n['reviewStatus'] == CONTEXT],
        'referenceOnly': ['st17'], 'missingDescriptionIds': [],
        'coordinatesIdsLocationAndMeridianUnchanged': True,
        'preservedPointContentFingerprintSha256': before,
        'counts': data['counts'], 'acupointsSha256': sha(path.read_bytes()),
        'boundariesZh': ['408条为有来源的传统主治短摘要，不是408条现代临床疗效认证。',
                         '急脉、新设、腰宜、肩前4条仅提供联合用穴或研究背景，传统主治范围仍待权威教材/标准逐条复核。',
                         '乳中只说明定位参考用途，不编造治疗用途。',
                         '全部文字与三维标记尚未经本项目专业医学审校；没有针刺方向、深度、剂量或自疗操作指导。',
                         'GB/T 30233—2013《腧穴主治》官方状态已核验为现行，但尚未取得全文逐条比对，不将其写作已完成内容依据。']
    }
    (ROOT / 'manifests/content-functions.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: report[k] for k in ['checksPassed', 'describedEntries', 'traditionalIndicationSummaries',
                                            'contextOnlyEntries', 'referenceOnlyEntries', 'professionalReviewedEntries']}, ensure_ascii=False))
    return report

if __name__ == '__main__':
    apply_to_catalogue()
