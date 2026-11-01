<picture><source media="(max-width: 767px)" srcset="docs/assets/dsh-mobile-banner-mobile.png"><img src="docs/assets/dsh-mobile-banner.png" alt="Экраны DSH Mobile для Android. Чаты и уведомления — на телефоне, агент работает на компьютере."></picture>

# DSH Mobile

Кейс на сайте: <https://logachev.net/#case-dsh-mobile> · Статья в журнале: [DeepSeek Harness с телефона: как управлять агентом на компьютере с Android](https://logachev.net/journal/deepseek-harness-s-telefona-android/) · [Технический разбор на Хабре](https://habr.com/ru/articles/1099110/)

[![Последний релиз](https://img.shields.io/github/v/release/pavel-logachev/dsh-mobile?style=flat-square&labelColor=0b0f14&label=%D1%80%D0%B5%D0%BB%D0%B8%D0%B7&color=c3f451)](https://github.com/pavel-logachev/dsh-mobile/releases/latest) [![Проверки в CI](https://img.shields.io/github/actions/workflow/status/pavel-logachev/dsh-mobile/ci.yml?branch=main&style=flat-square&labelColor=0b0f14&label=CI)](https://github.com/pavel-logachev/dsh-mobile/actions/workflows/ci.yml) [![Лицензия](https://img.shields.io/github/license/pavel-logachev/dsh-mobile?style=flat-square&labelColor=0b0f14&label=%D0%BB%D0%B8%D1%86%D0%B5%D0%BD%D0%B7%D0%B8%D1%8F&color=7f99ad)](<LICENSE>)

[English](README.md) | Русский

**Ваш агент [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) работает на компьютере. С Android-телефона вы ставите задачи с фото и файлами, читаете ответы в реальном времени, отвечаете на вопросы агента и получаете уведомление «Ответ готов».**

[Опубликованные релизы](https://github.com/pavel-logachev/dsh-mobile/releases) · [Кандидат 0.9.0](<docs/RELEASE_0.9.0.md>) · [Установка](#установка) · [Архитектура](#как-это-устроено) · [Безопасность](#безопасность)

> Неофициальный проект Павла Логачева. Разрабатывается независимо от DeepSeek.

Android 8.0+ · Привязка по QR-коду · Wi‑Fi или Tailscale

Модели, инструменты и подписки остаются на компьютере. Облачного сервера у DSH Mobile нет.

## Что нового в 0.9.0

Текущий исходный кандидат: **приложение 0.9.0 / versionCode 17 и host 0.9.0**, API `/v1` с аддитивными расширениями. Это не объявление публикации: финальные артефакты и физическая приёмка заполняются в [release checklist](<docs/RELEASE_0.9.0.md#verification>).

- Уведомления переделаны для задач, отправленных с телефона: завершение, ошибка и необходимость ответа через `GET /v1/sessions/{id}/prompt-outcomes` и capability `promptOutcomes`. Нет постоянного уведомления о связи и баннера покрытия в простое. Старый feed удалён: старое приложение с новым host показывает уведомления недоступными — обновляйте оба компонента вместе.
- Ошибки хода видны в чате. Recovery hold/witness и команды `recovery-status`, `recovery-hold`, `recovery-release --new-epoch` помогают оператору разбирать восстановление журнала, но не дают exactly-once.
- До 32 незавершённых dispatch на host; превышение — отказ до admission. Десятиминутный deadline после admission переводит исход в `uncertain`, без автоматической повторной отправки.
- Возврат сети будит reconnect; «ПК онлайн» появляется после первой авторизованной страницы списка. Чат сначала открывает live stream без предварительного полного GET; первый непустой текст публикуется сразу, следующие обновления сохраняют throttle. Численное ускорение реального телефона ещё требует замеров.
- Совместимость с DSH `0.2.1-rc.5` проверена полным изолированным canary: **25 проверок**. Это не физическая приёмка и не тест внешней модели. [Матрица evidence](<docs/DSH_CANARY.md#compatibility-evidence>).

## Что можно делать с телефона

- **Открывать чаты всех проектов.** Искать чаты и фильтровать по проекту. Список проектов следует боковой панели DSH и обновляется без повторной привязки.
- **Ставить задачи.** Выбрать проект и пресет и сразу написать задачу: приложение создаст чат и отправит текст один раз.
- **Отправлять фото и файлы.** Снимок с камеры, фотографии через системный выбор фото (Photo Picker), файлы через системный выбор файлов. Загрузка начинается сразу после выбора. В одном сообщении — до 20 вложений общим объемом до 200 МиБ, каждый файл — до 20 МиБ. Изображения PNG, JPEG, WebP и GIF агент получает как изображения, файлы передаются в хранилище вложений DSH в момент отправки. Неотправленные загрузки истекают на компьютере через 10 минут и в DSH не попадают.
- **Читать ответ в реальном времени.** Markdown, таблицы, блоки кода с копированием. Изображения из сообщений видны прямо в переписке; по нажатию открывается полноэкранный просмотр, где картинку можно увеличить, отправить через «Поделиться» или сохранить. Файлы из сообщений можно скачать — до 20 МиБ. Служебные события свернуты в строку «Действия агента» — полный текст доступен по нажатию. Длинный сжатый чат, старую историю которого не удается восстановить в пределах ограничений, открывается с последними сообщениями и пометкой, что история обрезана.
- **Дописывать и направлять, пока агент работает.** По умолчанию сообщение встанет в очередь DSH и дойдет до агента в следующем ходе. «Направить» доставляет его на следующем шаге агента, не останавливая текущий ход: удерживайте кнопку отправки или нажмите ↳ у сообщения в очереди. Сообщение можно и убрать из очереди.
- **Отвечать на вопросы агента.** Выбрать один или несколько вариантов, написать свой ответ или пропустить вопрос. Вариант, который рекомендует агент, отмечен, но не выбран заранее. Подтверждения действий агента остаются на компьютере.
- **Переименовывать чат и менять его модель.** Оба действия — в меню ⋯. Новая модель применяется со следующего запроса к модели, возможно уже на следующем шаге идущего хода; запрос, который уже выполняется, не переключается.
- **Останавливать работу.** Отдельная кнопка «Стоп» запрашивает подтверждение.
- **Получать уведомления о своих задачах.** Один локальный переключатель включает слежение за задачами, отправленными с телефона, если host публикует `promptOutcomes`. Завершение, ошибка и «Нужен ответ» открывают соответствующий чат; итог заменяет уведомление об ожидании ответа. Системные каналы управляют звуком. Desktop-задачи автоматически не отслеживаются. [Контракт и ограничения](<docs/NOTIFICATIONS_0.9.md>).
- **Возвращаться к переписке.** Поиск по сообщениям, черновик для каждого чата и копирование по долгому нажатию.

Функции доступны только при соответствующем capability host. Для новой схемы уведомлений используйте пару 0.9.0/0.9.0; старый notification feed не является запасным путём. Вложения, очередь/«Направить», ответы на вопросы, переименование, модель и скачивание файлов сохраняются.

Тема следует системной, светлую или темную можно выбрать вручную. Интерфейс на русском и английском: язык следует системному или выбирается в настройках приложения. Поддерживается крупный шрифт.

<picture><source media="(max-width: 767px)" srcset="docs/assets/dsh-mobile-screens-mobile.png"><img src="docs/assets/dsh-mobile-screens.png" alt="Три экрана: чаты разных проектов, ответ в Markdown во время работы агента и выбор проекта перед новым чатом."></picture>

Снимки сделаны в настоящем приложении с синтетическими демо-данными. Реальных чатов и подключенных моделей на них нет.

## Скриншоты

Исторические снимки версии 0.8; экран уведомлений не описывает схему 0.9.0.

![DSH Mobile 0.8](docs/images/0.8/hero.png)

DSH Mobile 0.8: агент работает на компьютере, а чат с ним — у вас в телефоне.

![Чаты всех проектов](docs/images/0.8/chats.png)

Чаты всех проектов DSH в одном списке.

![Сообщение во время работы агента](docs/images/0.8/steer.png)

Агент работает, а вы дописываете: в очередь или «Направить» на следующем шаге.

![Вложения в сообщении](docs/images/0.8/attachments.png)

Фото с камеры, снимки из галереи и файлы — в одном сообщении агенту.

![Вопрос агента](docs/images/0.8/question.png)

Вопрос агента: выберите вариант, напишите свой ответ или пропустите.

![Меню чата](docs/images/0.8/menu.png)

Меню ⋯: переименование чата и смена модели.

![Просмотр изображения](docs/images/0.8/viewer.png)

Изображение из чата на весь экран: увеличить, поделиться, сохранить.

![Уведомления](docs/images/0.8/notifications.png)

Уведомления: ответ готов, агент задал вопрос.

## Как это устроено

На компьютере устанавливается плагин-компаньон. Он открывает привязанному телефону узкий API через защищенное соединение, но не публикует наружу веб-интерфейс DSH.

```text
Android-приложение
  │ HTTPS по Wi‑Fi или Tailscale
  │ или через ваш собственный relay
  ▼
Плагин-компаньон внутри DSH
  │
  ▼
Ваши проекты и агенты на компьютере
```

Телефон — только клиент: агент продолжает работать и после закрытия приложения. Основная история переписки хранится в DSH на компьютере.

Android-клиент написан на Kotlin и Jetpack Compose, плагин работает на Node.js. Собственный relay — необязательный вариант подключения; общего публичного relay проект не предоставляет.

## Установка

Подробную [инструкцию установки](<docs/SETUP.md>) можно передать своему агенту DSH. В ней — проверки окружения, команды и отдельное согласование установки, прав доступа и активации.

1. **Проверьте требования.**

   Компьютер: Windows, DeepSeek Harness `0.2.0-rc.2`, `0.2.1-alpha.1` или `0.2.1-rc.5`, Node.js 24.x, OpenSSL 3. Для 0.9.0 целевая версия — rc.5 (25 canary checks); первые две оставлены по историческим full/registry canary, rc.4 не поддерживается. [Различия evidence](<docs/DSH_CANARY.md#compatibility-evidence>). Телефон: Android 8.0+. Node 24 нужен процессу DSH, не только PATH.

2. **Скачайте и проверьте файлы релиза.**

   Откройте [Releases](https://github.com/pavel-logachev/dsh-mobile/releases). Для выбранного опубликованного релиза нужны подписанный APK, host ZIP и оба SHA-256 sidecar из одного выпуска. Кандидат 0.9.0 ещё требует [финальной приёмки](<docs/RELEASE_0.9.0.md#verification>); не придумывайте URL его assets и не подменяйте текущую пару старой. `UNSIGNED-verification.apk` — проверочный артефакт, не файл для установки.

   Укажите абсолютные пути к скачанным файлам и сравните хеши с `.sha256`. При несовпадении остановитесь.

   ```powershell
   $zip = '<ABSOLUTE_DOWNLOADED_HOST_ZIP>'
   $apk = '<ABSOLUTE_DOWNLOADED_SIGNED_APK>'
   (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
   (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
   # Compare exactly with the release's .sha256 files. A mismatch means STOP.
   ```

3. **Подготовьте плагин на компьютере.**

   Извлеките из архива и прочитайте [установщик](<tools/install-host.ps1>). В командах ниже замените заполнители: путь к скрипту и адрес компьютера в домашней сети или Tailscale. Если дополнительный адрес не нужен, удалите второй элемент массива.

   ```powershell
   $installer = '<ABSOLUTE_REVIEWED_INSTALL_HOST_PS1>'
   $hosts = @('<ACTUAL_PRIMARY_LAN_OR_TAILSCALE_HOST>', '<OPTIONAL_ADDITIONAL_SAN>')
   # Remove the second element if not needed. Substitute approved real values locally.
   & $installer -ZipPath $zip -Hosts $hosts -WhatIf
   & $installer -ZipPath $zip -Hosts $hosts
   ```

   Этот шаг готовит файлы и конфигурацию, но еще не подключает плагин к DSH.

4. **Активируйте плагин.**

   После проверки предлагаемых изменений:

   ```powershell
   & $installer -ZipPath $zip -Hosts $hosts -Activate
   ```

   Установщик не перезапускает DSH. Если плагин не запустился, следуйте диагностике в [инструкции](<docs/SETUP.md>): перезапуск может потребоваться, но согласуется отдельно.

   Если для домашнего Wi‑Fi нужно правило брандмауэра Windows, добавляйте его только с согласия владельца и только для частной сети. Установщик не меняет брандмауэр автоматически.

5. **Установите APK на телефон.**

   Разрешите Android установку из выбранного источника. После установки отключите это разрешение.

6. **Привяжите телефон по QR-коду.**

   На компьютере укажите каталог установленной версии плагина и проверьте конфигурацию:

   ```powershell
   $plugin = '<EXACT_LOCALAPPDATA_DSHMOBILE_PLUGIN_VERSION_DIRECTORY>'
   $cli = Join-Path $plugin 'dist/cli.js'
   $config = Join-Path $env:LOCALAPPDATA 'DSHMobile/host/host.json'
   node $cli verify-config --config $config
   ```

   Затем выберите **одну** команду привязки. Только чтение всех текущих и будущих проектов:

   ```powershell
   node $cli pair --config $config --read all --ttl 300 --qr
   ```

   Или чтение **и запуск задач** во всех этих проектах — только если вы согласны дать телефону такие права:

   ```powershell
   node $cli pair --config $config --read all --execute all --ttl 300 --qr
   ```

   В приложении нажмите «Сканировать QR-код», сверьте адрес компьютера и отпечаток сертификата, затем подтвердите привязку. Камера нужна только для сканирования; код распознается без сети и без сервисов Google.

**Для работы вне дома** установите Tailscale на компьютер и телефон, подключите их к своей сети Tailscale и укажите имя компьютера при подготовке плагина. Открывать порт на роутере не нужно. Альтернатива — [собственный relay на VPS](<docs/RELAY_DEPLOYMENT.md>).

**Обновление 0.9.0:** обновляйте приложение и host вместе, предварительно проверив подпись и хеши. APK для обновления должен иметь прежний сертификат; не удаляйте приложение ради обхода ошибки подписи. Host обновляется проверенным установщиком с `-Upgrade -Activate`: он unmount-ит прежний plugin, ждёт закрытия listener и подключает целый immutable build. Если требуется restart DSH, согласуйте его отдельно — он завершает идущие ходы. Сохраните приватный backup состояния и используйте [инструкцию обновления/отката](<docs/SETUP.md#10-upgrade-rollback-revoke-and-uninstall>) и [release notes](<docs/RELEASE_0.9.0.md#upgrade-and-rollback>). Прежние физические результаты не доказывают поведение нового кандидата.

Текущая исходная пара — **0.9.0/0.9.0**. Историческая опубликованная пара — приложение **0.8.2** и host **0.7.2** ([исторический релиз](https://github.com/pavel-logachev/dsh-mobile/releases/tag/v0.8.2)); её результаты ниже не являются приёмкой 0.9.0.

## Безопасность

- **Права агента остаются прежними.** Плагин работает внутри DSH с его правами. Фильтр по проектам — не песочница: агент с телефона может делать то же, что и с компьютера. Право запуска задач давайте только доверенным устройствам.
- **Приглашение — секрет.** QR-код одноразовый и действует не дольше 15 минут. Не публикуйте его, не отправляйте в чат и не сохраняйте в общие журналы. При привязке проверяются сертификат компьютера, его срок действия, адрес и закрепленный отпечаток.
- **Ключи провайдеров не передаются на телефон.** Данные привязки защищены Android Keystore и исключены из резервных копий. Приватные файлы плагина на Windows доступны только владельцу учетной записи.
- **Потерянный телефон нужно отозвать на компьютере.** Локальный выход из приложения удаляет данные привязки с телефона, но не означает отзыв доступа на стороне DSH.
- **Уведомления не содержат готового ответа.** Ошибка может включать краткое описание; содержимое скрыто на экране блокировки. Для названия чата используется локализованный fallback без запроса переписки. [Подробности](<docs/NOTIFICATIONS_0.9.md#durable-watch-and-notification-state>).
- **Relay не видит содержимое чатов.** Он пересылает зашифрованный трафик, но видит IP-адреса, время и объем передачи.

Не публикуйте наружу веб-порт DSH и не отключайте проверку TLS. Подробнее — в [описании безопасности](<docs/SECURITY.md>).

## Сборка из исходников

Из корня репозитория, в PowerShell:

```powershell
cd host;  npm ci; npm ci --prefix ../relay; npm run check   # плагин-компаньон, Node 24; тестам нужен соседний relay
cd ..\android
powershell -NoProfile -ExecutionPolicy Bypass -Command "& ../tools/android-build.ps1 -Tasks ':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug'"
```

Рекомендуется JDK 21; Windows-помощник также поддерживает JDK 17. Нужны Android SDK platform 36 и build-tools 36.0.0.

Обычная release-сборка APK не подписана и не устанавливается как есть. Сборка, упаковка плагина и подпись своим ключом описаны в [инструкции по сборке](<docs/BUILD.md>) и [инструкции выпуска APK](<docs/ANDROID_RELEASE.md>).

## Исторические проверки выпуска 0.8.2

Исторический выпуск: приложение 0.8.2 (versionCode 15) и host 0.7.2; [описание релиза](https://github.com/pavel-logachev/dsh-mobile/releases/tag/v0.8.2). Таблица относится только к той сборке, не к текущей установленной паре и не к 0.9.0.

| Часть | Что проверено |
|---|---|
| Android 0.8.2 | На Android-коде 3dc0126: два полных JVM-запуска по 357/357; lintDebug — 0 ошибок (46 предупреждений, 1 hint). Release helper выполняет assembleRelease, testDebugUnitTest и lintRelease |
| Подпись APK | Тот же сертификат SHA-256: F9080C63B49A0BDE6B74AA2ADF7E9531D6C4F305978A24D3F5D271A39F1B7B95; схемы v2/v3, выравнивание 16 КБ; non-debuggable, HTTPS-only |
| Плагин 0.7.2 | 282/282 с Cordis module env; без него — 275 pass и 5 skipped (280 total). Независимый re-review host-кода — GO после исправления трёх блокеров |
| Установщик | 28/28 синтетических сценариев; не проверка установки на телефоне |
| Физический телефон | OnePlus, Android 16, APK 3dc0126: «Отправляем…» через 0,3–0,7 с; отправка нового и существующего чата, после более 110 с простоя; send→Home ×2 — по одному доставленному prompt; обновление поверх 0.8.1 сохранило привязку |
| Не проверено инструментально на телефоне | Задержка уведомлений и маршрутизация tap с host 0.7.2; auto-retry и баннер «Не отправлено» при реальном переключении сети (кнопка отправки была disabled во время reconnect); доставка в реальном Doze |
| Разрешения APK | INTERNET, CAMERA, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, ACCESS_NETWORK_STATE и собственное signature-разрешение receiver. Без телеметрии и сервисов Google |

## Ограничения

- **ПК и DSH должны быть включены.** Телефон не запускает агента автономно; одна привязка относится к одному компьютеру. В списке учитываются первые 100 зарегистрированных проектов.
- **Это намеренно не весь desktop DSH.** Управление плагинами и skills на телефон не выведено. Approvals действий остаются на ПК из-за отсутствия проверенного plugin API; они отличаются от вопросов агента, на которые можно отвечать с телефона.
- **Уведомления — best effort, без FCM/UnifiedPush.** Нет постоянной службы/уведомления в простое и баннера покрытия. Пока задача с телефона ещё ожидается, работает FGS: невидимость его уведомления на всех OEM гарантировать нельзя. Валидные watches/outbox восстанавливаются после смерти процесса и разблокированного reboot при сохранённых привязке и разрешении. После force-stop откройте приложение вручную. Doze, энергосбережение, отсутствие сети, истечение 24 часов или вытеснение из лимита 32 watches могут помешать доставке. [Точные границы](<docs/NOTIFICATIONS_0.9.md>).
- **Офлайн доступен только ограниченный stale/read-only кэш.** До 8 снимков, суммарно 3500 КиБ сериализованного UTF-8, срок 24 часа; один снимок до 2 МиБ. Индекс ограничен отдельно. Кэш не подтверждает права или доставку команд и не является полным архивом. Свежий снимок содержит до 100 сообщений/2 МиБ, а восстановление старой истории ограничено 16 страницами/16 МиБ; upstream JSONL пока читает весь лог до этих ограничений.
- **Обновление DSH требует проверки совместимости.** Setup может заполнить версию из `dsh --version`; runtime plugin проверяет декларацию в настройках, а не обнаруживает установленную версию. Допустимы только точные версии из [матрицы](<docs/DSH_CANARY.md#compatibility-evidence>).
- **Обрыв связи не доказывает, что задача не отправлена.** Автоповтор допустим только при транспортном доказательстве, что заголовки **запроса** ещё не отправлены: до трёх попыток с тем же requestId. После admission автоматического resend нет; потерянный ответ или receipt404 не доказывает неисполнение. При доказанном not-sent черновик остаётся и доступен ручной Retry; при `uncertain` сначала проверьте DSH.
- **Recovery — не exactly-once.** Witness обнаруживает только доказуемый локальный rollback. При утрате/совместном откате DB и witness нужен manual hold до открытия admission. Эпоха — запись решения оператора, не client-visible fencing; после release потерянный requestId может быть принят заново. [Процедура](<docs/SETUP.md#restoring-the-command-ledger-from-a-backup>).

### Что означает название DeepSeek Harness?

DSH Mobile — companion к хосту DeepSeek Harness, а не покупка конкретной модели или подписки DeepSeek. Модель, провайдер и тариф выбираются на ПК. Сравнений стоимости или числа задач с другими подписками этот проект не доказывает.

## Документация

- [Установка, обновление и удаление](<docs/SETUP.md>)
- [Архитектура](<docs/ARCHITECTURE.md>)
- [Протокол мобильного клиента](<docs/PROTOCOL.md>)
- [Безопасность](<docs/SECURITY.md>)
- [Интеграция с DSH](<docs/DSH_INTEGRATION.md>)
- [Развертывание своего relay](<docs/RELAY_DEPLOYMENT.md>) и [его протокол](<docs/RELAY_PROTOCOL.md>)
- [Сборка из исходников](<docs/BUILD.md>)
- [Подпись и выпуск Android APK](<docs/ANDROID_RELEASE.md>)
- [Участие в проекте](<CONTRIBUTING.md>)

## Нужно похожее решение?

Проект сделал [Павел Логачев](https://github.com/pavel-logachev). Делаю сайты, веб-сервисы, Telegram-ботов, приложения и автоматизацию под задачу: сам разбираю, делаю и запускаю. 20 лет в ИТ: инженер, затем руководитель проектов. Расскажите о задаче [на почту](mailto:pavel@logachev.net) или [в Telegram](https://t.me/pavel_logachev) — разбор на 20–30 минут бесплатный, готовое ТЗ не нужно. Цены и другие проекты — на [logachev.net](https://logachev.net/) и [в профиле](https://github.com/pavel-logachev).

## English

DSH Mobile is an unofficial Android client for DeepSeek Harness, developed independently of DeepSeek. Your agent runs on your computer; your phone lets you browse chats across projects, send tasks with photos and files, follow live Markdown replies with inline images, queue a message while the agent is working or steer it so the agent gets it at its next step, answer the agent's questions, rename a chat or change its model, and stop a run. Optional notifications tell you when an answer is ready or the agent has a question. Models, tools, subscriptions and canonical history stay on the computer.

Pair with a one-use QR code and connect over Wi‑Fi, Tailscale or your own optional relay. DSH Mobile provides no hosted cloud server or shared relay. A companion plugin exposes a narrow HTTPS API, not the DSH web UI. The phone verifies the computer's pinned certificate, and each device has revocable permissions. Project filtering is not a sandbox: agents retain their desktop tool permissions.

Requires Android 8.0+, Windows, DSH `0.2.0-rc.2`, `0.2.1-alpha.1` or `0.2.1-rc.5`, Node.js 24.x and OpenSSL 3. The current source pair is app/host **0.9.0/0.9.0**, pending [final verification](<docs/RELEASE_0.9.0.md#verification>). Upgrade both together: phone-task notifications use `promptOutcomes`, not the retired feed. No idle permanent notification or coverage banner; active-task FGS disclosure is still OEM-dependent. Pending watches may restore after unlocked reboot; force-stop requires a user launch. Approvals and plugin/skill management remain desktop-only. Follow the [setup guide](<docs/SETUP.md>) and use only reviewed published release assets.

Built by [Pavel Logachev](https://github.com/pavel-logachev): 20 years in IT, first as an engineer, then as a project manager. I build websites, web services, Telegram bots, apps and automation for your task. Have a similar one? [pavel@logachev.net](mailto:pavel@logachev.net) · [logachev.net](https://logachev.net/)

## Лицензия

[MIT](<LICENSE>) © 2026 Pavel Logachev. Сторонние зависимости распространяются под своими лицензиями.
