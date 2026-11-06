# qWDTT transport for AutoTurn

GPL-3.0; see LICENSE. The complete modified sources are in src/.

- qWDTT transport: https://github.com/SpaceNeuroX/proxy-turn-vk-android
  revision a296c57eaba69bb9479a24f9157856490890e47d, go_client/.
- Authenticated SOCKS5 TCP/UDP implementation and its tests:
  https://github.com/Ivan4537/WDTT-Plus
  revision f706ccd373685bbde20cae33b2b7f400e8335ecc,
  go_client/socks5_server.go and socks5_server_test.go (GPL-3.0).

Local changes: adapt SOCKS server entry point; accept hashes/tunnel/SOCKS passwords
through child environment rather than command arguments; never log stdin secrets;
cancel native transport on stdin EOF (force exit after two seconds if upstream
authentication waits fail to cancel) so Android parent termination stops it;
cancel SOCKS clients before waiting for them on listener failure.
All native stdout/stderr is consumed privately by AutoTurn and never sent to logcat.
In AutoTurn's SOCKS mode, the generated WG config is neither printed nor saved.

Rebuild: PowerShell tools/build-qwdtt.ps1 -Go /absolute/path/to/go.exe.
Built with Go 1.27.3, GOOS=android GOARCH=arm64 CGO_ENABLED=0,
-trimpath -ldflags="-checklinkname=0 -s -w". The output is an Android PIE executable
packaged as libqwdtt.so and extracted by Android's native-library installer.
No Android NDK is required for this arm64 pure-Go build.

qWDTT support is currently arm64-v8a only. Existing VPN types retain their ABIs.
The external client runs in a separate OS process, avoiding two Go runtimes in the
Xray process. Its loopback proxy uses random per-session credentials. AutoTurn's
UID bypasses its TUN to avoid transporting TURN inside itself. Health checks verify
TLS through Xray's authenticated probe inbound and its qWDTT SOCKS outbound, and
require a present Android VPN network; this
does not replace device tests of the complete Android TUN path and app policies.

No account-login implementation is imported. The normal path is anonymous VKCalls,
with a user-operated VK CAPTCHA WebView for legacy fallback when required.
