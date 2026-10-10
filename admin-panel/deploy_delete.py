"""Run from staged directory as root. Does not delete any live profiles."""
from pathlib import Path
import datetime
import json
import shutil
import sqlite3
import subprocess

files=['app.py','helper.py','hy2_control.py','hy2_routes.py','static/panel.js','static/hysteria.js']
backup=Path('/root/hl100-delete-backup-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
backup.mkdir(mode=0o700)
base=Path('/opt/hl100-panel');candidate=Path(__file__).parent
for f in files:
    dest=backup/f;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(base/f,dest)
shutil.copy2('/etc/hl100-panel/users.json',backup/'proxy-users.json')
with sqlite3.connect('/var/lib/hl100-hy2/hy2.sqlite3') as a,sqlite3.connect(backup/'hysteria.sqlite3') as b:a.backup(b)
rollback=backup/'rollback.sh'
rollback.write_text('#!/bin/sh\nset -eu\n'+''.join('cp '+str(backup/f)+' '+str(base/f)+'\n' for f in files)+'systemctl restart hl100-helper hl100-hy2\nsystemctl restart hysteria-server hl100-panel\n')
rollback.chmod(0o700)
try:
    for f in files:shutil.copy2(candidate/f,base/f);(base/f).chmod(0o644)
    subprocess.run(['systemctl','restart','hl100-helper','hl100-hy2'],check=True)
    subprocess.run(['systemctl','restart','hysteria-server','hl100-panel'],check=True)
except Exception:
    subprocess.run(['sh',str(rollback)],check=True)
    raise
print('Backup:',backup)
print('Existing proxy profiles:',len(json.loads(Path('/etc/hl100-panel/users.json').read_text())['users']))
