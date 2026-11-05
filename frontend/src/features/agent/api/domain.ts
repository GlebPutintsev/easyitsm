import type { AvailableTransition, Level, Priority, SecretFinding, SlaTimer, StatusCategory, TicketSummary } from './model';

/** Матрица приоритета по влиянию и срочности (5.2 ТЗ). */
const MATRIX: Record<Level, Record<Level, Priority>> = {
  high: { high: 'P1', medium: 'P2', low: 'P3' },
  medium: { high: 'P2', medium: 'P3', low: 'P4' },
  low: { high: 'P3', medium: 'P4', low: 'P4' },
};

export function priorityOf(impact: Level, urgency: Level): Priority {
  return MATRIX[impact][urgency];
}

/** Категории, которые агент считает открытыми: их показывают очереди по умолчанию. */
export const OPEN_STATUSES: StatusCategory[] = ['new', 'approval', 'assigned', 'in_progress', 'pending'];

export function isOpen(category: StatusCategory): boolean {
  return OPEN_STATUSES.includes(category);
}

/** Главное действие карточки по статусу (10.7): «Взять в работу», «Решить», «Возобновить». */
export type MainAction = 'take' | 'resolve' | 'resume' | null;

export function transitionTo(
  ticket: { transitions: AvailableTransition[] },
  category: StatusCategory,
): AvailableTransition | undefined {
  return ticket.transitions.find((t) => t.category === category);
}

export function mainActionOf(
  ticket: Pick<TicketSummary, 'status' | 'assignee'> & { transitions: AvailableTransition[] },
  meId: string,
): MainAction {
  const category = ticket.status.category;
  if (category === 'new' || category === 'assigned') return 'take';
  if (category === 'in_progress') {
    if (ticket.assignee?.id !== meId) return 'take';
    return transitionTo(ticket, 'resolved') ? 'resolve' : null;
  }
  if (category === 'pending' || category === 'resolved') return transitionTo(ticket, 'in_progress') ? 'resume' : null;
  return null;
}

/** Оставшееся время таймера SLA в миллисекундах (отрицательное — просрочено). */
export function slaRemainingMs(timer: SlaTimer, now: number): number {
  if (timer.state === 'paused') return timer.remainingMs ?? 0;
  return new Date(timer.dueAt).getTime() - now;
}

export type SlaTone = 'breached' | 'risk' | 'ok' | 'paused' | 'met';

/** Цвет таймера: меньше часа — риск. Пороги 50/75/90 % считает модуль sla, здесь только отображение. */
export function slaToneOf(timer: SlaTimer, now: number): SlaTone {
  if (timer.state === 'met') return 'met';
  if (timer.state === 'paused') return 'paused';
  const left = slaRemainingMs(timer, now);
  if (timer.state === 'breached' || left < 0) return 'breached';
  if (left < 60 * 60 * 1000) return 'risk';
  return 'ok';
}

const SECRET_PATTERNS: { kind: string; re: RegExp }[] = [
  { kind: 'private_key', re: /-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?(-----END [A-Z ]*PRIVATE KEY-----|$)/g },
  { kind: 'password', re: /(?<=(?:пароль|password|passwd|pwd|pass)\s*[:=]\s*)[^\s,;]{4,}/giu },
  { kind: 'token', re: /\b(?:ghp_[A-Za-z0-9]{20,}|glpat-[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|xox[abprs]-[A-Za-z0-9-]{10,}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,})\b/g },
  { kind: 'card', re: /\b(?:\d[ -]?){15}\d\b/g },
];

/**
 * Быстрая проверка на секреты в браузере, повторяет основные правила SecretScanner модуля tickets.
 * Нужна для моков; при работе с сервером источник истины — POST /tickets/secret-scan.
 */
export function scanSecrets(text: string): { findings: SecretFinding[]; masked: string } {
  const findings: SecretFinding[] = [];
  for (const { kind, re } of SECRET_PATTERNS) {
    for (const m of text.matchAll(re)) {
      const start = m.index ?? 0;
      findings.push({ kind, start, end: start + m[0].length, masked: maskValue(m[0]) });
    }
  }
  findings.sort((a, b) => a.start - b.start);
  let masked = '';
  let pos = 0;
  for (const f of findings) {
    if (f.start < pos) continue;
    masked += text.slice(pos, f.start) + f.masked;
    pos = f.end;
  }
  return { findings, masked: masked + text.slice(pos) };
}

function maskValue(value: string): string {
  if (value.startsWith('-----BEGIN')) return '[PRIVATE KEY]';
  return value.length <= 4 ? '••••' : `${value.slice(0, 2)}${'•'.repeat(Math.min(8, value.length - 2))}`;
}
