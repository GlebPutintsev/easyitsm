import type { TicketSummary } from '../api/model';

/**
 * Значение ячейки CSV с защитой от формул (RPT-04): строка, начинающаяся с = + - @ или табуляции,
 * открывается в Excel как формула, поэтому перед ней ставится апостроф.
 */
export function csvCell(value: string): string {
  const safe = /^[=+\-@\t\r]/.test(value) ? `'${value}` : value;
  return /[",;\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
}

export function ticketsToCsv(rows: TicketSummary[], headers: string[]): string {
  const lines = [headers.map(csvCell).join(';')];
  for (const r of rows) {
    lines.push(
      [r.number, r.priority, r.title, r.status.name, r.requester.name, r.assignee?.name ?? '', r.group?.name ?? '', r.createdAt, r.updatedAt]
        .map(csvCell)
        .join(';'),
    );
  }
  // BOM, чтобы Excel правильно открыл кириллицу.
  return `\uFEFF${lines.join('\r\n')}`;
}

export function downloadCsv(filename: string, content: string) {
  const blob = new Blob([content], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
