import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { formatSlaRemaining, SlaTimer, slaState } from './SlaTimer';
import { PriorityBadge, StatusBadge, statusTone } from './TicketBadges';

const NOW = Date.parse('2026-11-05T10:00:00Z');
const min = 60_000;

describe('StatusBadge', () => {
  it('берёт цвет из категории, а текст из названия статуса заказчика', () => {
    renderWithProviders(<StatusBadge category="in_progress" label="Диагностика" />);
    const badge = screen.getByText('Диагностика');
    expect(badge).toHaveAttribute('data-tone', 'warn');
  });

  it('без названия показывает категорию', () => {
    renderWithProviders(<StatusBadge category="pending" />);
    expect(screen.getByText('Ожидание')).toHaveAttribute('data-tone', 'wait');
  });

  it('у каждой категории контракта есть цвет', () => {
    expect(Object.keys(statusTone)).toHaveLength(9);
  });
});

describe('PriorityBadge', () => {
  it('в таблице коротко, но с полным названием для экранного диктора', () => {
    renderWithProviders(<PriorityBadge priority="P1" />);
    expect(screen.getByLabelText('P1 Критический')).toHaveTextContent('P1');
  });

  it('в шапке полностью', () => {
    renderWithProviders(<PriorityBadge priority="P2" variant="full" />);
    expect(screen.getByText('P2 Высокий')).toBeInTheDocument();
  });
});

describe('slaState', () => {
  const start = new Date(NOW - 60 * min);
  it('в норме до 75%', () => {
    expect(slaState({ dueAt: new Date(NOW + 60 * min), startAt: start, now: NOW })).toBe('ok');
  });
  it('после 75% предупреждение', () => {
    expect(slaState({ dueAt: new Date(NOW + 15 * min), startAt: start, now: NOW })).toBe('warn');
  });
  it('после срока нарушение', () => {
    expect(slaState({ dueAt: new Date(NOW - min), startAt: start, now: NOW })).toBe('breached');
  });
  it('пауза важнее срока', () => {
    expect(slaState({ dueAt: new Date(NOW - min), paused: true, now: NOW })).toBe('paused');
  });
  it('без срока — нет SLA', () => {
    expect(slaState({ dueAt: null, now: NOW })).toBe('none');
  });
});

describe('formatSlaRemaining', () => {
  it('часы и минуты', () => expect(formatSlaRemaining(78 * min).text).toBe('1:18'));
  it('от суток — дни', () => expect(formatSlaRemaining(50 * 60 * min).days).toBe(2));
});

describe('SlaTimer', () => {
  it('нарушенный срок показан со знаком минус', () => {
    renderWithProviders(<SlaTimer dueAt={new Date(NOW - 42 * min)} now={NOW} />);
    const timer = screen.getByLabelText('Срок нарушен на 0:42');
    expect(timer).toHaveTextContent('−0:42');
    expect(timer).toHaveAttribute('data-state', 'breached');
  });

  it('на паузе показывает паузу', () => {
    renderWithProviders(<SlaTimer dueAt={new Date(NOW + 42 * min)} paused now={NOW} />);
    expect(screen.getByText('пауза')).toBeInTheDocument();
  });
});
