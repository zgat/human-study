#!/usr/bin/env python3
"""Read-only regional review of the 160 non-thoracoabdominal cun entries.

This is a source-specific audit, not a medical locator or an asset generator. Numeric
checks use explicitly labelled provisional mesh landmarks. They do not certify them.
Only the requested ignored JSON report is written; no content/model input is changed.
"""
from __future__ import annotations

import argparse
import ast
import collections
import hashlib
import json
import math
from pathlib import Path
import re
import unicodedata

ROOT = Path(__file__).resolve().parents[2]
GENERATOR = ROOT / "tools/content/anchor_points.py"
REVIEWED_GENERATOR_SHA256 = "e33cf287a378a4974bfa9853fd9fcd241082436aefb9727c2426330c0923f8cb"
EXCLUDED = {"前胸部", "侧胸部", "上腹部", "下腹部", "腹股沟"}
OFFICIAL = {
    "gb12346-2021": {
        "url": "https://www.ntcamsac.ac.cn/upload/std_info/202306192125376309.pdf",
        "registryUrl": "https://std.samr.gov.cn/gb/search/gbDetailed?id=D1E86BE73ADD430EE05397BE0A0A206B",
        "reviewedGeneralClauses": ["4.2.1", "4.2.2 table 1", "4.2.3", "4.3"],
    },
    "gb40997-2021": {
        "url": "https://www.ntcamsac.ac.cn/cms/content?id=104",
        "registryUrl": "https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=D2AEF8AD07C0150E19859079579EF99F",
        "reviewedGeneralClauses": ["5", "7.1", "7.3", "7.4", "7.5", "7.6"],
    },
}
CUN_PATTERN = re.compile(r"(?P<a>\d+(?:\.\d+)?)(?:[~～至-](?P<b>\d+(?:\.\d+)?))?寸")


def normalized(text):
    return unicodedata.normalize("NFKC", text)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def line_ref(source, needle):
    lines = source.splitlines()
    hits = [i + 1 for i, line in enumerate(lines) if needle in line]
    if not hits:
        raise ValueError(f"Reviewed source expression is absent: {needle}")
    return {"path": "tools/content/anchor_points.py", "line": hits[0], "expression": needle}


def constants(source):
    result = {}
    for node in ast.parse(source).body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            name = node.targets[0].id
            if name in {"HEADFACE", "HEADSCALP", "BACK_LEVELS"}:
                result[name] = ast.literal_eval(node.value)
    return result


def official_clauses(path):
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"(?<=\d)\s*\.\s*(?=\d)", ".", text)
    # Official indexed text sometimes appends a heading directly after page furniture.
    heads = list(re.finditer(r"(?m)(?<!\d)([57]\.\d+\.\d+\+?)[ \t]+[^\n]+", text))
    clauses = {}
    for i, head in enumerate(heads):
        body = text[head.end():heads[i + 1].start() if i + 1 < len(heads) else len(text)]
        location = re.search(r"在[^。]+。", body, re.S)
        if location and head.group(1) not in clauses:
            raw = re.sub(r"\s+", "", location.group())
            raw = re.sub(r"[（(](?:见|图见)GB/T.*?[）)]", "", raw)
            clauses[head.group(1)] = {"location": raw, "sourceTextLine": text[:head.start()].count("\n") + 1}
    if len(clauses) < 40:
        raise ValueError(f"Not enough standard clauses extracted from {path}: {len(clauses)}")
    return clauses


def compare_text(text):
    value = normalized(re.sub(r"\s+", "", text)).replace(";", ",").replace("；", ",").replace("，", ",")
    value = value.removeprefix("在").rstrip("。")
    return value


def constraints(text):
    text = normalized(text)
    result = []
    for index, match in enumerate(CUN_PATTERN.finditer(text)):
        left = max(text.rfind(";", 0, match.start()), text.rfind("；", 0, match.start())) + 1
        right_candidates = [x for x in (text.find(";", match.end()), text.find("；", match.end()), text.find("。", match.end())) if x >= 0]
        right = min(right_candidates) if right_candidates else len(text)
        result.append({"constraintIndex": index, "valueCun": float(match["a"]),
            "upperValueCun": float(match["b"]) if match["b"] else None,
            "criterion": text[left:right], "textOffset": [match.start(), match.end()]})
    return result


def group(point):
    region = point["region"]
    if region in {"头部", "面部", "面颊部", "项部", "颈前部", "颈部", "颈后部"}:
        return "head-face-neck"
    if region in {"背部", "腰部", "腰区", "脊柱区", "骶部"} or point["id"] in {"bl35", "bl53", "bl54"}:
        return "back-lumbar-sacral"
    if any(word in region for word in ("臂", "肘", "腕", "手", "肩")):
        return "upper-limb-shoulder"
    return "lower-limb-gluteal"


def finding(code, status, reason, source, *needles):
    return {"code": code, "status": status, "reasonZh": reason,
        "sourceCodeReferences": [line_ref(source, needle) for needle in needles]}


def review(point, source, const):
    pid = point["id"]
    area = group(point)
    loc = normalized(point["locationZh"])
    findings = []
    # Findings below inspect the actual numeric constants/reference expressions, not method labels.
    if area == "head-face-neck":
        if pid in const["HEADSCALP"] or pid in {"gv18", "gv19", "gv20", "gv21", "gv22", "gv23", "gv24", "ex-hn2"}:
            findings.append(finding("scalp-not-hairline-bone-cun", "unimplemented-standard-reference",
                "头皮由固定圆心/半径和分段角度生成：前段每寸0.21弧度，后段每寸2/7弧度；未建立前后发际12寸或眉间—大椎18寸标志链。数值输入不是已校准骨度寸。", source,
                "theta=1.05*(1-cun/5)", "1.607+.112*math.cos(theta)"))
            if "旁开" in loc:
                findings.append(finding("fixed-head-transverse-offset", "unimplemented-standard-reference",
                    "头部横向使用HEADSCALP固定世界X，未按两额角发际9横寸折量；同为入发际0.5寸的ST8/GB13/BL4横列没有统一头部横寸。最近顶点吸附还会改变请求的X值。", source,
                    "HEADSCALP=", "return nearest(t)"))
            if "瞳孔直上" in loc:
                findings.append(finding("pupil-line-not-bound", "unimplemented-standard-reference",
                    "瞳孔直上的X并未从同侧眼/瞳孔标志读取；GB15–18使用固定0.050m，当阳使用0.030m，需逐点核对瞳孔垂线。", source, "HEADSCALP=", "extra={'当阳'"))
        elif pid == "st12":
            findings.append(finding("st12-chest-column-only", "teaching-reference-only",
                "使用专门上胸几何分支，已保留锁骨相对定位证据；4寸旁开仍复用固定教学胸列，不是完成骨度测量。", source, "if pid in CHEST_POINT_IDS:"))
        elif pid == "ex-hn1":
            findings.append(finding("sishencong-reference-and-scale", "unimplemented-standard-reference",
                "四神聪围绕皮肤最高点crown而非已映射GV20，四方向统一偏移CHESTCUN=26mm后吸附；未建立头部纵横不同骨度/表面距离。", source,
                "t=L['crown'].copy();d=CHESTCUN", "offset=[d,0,0]"))
        elif pid in {"ex-hn14", "extra-qianzheng", "ex-hn15", "extra-xueyadian"}:
            reason = {
                "ex-hn14": "翳明未引用TE17坐标，也未计算后1寸；直接指定(.069,1.550)再从背面投影，可能移至后头表面而非耳后局部。",
                "extra-qianzheng": "牵正未建立耳垂中点与前方0.5～1寸量尺；固定(.065,1.549)及正面投影不能验证该范围。",
                "ex-hn15": "颈百劳未从C7直上2寸计算Y；固定Y=1.473、X=26mm，未采用标准大椎—后发际3寸的项部纵寸。",
                "extra-xueyadian": "血压点未求C6/C7棘突间平面；固定Y=1.445、X=52mm，旁开量尺复用胸寸而非已校准项部尺度。",
            }[pid]
            needle = "if name=='翳明'" if pid == "ex-hn14" else "'牵正':1.549" if pid == "extra-qianzheng" else "if name=='颈百劳'" if pid == "ex-hn15" else "if name=='血压点'"
            findings.append(finding("local-head-neck-reference-missing", "unimplemented-standard-reference", reason, source, needle))
        else:
            findings.append(finding("fixed-face-cun-landmark", "unimplemented-standard-reference",
                "该穴落HEADFACE固定米制点表；未从标准所述口角/目外眦/眉缘/耳尖或发际建立指定寸距，不能由表内坐标或已识别方法名证明定位。", source, "HEADFACE="))
        return findings
    if area == "back-lumbar-sacral":
        if pid in {"si14", "si15"}:
            findings.append(finding("shoulder-spinal-level-fixed", "unimplemented-standard-reference",
                "肩外俞固定X=.075/Y=1.388，肩中俞固定X=.052/Y=1.422；未从标准指定T1/C7棘突下平面计算，亦无肩胛内侧缘3横寸尺度。不能套用膀胱经BACK_LEVELS分支作验收。", source,
                "shoulder={'li15'"))
            return findings
        findings.append(finding("back-width-uses-fixed-chest-cun", "unimplemented-standard-reference",
            "旁开量由固定CHESTCUN=0.026m计算；标准表1规定肩胛骨内侧缘至后正中线为3横寸，当前未从该模型建立量尺，横向乘法一致不代表标准定位一致。", source,
            "CHESTCUN=.026", "if ch=='BL' and n in BACK_LEVELS:"))
        if point["region"] in {"骶部", "臀部"}:
            findings.append(finding("sacral-level-fixed-y", "unimplemented-standard-reference",
                "骶后孔/尾骨参考高度来自固定米制Y表，未识别实际骶后孔或尾骨端；横平关系与局部凹陷仍未核验。", source,
                "if ch=='BL' and 27<=n<=35:", "if ch=='BL' and n in [53,54]:"))
        else:
            findings.append(finding("spinous-inferior-proxy", "anatomical-reference-unreviewed",
                "节段号可与标准逐条比对，但spinous标志取整块椎骨后6%顶点中的最低Y，再固定下移3mm；尚未验证这就是标准棘突下的体表凹陷。", source,
                "q=v[v[:,2]<np.quantile(v[:,2],.06)]", "q[np.argmin(q[:,1])]"))
        return findings
    if area == "upper-limb-shoulder":
        if "甲" in loc:
            findings.append(finding("nail-reference-and-f-cun-absent", "unimplemented-standard-reference",
                "仅取末节指骨远端4mm范围均值，部分穴再加减固定世界X 3mm；未识别甲根角/游离缘，也未计算0.1指寸，不能把指骨端等同甲角。", source,
                "q=v[v[:,1]<v[:,1].min()+.004]", "if pid!='pc9' and name!='十宣'"))
        elif pid == "ex-ue8":
            findings.append(finding("waigong-half-f-cun-not-applied", "unimplemented-standard-reference",
                "外劳宫使用第2/3掌骨中心均值和固定背侧20mm偏移，未引用掌指关节或实现其后0.5指寸。", source,
                "t=(center(fids[0])+center(fids[1]))/2", "t[2]+=.020 if reg=='手掌'"))
        elif pid in {"si9", "si14", "si15", "extra-jianqian"}:
            findings.append(finding("shoulder-local-reference-fixed", "unimplemented-standard-reference",
                "肩贞/肩前没有独立腋后/腋前纹头直上量尺；肩外俞/肩中俞未从指定T1/C7棘突下平面及背部横寸计算。各穴使用固定肩部点表。", source,
                "shoulder={'li15'", "elif name=='肩前'"))
        else:
            findings.append(finding("limb-endpoints-provisional", "anatomical-reference-unreviewed",
                "前臂12等份和上臂9等份虽有算式，但肘点是肱骨最低端12mm顶点均值、腕点是桡骨最低端均值、腋点为固定坐标；未核对真实横纹/鹰嘴/前后腋纹头，后侧亦复用前腋9寸链。肌腱沟与参考穴连线未逐点约束。", source,
                "for key,bone,yend in", "landmark(f'{s}-axilla'", "armcun=(ax[1]-el[1])/9"))
            if pid == "te9":
                findings.append(finding("te9-five-cun-not-parsed", "definite-implementation-mismatch",
                    "标准为尺骨鹰嘴尖下5寸；现正则只处理含‘肘’的上下距离，未匹配鹰嘴下5，TE9没有专门覆写，落前臂中点即肘代理下6个前臂等份。", source,
                    "if '前臂' in reg:y=(wr[1]+el[1])/2", "r'肘[^；]*?下"))
            if pid == "ex-ue2":
                findings.append(finding("erbai-fraction-and-tendon", "arithmetic-only-reference-unvalidated",
                    "二白纵向取腕*.667+肘*.333，即12等份中的3.996份，近似4；两个点仅以世界X±7mm分开，未寻找桡侧腕屈肌腱两侧。", source,
                    "t=L[s+'-wrist']*.667+L[s+'-elbow']*.333"))
        return findings
    # Lower limb: distinguish the three standard chains rather than treating a method name as evidence.
    if "甲" in loc:
        findings.append(finding("toenail-reference-and-f-cun-absent", "unimplemented-standard-reference",
            "取趾末节骨最前顶点再上移4mm，井穴另加固定X 4mm、后移3mm；未识别趾甲根角/游离缘或计算0.1指寸。", source,
            "t=v[np.argmax(v[:,2])].copy();t[1]+=.004", "t[2]-=.003"))
    elif pid in {"ki5", "ki6"}:
        findings.append(finding("foot-one-cun-fixed-mm", "unimplemented-standard-reference",
            "一寸下移以固定25mm替代；KI5也未从已映射KI3计算，KI6未建立内踝尖至足底3寸量尺及内踝下缘凹陷。", source,
            "if pid in ['ki2','ki3','ki4','ki5','ki6']:"))
    elif pid == "ex-le1":
        findings.append(finding("kuangu-transverse-unit", "unimplemented-standard-reference",
            "基于ST34建立成对位置，但两旁各1.5寸用胸部固定26mm横尺，再全身最近顶点吸附；未建立股前横向/指寸量尺，吸附亦可能改变纵向高度。", source,
            "ref=pick('st34').copy()"))
    elif pid == "ex-le6":
        findings.append(finding("gallbladder-extra-fixed-50mm", "unimplemented-standard-reference",
            "以腓骨最高Y减固定50mm替代腓骨小头直下2寸；未建立外侧腘纹至外踝16寸量尺，也未核验最高骨顶点就是腓骨小头体表标志。", source,
            "if name=='胆囊':"))
    elif pid == "ex-le7":
        findings.append(finding("appendix-extra-fixed-52mm", "unimplemented-standard-reference",
            "从ST36直接再减52mm替代髌韧带外凹下5寸/相邻穴比例关系，没有按局部2寸增量重算，外一中指宽也未实现。", source,
            "if name=='阑尾':"))
    else:
        findings.append(finding("leg-cun-endpoints-and-chain", "unimplemented-standard-reference",
            "小腿所有方位共用(髌底—内踝)/15：标准内侧应髌尖—内踝15，外侧/后侧应腘纹(平髌尖)—外踝16。股前18、股后臀沟—腘纹14、股外大转子—腘纹19也未分开；代码以腹股沟—髌底18通用。", source,
            "c=(kne[1]-ank[1])/15", "thigh=(hip[1]-kne[1])/18"))
        if pid in {"gb31", "gb32"}:
            findings.append(finding("gb31-gb32-legacy-numeric-override", "definite-implementation-mismatch",
                "2021标准GB31为腘纹上9寸、GB32上7寸；前面的正则读取后，又被7/5硬编码覆盖，并改从髌底起算、使用前股18寸单位，兼有数值与参考点错配。", source,
                "if pid=='gb31':y=kne[1]+7*thigh", "if pid=='gb32':y=kne[1]+5*thigh"))
        if pid in {"sp8", "ki9"}:
            findings.append(finding("named-reference-offset-not-applied", "definite-implementation-mismatch",
                "标准的阴陵泉下3寸(SP8)/太溪上5寸(KI9)未从对应参考穴求差；未命中现有正则，也无专门规则，回落内踝代理上7个通用小腿等份。", source,
                "else:y=ank[1]+c*7", "for pattern,base,mult in [(r'(?:腘横纹"))
        if pid == "lr7":
            findings.append(finding("lr7-posterior-cun-missing", "definite-implementation-mismatch",
                "阴陵泉后1寸没有任何参考穴后移计算；只因文本含胫骨内侧髁而覆写Y，仍采用肝经通用前内侧方向，未实现标准的后1寸。", source,
                "if '胫骨内侧髁' in loc", "if p['meridianId']=='LR':direction"))
        if pid == "bl37":
            findings.append(finding("bl37-posterior-chain-missing", "unimplemented-standard-reference",
                "臀沟固定为Y=.79，臀沟下6寸却乘腹股沟—髌底18等份；标准臀沟—腘纹14等份，未建立实际臀沟和后股链。", source,
                "if pid=='bl37':y=.79-6*thigh"))
        if pid == "bl38":
            findings.append(finding("bl38-one-cun-is-parsed", "arithmetic-only-reference-unvalidated",
                "已明确命中腘横纹上1寸正则，不能称缺规则；问题是腘平面取髌底减20mm，寸长仍取前股18等份，未从真实腘纹/股后链定量。", source,
                "r'(?:腘横纹|膝中)上([\\d.]+)寸'"))
        if pid == "st40":
            findings.append(finding("st40-reference-overridden", "definite-implementation-mismatch",
                "文本外踝尖上8寸虽先命中外踝正则，随后胃经36–40统一规则覆盖成髌底减25mm再下8个通用小腿等份；不再从外踝上8寸求值。", source,
                "36<=int(code[2:])<=40", "dist={36:3,37:6,38:8,39:9,40:8}"))
    return findings


def instance_for(point, side):
    return next((item for item in point["instances"] if item["side"] == side), point["instances"][0])


def measurement(point, instance, constraint, points, landmarks, findings):
    """Evaluate the declared standard relationship against the delivered coordinate.

    Where a named landmark is not anatomically established, leave expected millimetres
    undefined. Do not manufacture a 'standard cun' from the source generator's method.
    """
    position = instance["anchor"]["position"]
    side = instance["side"] if instance["side"] in {"left", "right"} else "left"
    area = group(point)
    ctx = constraint["criterion"]
    number = constraint["valueCun"]
    result = {**constraint, "instanceId": instance["id"], "units": "metre/model-axis, diagnostic cun only when explicit",
        "expected": {"standardCun": number, "upperStandardCun": constraint["upperValueCun"]},
        "actual": {"positionM": position}, "status": "standard-reference-not-calibrated",
        "reasonZh": "未建立标准要求的局部标志/量尺，保留原始坐标；未用方法名判定合格。",
        "sourceCodeReferences": findings[0]["sourceCodeReferences"]}
    y = position[1]
    lm = lambda key: landmarks[f"{side}-{key}"]
    base = None
    unit = None
    sign = 1
    axis = 1
    reference = None
    unit_note = None
    # The following are measured proxies; their unreviewed anatomical endpoints remain visible.
    if "腕" in ctx and re.search(r"上[\d.]+寸", ctx):
        base = lm("wrist")[1]
        unit = (lm("elbow")[1] - base) / 12
        reference = f"{side}-wrist"
        unit_note = "腕/肘均为现有骨极值代理，12份数值仅作算术诊断。"
    elif any(word in ctx for word in ("肘横纹", "鹰嘴尖")) and re.search(r"[上下][\d.]+寸", ctx):
        base = lm("elbow")[1]
        sign = -1 if re.search(r"下[\d.]+寸", ctx) else 1
        unit = (base - lm("wrist")[1]) / 12 if sign == -1 else (lm("axilla")[1] - base) / 9
        reference = f"{side}-elbow (humeral distal proxy; not reviewed crease/olecranon)"
        unit_note = "前臂下行用12等份、上臂上行用前腋9等份代理；后侧未建立独立参考。"
    elif "腋前纹头下" in ctx:
        base = lm("axilla")[1]
        unit = (base - lm("elbow")[1]) / 9
        sign = -1
        reference = f"{side}-axilla (fixed anterior-fold proxy)"
        unit_note = "前腋点固定；9等份算术不证明腋纹头定位。"
    elif "髌底" in ctx and "上" in ctx:
        base = lm("patella-base")[1]
        unit = (lm("groin")[1] - base) / 18
        reference = f"{side}-patella-base"
        unit_note = "前股18份以现有耻骨上缘同高的groin代理至髌底计算；髌底内/外侧端与肌腱仍待核验。"
    elif "腘横纹上" in ctx:
        base = lm("patella-base")[1] - .020
        unit = (lm("groin")[1] - lm("patella-base")[1]) / 18
        reference = "implementation popliteal proxy = patella-base minus 20mm"
        unit_note = "这里故意按代码代理作数值诊断；它不是标准股外19或股后14骨度寸。"
    elif "内踝尖上" in ctx or "外踝尖上" in ctx or "腘横纹下" in ctx:
        external = "外踝" in ctx or "后" in point["region"]
        malleolus = "ankle-lateral" if external else "ankle-medial"
        endpoint = lm("patella-tip")[1]
        unit = (endpoint - lm(malleolus)[1]) / (16 if external else 15)
        base = endpoint if "腘横纹下" in ctx else lm(malleolus)[1]
        sign = -1 if "腘横纹下" in ctx else 1
        reference = f"{side}-patella-tip proxy" if sign == -1 else f"{side}-{malleolus}"
        unit_note = "按标准链分别用髌尖—内踝15或髌尖平腘纹—外踝16；踝点仍是现有骨极值均值代理，结果不是医学误差。"
    else:
        named = {"阴陵泉": "sp9", "太溪": "ki3", "犊鼻": "st35", "昆仑": "bl60",
            "翳风": "te17", "梁丘": "st34", "百会": "gv20", "气冲": "st30"}
        target = next(((name, pid) for name, pid in named.items() if name in ctx), None)
        if target:
            name, pid = target
            ref = instance_for(points[pid], side)["anchor"]["position"]
            delta = [position[i] - ref[i] for i in range(3)]
            result["reference"] = {"pointId": pid, "positionM": ref, "referenceStatus": "same unreviewed asset"}
            result["actual"].update(deltaFromNamedPointMm=[round(v * 1000, 5) for v in delta],
                distanceFromNamedPointMm=round(math.dist(position, ref) * 1000, 5))
            if area == "lower-limb-gluteal" and pid in {"sp9", "ki3", "st35", "bl60", "st30"}:
                external = pid in {"st35", "bl60"}
                if pid == "st30":
                    unit = (lm("groin")[1] - lm("patella-base")[1]) / 18
                else:
                    ankle = lm("ankle-lateral" if external else "ankle-medial")[1]
                    unit = (lm("patella-tip")[1] - ankle) / (16 if external else 15)
                if re.search(r"[上下][\d.]+寸", ctx):
                    base = ref[1]
                    sign = -1 if re.search(r"下[\d.]+寸", ctx) else 1
                    reference = f"delivered {pid} position"
                    unit_note = "引用同一未审校资产中的参考穴，并使用标准端点链代理；用于发现关系错配，不能认定医学距离。"
            if "后" in ctx and pid in {"sp9", "te17"}:
                result["actual"]["posteriorSignedDisplacementMm"] = round(-delta[2] * 1000, 5)
                result["reasonZh"] = "已记录相对参考穴的前后方向；1寸局部量尺尚未建立，不把三维直距当表面寸距。"
    if base is not None and unit is not None:
        actual = sign * (y - base) / unit
        result["reference"] = {"name": reference, "axis": "+Y superior", "coordinateM": base,
            "diagnosticUnitM": unit, "limitationZh": unit_note}
        result["expected"].update(axisCoordinateUsingNamedProxiesM=base + sign * number * unit)
        result["actual"].update(diagnosticCun=round(actual, 6), deviationCun=round(actual - number, 6),
            axisDeviationMm=round((y - (base + sign * number * unit)) * 1000, 6))
        result.update(measurementKind="directed-world-Y-displacement-under-explicit-provisional-landmark-chain",
            expectedDistanceMm=round(number * unit * 1000, 6), actualDistanceMm=round(sign * (y - base) * 1000, 6),
            signedDeviationMm=round((sign * (y - base) - number * unit) * 1000, 6))
        result["status"] = "proxy-coordinate-measurement-only"
        result["reasonZh"] = unit_note
    if "旁开" in ctx and area == "back-lumbar-sacral":
        actual = abs(position[0]) / .026
        result["reference"] = {"name": "world sagittal plane X=0", "diagnosticUnitM": .026,
            "limitationZh": "代码固定胸寸，不是标准肩胛内侧缘3横寸。"}
        result["actual"].update(fixed26mmCun=round(actual, 6), deviationFromFixed26mmFormulaMm=round((abs(position[0]) - .026 * number) * 1000, 6))
        result.update(measurementKind="absolute-world-X-distance-from-model-midline-under-unvalidated-fixed-26mm-unit",
            expectedDistanceMm=round(number * 26, 6), actualDistanceMm=round(abs(position[0]) * 1000, 6),
            signedDeviationMm=round((abs(position[0]) - number * .026) * 1000, 6))
        result["status"] = "arithmetic-only-reference-unvalidated"
    if point["id"] in {"ex-hn15", "extra-xueyadian"}:
        result["actual"]["heightAboveC7SpinousMm"] = round((y - landmarks["cervical-7-spinous"][1]) * 1000, 6)
    if "甲" in point["locationZh"]:
        result["reasonZh"] = "缺少甲根角/游离缘与同身指寸参考；未伪造0.1寸的毫米期望。"
    result["reason"] = result["reasonZh"]
    return result


def locate_standard(explicit, names):
    if explicit:
        path = Path(explicit)
    else:
        paths = [ROOT / "tools/content/sources" / name for name in names]
        path = next((item for item in paths if item.exists()), paths[0])
    if not path.exists():
        raise SystemExit(f"Official text is required (no university-copy fallback): {path}")
    if "university" in path.name:
        raise SystemExit("University transcriptions must not be substituted for the canonical official text")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--meridian-text")
    parser.add_argument("--extra-text")
    parser.add_argument("--geometry-only", action="store_true", help="Freeze geometry evidence while official text is pending; never claim a text comparison")
    parser.add_argument("--attach-official-to-existing", action="store_true", help="Attach official text to an already frozen geometry report without rereading current coordinates/formulas")
    parser.add_argument("--output", type=Path, default=ROOT / "manifests/cun-regional-audit.json")
    args = parser.parse_args()
    if args.attach_official_to_existing:
        report = json.loads(args.output.read_text())
        supplied = {key: Path(value) for key, value in {"gb12346-2021": args.meridian_text,
            "gb40997-2021": args.extra_text}.items() if value}
        if not supplied:
            raise SystemExit("Attach mode requires at least one explicit official text path")
        parsed = {key: official_clauses(path) for key, path in supplied.items()}
        for entry in report["entries"]:
            standard = entry["standard"]
            if standard["sourceId"] not in parsed:
                continue
            clause = parsed[standard["sourceId"]].get(standard["clause"])
            if clause is None:
                raise SystemExit(f"Official clause missing for frozen entry {entry['id']}: {standard['clause']}")
            path = supplied[standard["sourceId"]]
            standard.update(officialClauseText=clause["location"], sourceTextPath=str(path.relative_to(ROOT)),
                sourceTextLine=clause["sourceTextLine"], textReviewStatus="canonical-web-extracted-clause-compared" if "-web" in path.stem else "canonical-clause-compared")
            entry["standardTextDiff"]["literalMatchAfterBasicPunctuationNormalization"] = compare_text(clause["location"]) == compare_text(entry["assetLocationZh"])
        existing_paths = {item["path"] for item in report["inputs"]}
        for path in supplied.values():
            relative = str(path.relative_to(ROOT))
            if relative not in existing_paths:
                report["inputs"].append({"path": relative, "sha256": digest(path), "kind": "web-extracted-text-not-original-pdf" if "-web" in path.stem else "official-text"})
        report["summary"]["canonicalTextReviewPending"] = sum(entry["standardTextDiff"]["literalMatchAfterBasicPunctuationNormalization"] is None for entry in report["entries"])
        report["summary"]["standardTextLiteralDifferences"] = [entry["id"] for entry in report["entries"] if entry["standardTextDiff"]["literalMatchAfterBasicPunctuationNormalization"] is False]
        report["textAttachmentBoundaryZh"] = "仅在已冻结的旧几何基线上附官方条文对照；未重新读取或重算当前修改中的坐标和定位生成器。web提取文件的SHA不代表原始PDF。"
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
        return
    source = GENERATOR.read_text(encoding="utf-8")
    if digest(GENERATOR) != REVIEWED_GENERATOR_SHA256:
        raise SystemExit("anchor_points.py differs from this manually reviewed source snapshot; review the changed formulas before updating this audit")
    paths = {} if args.geometry_only else {
        "gb12346-2021": locate_standard(args.meridian_text, ["gb12346-canonical.txt", "gb12346-official.txt"]),
        "gb40997-2021": locate_standard(args.extra_text, ["gb40997-canonical.txt", "gb40997-official.txt"]),
    }
    texts = {key: official_clauses(path) for key, path in paths.items()}
    data_path = ROOT / "app/src/main/assets/content/acupoints.json"
    landmarks_path = ROOT / "app/src/main/assets/content/landmarks.json"
    data = json.loads(data_path.read_text())
    points = {item["id"]: item for item in data["points"]}
    landmarks = {item["id"]: item["position"] for item in json.loads(landmarks_path.read_text())["landmarks"]}
    const = constants(source)
    selected = [point for point in points.values() if "寸" in point["locationZh"] and point["region"] not in EXCLUDED]
    if len(selected) != 160:
        raise SystemExit(f"Expected the fixed 160-entry scope, got {len(selected)}; recheck the inventory boundary")
    entries = []
    for point in selected:
        sources = point["sourceRefs"]
        ref = next(value for value in sources if value["sourceId"] in OFFICIAL)
        official = texts.get(ref["sourceId"], {}).get(ref["clause"])
        if official is None and not args.geometry_only:
            raise SystemExit(f"Official clause not extracted for {point['id']}: {ref}")
        findings = review(point, source, const)
        if not findings:
            raise SystemExit(f"No concrete formula/reference review for {point['id']}")
        text_match = compare_text(official["location"]) == compare_text(point["locationZh"]) if official else None
        entry = {"id": point["id"], "nameZh": point["nameZh"], "region": point["region"], "reviewGroup": group(point),
            "standard": {**ref, "url": OFFICIAL[ref["sourceId"]]["url"], "officialClauseText": official["location"] if official else None,
                "sourceTextPath": str(paths[ref["sourceId"]].relative_to(ROOT)) if official else None,
                "sourceTextLine": official["sourceTextLine"] if official else None,
                "textReviewStatus": "canonical-clause-compared" if official else "canonical-clause-text-pending"},
            "assetLocationZh": point["locationZh"], "standardTextDiff": {"literalMatchAfterBasicPunctuationNormalization": text_match,
                "normalization": "NFKC/whitespace/leading 在/comma-semicolon/final period only; no semantic substitution"},
            "findings": findings, "measurements": [], "medicalReviewCompleted": False}
        for constraint in constraints(point["locationZh"]):
            for instance in point["instances"]:
                entry["measurements"].append(measurement(point, instance, constraint, points, landmarks, findings))
        entries.append(entry)
    statuses = collections.Counter(item["status"] for entry in entries for item in entry["findings"])
    summary = {"entries": len(entries), "groups": dict(collections.Counter(item["reviewGroup"] for item in entries)),
        "constraints": sum(len(constraints(point["locationZh"])) for point in selected),
        "instanceConstraintMeasurements": sum(len(entry["measurements"]) for entry in entries),
        "findingStatuses": dict(statuses), "standardTextLiteralDifferences": [entry["id"] for entry in entries
            if entry["standardTextDiff"]["literalMatchAfterBasicPunctuationNormalization"] is False],
        "canonicalTextReviewPending": sum(entry["standardTextDiff"]["literalMatchAfterBasicPunctuationNormalization"] is None for entry in entries),
        "definiteImplementationMismatchIds": [entry["id"] for entry in entries if any(
            item["status"] == "definite-implementation-mismatch" for item in entry["findings"])], "medicalReviewCompleted": 0}
    report = {"schemaVersion": 1, "scope": "160 of 226 cun-bearing entries, excluding 66 thoracoabdominal entries",
        "purposeZh": "固定标准原文、源码和已交付坐标的工程关系审计；不代替专业医学定位审校。",
        "inputs": [{"path": str(path.relative_to(ROOT)), "sha256": digest(path)} for path in [GENERATOR, data_path, landmarks_path, *paths.values()]],
        "sourceSnapshot": {"path": "tools/content/anchor_points.py", "sha256": digest(GENERATOR), "text": source},
        "landmarkSnapshot": landmarks,
        "officialSources": OFFICIAL, "generalStandardFacts": {
            "head": "anterior/posterior hairline 12; glabella/anterior hairline 3; C7/posterior hairline 3; forehead corners 9 transverse",
            "back": "medial scapular border to posterior midline 3 transverse cun",
            "arm": "anterior axillary fold to elbow crease 9; posterior fold to olecranon 9; elbow crease to wrist crease 12",
            "leg": "pubic superior border to patellar base 18; patellar base to apex 2; patellar apex to medial malleolus 15; greater trochanter to popliteal crease 19; gluteal fold to popliteal crease 14; popliteal crease to lateral malleolus 16; medial malleolus to sole 3",
            "fingerCun": "patient-specific finger landmarks; nail root/free edge is not distal phalanx endpoint"},
        "limitationsZh": ["医学审校完成数仍为0。", "毫米偏差只是在明确说明的现有骨/参考穴代理之间计算；没有据此宣布临床定位误差。",
            "未通过已识别方法名、落在皮肤上或乘法相等判定符合标准。", "标准公开可读不意味着开放再分发许可；原PDF不进入安装包。"],
        "summary": summary, "entries": entries}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
