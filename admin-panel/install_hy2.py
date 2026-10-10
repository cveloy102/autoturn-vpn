"""Deploy only Hysteria integration; existing owner and Telegram keys stay intact."""
import datetime as dt
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
from hy2_control import Controller

def run(*args): subprocess.run(args,check=True,capture_output=True)
def write(path,text,mode=0o644):
    p=Path(path);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');p.chmod(mode)

stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup=Path('/root/hy2-panel-backup-'+stamp);backup.mkdir(mode=0o700)
source=Path(__file__).parent;target=Path('/opt/hl100-panel')
files=['app.py','hy2_routes.py','hy2_control.py','templates/index.html','templates/hysteria.html','static/panel.css','static/hysteria.css','static/hysteria.js']
manifest=[]
for file in files:
    dest=target/file
    if dest.exists():
        saved=backup/'panel'/file;saved.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(dest,saved)
    else: manifest.append(str(dest))
shutil.copy2('/etc/hysteria/config.yaml',backup/'hysteria.yaml')
for db,name in [('/var/lib/hl100-panel/panel.sqlite3','owner.sqlite3'),('/var/lib/hl100-hy2/hy2.sqlite3','hy2.sqlite3')]:
    if Path(db).exists():
        with sqlite3.connect(db) as a,sqlite3.connect(backup/name) as b:a.backup(b)
write(backup/'rollback.sh','''#!/bin/sh
set -eu
systemctl stop hysteria-server
cp "'''+str(backup)+'''/hysteria.yaml" /etc/hysteria/config.yaml
chown root:hysteria /etc/hysteria/config.yaml
chmod 640 /etc/hysteria/config.yaml
rm -f /etc/systemd/system/hysteria-server.service.d/panel.conf
cp -a "'''+str(backup)+'''/panel/." /opt/hl100-panel/
systemctl daemon-reload
systemctl restart hysteria-server hl100-panel
systemctl disable --now hl100-hy2
''',0o700)

for file in files:
    dest=target/file;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source/file,dest);dest.chmod(0o644)
write('/root/hy2-panel-backup-path',str(backup)+'\n',0o600)
write(backup/'new-files.json',json.dumps(manifest),0o600)

cover=Path('/var/lib/hysteria/cover');cover.mkdir(exist_ok=True)
write(cover/'index.html','''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>HL100</title><style>body{background:#101113;color:#efeee9;font:16px/1.6 monospace;max-width:680px;padding:12vh 24px;margin:auto}h1{font-size:48px;font-weight:400}small{color:#a5a5ad}span{color:#f45050}</style><small>HL100 / PERSONAL SPACE</small><h1>Hello, <span>friend.</span></h1><p>A quiet corner of the internet.</p><small>More soon.</small></html>''')
run('chown','-R','hysteria:hysteria',str(cover))
Path('/var/lib/hl100-hy2').mkdir(mode=0o700,exist_ok=True)
write('/etc/systemd/system/hl100-hy2.service','''[Unit]
Description=HL100 Hysteria authentication and accounting controller
After=network.target
[Service]
Group=hladmin
ExecStart=/usr/bin/python3 /opt/hl100-panel/hy2_control.py
Restart=on-failure
RuntimeDirectory=hl100-hy2
RuntimeDirectoryMode=0750
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
ReadWritePaths=/var/lib/hl100-hy2 /etc/hysteria /run/hl100-hy2
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
CapabilityBoundingSet=CAP_CHOWN CAP_DAC_READ_SEARCH
IPAddressDeny=any
IPAddressAllow=localhost
MemoryMax=128M
TasksMax=64
[Install]
WantedBy=multi-user.target
''')
write('/etc/systemd/system/hysteria-server.service.d/panel.conf','''[Unit]
After=hl100-hy2.service
Requires=hl100-hy2.service
''')
run('systemctl','daemon-reload')
run('systemctl','enable','--now','hl100-hy2.service')
controller=Controller()
with controller.db() as c:settings=controller.settings(c)
controller.apply_settings(settings)
run('systemctl','restart','hl100-panel.service')
print('Backup:',backup)
print('Panel: https://tg.hl100.ru/control/hysteria')
