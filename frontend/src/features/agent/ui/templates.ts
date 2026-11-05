import type { Ticket, UserRef } from '../api/model';

/** Подстановка переменных готового ответа (TCK-09); неизвестная переменная остаётся как есть. */
export function fillTemplate(body: string, ticket: Pick<Ticket, 'number' | 'title' | 'requester'>, agent: UserRef | undefined): string {
  const vars: Record<string, string> = {
    'ticket.number': ticket.number,
    'ticket.title': ticket.title,
    'requester.name': ticket.requester.name,
    'requester.firstName': ticket.requester.name.split(' ')[0] ?? ticket.requester.name,
    'agent.name': agent?.name ?? '',
  };
  return body.replace(/\{\{\s*([\w.]+)\s*\}\}/g, (all, name: string) => vars[name] ?? all);
}
