import { Badge } from '@mantine/core';
import type { StatusCategory } from './tickets';

const COLORS: Record<StatusCategory, string> = {
  draft: 'gray',
  new: 'blue',
  approval: 'violet',
  assigned: 'blue',
  in_progress: 'cyan',
  pending: 'yellow',
  resolved: 'teal',
  closed: 'gray',
  cancelled: 'gray',
};

/** Статус заявки: название из настраиваемого цикла, цвет — по категории. */
export function StatusBadge({ status }: { status: { name: string; category: StatusCategory } }) {
  return (
    <Badge variant="light" color={COLORS[status.category]} style={{ textTransform: 'none' }}>
      {status.name}
    </Badge>
  );
}
