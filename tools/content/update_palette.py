#!/usr/bin/env python3
"""Reproduce and audit the 20 route colors without regenerating route geometry."""
from pathlib import Path
import hashlib
import itertools
import json
import numpy as np
from meridian_definitions import COLORS

ROOT = Path(__file__).resolve().parents[2]

def linear(rgb):
    v = np.asarray(rgb) / 255.0
    return np.where(v <= .04045, v / 12.92, ((v + .055) / 1.055) ** 2.4)

def luminance(rgb):
    return linear(rgb) @ np.array([.2126, .7152, .0722])

def lab(rgb):
    xyz = linear(rgb) @ np.array([[.4124564, .2126729, .0193339],
                                [.3575761, .7151522, .1191920],
                                [.1804375, .0721750, .9503041]]) / np.array([.95047, 1, 1.08883])
    f = np.where(xyz > (6 / 29) ** 3, xyz ** (1 / 3), xyz / (3 * (6 / 29) ** 2) + 4 / 29)
    return np.column_stack([116 * f[:, 1] - 16, 500 * (f[:, 0] - f[:, 1]),
                            200 * (f[:, 1] - f[:, 2])])

def build():
    rgb = np.array(list(itertools.product(range(32, 256, 16), repeat=3)))
    coordinates = lab(rgb)
    background = [20, 34, 45]  # Product panel #14222D, the lighter of its two dark backgrounds.
    contrast = (luminance(rgb) + .05) / (luminance(background) + .05)
    mask = (contrast >= 5) & (coordinates[:, 0] >= 58) & (coordinates[:, 0] <= 90) & \
           (np.linalg.norm(coordinates[:, 1:], axis=1) >= 35)
    rgb, coordinates = rgb[mask], coordinates[mask]
    selected = [int(np.argmin(np.sum((rgb - np.array([240, 192, 48])) ** 2, axis=1)))]
    while len(selected) < 20:
        distances = np.min(np.linalg.norm(coordinates[:, None] - coordinates[selected][None], axis=2), axis=1)
        selected.append(int(np.argmax(distances)))
    computed = ['#' + ''.join(f'{int(c):02X}' for c in color) for color in rgb[selected]]
    assert list(COLORS.values()) == computed
    rgb = rgb[selected]
    coordinates = coordinates[selected]
    distances = np.linalg.norm(coordinates[:, None] - coordinates[None], axis=2)
    np.fill_diagonal(distances, np.inf)
    assert len(set(computed)) == 20 and float(distances.min()) >= 28
    path = ROOT / 'app/src/main/assets/content/meridians.json'
    data = json.loads(path.read_text())
    before = [{k: v for k, v in r.items() if k != 'color'} for r in data['meridians']]
    for route in data['meridians']:
        route['color'] = COLORS[route['id']]
    assert before == [{k: v for k, v in r.items() if k != 'color'} for r in data['meridians']]
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    report = {
        'schemaVersion': 1, 'colors': COLORS, 'count': 20, 'allUnique': True,
        'algorithm': 'sRGB 32..240 step 16; D65 CIELAB; L*58..90, chroma>=35, panel contrast>=5; seed nearest #F0C030 then greedy farthest-point CIE76',
        'background': '#14222D', 'minimumPanelContrastRatio': float(min((luminance(rgb) + .05) / (luminance(background) + .05))),
        'minimumPairwiseCie76DeltaE': float(distances.min()),
        'routeGeometryAndAssociationsUnchanged': True,
        'limitationsZh': ['色差和对比为标准色彩空间计算，不能保证所有色觉差异下20色均可辨认；名称、选中强调和目录开关仍为识别依据。',
                           '对比值针对暗色面板，不代表覆盖在皮肤或器官上时的最小对比。'],
        'meridiansSha256': hashlib.sha256(path.read_bytes()).hexdigest()
    }
    (ROOT / 'manifests/meridian-palette.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))

if __name__ == '__main__':
    build()
