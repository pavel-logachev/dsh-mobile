# UX-аудит DSH Mobile — октябрь 2026

**Метод и пределы.** Ручной разбор Compose-кода и имеющихся fixture-снимков по Impeccable UX critique/native audit. Пользователь запретил делегирование, поэтому оценка inline. `impeccable critique --help` → `Unknown command: critique`; native detector недоступен. Эмулятор не запускал; PNG queue-quiet не появились. Артефакты synthetic, не live DSH/физический телефон; незакоммиченные изменения на момент аудита указаны в `artifacts/queue-quiet-verify-20261006-1915/initial-status.txt`.

## Оценки экранов /10 и главные проблемы

| Экран | Балл | Ключевое наблюдение |
|---|---:|---|
| Чат + композер | 7.5 | Ясные Send/Stop, очередь и статусы; нет поиска/меню long-press; плотность растёт при activity+предупреждениях |
| Чаты / проектный фильтр | 7.5 | Поиск, pull-to-refresh, empty/offline; FAB перекрывает нижний ряд (pairing-verify report), нет unread/favorites |
| Создание чата | 7.5 | Проекты/пресеты доступны; длинный выбор и горизонтальные чипы требуют прокрутки |
| Pairing / доверие | 7.5 | QR/импорт/вставка, подтверждение личности; много пояснений и плотный trust с отпечатком |
| QR-сканер | 6.5 | Back закрывает камеру, рамка/статус есть; исторически landscape обрезал область, свежий landscape PNG не доказывает доступность всех подсказок |
| Настройки | 7.5 | Статус/retry/темы/ограничения понятны; один длинный скролл, мало приоритета соединению |
| Empty/error/offline/reconnect | 8.0 | Состояния и явный retry хороши; часть ошибок длинная/повторяется |

**Навигация:** уже paired, нужный чат виден → последний чат: 1 тап. Первый pairing через QR → скан → проверка доверия → чат: минимум 4–5 действий плюс permission prompt. Нет списка «последние непрочитанные».

## UX-критерии

- Иерархия/плотность: chat-first и фиксированный композер; список перегружен поиском, project chips, connection pill и FAB одновременно.
- Читаемость: body 14–16sp, мелкие метки 11–12sp; снимки читаемы в 1.0/1.3. Проверки 2.0/375dp не было.
- Touch: ключевые контролы в коде ≥48dp (Stop 48, Send 52, project chips min 48); не измерял каждый элемент.
- Состояния/feedback: loading, empty, error, offline, reconnect, pending/uncertain delivery, copy snackbar и подтверждение Stop реализованы.
- RU copy честный и ясный; «проект/пресет/отпечаток» могут требовать подсказки новичку.
- Контраст, вычислен по токенам `ui/theme/Color.kt`: #E6E9E4/#0F1214 **15.35:1**, lime #C5F04A/graphite **14.27:1**, lime/#14180A **13.68:1**, muted #9AA39E/graphite **7.26:1**. Не всякое сочетание контейнеров проверено; TalkBack на устройстве не проверялся.
- Insets: IME padding есть в composer/pairing/new-chat. Font scale 1.3 снимок есть; 2.0 и повороты основных экранов не проверены. `Type.kt:9–23` использует системный SansSerif, не Onest; `res/font` отсутствует.

## Everyday-функции: наличие

- Переход к новым сообщениям в открытом чате — **есть**: `ui/chat/ChatTimeline.kt:86–89`; unread badge в списке — **нет** (`home/ChatListScreen.kt:155–178`).
- Pull-to-refresh — **есть**: `home/ChatListScreen.kt:95–96`; обновление чата — `chat/ChatScreen.kt:47`.
- Long-press действий — **нет**; copy и выделение доступны: `chat/MessageItem.kt:18–40`.
- Черновик на чат — **есть**, зашифрованная персистентность: `ui/MobileViewModel.kt:98–108`, `data/NetworkMobileRepository.kt:255–277`, `data/StoredState.kt:14–19`.
- Copy code + horizontal scroll — **есть**: `ui/markdown/MarkdownRenderer.kt:97–110`; таблицы `:118–137`.
- Поиск чата — **есть**, поиск внутри переписки — **нет** (`home/ChatListScreen.kt:77–85`).
- New chat — **есть**: `home/ChatListScreen.kt:62–71`, `newchat/NewChatSheet.kt:31–94`.
- Pin/favorite project — **не найдено** в проверенных UI/model/storage модулях; haptics — **не найдено** по Kotlin app.
- Connection banner/retry — **есть**: `components/ConnectionPill.kt:82–92`; статусы pill `:20–46`. Collapsed agent actions — **есть**: `ui/chat/AgentActivityGroup.kt:23`.

## Top-10 backlog (P0 визуальное, P1 функции, P2 polish)

1. **P0 Visual · FAB не закрывает строки списка.** AC: последний чат виден/доступен при 1.3/2.0 portrait+landscape. `ui/home/ChatListScreen.kt`. **S**.
2. **P0 Visual · Сканер и pairing в landscape/крупном тексте.** AC: back, QR, статус/подсказка и trust-actions доступны при повороте и 2.0. `ui/pairing/InvitationScannerScreen.kt`, `PairingScreen.kt`, `InvitationPreview.kt`. **M**.
3. **P0 Visual · Уплотнить pairing/trust-copy без потери предупреждений.** AC: следующий шаг сразу ясен, fingerprint можно прочесть/скопировать, RU/EN parity. `ui/pairing/PairingScreen.kt`, `ui/InvitationPreview.kt`, `res/values-ru/mobile_strings.xml`, `res/values/mobile_strings.xml`. **M**.
4. **P0 Visual · Типографика и Onest.** AC: Onest доступен с кириллическим fallback, мелкие метки не критичны, 2.0 без обрезания. `ui/theme/Type.kt`, `MobileTheme.kt`, resources/font (если лицензия/asset доступны). **M**.
5. **P1 Function · Unread badges.** AC: новые ответы помечаются и озвучиваются, вход в чат сбрасывает статус, reconnect не дублирует. `model/MobileModels.kt`, `data/StoredState.kt`, `data/NetworkMobileRepository.kt`, `ui/home/ChatListScreen.kt`, strings. **L**.
6. **P1 Function · Поиск по текущей переписке.** AC: искать в загруженной истории, переходить к совпадению; обозначить частичную историю и empty result. `ui/chat/ChatScreen.kt`, `ChatTimeline.kt`, `MessageItem.kt`, RU/EN strings. **M**.
7. **P1 Function · Проверить UX черновиков per-chat.** AC: A не попадает в B, A восстанавливается после навигации/перезапуска, есть regression test; хранилище уже реализовано. `ui/MobileViewModel.kt`, `data/NetworkMobileRepository.kt`, `data/StoredState.kt`, tests. **M**.
8. **P1 Function · Избранные/закреплённые проекты.** AC: закреплённое наверху фильтра и new-chat picker, порядок сохраняется, открепление не меняет разрешения. `data/StoredState.kt`, `ui/home/ChatListScreen.kt`, `ui/newchat/NewChatSheet.kt`, model/repository, strings. **M**.
9. **P1 Function · Long-press меню сообщения.** AC: доступно копирование без конфликта выделения, эквивалентно для TalkBack; кнопка copy остаётся fallback. `ui/chat/MessageItem.kt`, `ChatTimeline.kt`, strings. **S**.
10. **P2 Polish · Haptic после действия.** AC: короткий feedback на принятую отправку/stop/copy, уважает системную настройку и не срабатывает на stream-обновления. `ui/chat/Composer.kt`, `ChatScreen.kt`, tests. **S**.

## Предлагаемый раздел работ

- **Работник A — список/проекты:** пункты 1, 5, 8; зона `ui/home/**` и отдельные состояния избранного/unread. Не менять чат-композер.
- **Работник B — chat/pairing:** пункты 2, 3, 6, 7, 9, 10; зона `ui/chat/**`, `ui/pairing/**`, связанные strings/tests. Не менять список.
- **Интегратор:** пункт 4 — theme/fonts. Модель/repository/storage могут стать общими для 5/8; заранее согласовать API или последовательно интегрировать, иначе split не disjoint.

**Не проверено:** TalkBack вживую, телефон/WAN, реальный ответ хоста, 2.0 scale, 375dp, IME+rotation; queue-quiet снимков нет. Файлы приложения не менялись; создан только этот отчёт.
