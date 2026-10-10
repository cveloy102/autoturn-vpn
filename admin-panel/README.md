# HL100 control panel

Production: https://tg.hl100.ru/control/

The HTTP service runs as `hladmin`, bound to loopback 8082. Caddy routes `/control` and `/control/*` to it. The relay remains on loopback 8080 and its admin on 8081. A root helper accepts only local Unix socket connections from root and hladmin and controls a fixed list of operations. There is no public helper endpoint.

The existing profile `default` is imported without changing its secret. Up to 16 keys can be active together (MTProxy's upstream limit). Pausing and resuming keeps the same secret. Rotation invalidates the previous secret. Every key-list change restarts MTProxy and the relay; other clients briefly reconnect. Global pause substitutes an unpredictable dummy profile, while the website and panel remain available. No automatic suspensions or rotations, and no Telegram bot.

First login uses a server-generated one-time setup code valid for 24 hours. The owner chooses a password and enrolls TOTP. Recovery codes are displayed once. Passwords use scrypt, TOTP login codes cannot be reused, and recovery codes are consumed once. Secure HttpOnly SameSite cookies, Origin/CSRF checks, restrictive CSP and login rate limits protect the panel. No proxy secrets appear in state responses; sharing a key explicitly fetches its link and locally generated QR.

Accounting is optional relay instrumentation controlled by `TPROXY_ACCOUNTING_FILE`. It records profile, authenticated carrier source IP, first/last activity, last session creation/address change, session count and bytes carried through the relay. It does not record message contents. Traffic counters are relay application bytes, excluding HTTP/TLS overhead. Traffic is attributed to the latest authenticated source IP of each session. Online means a retained session active within 90 seconds, not a Telegram presence indicator. The panel refreshes every five seconds. Counters start when installed; there is no historical backfill. All known IP rows and total counters persist, daily counters retain 90 days, hourly 48 hours, alert events 180 days. Existing counters survive service restarts.

Suspicion rules: simultaneous IPv4 /24 or IPv6 /64 networks for a configurable duration, and traffic exceeding a configurable threshold in the current UTC hour. They are heuristics, not proof of a leak. Alerts only appear in the panel until a notification channel is added. Acknowledging an alert does not affect a key.

Files on VPS:

- `/opt/hl100-panel`: app source, relay source and candidate binary.
- `/etc/hl100-panel/users.json`: private controller state (root only).
- `/var/lib/hl100-panel`: owner authentication config and SQLite settings/events (hladmin only).
- `/var/lib/tproxy-accounting/usage.json`: tproxy-owned accounting, readable by hladmin.
- `/root/hl100-panel-backup-path`: path of installation backup of original binary, profiles and Caddyfile.
- `/root/hl100-panel-access.txt`: setup code. Delete after first setup.

Checks: `python3 test_panel.py` in an isolated temp directory; original Go tests plus race tests and accounting persistence/address/shutdown test; on-server `verify_live.py` confirms pause/resume, preserved original key, fresh accounting and panel availability.

Deployment key is temporary and must be removed from `/root/.ssh/authorized_keys` and the local workspace after deployment. Do not package or commit it.
