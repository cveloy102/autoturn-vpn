# Hysteria / HL100

Production: https://tg.hl100.ru/control/hysteria

Uses the existing Telegram panel owner login, TOTP, session, Origin checks and CSRF protection. The Hysteria API lives under `/control/api/hy2/`; state never includes connection passwords or the internal API/auth secrets. Links and QR codes are fetched explicitly. Both panels link to each other.

The existing Hysteria password is imported as `default` unchanged. Each new user gets a separate random secret. HTTP authentication returns the stable user ID used by the Hysteria accounting API. Creating/resuming keys does not restart Hysteria. Pausing/rotating keys rejects further authentication and kicks the affected existing connections. Global pause affects Hysteria users only. If an immediate kick fails, the collector retries it every five seconds; the UI reports this.

Optional lifetime quota and expiry default to warnings only. Enforcement is opt-in for each user and uses the same checks for authentication and kicking existing clients. Dates use UTC+6 and expire at the end of the selected day. Quotas apply to the entire life of the user ID; rotating its secret does not reset counters. There are no automatic secret rotations or Telegram bots.

Daily and total upload/download counters are persisted in SQLite. The collector reads `/traffic` without clearing it, stores previous samples atomically with totals, and detects Hysteria process restarts using systemd InvocationID. Polling interval: 5 seconds; an abrupt Hysteria shutdown may lose the last uncollected interval. Statistics start at installation, with no historical backfill. Daily history: 90 days; events: 180 days; graph: 14 days. Online clients are client application instances, not a reliable count of physical devices. Offline/missing telemetry is shown as unknown.

Server settings allow a free UDP port, local cover page or public HTTPS reverse-proxy target, and Salamander obfuscation. Settings preserve TLS/ACME and existing unrelated configuration. TLS validation stays enabled. A settings change restarts only Hysteria; failed startup restores the previous YAML. Port/obfuscation changes require reissuing client profiles. TLS SNI remains the certificate domain. The cover is served over the Hysteria HTTP/3 listener; TCP web listeners remain owned by Caddy.

The web process remains unprivileged (`hladmin`). `hl100-hy2.service` runs a small controller with fixed operations on a restricted Unix socket; only root and hladmin are accepted through SO_PEERCRED. Its HTTP auth callback listens only on `127.0.0.1:8083` and uses an unguessable path. The Hysteria stats API listens only on `127.0.0.1:9998` with a separate secret. Neither administrative interface is routed publicly. The controller is limited to localhost networking, fixed writable paths, 128 MiB memory and 64 tasks.

VPS files:

- `/var/lib/hl100-hy2/hy2.sqlite3`: private keys, settings, counters and events (root-only directory/file).
- `/etc/hysteria/config.yaml`: generated Hysteria configuration, root:hysteria 0640.
- `/var/lib/hysteria/cover/index.html`: static cover.
- `/opt/hl100-panel/hy2_control.py`, `hy2_routes.py`, templates and assets.
- `/root/hy2-panel-backup-path`: backup location, including original files and owner DB snapshot.
- `/root/hy2-panel-backup-20261009T170059Z/rollback.sh`: rollback for the initial deployment.

Validation: `python3 -m unittest test_hy2 test_panel` passed (11 tests) on Linux. Covers preserved secrets, isolated lifecycle, pause/resume, warnings vs enforcement, persistence/reset accounting, settings rollback and unauthenticated/CSRF API rejection. Live validation used the unchanged original Windows profile, received the VPS IP and HTTPS 200 through Hysteria, verified traffic/online in the panel and QR rendering. All existing services remained active, with zero failed units.

To install a future version, first review the fixed file list in `install_hy2.py`; the installer saves backups before replacement. Do not package credentials or temporary deployment keys. Remove temporary SSH access after deployment.

Profile deletion: requires exact name confirmation in both UI and controller. Removes the Hysteria key and its accounting rows, retains the event log, and permanently records the revoked ID for retrying failed/racing disconnects. Deleting the last user leaves an empty panel across restarts. Telegram deletion removes the profile from both active backends; its normal configuration reload briefly reconnects other clients. Update validated by 12 Linux tests and both live confirmation dialogs.
