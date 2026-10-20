"""Teaching route topology. This is an editorial schematic, not a segmented vessel.
Acupoint order is NOT used as a universal path: branches and regional runs are explicit.
"""
PRIMARY={
'LU':('前胸经上肢前内侧至拇指。',[['lu2','lu1','lu3','lu4','lu5','lu6','lu7','lu8','lu9','lu10','lu11']]),
'LI':('食指经上肢外侧、肩颈至鼻旁。',[['li1','li2','li3','li4','li5','li6','li7','li8','li9','li10','li11','li12','li13','li14','li15','li16','li17','li18','li19','li20']]),
'ST':('面部经颈前、胸腹、下肢前外侧至第二趾。',[[f'st{i}' for i in range(1,9)],['st5','st9','st10','st11','st12']+[f'st{i}' for i in range(13,46)]]),
'SP':('足大趾经下肢内侧、腹部至胸侧。',[[f'sp{i}' for i in range(1,22)]]),
'HT':('腋窝经上肢内侧、掌侧至小指。',[[f'ht{i}' for i in range(1,10)]]),
'SI':('小指经上肢后内侧、肩胛、颈侧至耳前。',[[f'si{i}' for i in range(1,20)]]),
'BL':('目内眦经头背、腰骶、下肢后侧至小趾；背部分两行。',[[f'bl{i}' for i in range(1,11)],['bl10']+[f'bl{i}' for i in range(11,31)]+['bl35','bl36','bl37','bl38','bl39','bl40'],['bl10']+[f'bl{i}' for i in range(41,55)]+['bl40'],['bl40']+[f'bl{i}' for i in range(55,68)]]),
'KI':('足底经足内侧、下肢内侧、腹胸近中线处上行。',[[f'ki{i}' for i in range(1,11)],[f'ki{i}' for i in range(11,28)]]),
'PC':('胸部经上肢前内侧、前臂掌侧至中指。',[[f'pc{i}' for i in range(1,10)]]),
'TE':('无名指经上肢后侧、肩颈、耳周至眉梢。',[[f'te{i}' for i in range(1,24)]]),
'GB':('目外眦经头侧、肩侧、胸胁、髋及下肢外侧至第四趾。',[[f'gb{i}' for i in range(1,22)],[f'gb{i}' for i in range(21,45)]]),
'LR':('足大趾经足背、下肢内侧、腹股沟至胸胁。',[[f'lr{i}' for i in range(1,15)]])}
EXTRA={
'GV':('督脉','Dūmài','会阴、脊柱后正中线与头面正中线。',[[f'gv{i}' for i in range(1,25)]+['gv24-plus','gv25','gv26','gv27','gv28']]),
'CV':('任脉','Rènmài','会阴经腹胸前正中线至颏唇部。',[[f'cv{i}' for i in range(1,25)]]),
'CHONG':('冲脉','Chōngmài','以下腹、腹胸部为主的教学示意；并有深部与下肢分支。',[['cv1','cv2','ki11','ki12','ki13','ki14','ki15','ki16','ki17','ki18','ki19','ki20','ki21','cv22','cv23']]),
'DAI':('带脉','Dàimài','围绕腰腹的带状循行示意。',[['gb26','gb27','gb28']]),
'YINQIAO':('阴跷脉','Yīnqiāomài','足内踝向下肢内侧、腹胸颈与目内眦上行的示意。',[['ki6','ki8','ki10','sp11','cv4','ki21','cv22','st9','bl1']]),
'YANGQIAO':('阳跷脉','Yángqiāomài','外踝经下肢及躯干外侧、肩颈与面部的示意。',[['bl62','bl61','bl59','gb29','si10','li15','li16','st4','st3','st1','bl1','gb20']]),
'YINWEI':('阴维脉','Yīnwéimài','小腿内侧经大腿与腹部至胸颈的示意。',[['ki9','sp13','sp15','sp16','lr14','cv22','cv23']]),
'YANGWEI':('阳维脉','Yángwéimài','外踝经下肢外侧、肩项和头侧的示意。',[['bl63','gb35','si10','te15','gb21','gb13','gb14','gb15','gb16','gb17','gb18','gb19','gb20','gv16','gv15']])}
# These associations are shared points, NOT a newly invented set of extra-meridian acupoints.
ASSOCIATIONS={k:sorted({p for segment in v[3] for p in segment}) for k,v in EXTRA.items() if k not in ['GV','CV','CHONG','YINQIAO']}
ASSOCIATIONS['CHONG']=[f'ki{i}' for i in range(11,22)]
ASSOCIATIONS['YINQIAO']=['ki6','ki8','bl1']
# The same canonical color is consumed by route lines, point markers and labels.
# Deterministic CIELAB farthest-point selection; see update_palette.py and its manifest.
COLORS={'LU':'#F0C030','LI':'#F040F0','ST':'#20C0F0','SP':'#20F020','HT':'#F06070','SI':'#50A060','BL':'#C090E0','KI':'#F07020','PC':'#C0F050','TE':'#20F0E0','GB':'#F0B080','LR':'#20F090','GV':'#D0E090','CV':'#F060B0','CHONG':'#40B020','DAI':'#90A020','YINQIAO':'#5090F0','YANGQIAO':'#D080A0','YINWEI':'#90F0B0','YANGWEI':'#C070F0'}
