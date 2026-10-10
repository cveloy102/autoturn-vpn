# Сборка AutoTurn

Требуются JDK 17 и Android SDK Platform 37. Исходники используют Gradle Wrapper.

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

APK создаётся в `app/build/outputs/apk/debug/app-debug.apk`. Библиотеки Xray и AmneziaWG включены в репозиторий; `libXray.aar` занимает около 95 МиБ.

Для обновления установленного приложения требуется прежний ключ подписи. Ключи, `local.properties`, SDK и кеши не хранятся в Git. Если доступен первоначальный ключ `.android/debug.keystore`, проект использует его для debug-сборки. На другом компьютере следует отдельно настроить SDK и подпись.

Проверки приложения находятся в `app/src/androidTest/`. Проверки реальных туннелей запускайте на специально выбранном устройстве: они могут переключать VPN. Документация серверных компонентов: [панель](../admin-panel/README.md), [relay](../admin-panel/relay/README.md).

Готовую APK публикуйте как вложение GitHub Release, а не как файл исходников. Перед публикацией сверяйте versionName/versionCode, подпись, SHA256 и работоспособность на целевом устройстве.
