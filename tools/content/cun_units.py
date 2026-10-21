"""Explicit teaching scales; numeric agreement is not anatomical/medical calibration."""
import math
import re
import unicodedata


def normalized_location(text):
    text = unicodedata.normalize('NFKC', text)
    # The official PDF's extracted ST26 glyph is lower-case l; KI15/CV7's same-level
    # note independently establishes one cun. Do not replace arbitrary letters.
    return re.sub(r'(?<=脐中下)[lI](?=寸)', '1', text)


def segment_cun(start, end, divisions):
    if not all(math.isfinite(float(x)) for x in (start, end, divisions)) or divisions <= 0 or start <= end:
        raise ValueError('A cun scale needs ordered anatomical endpoints and positive divisions')
    return (start - end) / divisions


def torso_scales(landmarks, transverse_teaching_cun=.026):
    """Keep transverse, chest-vertical, upper/lower-abdominal scales separate.

    The transverse 26mm is the established model column, NOT a measured nipple
    span or a universal cun. Do not derive transverse offsets from vertical 5 cun.
    """
    return {
        'transverse': transverse_teaching_cun,
        'chest_vertical': segment_cun(landmarks['sternal-notch'][1], landmarks['xiphoid-tip'][1], 9),
        'upper_abdomen_vertical': segment_cun(landmarks['xiphoid-tip'][1], landmarks['navel'][1], 8),
        'lower_abdomen_vertical': segment_cun(landmarks['navel'][1], landmarks['pubic-top'][1], 5),
    }
