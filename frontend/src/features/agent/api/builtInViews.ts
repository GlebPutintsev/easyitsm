import { OPEN_STATUSES } from './domain';
import type { Directory, SavedView } from './model';

/**
 * Встроенные представления очереди (10.6): мои, моей группы, неназначенные, просроченные, все открытые.
 * Хранятся на клиенте, сервер отдаёт только сохранённые пользователями (TCK-16).
 * Название берётся из перевода по id.
 */
export function builtInViews(directory: Directory | undefined): SavedView[] {
  const myGroups = directory?.agents.find((a) => a.id === directory.me.id)?.groupIds ?? [];
  const open = { status: OPEN_STATUSES };
  const bySla = { field: 'sla', dir: 'asc' } as const;
  return [
    { id: 'mine', name: 'mine', visibility: 'personal', builtIn: true, filter: { ...open, assignee: ['me'] }, sort: bySla },
    { id: 'group', name: 'group', visibility: 'group', builtIn: true, filter: { ...open, groupId: myGroups }, sort: bySla },
    { id: 'unassigned', name: 'unassigned', visibility: 'shared', builtIn: true, filter: { ...open, assignee: ['none'], groupId: myGroups }, sort: bySla },
    { id: 'overdue', name: 'overdue', visibility: 'shared', builtIn: true, filter: { ...open, sla: ['breached'] }, sort: bySla },
    { id: 'all', name: 'all', visibility: 'shared', builtIn: true, filter: open, sort: { field: 'updatedAt', dir: 'desc' } },
  ];
}
