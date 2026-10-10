import pathlib
import subprocess

base = pathlib.Path(__file__).parent / 'relay'
def original(name):
    return subprocess.check_output(['git', '-c', 'safe.directory=' + base.as_posix(), '-C', str(base), 'show', 'HEAD:' + name]).decode()
def replace(source, old, new, count=1):
    assert source.count(old) == count, (old, source.count(old))
    return source.replace(old, new)
def write(name, source):
    (base / name).write_text(source, encoding='utf-8')

name='internal/session/session.go'
s=original(name)
s=replace(s,'"sync"','"sync"\n "sync/atomic"')
s=replace(s,'clientIP string','clientIP string\n accountIP atomic.Value')
s=replace(s,'return result\n}', 'result.accountIP.Store(options.clientIP)\n return result\n}')
write(name,s)
name='internal/session/manager.go'
s=original(name)
s=replace(s,'"errors"','"errors"\n "os"')
s=replace(s,'config      config.Config','account *accounting\n config      config.Config')
s=replace(s,'config:              value,','account: newAccounting(os.Getenv("TPROXY_ACCOUNTING_FILE")),\n config:              value,')
s=replace(s,'go result.cleanupLoop()','go result.cleanupLoop()\n go result.accountingLoop()')
s=replace(s,'created := newSession','var created *Session\n created = newSession')
s=replace(s,'m.bytesUp.Add(uint64(count))','m.bytesUp.Add(uint64(count)); m.account.bytes(entry.profile.Name, created.accountIP.Load().(string), count, true)')
s=replace(s,'m.bytesDown.Add(uint64(count))','m.bytesDown.Add(uint64(count)); m.account.bytes(entry.profile.Name, created.accountIP.Load().(string), count, false)')
s=replace(s,'m.sessionsCreated.Add(1)','m.sessionsCreated.Add(1)\n m.account.opened(entry.profile.Name, clientIP)')
s=replace(s,'<-m.done','<-m.done\n <-m.account.done',2)
s=replace(s,'<-m.account.done\n}', '<-m.account.done\n m.flushAccounting()\n}')
write(name,s)
name='internal/server/server.go'
s=original(name)
s=replace(s,'switch suffix {','s.manager.NoteAddress(token, clientIP)\n switch suffix {')
s=replace(s,'acquired := false','if ip,err:=s.clientIP(r);err==nil {s.manager.NoteAddress(token,ip)}\n acquired := false')
write(name,s)
name='internal/session/accounting.go'
s=(base/name).read_text()
s=s.replace('s.profile.Name,s.clientIP,s.LastActivity()', 's.profile.Name,s.accountIP.Load().(string),s.LastActivity()')
write(name,s)
