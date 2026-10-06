<p align="center">
  <img src="docs/assets/dsh-mobile-showcase.png" alt="DSH Mobile — список чатов и чат с работающим агентом на Android" width="100%">
</p>

# DSH Mobile

**Нативное Android-приложение для [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness): чаты всех ваших проектов, отправка задач и наблюдение за агентом с телефона. Модели, инструменты и подписки остаются на компьютере.**

<p align="center">
  <a href="https://github.com/pavel-logachev/dsh-mobile/releases/tag/v0.4.0"><strong>Скачать APK 0.4.0</strong></a> &nbsp;·&nbsp;
  <a href="docs/SETUP.md">Установка</a> &nbsp;·&nbsp;
  <a href="docs/ARCHITECTURE.md">Архитектура</a> &nbsp;·&nbsp;
  <a href="docs/SECURITY.md">Безопасность</a> &nbsp;·&nbsp;
  <a href="https://github.com/pavel-logachev/dsh-mobile/actions/workflows/ci.yml">CI</a> &nbsp;·&nbsp;
  Android 8+ &nbsp;·&nbsp;
  <a href="LICENSE">MIT</a> &nbsp;·&nbsp;
  <a href="#english">English</a>
</p>

> **Статус:** версия 0.4, работает у автора на телефоне с его DSH. Независимый неофициальный проект, не продукт DeepSeek.

## Что умеет

- **Все проекты DSH.** Телефон показывает тот же список проектов, что боковая панель DSH, в том же порядке. Новый проект появляется сам, без повторной привязки.
- **Чаты и задачи.** Поиск, фильтр по проекту, группировка «Сегодня / Вчера / На неделе», создание чата в нужном проекте и с нужным пресетом, отправка текстовой задачи, остановка выполнения.
- **Живой ответ.** Ответ агента обновляется в реальном времени и рендерится как Markdown: заголовки, списки, таблицы, блоки кода с копированием.
- **Чистая переписка.** В чате только ваши сообщения и ответы агента. Служебные события DSH (результаты сабагентов, фоновые задачи, обновления контекста) свёрнуты в строку «Действия агента», полный текст доступен по нажатию.
- **Сообщения во время работы.** Пока агент работает, можно отправить ещё сообщение: оно встанет в очередь DSH и уйдёт агенту на следующем шаге. Кнопка «Стоп» отдельная и спрашивает подтверждение.
- **Уведомления (по желанию).** «Ответ готов» и «Агент ждёт вашего решения» с названием проекта и чата, без текста ответа. Включаются в настройках; пока включены, в шторке висит «DSH Mobile на связи». Без Google-сервисов.
- **Поиск по переписке**, меню сообщения по долгому нажатию, черновик для каждого чата.
- **Честная доставка.** Если сеть пропала в момент отправки, приложение не отправит задачу второй раз вслепую, а сверит её статус с компьютером.
- **Привязка по QR-коду.** Компьютер показывает одноразовый QR, телефон сканирует. Камера запрашивается только на время сканирования, распознавание идёт без сети и без сервисов Google.
- **Тёмная и светлая темы**, русский и английский интерфейс, крупный шрифт.

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

На компьютере в DSH устанавливается небольшой плагин-компаньон. Он открывает узкий защищённый API только для привязанных телефонов. Сам веб-интерфейс DSH наружу не публикуется. Телефон проверяет компьютер по закреплённому сертификату из приглашения, а у каждого телефона свой ключ и свои права, которые можно отозвать.

## Установка

Полная пошаговая инструкция — **[docs/SETUP.md](docs/SETUP.md)**. Она написана так, что её можно целиком отдать своему агенту DSH: агент проверит версии, установит плагин и спросит вас перед каждым важным действием. Если коротко:

1. **Проверьте требования:** Windows, DeepSeek Harness `0.2.0-rc.2` или `0.2.1-alpha.1`, Node.js 24, OpenSSL 3, Android 8.0+.
2. **Скачайте из [Releases](https://github.com/pavel-logachev/dsh-mobile/releases/latest)** подписанный APK, архив плагина `dsh-mobile-host-<версия>.zip` и их файлы `.sha256`. Сверьте контрольные суммы.
3. **Установите плагин:** запустите `install-host.ps1` из архива и укажите адрес компьютера (IP в домашней сети или имя в Tailscale). Скрипт сначала только готовит конфигурацию, а подключает плагин к DSH отдельным шагом `-Activate`, без перезапуска DSH.
4. **Разрешите доступ:** чтение всех проектов и, если хотите, запуск задач. Правило брандмауэра Windows добавляется только для частной сети и только с вашего согласия.
5. **Установите APK** на телефон. Android попросит разрешить установку из этого источника.
6. **Привяжите телефон:** на компьютере `pair --qr`, в приложении «Сканировать QR-код», затем сверьте адрес и отпечаток сертификата и подтвердите.

**Вне дома.** Поставьте [Tailscale](https://tailscale.com/) на компьютер и телефон и укажите при установке имя компьютера в tailnet. Это бесплатно, роутер настраивать не нужно. Свой relay на VPS — продвинутый вариант для тех, кому Tailscale не подходит: [RELAY_DEPLOYMENT.md](docs/RELAY_DEPLOYMENT.md). Общего публичного relay проект не предоставляет.

## Безопасность

- **Плагин работает внутри DSH** с теми же правами, что и сам DSH на вашем компьютере. Фильтрация по проектам — это не песочница: агент DSH с телефона может делать всё то же, что и с компьютера. Права на запуск задач давайте только своим доверенным устройствам.
- Приглашение одноразовое и живёт не дольше 15 минут. Передавайте его только напрямую: QR-кодом с экрана своего компьютера или файлом по доверенному каналу.
- Ключи телефона хранятся в Android Keystore и не попадают в резервные копии. Ключи компьютера лежат в профиле пользователя Windows с доступом только для владельца.
- Relay, если вы его используете, пересылает зашифрованный трафик и не видит содержимое чатов. Но он видит IP-адреса, время и объём трафика.

Подробно — в [SECURITY.md](docs/SECURITY.md).

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
| Плагин | 85 тестов: права устройств, отзыв, повторная доставка, гонки при смене проектов. Изолированные прогоны на настоящем DSH обеих поддерживаемых версий |
| Установщик | 28 сценариев на синтетическом профиле: подготовка, активация, обновление, откат, удаление, сохранение байтов профиля |
| Android | 142 JVM-теста, lint; регрессионная приёмка и снимки экранов на эмуляторе |
| Релиз | В APK только три разрешения: интернет, камера и внутреннее служебное. Никакой телеметрии и сервисов Google |

## Ограничения

- Компьютер и DSH должны быть включены. Уведомления работают через постоянное фоновое соединение: после перезагрузки телефона или принудительной остановки приложения его нужно открыть, а агрессивная экономия батареи (OnePlus, Xiaomi и др.) может задерживать доставку. Push через UnifiedPush — в планах.
- Пока не поддерживаются вложения, ответы на вопросы агента и подтверждения действий: их нужно делать на компьютере.
- Один привязанный компьютер на приложение. В списке до 100 проектов.
- Поддерживаются только проверенные версии DSH. После обновления DSH плагин откажется запускаться, пока совместимость не проверят заново.

## Документация

[Установка](docs/SETUP.md) · [Архитектура](docs/ARCHITECTURE.md) · [Протокол](docs/PROTOCOL.md) · [Безопасность](docs/SECURITY.md) · [Интеграция с DSH](docs/DSH_INTEGRATION.md) · [Relay](docs/RELAY_PROTOCOL.md) · [Сборка](docs/BUILD.md) · [Дизайн](docs/DESIGN.md) · [Участие](CONTRIBUTING.md)

<a id="english"></a>

## English

DSH Mobile is an independent, unofficial native Android companion for DeepSeek Harness. It lists the chats of every registered DSH project, lets you create chats, send text tasks, watch live Markdown replies and stop running work, while models, tools, subscriptions and history stay on your computer. A small companion plugin runs inside DSH and exposes a narrow, pinned-TLS API to paired phones. Pairing is a one-use QR invitation, and scanning works offline without Google Play services. Connect over the same Wi‑Fi, over Tailscale, or through your own optional relay. Supported DSH versions: `0.2.0-rc.2` and `0.2.1-alpha.1`. See the agent-ready [setup runbook](docs/SETUP.md). Project filtering is not a sandbox, and service events are folded into a collapsible "Agent activity" row, messages sent while the agent works are queued, and optional notifications ("answer ready", "agent needs your decision") use an opt-in foreground connection without Google services. There are no attachments or approvals from the phone yet.

## Лицензия

[MIT](LICENSE) © 2026 Pavel Logachev. Сторонние зависимости распространяются под своими лицензиями.
