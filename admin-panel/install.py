"""Run as root after candidate relay tests/build. Secrets stay on the VPS."""
import datetime
import grp
import hashlib
import json
import os
import pathlib
import pwd
import secrets
import shutil
import subprocess
import time

P=pathlib.Path
def run(*args):subprocess.run(args,check=True)
def write(path,text,mode=0o644):
    path=P(path);path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(text);path.chmod(mode)
def owned(path,user,group,mode):
    path=P(path);os.chown(path,pwd.getpwnam(user).pw_uid,grp.getgrnam(group).gr_gid);path.chmod(mode)

stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup_file=P('/root/hl100-panel-backup-path')
backup=P(backup_file.read_text().strip()) if backup_file.exists() else P('/root/hl100-backup-'+stamp)
backup.mkdir(mode=0o700,exist_ok=True)
paths=['/usr/local/bin/tproxy-server','/etc/tproxy-server/profiles.json','/etc/caddy/Caddyfile']
for path in paths:
    if not (backup/P(path).name).exists():shutil.copy2(path,backup/P(path).name)
write('/root/hl100-panel-backup-path',str(backup)+'\n',0o600)
try:pwd.getpwnam('hladmin')
except KeyError:run('useradd','--system','--home-dir','/var/lib/hl100-panel','--shell','/usr/sbin/nologin','hladmin')
for path,user,group,mode in [('/var/lib/hl100-panel','hladmin','hladmin',0o700),('/var/lib/tproxy-accounting','tproxy','hladmin',0o2750),('/etc/hl100-panel','root','root',0o700)]:
    P(path).mkdir(exist_ok=True);owned(path,user,group,mode)

from helper import atomic
profiles=json.loads(P('/etc/tproxy-server/profiles.json').read_text())['profiles']
state={'host':'tg.hl100.ru','paused':False,'events':[],'users':[{'id':p['name'],'name':'HL100' if p['name']=='default' else p['name'],'group':'Личное','secret':p['secret'],'enabled':True,'created':int(time.time())} for p in profiles]}
if not P('/etc/hl100-panel/users.json').exists():atomic('/etc/hl100-panel/users.json',state)
if not P('/etc/mtproxy/managed-secrets.json').exists():atomic('/etc/mtproxy/managed-secrets.json',{'secrets':[p['secret'] for p in profiles]},0o640,'mtproxy')
code=secrets.token_urlsafe(24)
if not P('/var/lib/hl100-panel/auth.json').exists():
    atomic('/var/lib/hl100-panel/auth.json',{'host':'tg.hl100.ru','session_key':secrets.token_hex(32),'setup_hash':hashlib.sha256(code.encode()).hexdigest(),'setup_expires':int(time.time())+86400})
    write('/root/hl100-panel-access.txt',code+'\n',0o600)
owned('/var/lib/hl100-panel/auth.json','hladmin','hladmin',0o600)

write('/etc/systemd/system/hl100-helper.service','''[Unit]
Description=HL100 key controller (local socket only)
After=network.target
[Service]
ExecStart=/usr/bin/python3 /opt/hl100-panel/helper.py
Restart=on-failure
RuntimeDirectory=hl100-helper
RuntimeDirectoryMode=0750
Group=hladmin
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/etc/hl100-panel /etc/tproxy-server /etc/mtproxy /run/hl100-helper
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
[Install]
WantedBy=multi-user.target
''')
write('/etc/systemd/system/hl100-panel.service','''[Unit]
Description=HL100 private control panel
After=hl100-helper.service
Requires=hl100-helper.service
[Service]
User=hladmin
Group=hladmin
WorkingDirectory=/opt/hl100-panel
ExecStart=/usr/bin/gunicorn --bind 127.0.0.1:8082 --workers 1 --threads 4 --timeout 90 app:app
Restart=on-failure
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
ReadWritePaths=/var/lib/hl100-panel
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
CapabilityBoundingSet=
IPAddressDeny=any
IPAddressAllow=localhost
[Install]
WantedBy=multi-user.target
''')
write('/etc/systemd/system/tproxy-server.service.d/accounting.conf','''[Service]
Environment=TPROXY_ACCOUNTING_FILE=/var/lib/tproxy-accounting/usage.json
ReadWritePaths=/var/lib/tproxy-accounting
UMask=0027
''')
write('/etc/systemd/system/mtproxy.service.d/panel.conf','''[Service]
ExecStart=
ExecStart=/usr/bin/python3 /opt/hl100-panel/backend.py
''')
caddy='''{
 servers {
  protocols h1 h2
  timeouts {
   read_header 10s
   read_body 60s
  }
 }
}
tg.hl100.ru {
 encode gzip
 header -Via
 @panel path /control /control/*
 handle @panel {
  reverse_proxy 127.0.0.1:8082
 }
 handle {
  reverse_proxy 127.0.0.1:8080 {
   transport http {
    response_header_timeout 40s
   }
  }
 }
}
hl100.ru {
 redir https://tg.hl100.ru{uri} 302
}
'''
write('/opt/hl100-panel/Caddyfile',caddy)
run('caddy','validate','--config','/opt/hl100-panel/Caddyfile','--adapter','caddyfile')
run('systemd-analyze','verify','/etc/systemd/system/hl100-helper.service','/etc/systemd/system/hl100-panel.service','/etc/systemd/system/tproxy-server.service','/etc/systemd/system/mtproxy.service')
shutil.copy2('/opt/hl100-panel/tproxy-server.candidate','/usr/local/bin/tproxy-server.next')
os.replace('/usr/local/bin/tproxy-server.next','/usr/local/bin/tproxy-server')
P('/usr/local/bin/tproxy-server').chmod(0o755)
try:
    run('systemctl','daemon-reload')
    run('systemctl','enable','--now','hl100-helper','hl100-panel')
    run('systemctl','restart','mtproxy','tproxy-server')
    time.sleep(2)
    run('curl','--fail','--silent','http://127.0.0.1:8081/readyz')
    run('curl','--fail','--silent','-H','Host: tg.hl100.ru','http://127.0.0.1:8082/control/','-o','/dev/null')
    shutil.copy2('/opt/hl100-panel/Caddyfile','/etc/caddy/Caddyfile')
    run('systemctl','reload','caddy')
except Exception:
    run('systemctl','stop','tproxy-server')
    shutil.copy2(backup/'tproxy-server','/usr/local/bin/tproxy-server')
    shutil.copy2(backup/'profiles.json','/etc/tproxy-server/profiles.json')
    shutil.copy2(backup/'Caddyfile','/etc/caddy/Caddyfile')
    P('/etc/systemd/system/mtproxy.service.d/panel.conf').unlink(missing_ok=True)
    P('/etc/systemd/system/tproxy-server.service.d/accounting.conf').unlink(missing_ok=True)
    run('systemctl','daemon-reload');run('systemctl','restart','mtproxy','tproxy-server');run('systemctl','reload','caddy')
    raise
print('Installed; original keys preserved; setup code is in /root/hl100-panel-access.txt')
