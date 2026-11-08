# Local rollback baseline: Android 1.5.0

Snapshot of the existing Android checkout before protected-mode development, 2026-11-08.
Local Git tag: `baseline/android-v1.5.0`. No release is created by this checkpoint.

Existing signed APK: `releases/AutoTurn-1.5.0.apk`, versionCode 29.
SHA256: `d518206d657c3f0bb18521741efa6c909ada937a8e349642d7c5c09b612ee249`.
The APK and original `.android/debug.keystore` remain local and are not committed.
Keep the signing key: a source checkout alone cannot recreate its identity.

To inspect or build this source without changing the development checkout, use a separate
checkout of the baseline tag. Do not uninstall the installed application to roll back:
uninstallation destroys its Android Keystore key and can make encrypted profiles unreadable.
An APK with a lower versionCode cannot normally be installed over a newer build; a rollback
update must be signed with the same key and use an acceptable versionCode. Plan the rollback
installation before device testing and back up settings through an authorized procedure.

This is a snapshot of local sources labelled 1.5.0, not a claim of a newly reproduced
bit-for-bit build of the published APK. Device backups, keys, packet captures and unrelated
server/panel projects are deliberately outside this baseline commit.
