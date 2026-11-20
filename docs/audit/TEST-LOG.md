# Журнал проверок аудита

Дата: 19 ноября 2026. Код: `claude/autoturn-1.7.0-fixes` @ `84e6a67`.

## 1. Поиск последней версии

- Релизы на GitHub: последний стабильный `v1.7.0` (коммит `0508c8c`, совпадает с `main`).
- Ветки проверены `git log` по всем `refs/heads/*`. Строка «powered by» найдена только в
  `claude/autoturn-1.7.0-fixes`: `TerminalUi.java:45` (коммит `1aa8790`).
- Ветки `claude/project-diagnostics-66xlgb` и `claude/eloquent-franklin-9871rj` — более ранние
  этапы той же работы; `codex/test-1.6.0-2026-11-08` — старая ветка защищённого режима.

## 2. Юнит-тесты

Android SDK в среде аудита недоступен (dl.google.com закрыт), поэтому Gradle не запускался.
Тесты собраны вручную `javac` против `org.robolectric:android-all:16` (Maven Central), `libXray.aar`
(classes.jar), исходников `third_party/amneziawg-tunnel` и заглушек `BuildConfig`/androidx.annotation.

```
java -cp out:json-20240303.jar:junit-4.13.2.jar:hamcrest-core-1.3.jar:android-all-16.jar:... \
  org.junit.runner.JUnitCore app.autoturn.vpn.<все 8 классов из app/src/test>
→ OK (27 tests)
```

Основной код (`app/src/main`) компилируется без ошибок, кроме ожидаемых `R` и AIDL `IProtectedAwg`
(их генерирует Gradle).

## 3. CI

GitHub Actions «Android build and lint» для `1f7a0fa`, `1aa8790`, `84e6a67` — success
(`testDebugUnitTest assembleDebug assembleStaging lintDebug`).

## 4. Совместимость с Android 10 (minSdk 29)

Основной код скомпилирован против `android-all:10-robolectric-5803371`. Ошибки, не закрытые
проверкой `SDK_INT`:

- `AutomationService.java:111` — `ConnectivityManager.NetworkCallback(int)` отсутствует в API 29
  (`javap` показывает только `NetworkCallback()`).
- `android.net.wifi.WifiInfo` в API 29 не реализует `android.net.TransportInfo` → `getTransportInfo()`
  не вернёт SSID (`AutomationService.java:122-123`).

Остальные ошибки компиляции относятся к константам (инлайнятся) или к коду под `SDK_INT >= 30/33`.

## 5. Разбор релизного APK

`AutoTurn-1.7.0.apk` (120 600 213 байт) из релиза v1.7.0, разобран androguard:

- `debuggable=true`, `allowBackup=false`;
- сертификат `CN=Android Debug, O=Android, C=US`, SHA-256 `6B82 7876 2210 8787 69EC 313E C22A 0CC5 90DC DD06 F47C 7DDB 2264 F63B 76C1 5DE2`;
- подписи: v1 нет, v2 есть, v3 нет;
- 8 dex-файлов; native-библиотеки для arm64-v8a, armeabi-v7a, x86, x86_64 (`libqwdtt.so` только arm64).

## 6. Поиск секретов в истории git

`git log --all` по именам файлов (keystore, jks, pem, key, .env, local.properties) — ничего.
`git grep` по паттернам паролей/токенов по всем веткам — только тестовые значения и
`third_party/qwdtt-client/src/creds.go` (публичные ID приложений VK). Найден реальный IP VPS (см. README, п. 4).

## 7. Ручной разбор кода

Прочитаны все 35 файлов `app/src/main/java/app/autoturn/vpn` (~6 900 строк), манифест,
`app/build.gradle`, workflow CI, debug source set. Результаты — в [README.md](README.md).

## Что не проверялось

- Запуск на реальном устройстве и `androidTest` (нужен телефон/эмулятор).
- Lint-отчёт CI (артефакт не скачался из-за ограничений сети).
- Нативные части: libXray, AmneziaWG, qWDTT (сторонний код).
