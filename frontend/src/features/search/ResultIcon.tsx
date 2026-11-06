import { IconBook, IconShoppingCart, IconTicket } from '@tabler/icons-react';
import type { SearchKind } from './api';

export function ResultIcon({ kind, size = 18 }: { kind: SearchKind; size?: number }) {
  const Icon = kind === 'ticket' ? IconTicket : kind === 'article' ? IconBook : IconShoppingCart;
  return <Icon size={size} aria-hidden="true" />;
}
