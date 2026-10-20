# 1 人体模型来源与许可

应用中的几何资产用于解剖与经穴位置学习。成人男性参考网格不代表每个人的体形或全部解剖变异，不能作为诊断、针刺深度或手术导航依据。

## 1.1 BodyParts3D

署名：BodyParts3D, © The Database Center for Life Science licensed under CC Attribution 4.0 International.

原始模型：[BodyParts3D Release 4.0 官方下载](https://dbarchive.biosciencedbc.jp/en/bodyparts3d/download.html)。官方于 2025 年更新的[许可页](https://dbarchive.biosciencedbc.jp/en/bodyparts3d/lic.html)将数据库许可列为 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。离线全文见同目录 `CC-BY-4.0.txt`。

保留原始独立 OBJ 元件与 FMA 概念的对应关系。转换包括毫米到米、坐标轴旋转、脚底归零、法线重算、材质配置、中文名称与学习分组。骨骼、内脏和皮肤采用按结构顶点聚类；肌肉改为精确重合点合并及受误差预算约束的边折叠简化，并检查开边和非流形边不增加，不做填孔。原始血管及周围神经等非本应用请求系统未导出。

## 1.2 Z-Anatomy 肌肉与筋膜补充

署名：Z-Anatomy - The libre 3D atlas of anatomy - CC-BY-SA 4.0。原始基础来源署名同时保留：BodyParts3D - The Database Center for Life Science - CC-BY-SA 2.1 Japan。

来源为 [Z-Anatomy 官方 Unity 项目中的 MuscularSystem100.fbx](https://github.com/LluisV/Z-Anatomy/blob/PC-Version/Resources/Models/FBX/MuscularSystem100.fbx)，遵循 [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)。上游原始署名与说明保留于 `Z-Anatomy-upstream.txt`，许可全文保留于 `CC-BY-SA-4.0.txt`。

仅使用明确白名单中的 74 个肌肉网格及 60 个结缔组织网格：44 片筋膜或腱膜、16 片支持带。包含胸腰筋膜前、中、后层、足底和掌腱膜，以及头颈、躯干与四肢的可用筋膜；支持带单独标注组织类型。已存在的腕屈肌支持带、髂胫束未重复导入，阔筋膜张肌仍归肌肉。不导入内耳、肾脏或其他器官网格。转换包括 FBX 层级变换求值、厘米到米、共同肌肉的稳健平移配准与法线计算；肌肉使用受误差预算约束的边折叠简化，60 片结缔组织在两档均保留源网格分辨率。配准及肌肉分层尚需专业审校。

发布的合并 `body-standard.glb` 和 `body-low.glb` 均以 CC BY-SA 4.0 分发。该模型许可不用于变更本项目自行编写的应用代码许可。

## 1.3 内容和证据

中文名称与概述由本项目编写；引用的解剖教材用于事实核查，不包含教材图片或原文段落。中文术语、肌肉起止点、功能、层级与补充网格配准未经过专业人员逐项验收。

输入和输出 SHA-256、原始元件 ID、补充白名单、坐标变换及配准残差见 `docs/validation/model-build.json`、`docs/validation/model-supplement.json` 与 `docs/validation/model-connective-supplement.json`。分类与具体概念选择见 `docs/validation/model-taxonomy.json`。筋膜、腱、韧带等组成结缔支持组织显示组，不等于每项都是筋膜；解剖腔隙独立保留为参考模型。低精度和标准精度包含相同结构 ID；未用程序几何图元代替缺少的解剖结构。运行时半身剖露仅按正中矢状面裁切皮肤、肌肉和眼口外表面片元，不增加组织或改变穴位坐标；肌肉修复的源网格、开边和非流形对比见 `docs/validation/model-muscle-fix.json`。
