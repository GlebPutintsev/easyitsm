import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { theme } from '@/app/theme';
import { formatSize, type Attachment } from './api';
import { AttachmentsPanel } from './AttachmentsPanel';

const OWNER = '00000000-0000-7000-8000-0000000000aa';

function file(over: Partial<Attachment>): Attachment {
  return {
    id: crypto.randomUUID(), ownerType: 'ticket', ownerId: OWNER, visibility: 'public', fileName: 'отчёт.pdf',
    contentType: 'application/pdf', size: 2048, status: 'clean', previewable: false, verdict: null, sha256: null,
    scannedAt: null, uploadedBy: null, createdAt: '2026-11-06T08:00:00Z', canDelete: false, version: 1, ...over,
  };
}

function renderPanel(list: Attachment[], props: Partial<Parameters<typeof AttachmentsPanel>[0]> = {}) {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(list), { headers: { 'Content-Type': 'application/json' } })));
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MantineProvider theme={theme} env="test">
        <AttachmentsPanel ownerType="ticket" ownerId={OWNER} canUpload={false} {...props} />
      </MantineProvider>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('панель файлов', () => {
  it('проверенный файл скачивается через API, карантинный — нет, причина переведена', async () => {
    const clean = file({ fileName: 'отчёт.pdf' });
    const image = file({ fileName: 'экран.png', contentType: 'image/png', previewable: true, visibility: 'internal' });
    renderPanel([
      clean,
      image,
      file({ fileName: 'setup.exe', status: 'blocked', verdict: 'executable:application/x-msdownload' }),
      file({ fileName: 'eicar.txt', status: 'infected', verdict: 'Eicar-Test-Signature' }),
      file({ fileName: 'большой.zip', status: 'scanning' }),
    ]);

    const link = await screen.findByRole('link', { name: 'Скачать отчёт.pdf' });
    expect(link.getAttribute('href')).toBe(`/api/v1/attachments/${clean.id}/download`);
    expect(screen.getByRole('link', { name: 'Просмотр экран.png' }).getAttribute('href')).toBe(
      `/api/v1/attachments/${image.id}/preview`,
    );
    expect(screen.getByText('Внутренний')).toBeTruthy();

    expect(screen.queryByRole('link', { name: /setup\.exe/ })).toBeNull();
    expect(screen.getByText(/исполняемый файл: application\/x-msdownload/)).toBeTruthy();
    expect(screen.getByText(/Eicar-Test-Signature/)).toBeTruthy();
    expect(screen.queryByRole('link', { name: /большой/ })).toBeNull();
  });

  it('без права загрузки и без файлов панели нет', async () => {
    const { container } = renderPanel([]);
    await new Promise((r) => setTimeout(r, 20));
    expect(container.querySelector('section')).toBeNull();
  });

  it('с правом загрузки видна зона перетаскивания, переключатель внутреннего — только поддержке', async () => {
    renderPanel([], { canUpload: true });
    expect(await screen.findByRole('button', { name: /Перетащите файлы/ })).toBeTruthy();
    expect(screen.queryByLabelText(/Внутренний файл/)).toBeNull();
    cleanup();
    renderPanel([], { canUpload: true, canUploadInternal: true });
    expect(await screen.findByLabelText(/Внутренний файл/)).toBeTruthy();
  });

  it('размер файла по-русски', () => {
    expect(formatSize(512, 'ru')).toBe('512 Б');
    expect(formatSize(1536, 'ru')).toBe('1,5 КБ');
    expect(formatSize(100 * 1024 * 1024, 'en')).toBe('100 MB');
  });
});
