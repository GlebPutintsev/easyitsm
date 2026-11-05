import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ApiError } from '@/shared/api/client';
import { renderWithProviders } from '@/test/render';
import { ListState, listStateOf } from './ListState';

describe('ListState', () => {
  it('показывает код трассировки при ошибке сервера', () => {
    const error = new ApiError({ status: 500, detail: 'Внутренняя ошибка', trace_id: 'abc123' });
    renderWithProviders(<ListState state="error" error={error} />);
    expect(screen.getByRole('alert')).toHaveTextContent('abc123');
  });

  it('выбирает состояние по результату запроса', () => {
    expect(listStateOf({ isPending: true, error: null }, 0)).toBe('loading');
    expect(listStateOf({ isPending: false, error: new ApiError({ status: 403 }) }, 0)).toBe('forbidden');
    expect(listStateOf({ isPending: false, error: null }, 0, true)).toBe('noResults');
    expect(listStateOf({ isPending: false, error: null }, 3)).toBeNull();
  });
});
