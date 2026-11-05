import type { CursorPage } from '@/shared/api/client';
import type {
  Activity,
  BulkAction,
  BulkPreview,
  BulkResult,
  Directory,
  EscalationRequest,
  NewActivity,
  SecretFinding,
  ResponseTemplate,
  SavedView,
  Ticket,
  TicketPatch,
  TicketQuery,
  TicketSummary,
  TransitionRequest,
} from './model';

/**
 * Всё, что рабочему месту нужно от сервера. Компоненты работают только с этим интерфейсом,
 * поэтому переход с моков на настоящий API — замена одной реализации (см. index.ts).
 */
export interface TicketsApi {
  list(query: TicketQuery, signal?: AbortSignal): Promise<CursorPage<TicketSummary> & { total: number }>;
  /** Сколько заявок представления появилось или изменилось после момента since (плашка «Показать N новых»). */
  countChangedSince(query: TicketQuery, since: string, signal?: AbortSignal): Promise<number>;
  get(number: string, signal?: AbortSignal): Promise<Ticket>;
  update(id: string, patch: TicketPatch, version: number): Promise<Ticket>;
  transition(id: string, request: TransitionRequest, version: number): Promise<Ticket>;
  /** Взять в работу: исполнитель = я, статус категории in_progress. */
  take(id: string, version: number): Promise<Ticket>;
  escalate(id: string, request: EscalationRequest, version: number): Promise<Ticket>;
  /** Проверка текста на секреты до отправки (TCK-17). */
  secretScan(text: string): Promise<{ findings: SecretFinding[]; masked: string }>;
  activity(id: string, signal?: AbortSignal): Promise<Activity[]>;
  addActivity(id: string, activity: NewActivity, idempotencyKey: string): Promise<Activity>;
  /** Взять следующую: сервер назначает наиболее срочную заявку по правилам группы (10.6). */
  takeNext(): Promise<Ticket | null>;
  requesterTickets(requesterId: string, excludeId: string, signal?: AbortSignal): Promise<TicketSummary[]>;
  bulkPreview(ids: string[], action: BulkAction): Promise<BulkPreview>;
  bulkApply(ids: string[], action: BulkAction, idempotencyKey: string): Promise<BulkResult>;
  bulkUndo(operationId: string): Promise<void>;
  views(signal?: AbortSignal): Promise<SavedView[]>;
  saveView(view: Omit<SavedView, 'id' | 'builtIn'>, idempotencyKey: string): Promise<SavedView>;
  deleteView(id: string): Promise<void>;
  directory(signal?: AbortSignal): Promise<Directory>;
  templates(signal?: AbortSignal): Promise<ResponseTemplate[]>;
}
