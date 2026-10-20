# 1 人体资产构建

面向维护者；需要 Python 3.10 以上、Node.js 20 以上、curl 及能够访问官方数据源的网络环境。Python 转换脚本仅使用标准库，不需要 Blender、NumPy 或其他 pip 依赖；肌肉简化和遮挡网格使用随附的 meshoptimizer 模块。所有原始模型下载保存在本目录的 `source/`。

## 1.1 取得输入与构建

在项目根目录执行：

```sh
python3 tools/assets/download_sources.py
python3 tools/assets/build_models.py
node tools/assets/export_occlusion.mjs
python3 tools/assets/verify_models.py
python3 tools/assets/audit_muscle_topology.py
```

下载需要网络；若使用代理，请按本机环境配置。已取得原始输入后，资产转换可以断网运行。

只更新肌肉学习文字时运行 `python3 tools/assets/update_muscle_content.py`，它保持 GLB、结构 ID、坐标、分层和名称不变，并核验元数据重复运行结果一致。逐项别名、结构类型和事实概述位于 `muscle-learning.tsv`，常见大肌的完整概述位于 `muscle-descriptions.json`。文字仍待专业审校；肌肉系统包含分部和相关结缔组织，节点数不能当作独立肌肉条数。

显示模型生成后，另执行 `node tools/assets/export_occlusion.mjs` 生成 `models/skin-occlusion.bin`（Node.js 20 以上）。该工具只额外导出真实低档皮肤的 CPU 遮挡三角网格，不改 GLB；使用本目录随附的官方 meshoptimizer WASM 模块及 MIT 许可，无 npm 运行依赖。格式、误差指标及输入输出哈希见 `manifests/model-skin-occlusion.json`。

骨骼和内脏的标准档按 1.5 mm 网格聚类、低档按 5 mm 聚类；皮肤为薄层表面，低档直接复用标准档皮肤，避免合并不同朝向表面而产生三角斑。肌肉采用 meshoptimizer 1.3 边折叠简化，先合并完全相同的 float32 坐标，不合并邻近点、不移动顶点。标准/低档估计误差预算分别为 1.5/5 mm；在多个减面目标中，只接受开边及非流形边均不增加的候选，否则保留源几何，不做填孔。120 个三角面以下的小元件保持原始几何。`model-muscle-simplification.json` 记录每个候选误差、拓扑和回退情况；误差为简化器估计值，不能当作严格 Hausdorff 距离或医学测量精度。

可用 `--standard-cell-mm 0` 生成未经简化的标准档，用 `--low-cell-mm` 调整低档聚类宽度和肌肉误差预算。两档保持同一组独立结构节点、名称和坐标。`verify_muscle_fix.py before|check` 专门核验肌肉修复前后的 728 个非肌肉部件字节、结构元数据及穴位实例没有改变；其清单保留此次修复的前后证据，不应在普通日常重建中重新覆盖 `before`。

## 1.2 坐标与结构

最终单位为米，Y 向上、前方为 +Z、人体解剖左侧为 +X。原 BodyParts3D 为毫米、Z 向上、前方为 -Y。统一变换为 `[x/1000, (z+78.1112)/1000, -y/1000]`；脚底基准取自真实皮肤网格，不拉伸人体。

Z-Anatomy 只引入明确白名单中的74个肌肉网格及60个结缔组织网格（44片筋膜或腱膜、16片支持带）。先求 FBX 每个模型节点的完整层级变换，再将厘米转为米；以两套数据共同肌肉包围盒中心差的逐轴中位数计算统一平移。原始层级矩阵、配准对及残差均写入模型清单，不能把该配准当作经过临床验收的逐点配准。

模型导出到 `app/src/main/assets/models/`，结构信息导出到 `app/src/main/assets/anatomy.json`。同一概念包含多个原始元件时保留独立原件，不重复导出复合概念的叠加网格。所有结构能回查原始 FJ 或 Z-Anatomy FBX ID。

皮肤 FJ2810 的内外层间距约 2 mm，因此流畅档皮肤复用标准档几何。`manifests/model-low-skin-fix.json` 是此前皮肤修复阶段的历史证据，其中整包 SHA 不表示后续肌肉修复后的当前 GLB；当前文件与输入哈希以 `model-build.json`、`model-validation.json` 为准。

## 1.3 半身剖露材质

运行时按原始人体坐标的 `x=0` 正中矢状面裁切指定半侧皮肤、肌肉及眼和口唇等外表面片元；不是根据部件中心隐藏整块网格。深部内脏、骨骼、牙齿和穴位坐标保持原样，切换左右或模型精度时沿用同一裁切状态。颜色、深度和点选均使用同一 masked 材质；不生成切面填充或新增解剖组织。

APK 和源码包包含已编译的 `materials/cutaway.filamat`，常规 Android 构建无需重新编译。修改 `cutaway.mat` 后必须使用与运行时匹配的官方 Filament 1.78.0 `matc`：

```sh
python3 tools/assets/build_cutaway_material.py --matc /path/to/filament-1.78.0/bin/matc
```

输入、参数、产物和 SHA-256 记录在 `manifests/cutaway-material.json`。本工作区采用的 macOS 官方工具包 SHA-256 已与 GitHub 发布页公布摘要核对；该工具包位于忽略的 `.tools/`，不作为应用运行依赖。

## 1.4 验证范围

验证脚本核验 ZIP CRC、输入与模型 SHA-256、glTF 文件长度/访问器/顶点索引、有限坐标和法线、两档 ID 一致、中文名称覆盖及主要肌肉实际存在。它不证明医学精确性、设备帧率或用户视觉验收。完整许可和已知限制见 `licenses/MODEL-NOTICE.md`。

## 1.5 追加真实筋膜网格

当前已打包资产包含新增的52片筋膜、腱膜和支持带。常规构建直接使用随源码分发的GLB，无需重新导入。`append_fascia_assets.py` 仅适用于仍有1239个部件的0.4.0基线：

```sh
python3 tools/assets/append_fascia_assets.py
python3 tools/assets/verify_models.py
```

该命令需要原FBX和既有模型构建输入；不是对当前1291部件资产可重复执行的日常命令。追加过程验证原节点、材质、primitive、访问器与BIN前缀不变，保留穴位及筋膜表面锚点所依赖的三角形和顶点顺序；来源与检查见公开 `docs/validation/model-fascia-expansion.json`。新增薄片在标准与流畅档均保留源拓扑，可能包含自然边界，未做独立低档减面或专业配准验收。
