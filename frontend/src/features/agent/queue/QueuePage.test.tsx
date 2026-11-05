import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import { theme } from '@/app/theme';
import { resetMockState } from '../api/mock/mockTicketsApi';
import { QueuePage } from './QueuePage';

function renderQueue(path = '/agent/queues/all') {
  const router = createMemoryRouter(
    [
      { path: '/agent/queues/:viewId', element: <QueuePage /> },
      { path: '/agent/tickets/:number', element: <div>card</div> },
    ],
    { initialEntries: [path] },
  );
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider theme={theme} env="test">
      <QueryClientProvider client={qc}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('Очередь агента', () => {
  beforeEach(() => {
    localStorage.clear();
    resetMockState();
  });

  it('показывает заявки представления и число', async () => {
    renderQueue();
    const table = await screen.findByRole('table', { name: 'Заявки очереди' });
    await waitFor(() => expect(within(table).getAllByRole('row').length).toBeGreaterThan(5));
    expect(screen.getByRole('heading', { name: 'Все открытые' })).toBeInTheDocument();
  });

  it('выбор строк открывает панель массовых действий', async () => {
    renderQueue();
    const table = await screen.findByRole('table', { name: 'Заявки очереди' });
    const boxes = await within(table).findAllByRole('checkbox');
    fireEvent.click(boxes[1]!);
    fireEvent.click(boxes[2]!);
    expect(await screen.findByText('Выбрано 2')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Объединить/ })).toBeEnabled();
  });

  it('поиск без результатов предлагает сбросить фильтр', async () => {
    renderQueue('/agent/queues/all?q=такого-точно-нет');
    expect(await screen.findByText('Ничего не найдено по фильтру')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Сбросить фильтры' })).toBeInTheDocument();
  });
});
