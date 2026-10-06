<p align="center">
  <img src="docs/assets/dsh-mobile-showcase.png" alt="DSH Mobile — список чатов и чат с работающим агентом на Android" width="100%">
</p>

# DSH Mobile

**Нативное Android-приложение для [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness): открывайте чаты всех проектов, отправляйте задачи и читайте ответы агента с телефона.** Телефон привязывается по QR-коду и подключается к компьютеру через Wi‑Fi или Tailscale. Облачный сервер приложения не нужен: модели, инструменты и подписки остаются на компьютере.

<p align="center">
  <a href="https://github.com/pavel-logachev/dsh-mobile/releases/tag/v0.5.0">Скачать APK 0.5.0</a> &nbsp;·&nbsp;
  <a href="docs/SETUP.md">Установка</a> &nbsp;·&nbsp;
  <a href="docs/ARCHITECTURE.md">Архитектура</a> &nbsp;·&nbsp;
  <a href="docs/SECURITY.md">Безопасность</a> &nbsp;·&nbsp;
  <a href="https://github.com/pavel-logachev/dsh-mobile/actions/workflows/ci.yml">CI</a> &nbsp;·&nbsp;
  Android 8+ &nbsp;·&nbsp;
  <a href="LICENSE">MIT</a> &nbsp;·&nbsp;
  <a href="#english">English</a>
</p>

> Версия 0.5 работает у автора на телефоне с его DSH. Проект неофициальный, разрабатывается независимо от DeepSeek.

## Что умеет

- Список проектов совпадает с боковой панелью DSH, включая порядок. Новые проекты появляются без повторной привязки телефона.
- Чаты можно искать и фильтровать по проекту. Группы «Сегодня / Вчера / На неделе» помогают найти недавний разговор. Для нового чата выберите проект и пресет, затем отправьте текстовую задачу. Выполнение можно остановить из чата.
- Ответ агента обновляется в реальном времени. Приложение отображает Markdown: заголовки, списки, таблицы и блоки кода с копированием.
- В чате видны только ваши сообщения и ответы агента. Служебные события DSH — результаты сабагентов, фоновые задачи, обновления контекста — свёрнуты в строку «Действия агента»; полный текст открывается по нажатию.
- Пока агент работает, можно написать ещё одно сообщение: оно встанет в очередь DSH и дойдёт до агента на следующем шаге. Кнопка «Стоп» отдельная и просит подтверждения.
- По желанию можно включить уведомления «Ответ готов» и «Агент ждёт вашего решения». В них только название проекта и чата, без текста ответа. Пока уведомления включены, в шторке висит «DSH Mobile на связи». Сервисы Google не нужны.
- По переписке можно искать, долгое нажатие на сообщение открывает меню копирования, черновик сохраняется для каждого чата отдельно.
- При обрыве сети во время отправки приложение сверяет статус задачи с компьютером, чтобы не запустить её повторно.
- Для привязки сканируйте одноразовый QR-код с экрана компьютера. Камера нужна только на время сканирования; код распознаётся без сети и сервисов Google.
- Можно выбрать светлую или тёмную тему, русский или английский интерфейс и крупный шрифт.

<p align="center">
  <img src="docs/screenshots/home-dark.png" width="240" alt="Список чатов с фильтром по проектам">
  <img src="docs/screenshots/chat-markdown-running.png" width="240" alt="Чат с Markdown и работающим агентом">
  <img src="docs/screenshots/new-chat-sheet.png" width="240" alt="Выбор проекта для нового чата">
</p>
<p align="center"><sub>Настоящие снимки экрана приложения с синтетическими демо-данными: реальных чатов и моделей здесь нет.</sub></p>

## Как это устроено

```text
Android-приложение ──HTTPS (Wi‑Fi или Tailscale)──▶ плагин-компаньон внутри DSH ──▶ ваши проекты и агенты
                    └─ или через ваш собственный relay (опционально)
```

На компьютере в DSH устанавливается небольшой плагин-компаньон. Он даёт привязанным телефонам доступ к ограниченному API через защищённое соединение. Сам веб-интерфейс DSH наружу не публикуется. Телефон проверяет компьютер по закреплённому сертификату из приглашения, а у каждого телефона свой ключ и свои права, которые можно отозвать.

## Установка

Пошаговую инструкцию [docs/SETUP.md](docs/SETUP.md) можно передать своему агенту DSH. По ней агент проверит версии, установит плагин и запросит согласие перед каждым важным действием. Основные шаги:

1. Проверьте требования: Windows, DeepSeek Harness `0.2.0-rc.2` или `0.2.1-alpha.1`, Node.js 24, OpenSSL 3, Android 8.0+.
2. Скачайте из [Releases](https://github.com/pavel-logachev/dsh-mobile/releases/latest) подписанный APK, архив плагина `dsh-mobile-host-<версия>.zip` и их файлы `.sha256`. Сверьте контрольные суммы.
3. Установите плагин: запустите `install-host.ps1` из архива и укажите адрес компьютера (IP в домашней сети или имя в Tailscale). Скрипт сначала только готовит конфигурацию, а подключает плагин к DSH отдельным шагом `-Activate`, без перезапуска DSH.
4. Разрешите доступ: чтение всех проектов и, если хотите, запуск задач. Правило брандмауэра Windows добавляется только для частной сети и только с вашего согласия.
5. Установите APK на телефон. Android попросит разрешить установку из этого источника.
6. Привяжите телефон: на компьютере `pair --qr`, в приложении «Сканировать QR-код», затем сверьте адрес и отпечаток сертификата и подтвердите.

Для подключения вне дома поставьте [Tailscale](https://tailscale.com/) на компьютер и телефон и укажите при установке имя компьютера в tailnet. Это бесплатно, роутер настраивать не нужно. Если Tailscale не подходит, можно настроить свой relay на VPS: [RELAY_DEPLOYMENT.md](docs/RELAY_DEPLOYMENT.md). Общего публичного relay проект не предоставляет.

## Безопасность

- Плагин работает внутри DSH с теми же правами, что и сам DSH на вашем компьютере. Фильтрация по проектам не создаёт песочницу. Агент DSH с телефона может делать всё то же, что и с компьютера. Права на запуск задач давайте только своим доверенным устройствам.
- Приглашение одноразовое и живёт не дольше 15 минут. Передавайте его только напрямую: QR-кодом с экрана своего компьютера или файлом по доверенному каналу.
- Ключи телефона хранятся в Android Keystore и не попадают в резервные копии. Ключи компьютера лежат в профиле пользователя Windows с доступом только для владельца.
- Relay, если вы его используете, пересылает зашифрованный трафик и не видит содержимое чатов. Но он видит IP-адреса, время и объём трафика.

Подробности в [SECURITY.md](docs/SECURITY.md).

## Сборка из исходников

```powershell
cd host;  npm ci; npm run check            # плагин-компаньон, Node 24
cd ..\android
powershell -NoProfile -ExecutionPolicy Bypass -Command "& ../tools/android-build.ps1 -Tasks ':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug'"
```

Нужны JDK 21, Android SDK platform 36 и build-tools 36.0.0. Сборка релиза, подпись своим ключом и упаковка плагина описаны в [BUILD.md](docs/BUILD.md) и [ANDROID_RELEASE.md](docs/ANDROID_RELEASE.md).

## Проверки

| Часть | Что проверяется |
|---|---|
| Плагин | 121 тест: права устройств, отзыв, повторная доставка, гонки при смене проектов. Изолированные прогоны на настоящем DSH обеих поддерживаемых версий |
| Установщик | 28 сценариев на синтетическом профиле: подготовка, активация, обновление, откат, удаление, сохранение байтов профиля |
| Android | 190 JVM-тестов, lint; регрессионная приёмка и снимки экранов на эмуляторе |
| Релиз | Разрешения APK: интернет, камера, внутреннее служебное, а также уведомления и фоновая служба — последние два используются, только если вы включили уведомления. Никакой телеметрии и сервисов Google |

## Ограничения

- Компьютер и DSH должны быть включены. Уведомления работают через постоянное фоновое соединение: после перезагрузки телефона или принудительной остановки приложения его нужно открыть, а агрессивная экономия батареи (OnePlus, Xiaomi и др.) может задерживать доставку. Push через UnifiedPush — в планах.
- Пока не поддерживаются вложения, ответы на вопросы агента и подтверждения действий: их нужно делать на компьютере.
- Один привязанный компьютер на приложение. В списке до 100 проектов.
- Поддерживаются только проверенные версии DSH. После обновления DSH плагин откажется запускаться, пока совместимость не проверят заново.

## Документация

[Установка](docs/SETUP.md) · [Архитектура](docs/ARCHITECTURE.md) · [Протокол](docs/PROTOCOL.md) · [Безопасность](docs/SECURITY.md) · [Интеграция с DSH](docs/DSH_INTEGRATION.md) · [Relay](docs/RELAY_PROTOCOL.md) · [Сборка](docs/BUILD.md) · [Дизайн](docs/DESIGN.md) · [Участие](CONTRIBUTING.md)

<a id="english"></a>

## English

DSH Mobile is an unofficial native Android app for DeepSeek Harness, developed independently. It shows chats from every registered DSH project. You can create a chat, send a text task, read live Markdown replies or stop running work. Models, tools, subscriptions and history stay on your computer.

A companion plugin runs inside DSH and gives paired phones access to a limited API over TLS. The phone pins the computer’s certificate. Pairing uses a one-use QR invitation; scanning works offline without Google Play services. You can connect over the same Wi‑Fi, over Tailscale or through your own relay.

Supported DSH versions are `0.2.0-rc.2` and `0.2.1-alpha.1`. You can give the [setup runbook](docs/SETUP.md) to your DSH agent. Project filtering does not create a sandbox. Service events are folded into a collapsible "Agent activity" row, and messages sent while the agent works are queued. Optional notifications ("answer ready", "agent needs your decision") use an opt-in foreground connection without Google services. Attachments and approvals from the phone are not supported yet.

## Лицензия

[MIT](LICENSE) © 2026 Pavel Logachev. Сторонние зависимости распространяются под своими лицензиями.
