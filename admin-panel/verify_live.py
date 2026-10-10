"""On-server checks. Never print secrets, addresses, tokens or payloads."""
import json
import pathlib
import socket
import time
import urllib.request

def call(payload):
    with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as sock:
        sock.settimeout(80);sock.connect('/run/hl100-helper/control.sock')
        sock.sendall(json.dumps(payload).encode()+b'\n');raw=b''
        while not raw.endswith(b'\n'):raw+=sock.recv(65536)
    value=json.loads(raw)
    assert value['ok'],value.get('error')
    return value['data']

def ready():
    assert urllib.request.urlopen('http://127.0.0.1:8081/readyz',timeout=5).status==200

before=pathlib.Path('/etc/hl100-panel/users.json').read_bytes()
state=json.loads(before)
assert not state['paused']
original=json.loads((pathlib.Path(pathlib.Path('/root/hl100-panel-backup-path').read_text().strip())/'profiles.json').read_text())
assert {p['name']:p['secret'] for p in original['profiles']}=={u['id']:u['secret'] for u in state['users']}
assert all('secret' not in u for u in call({'op':'status'})['users'])
try:
    call({'op':'global','paused':True});ready()
    profiles=json.loads(pathlib.Path('/etc/tproxy-server/profiles.json').read_text())['profiles']
    assert all(p['name']=='suspended' for p in profiles)
    assert urllib.request.urlopen('https://tg.hl100.ru/control/',timeout=10).status==200
finally:
    call({'op':'global','paused':False})
ready()
now=json.loads(pathlib.Path('/etc/hl100-panel/users.json').read_text())
assert now['users']==state['users'] and not now['paused']
print('PASS: helper socket; global pause/resume; panel remains available; original keys preserved')
time.sleep(6)
usage=json.loads(pathlib.Path('/var/lib/tproxy-accounting/usage.json').read_text())
assert time.time()-usage['updated']<15
print('PASS: fresh accounting file; known IP rows:',len(usage['rows']))
