#!/usr/bin/env python3
"""Download the pinned, publicly readable reference copies. Source env.sh before use.
PDFs remain in tools/content/sources and are never bundled in the APK.
"""
from pathlib import Path
import subprocess,hashlib,json
BASE=Path(__file__).resolve().parent
SOURCES={
 'gb12346-university.pdf':'https://zynj.shutcm.edu.cn/_upload/article/files/66/b4/b34a95604d04b0bf686251b2d317/368ea8b0-187c-48a7-a402-ffdf1e11bb99.pdf',
 'gb40997-university.pdf':'https://zynj.shutcm.edu.cn/_upload/article/files/66/b4/b34a95604d04b0bf686251b2d317/72823dbe-8f55-4e2b-b98b-ff106160f598.pdf'
}
for name,url in SOURCES.items():
 path=BASE/'sources'/name;path.parent.mkdir(parents=True,exist_ok=True)
 if not path.exists():
  tmp=path.with_suffix('.download')
  subprocess.run(['curl','-fLsS','--retry','2','--connect-timeout','15','--max-time','120','-o',str(tmp),url],check=True)
  assert tmp.read_bytes().startswith(b'%PDF'),name
  tmp.replace(path)
 print(name,hashlib.sha256(path.read_bytes()).hexdigest())
