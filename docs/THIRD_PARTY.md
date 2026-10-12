# Сторонние компоненты

[← Обзор](../README.md) · [Сборка](DEVELOPMENT.md)

| Компонент | Происхождение | Лицензия в репозитории |
| --- | --- | --- |
| XTLS/libXray | Официальный Android-артефакт v26.9.9; [ORIGIN](../app/libs/ORIGIN.md) | [MIT](../app/libs/libXray.LICENSE) |
| AmneziaWG Android tunnel | Java-исходники v3.1.20260814 и нативные библиотеки из официального APK; [ORIGIN](../third_party/amneziawg-tunnel/ORIGIN.md) | [Apache-2.0](../third_party/amneziawg-tunnel/LICENSE) |

AmneziaWG GoBackend адаптирован для работы службы в foreground на Android. Файл сборки модуля относится к AutoTurn. При обновлении библиотек сохраняйте происхождение, лицензии и описание местных изменений.

AndroidX и аннотации разрешаются Gradle из Maven-репозиториев; зависимости перечислены в [файле сборки туннеля](../third_party/amneziawg-tunnel/build.gradle). Лицензия сторонней библиотеки относится к её коду; этот список не назначает новую лицензию собственному коду AutoTurn.
