# 1 TARA 原文资料与许可

本应用离线附带 TARA Acupoints Ontology 项目的原始资料，作为独立的学习参考集合。资料保持英文原文，中文栏目名及本应用条目的对应关系由本项目提供；不以这些对应关系替换本应用的国标编号、归经或三维锚点。引用不表示 TARA、作者、其任职机构或 NIH 对本应用背书。

## 1.1 来源、作者和版本

资料名称：TARA Acupoints Ontology。作者元数据：Fahim T. Imam（creator）；Fahim T. Imam、Ilias Ziogas、Maryann E. Martone、Richard Harris（contributors）。元数据中的作者标识分别为：

- Fahim T. Imam：https://orcid.org/0000-0003-4752-543X
- Ilias Ziogas：https://orcid.org/0000-0002-9210-2455
- Maryann E. Martone：https://orcid.org/0000-0002-8406-3871
- Richard Harris：https://orcid.org/0000-0002-2067-8447

TARA（Topological Atlas and Repository for Acupoint Research）是 NIH/NCCIH 资助项目（U24AT012560）。项目说明：https://tara-repository.mgb.org/about；本体仓库：https://github.com/SciCrunch/TARA-Ontology-Repository。

本应用随附正式发布的本体 `1.7.2 - Asserted Ontology`，其发行声明为 `September 09, 2026`，固定仓库提交为 `7c05c8407f6e529bc839d34109e67ab00c642c73`。运行时直接只读解析该 Turtle 原件，不以编辑工作表或其他格式的工作快照替代正式发布内容。

原始文件的仓库前缀为：

`https://github.com/SciCrunch/TARA-Ontology-Repository/blob/7c05c8407f6e529bc839d34109e67ab00c642c73/`

| 安装包内路径 | 原仓库相对路径 | 字节数 | SHA-256 |
| --- | --- | ---: | --- |
| `third_party/tara/acupoints-1.7.2.ttl` | `docs/distribution/ontology/version/1.7.2/asserted/acupoints.ttl` | 2594678 | `c4967c2bb7f4936200f77fac5da19219e6ca5850e7f46dfc8a87bd28b7c68296` |

原件逐字节保留，未翻译、改写、修订拼写或删除三元组；本体、作者及许可声明完整保存。该原件是 TARA 学习面板的唯一内容输入。

## 1.2 原文显示范围

条目通过稳定的术语 IRI 识别，例如中府为 `http://purl.org/tara/ontology/TARA_0913913`，印堂为 `http://purl.org/tara/ontology/TARA_3135569`。学习面板选取以下原文字段；同一属性有多个字面量时保留各个原值，不翻译、拼接改写或修补原文。缺失字段保持缺失，不推算或生成介绍。

| 原件中的属性 | 学习内容 |
| --- | --- |
| `rdfs:label` | 原标准编号或术语标签 |
| `tara-ap:hasPinyinLabel` | 拼音 |
| `tara-ap:hasChineseLabel` | 来源的中文标签 |
| `tara-ap:hasSynonym` | 别名及其他标准编号 |
| `tara-ap:hasLocationalDescription` | 定位说明 |
| `tara-ap:hasListedIndications` | 原文列出的传统用途 |
| `tara-ap:hasVasculatureInformation` | 血管说明 |
| `tara-ap:hasInnervationInformation` | 神经说明 |
| `dcterms:bibliographicCitation` | 该条目的原出版物引用 |

`tara-ap:` 前缀为 `http://purl.org/tara/ontology/annotation-property/`。本体另以 `tara-ap:isLocatedOnMeridian` 引用经络 IRI；本面板暂不展示该关系。`rdfs:` 为 `http://www.w3.org/2000/01/rdf-schema#`，`dcterms:` 为 `http://purl.org/dc/terms/`。

原始本体含有 `tara-ap:hasMethodDescription` 等其他属性。本应用不在学习面板展示针刺或艾灸操作方法及编辑批注；这些三元组随原文件保留，仅为保持原件完整。展示的英文主治为来源的传统用途记载，不等同于已证实的单穴临床疗效。

该资料使用 WHO 等来源的分类体系，包含 361 个标准经穴、性别子类、奇穴及奇穴的细分类型，不能按本体类数计算唯一穴位。中府术语声明始于原件第 16070 行；印堂为第 770 行的 `TARA:3135569`，保留 `Yintang / EX-HN 3` 的源标签与别名，本应用仍使用国标条目 `GV24+`。金津（源标签 `Jinjing`）和玉液分别为 `TARA:3641900`、`TARA:7471145`，两份来源原文关联同一国标条目。夹脊为 `TARA:3734471`；夹脊、四神聪、八邪、八风等群穴原文不代表每个显示实例分别经过医学定位校准。来源尚未对应的条目不补造 TARA 介绍，原件中的信息也不构成三维坐标验证。

## 1.3 原文引用的出版物

每个条目的 `dcterms:bibliographicCitation` 原文随对应介绍展示，以下资料不因被引用而变为本项目原创内容：

- World Health Organization, WHO Regional Office for the Western Pacific. *WHO Standard Acupuncture Point Locations in the Western Pacific Region*. 2008. https://iris.who.int/handle/10665/353407 。来源用于标准编号、拼音、中文名称和体表定位。
- Cheng Xinnong, ed. *Chinese Acupuncture and Moxibustion*. Revised edition. Foreign Languages Press, Beijing, 1999. ISBN 978-7-119-01758-7。来源用于奇穴、传统用途及血管和神经说明。
- Shanghai College of Traditional Medicine. *Acupuncture: A Comprehensive Text*. Translated and edited by John O'Connor and Dan Bensky. Eastland Press, Chicago, 1981。来源用于奇穴的其他编号和别名。

## 1.4 许可及再利用边界

随附本体第 44 行的原始声明为：`dcterms:license "https://creativecommons.org/licenses/by-nc-nd/4.0/" ;`。TARA 原文按该声明保留 **Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International（CC BY-NC-ND 4.0）**，不按本项目应用代码的 Apache-2.0 或模型的 CC BY-SA 4.0 再许可。许可概要：https://creativecommons.org/licenses/by-nc-nd/4.0/；完整英文条款：https://creativecommons.org/licenses/by-nc-nd/4.0/legalcode.en 。离线原文见同目录 `CC-BY-NC-ND-4.0.txt`，原始获取地址为 https://creativecommons.org/licenses/by-nc-nd/4.0/legalcode.txt ，字节数 19127，SHA-256 为 `38762e3777f4ec00a6f769062a7c3f704fb78ce08303ecff88558da4c49cf9ea`。

保留该发布本体的许可声明不表示第三方原文的权利已全部核清，也不产生更宽泛的再许可。

许可第 2(a)(1) 条区分非商业的原文完整或部分分享与改编材料分享；第 2(a)(4) 条允许为使用不同介质和格式所需的技术转换。Creative Commons 官方 FAQ 说明，未改动的节选或独立作品可以在满足条件时收入合集，而翻译或其他演绎材料的分发不属于 ND 授权范围。依据：https://creativecommons.org/faq/#can-i-reuse-an-excerpt-of-a-larger-work-that-is-licensed-with-the-noderivs-restriction 和 https://creativecommons.org/faq/#if-i-create-a-collection-that-includes-a-work-offered-under-a-cc-license-which-licenses-may-i-choose-for-the-collection 。

本应用保留独立原文件，并在运行时只读选取原文字段；TARA 内容不并入本应用 Room 中文内容库，也不以对照表改写来源的分类或表达。特别数据库权适用时，许可第 4 条对把全部或大量内容纳入另一个受特别数据库权保护的数据库另有规定；保留原件与来源边界不等于对任何后续再利用作出法律批准。

再利用者须自行核对非商业、署名、保留许可及免责声明等条件，不得暗示来源方背书。将介绍翻译、改编后分发或用于商业目的，需要另行取得相应权利人的许可或具备其他法律依据。TARA 引用的 WHO、教材及其他第三方表达仍保留各自权利；本体声明及本项目许可仅涉及许可方有权授予的权利。资料按原样提供，许可不保证权利完整性、准确性、适销性或特定用途适用性。

## 1.5 输入校验

源码中的 `python3 tools/content/fetch_tara.py` 默认完全离线，只校验正式发布本体原件、两处许可全文及两处署名说明的一致性。缺少固定输入时，维护者可显式运行 `python3 tools/content/fetch_tara.py --fetch`，按固定 URL 获取并检查 SHA-256；已有输入不被覆盖。常规 Android 构建与首次启动无需网络。
