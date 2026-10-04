import { HostError } from './errors.ts';
import type { HostAdapter, HostSession, HostSnapshot, Preset } from './types.ts';

/** All content is synthetic. This adapter has no DSH, model or network dependency. */
export const FIXTURE = Object.freeze({
  hostName: 'DSH Mobile demo — not connected to DSH', workspaceId: 'demo', workspaceName: 'Demo workspace',
  existingSessionId: 'demo-session', existingSessionTitle: 'Synthetic example',
  existingMessage: 'This is a synthetic conversation. No model or DSH runtime is connected.',
  createdSessionTitle: 'Demo conversation', expectedAssistantText: 'Synthetic demo answer. No model was called.',
});
export const MULTI_PROJECT_FIXTURE = Object.freeze({
  markdownSessionId: 'demo-fund-plan', markdownAnchor: 'План портала фонда', filterWorkspaceId: 'demo-fund',
  workspaces: Object.freeze([
    { id: 'demo-fund', name: 'Портал фонда (демо)' },
    { id: 'demo-tenders', name: 'Тендерный радар (демо)' },
    { id: 'demo-site', name: 'Сайт (демо)' },
    { id: 'demo-mobile', name: 'Мобильное приложение (демо)' },
    { id: 'demo-pc', name: 'Администрирование ПК (демо)' },
  ]),
});
const FUND_MARKDOWN = '# План портала фонда\n\nЭто **синтетический пример** для проверки интерфейса. Здесь нет данных настоящего фонда, подключённой модели или выполненных действий.\n\n## Что подготовлено\n\n- Главная страница с понятным описанием программ.\n- Каталог инициатив и карточка отдельного проекта.\n- Черновик формы обратной связи с полем `email`.\n\nСледующий шаг — проверить навигацию на небольшом экране. Важные действия должны быть доступны без горизонтальной прокрутки.\n\n## Порядок проверки\n\n1. Открыть главную страницу.\n2. Выбрать демо-проект в каталоге.\n3. Проверить подписи и состояние загрузки.\n\nПример модели состояния для Android:\n\n```kotlin\ndata class DemoProject(\n    val title: String,\n    val isLoading: Boolean = false,\n)\n\nval project = DemoProject("Портал фонда (демо)")\n```\n\n**Важно:** этот код — иллюстрация, а не результат работы на компьютере.';
const TENDER_MARKDOWN = '## Обзор демонстрационного радара\n\nПодготовлен **учебный** список закупок. Все названия и значения вымышлены; реальные площадки не запрашивались.\n\n- Новые записи выделены отдельным блоком.\n- Для каждого результата показаны срок и краткое описание.\n- Фильтр `region` можно сбросить одним действием.\n\n### Что проверить дальше\n\n1. Сравнить пустое состояние и список результатов.\n2. Увеличить размер системного шрифта.\n3. Убедиться, что длинные заголовки переносятся.\n\nОтчёт остаётся черновиком. Отправка уведомлений и поиск на внешних площадках в этом демо **не выполняются**.';
const MULTI_SESSIONS = [
  ['demo-fund-plan', 'demo-fund', 'План портала и структура разделов', true, FUND_MARKDOWN],
  ['demo-tenders-review', 'demo-tenders', 'Проверка фильтров тендерного радара', true, TENDER_MARKDOWN],
  ['demo-mobile-chat', 'demo-mobile', 'Чат: отступы и длинные ответы', false, 'Синтетический итог: проверены переносы текста и состояния кнопок. Модель не вызывалась.'],
  ['demo-site-navigation', 'demo-site', 'Навигация и мобильное меню', false, 'Демонстрационный черновик меню подготовлен. Настоящий сайт не изменялся.'],
  ['demo-fund-form', 'demo-fund', 'Форма заявки: валидация полей', false, 'Это учебная проверка полей формы. Никакие заявки не отправлены.'],
  ['demo-pc-backup', 'demo-pc', 'План резервного копирования', false, 'Синтетический план: выбрать папки, проверить свободное место, согласовать расписание. Команды не запускались.'],
  ['demo-tenders-digest', 'demo-tenders', 'Еженедельная сводка: макет', false, TENDER_MARKDOWN],
  ['demo-mobile-offline', 'demo-mobile', 'Состояния без сети и повторное подключение', false, 'Учебный сценарий: показать статус соединения и сохранить черновик. Сервер DSH не подключён.'],
  ['demo-site-a11y', 'demo-site', 'Доступность карточек и контраст', false, 'Демо-заметка: проверить подписи и клавиатурный фокус. Аудит реального продукта не выполнялся.'],
  ['demo-pc-storage', 'demo-pc', 'Свободное место и очистка: черновик', false, 'Синтетический список шагов. Файлы не удалялись и настройки ПК не менялись.'],
  ['demo-fund-content', 'demo-fund', 'Тексты главной страницы', false, FUND_MARKDOWN],
  ['demo-mobile-settings', 'demo-mobile', 'Экран настроек соединения', false, 'Учебный текст для экрана настроек. Ключи и адреса настоящего сервера отсутствуют.'],
] as const;
const EPOCH = 1700000000000;
const MULTI_DAYS_AGO = [0, 0, 0, 0, 1, 1, 2, 3, 5, 8, 12, 20] as const;
function multiProjectDate(startedAt: number, index: number): number {
  const date = new Date(startedAt), daysAgo = MULTI_DAYS_AGO[index]!;
  if (daysAgo === 0) {
    date.setHours(0, 0, 0, 0);
    // The two running rows are updated today even when the demo starts just
    // after midnight; their last user messages still show realistic elapsed time.
    return startedAt - Math.min(Math.max(0, index - 1) * 45 * 60_000, startedAt - date.getTime());
  }
  date.setDate(date.getDate() - daysAgo);
  date.setHours(index % 2 ? 10 : 12, 0, 0, 0);
  return date.getTime();
}
export interface FixtureOptions { answerDelayMs?: number; multiProject?: boolean }

export class FixtureAdapter implements HostAdapter {
  readonly upstreamVersion = 'fixture';
  private readonly sessions = new Map<string, HostSnapshot>();
  private readonly listeners = new Map<string, Set<() => void>>();
  private readonly tasks = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly requests = new Map<string, string>();
  private readonly delay: number;
  private readonly epoch: number;
  private readonly workspaceIds: ReadonlySet<string>;
  private tick = 0;
  private disposed = false;
  constructor(options: FixtureOptions = {}) {
    this.delay = options.answerDelayMs ?? 2000;
    if (!Number.isSafeInteger(this.delay) || this.delay < 0 || this.delay > 60000 || (options.multiProject !== undefined && typeof options.multiProject !== 'boolean')) throw new HostError('invalid_config');
    this.epoch = options.multiProject ? Date.now() : EPOCH;
    this.workspaceIds = new Set(options.multiProject ? MULTI_PROJECT_FIXTURE.workspaces.map(item => item.id) : [FIXTURE.workspaceId]);
    if (options.multiProject) {
      for (const [index, [id, workspaceId, title, running, answer]] of MULTI_SESSIONS.entries()) {
        const updatedAt = multiProjectDate(this.epoch, index);
        const userAt = running ? this.epoch - (index === 0 ? 3 : 5) * 60_000 : updatedAt - 1000;
        this.sessions.set(id, { session: { id, workspaceId, title, running, updatedAt }, cursor: 1, hasMore: false, activity: running ? 'running' : 'idle', messages: [
          { id: `${id}-user`, role: 'user', text: `Демо-задача: ${title.toLowerCase()}. Используй только вымышленные примеры.`, createdAt: userAt },
          { id: `${id}-assistant`, role: 'assistant', text: answer, createdAt: updatedAt },
        ] });
      }
      return;
    }
    this.sessions.set(FIXTURE.existingSessionId, {
      session: { id: FIXTURE.existingSessionId, title: FIXTURE.existingSessionTitle, workspaceId: FIXTURE.workspaceId, updatedAt: EPOCH, running: false },
      cursor: 1, hasMore: false, activity: 'idle', messages: [
        { id: 'demo-user', role: 'user', text: FIXTURE.existingMessage, createdAt: EPOCH },
        { id: 'demo-assistant', role: 'assistant', text: FIXTURE.expectedAssistantText, createdAt: EPOCH + 1 },
      ],
    });
  }
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    for (const timer of this.tasks.values()) clearTimeout(timer);
    this.tasks.clear();
    for (const set of this.listeners.values()) for (const wake of set) wake();
    this.listeners.clear();
  }
  private check(signal: AbortSignal): void { signal.throwIfAborted(); if (this.disposed) throw new HostError('unavailable'); }
  private current(id: string): HostSnapshot { const value = this.sessions.get(id); if (!value) throw new HostError('not_found'); return value; }
  private publish(id: string): void { for (const wake of this.listeners.get(id) ?? []) wake(); }
  async listPresets(signal: AbortSignal): Promise<Preset[]> { this.check(signal); return [{ id: 'fixture', name: 'Synthetic demo (no model)' }]; }
  async listSessions(signal: AbortSignal): Promise<HostSession[]> {
    this.check(signal);
    return [...this.sessions.values()].map((item) => ({ ...item.session })).sort((a, b) => b.updatedAt - a.updatedAt);
  }
  async snapshot(sessionId: string, signal: AbortSignal): Promise<HostSnapshot> { this.check(signal); return structuredClone(this.current(sessionId)); }
  async *watch(sessionId: string, signal: AbortSignal): AsyncIterable<HostSnapshot> {
    this.check(signal); this.current(sessionId);
    let wake: () => void = () => {};
    let changed = true;
    const notify = () => { changed = true; wake(); };
    const set = this.listeners.get(sessionId) ?? new Set<() => void>();
    this.listeners.set(sessionId, set); set.add(notify);
    signal.addEventListener('abort', notify, { once: true });
    try {
      while (!signal.aborted && !this.disposed) {
        if (changed) { changed = false; yield await this.snapshot(sessionId, signal); continue; }
        await new Promise<void>((resolve) => { wake = resolve; if (changed || signal.aborted || this.disposed) resolve(); });
      }
    } finally {
      set.delete(notify);
      if (!set.size) this.listeners.delete(sessionId);
      signal.removeEventListener('abort', notify); wake = () => {};
    }
  }
  async createSession(input: { workspaceId: string; presetId?: string; requestId: string }, signal: AbortSignal, expectedWorkspaceId: string): Promise<{ sessionId: string }> {
    this.check(signal);
    if (!this.workspaceIds.has(input.workspaceId) || input.workspaceId !== expectedWorkspaceId) throw new HostError('not_found');
    if (input.presetId !== undefined && input.presetId !== 'fixture') throw new HostError('invalid_request');
    if (!/^[0-9a-f-]{36}$/i.test(input.requestId)) throw new HostError('invalid_request');
    const sessionId = `demo-${input.requestId.toLowerCase()}`;
    if (!this.sessions.has(sessionId)) {
      this.sessions.set(sessionId, { session: { id: sessionId, title: FIXTURE.createdSessionTitle, workspaceId: input.workspaceId, updatedAt: this.epoch + ++this.tick, running: false }, messages: [], cursor: -1, hasMore: false, activity: 'idle' });
    }
    return { sessionId };
  }
  async prompt(sessionId: string, text: string, requestId: string, signal: AbortSignal, expectedWorkspaceId: string): Promise<void> {
    this.check(signal);
    const current = this.current(sessionId);
    if (current.session.workspaceId !== expectedWorkspaceId) throw new HostError('not_found');
    if (!text.trim()) throw new HostError('invalid_request');
    const key = `${sessionId}:${requestId}`;
    const previous = this.requests.get(key);
    if (previous !== undefined) { if (previous !== text) throw new HostError('conflict'); return; }
    if (this.tasks.has(sessionId)) throw new HostError('conflict');
    this.requests.set(key, text);
    current.messages.push({ id: `demo-user-${requestId}`, role: 'user', text, createdAt: this.epoch + ++this.tick, requestId });
    current.session.updatedAt = this.epoch + this.tick;
    current.session.running = true; current.activity = 'running'; current.cursor++;
    const timer = setTimeout(() => {
      this.tasks.delete(sessionId);
      if (this.disposed) return;
      current.messages.push({ id: `demo-assistant-${requestId}`, role: 'assistant', text: FIXTURE.expectedAssistantText, createdAt: this.epoch + ++this.tick });
      if (current.messages.length > 100) { current.messages = current.messages.slice(-100); current.hasMore = true; }
      current.session.running = false; current.activity = 'idle'; current.cursor++;
      this.publish(sessionId);
    }, this.delay);
    this.tasks.set(sessionId, timer);
    this.publish(sessionId);
  }
  async cancel(sessionId: string, signal: AbortSignal, expectedCursor: number, expectedWorkspaceId: string): Promise<void> {
    this.check(signal);
    const current = this.current(sessionId);
    if (current.session.workspaceId !== expectedWorkspaceId) throw new HostError('not_found');
    if (!Number.isSafeInteger(expectedCursor) || expectedCursor < -1) throw new HostError('invalid_request');
    if (!current.session.running || current.cursor !== expectedCursor) throw new HostError('conflict');
    const timer = this.tasks.get(sessionId); if (timer) clearTimeout(timer);
    this.tasks.delete(sessionId);
    current.session.running = false; current.activity = 'idle'; current.cursor++;
    this.publish(sessionId);
  }
}
