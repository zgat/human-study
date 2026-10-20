#!/usr/bin/env python3
"""Rebuild content from cached source PDFs and the completed real model inventory."""
import subprocess,sys
from pathlib import Path
base=Path(__file__).resolve().parent
for step in ['build_content.py','anchor_points.py','build_meridians.py','update_functions.py','update_palette.py','build_fascia.py','validate_content.py']:
 subprocess.run([sys.executable,str(base/step)],check=True)
