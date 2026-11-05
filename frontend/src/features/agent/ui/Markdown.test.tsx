import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { Markdown, safeHref } from './Markdown';
import { fillTemplate } from './templates';

describe('Markdown без HTML', () => {
  it('не исполняет HTML из текста', () => {
    const { container } = renderWithProviders(<Markdown text={'<img src=x onerror="alert(1)"> **жирный**'} />);
    expect(container.querySelector('img')).toBeNull();
    expect(container.textContent).toContain('<img src=x');
    expect(screen.getByText('жирный').tagName).toBe('STRONG');
  });
  it('пропускает только безопасные ссылки', () => {
    expect(safeHref('javascript:alert(1)')).toBeNull();
    expect(safeHref('https://example.ru')).toBe('https://example.ru');
    renderWithProviders(<Markdown text="[плохая](javascript:alert(1)) и [хорошая](https://example.ru)" />);
    expect(screen.queryByRole('link', { name: 'плохая' })).toBeNull();
    expect(screen.getByRole('link', { name: 'хорошая' })).toHaveAttribute('rel', expect.stringContaining('noopener'));
  });
  it('рисует списки и код', () => {
    const { container } = renderWithProviders(<Markdown text={'- один\n- два\n\n`ls -la`'} />);
    expect(container.querySelectorAll('li')).toHaveLength(2);
    expect(container.querySelector('code')?.textContent).toBe('ls -la');
  });
});

describe('готовые ответы', () => {
  it('подставляет переменные и оставляет неизвестные', () => {
    const text = fillTemplate('{{requester.name}}, заявка {{ticket.number}}. {{agent.name}} {{unknown}}', {
      number: 'INC-000001',
      title: 'x',
      requester: { id: '1', name: 'Павел Соколов' },
    }, { id: '2', name: 'Анна' });
    expect(text).toBe('Павел Соколов, заявка INC-000001. Анна {{unknown}}');
  });
});
