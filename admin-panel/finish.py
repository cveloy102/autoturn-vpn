"""Remove this installation's temporary access and return the new owner setup code."""
import pathlib
import json
import datetime

path=pathlib.Path('/root/.ssh/authorized_keys')
token=__import__("os").environ["AUTOTURN_TEMP_SSH_KEY_TOKEN"].encode()
lines=path.read_bytes().splitlines(keepends=True)
matching=[line for line in lines if token in line.split()]
assert len(matching)==1,'Expected exactly one temporary deployment key'
path.write_bytes(b''.join(line for line in lines if line not in matching))
path.chmod(0o600)
assert token not in path.read_bytes()
config=json.loads(pathlib.Path('/var/lib/hl100-panel/auth.json').read_text())
print('Temporary SSH key removed.')
print('Owner setup code: '+pathlib.Path('/root/hl100-panel-access.txt').read_text().strip())
print('Expires Omsk: '+datetime.datetime.fromtimestamp(config['setup_expires'],datetime.timezone(datetime.timedelta(hours=6))).isoformat())
