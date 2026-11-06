import { ActionIcon, Card, Group, SimpleGrid, Table, Text, Tooltip, UnstyledButton } from '@mantine/core';
import { useElementSize } from '@mantine/hooks';
import { IconChartLine, IconTable } from '@tabler/icons-react';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { DrillParams, ReportColumn, ReportRow, Widget } from './api';
import { formatDay, formatValue, niceMax } from './format';

/** Клик по значению: параметры строки плюс показатель колонки. */
export type OnDrill = (params: DrillParams, label: string) => void;

function drillOf(row: ReportRow, column?: ReportColumn): DrillParams | null {
  if (column?.drill) return { ...(row.drill ?? {}), metric: column.drill };
  if (row.drill?.metric) return row.drill;
  return null;
}

/** Значение, которое открывает список заявок, если пользователю он доступен. */
function DrillValue({
  children,
  params,
  label,
  onDrill,
}: {
  children: ReactNode;
  params: DrillParams | null;
  label: string;
  onDrill?: OnDrill;
}) {
  const { t } = useTranslation();
  if (!params || !onDrill) return <>{children}</>;
  return (
    <UnstyledButton
      className="ei-report-drill"
      onClick={() => onDrill(params, label)}
      aria-label={t('reports.drill.open', { label })}
    >
      {children}
    </UnstyledButton>
  );
}

export function WidgetCard({ widget, onDrill, span }: { widget: Widget; onDrill?: OnDrill; span?: boolean }) {
  const { t } = useTranslation();
  const [asTable, setAsTable] = useState(false);
  const chart = widget.kind === 'line' || widget.kind === 'bar';
  return (
    <Card withBorder padding="md" radius="md" className={span ? 'ei-report-span' : undefined}>
      <Group justify="space-between" mb="sm" wrap="nowrap">
        <Text fw={600} size="sm" component="h3" m={0}>
          {widget.title}
        </Text>
        {chart && (
          <Tooltip label={asTable ? t('reports.view.chart') : t('reports.view.table')}>
            <ActionIcon
              variant="subtle"
              color="gray"
              size="sm"
              onClick={() => setAsTable((v) => !v)}
              aria-label={asTable ? t('reports.view.chart') : t('reports.view.table')}
              aria-pressed={asTable}
            >
              {asTable ? <IconChartLine size={16} /> : <IconTable size={16} />}
            </ActionIcon>
          </Tooltip>
        )}
      </Group>
      {widget.kind === 'kpi' && <KpiGrid widget={widget} onDrill={onDrill} />}
      {widget.kind === 'line' && !asTable && <LineChart widget={widget} onDrill={onDrill} />}
      {widget.kind === 'bar' && !asTable && <BarList widget={widget} onDrill={onDrill} />}
      {(widget.kind === 'table' || (chart && asTable)) && <DataTable widget={widget} onDrill={onDrill} />}
    </Card>
  );
}

export function KpiGrid({ widget, onDrill }: { widget: Widget; onDrill?: OnDrill }) {
  const { t, i18n } = useTranslation();
  return (
    <SimpleGrid cols={{ base: 2, sm: 3, lg: widget.rows.length > 6 ? 5 : widget.rows.length }} spacing="sm">
      {widget.rows.map((row) => {
        const label = row.label ?? row.key;
        return (
          <div key={row.key} className="ei-report-kpi">
            <Text size="xs" c="var(--ei-text-3)" fw={500} lh={1.3}>
              {label}
            </Text>
            <DrillValue params={drillOf(row)} label={label} onDrill={onDrill}>
              <Text fz={26} fw={650} lh={1.2} mt={6} className="ei-report-kpi-value">
                {formatValue(row.value, row.type, i18n.language, t)}
              </Text>
            </DrillValue>
          </div>
        );
      })}
    </SimpleGrid>
  );
}

export function BarList({ widget, onDrill }: { widget: Widget; onDrill?: OnDrill }) {
  const { t, i18n } = useTranslation();
  const valueColumn = widget.columns[1];
  if (!valueColumn) return <Empty />;
  const max = Math.max(1, ...widget.rows.map((r) => Number(r[valueColumn.key] ?? 0)));
  if (widget.rows.length === 0) return <Empty />;
  return (
    <ul className="ei-report-bars">
      {widget.rows.map((row) => {
        const value = Number(row[valueColumn.key] ?? 0);
        const label = row.label ?? row.key;
        const params = drillOf(row, valueColumn);
        return (
          <li key={row.key}>
            <Text size="sm" className="ei-report-bar-label" title={label}>
              {label}
            </Text>
            <div className="ei-report-bar-track" aria-hidden>
              {value > 0 && <div className="ei-report-bar" style={{ width: `${(value / max) * 100}%` }} />}
            </div>
            <DrillValue params={value > 0 ? params : null} label={`${widget.title}: ${label}`} onDrill={onDrill}>
              <Text size="sm" fw={600} className="ei-report-bar-value">
                {formatValue(value, valueColumn.type, i18n.language, t)}
              </Text>
            </DrillValue>
          </li>
        );
      })}
    </ul>
  );
}

const HEIGHT = 220;
const PAD = { top: 12, right: 12, bottom: 28, left: 44 };

/** Линейный график с одной осью: до двух рядов, перекрестие и подсказка, клик — список заявок за точку. */
export function LineChart({ widget, onDrill }: { widget: Widget; onDrill?: OnDrill }) {
  const { t, i18n } = useTranslation();
  const { ref, width } = useElementSize();
  const [hover, setHover] = useState<number | null>(null);
  const series = widget.columns.slice(1);
  const rows = widget.rows;
  const values = rows.flatMap((r) => series.map((s) => Number(r[s.key] ?? 0)));
  const integers = series.every((s) => s.type === 'number');
  const rawMax = niceMax(Math.max(0, ...values));
  // Для счётчиков деления шкалы целые: 0, 2, 4… вместо 1,25.
  const max = integers && rawMax < 20 ? Math.max(4, Math.ceil(rawMax / 4) * 4) : rawMax;
  const plotW = Math.max(10, width - PAD.left - PAD.right);
  const plotH = HEIGHT - PAD.top - PAD.bottom;
  const x = (i: number) => PAD.left + (rows.length <= 1 ? plotW / 2 : (i / (rows.length - 1)) * plotW);
  const y = (v: number) => PAD.top + plotH - (v / max) * plotH;
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((k) => k * max);
  const labelEvery = Math.max(1, Math.ceil(rows.length / Math.max(2, Math.floor(plotW / 64))));
  const fmt = (v: unknown, type: ReportColumn['type']) => formatValue(v, type, i18n.language, t);
  const index = (clientX: number, rect: DOMRect) => {
    const rel = clientX - rect.left - PAD.left;
    const i = rows.length <= 1 ? 0 : Math.round((rel / plotW) * (rows.length - 1));
    return Math.min(rows.length - 1, Math.max(0, i));
  };
  const hovered = hover === null ? null : (rows[hover] ?? null);

  return (
    <div>
      {series.length > 1 && (
        <Group gap="md" mb={6} className="ei-report-legend">
          {series.map((s, i) => (
            <Group key={s.key} gap={6} wrap="nowrap">
              <span className="ei-report-swatch" style={{ background: `var(--ei-chart-${i + 1})` }} aria-hidden />
              <Text size="xs" c="var(--ei-text-2)">
                {s.label}
              </Text>
            </Group>
          ))}
        </Group>
      )}
      <div ref={ref} className="ei-report-line" style={{ height: HEIGHT }}>
        {width > 0 && (
          <svg
            width={width}
            height={HEIGHT}
            role="img"
            aria-label={widget.title}
            onMouseMove={(e) => setHover(index(e.clientX, e.currentTarget.getBoundingClientRect()))}
            onMouseLeave={() => setHover(null)}
            onClick={(e) => {
              if (!onDrill) return;
              const rect = e.currentTarget.getBoundingClientRect();
              const i = index(e.clientX, rect);
              const row = rows[i];
              if (!row) return;
              // Ряд, ближайший к курсору по вертикали.
              const my = e.clientY - rect.top;
              const nearest = series
                .map((s) => ({ s, d: Math.abs(y(Number(row[s.key] ?? 0)) - my) }))
                .sort((a, b) => a.d - b.d)[0]?.s;
              const params = nearest ? drillOf(row, nearest) : null;
              if (params) onDrill(params, `${nearest!.label}, ${formatDay(row.key, i18n.language)}`);
            }}
            style={{ cursor: onDrill ? 'pointer' : undefined }}
          >
            {ticks.map((v) => (
              <g key={v}>
                <line x1={PAD.left} x2={PAD.left + plotW} y1={y(v)} y2={y(v)} stroke="var(--ei-chart-grid)" strokeWidth={1} />
                <text x={PAD.left - 8} y={y(v) + 4} textAnchor="end" className="ei-report-axis">
                  {fmt(v, series[0]?.type === 'score' ? 'score' : 'number')}
                </text>
              </g>
            ))}
            {rows.map((r, i) =>
              i % labelEvery === 0 ? (
                <text key={r.key} x={x(i)} y={HEIGHT - 8} textAnchor="middle" className="ei-report-axis">
                  {formatDay(r.key, i18n.language)}
                </text>
              ) : null,
            )}
            {hover !== null && (
              <line x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={PAD.top + plotH} stroke="var(--ei-border-strong)" strokeWidth={1} />
            )}
            {series.map((s, si) => {
              const points = rows
                .map((r, i) => (r[s.key] === null || r[s.key] === undefined ? null : `${x(i)},${y(Number(r[s.key]))}`))
                .filter(Boolean)
                .join(' ');
              return (
                <g key={s.key}>
                  <polyline points={points} fill="none" stroke={`var(--ei-chart-${si + 1})`} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
                  {hover !== null && hovered && hovered[s.key] !== null && hovered[s.key] !== undefined && (
                    <circle
                      cx={x(hover)}
                      cy={y(Number(hovered[s.key]))}
                      r={4}
                      fill={`var(--ei-chart-${si + 1})`}
                      stroke="var(--ei-surface)"
                      strokeWidth={2}
                    />
                  )}
                </g>
              );
            })}
          </svg>
        )}
        {hovered && hover !== null && (
          <div
            className="ei-report-tooltip"
            style={{ left: Math.min(Math.max(x(hover) + 12, 0), Math.max(0, width - 180)), top: PAD.top }}
            role="status"
          >
            <Text size="xs" fw={600} mb={4}>
              {widget.columns[0]?.label}: {formatDay(hovered.key, i18n.language)}
            </Text>
            {series.map((s, si) => (
              <Group key={s.key} gap={6} wrap="nowrap" justify="space-between">
                <Group gap={6} wrap="nowrap">
                  <span className="ei-report-swatch" style={{ background: `var(--ei-chart-${si + 1})` }} aria-hidden />
                  <Text size="xs" c="var(--ei-text-2)">
                    {s.label}
                  </Text>
                </Group>
                <Text size="xs" fw={600}>
                  {fmt(hovered[s.key], s.type)}
                </Text>
              </Group>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

export function DataTable({ widget, onDrill }: { widget: Widget; onDrill?: OnDrill }) {
  const { t, i18n } = useTranslation();
  if (widget.rows.length === 0) return <Empty />;
  const firstIsLabel = widget.kind !== 'line';
  return (
    <Table.ScrollContainer minWidth={widget.columns.length * 110}>
      <Table striped={false} highlightOnHover verticalSpacing={6} fz="sm" className="ei-report-table">
        <Table.Thead>
          <Table.Tr>
            {widget.columns.map((c, i) => (
              <Table.Th key={c.key} ta={i === 0 ? 'left' : 'right'}>
                {c.label}
              </Table.Th>
            ))}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {widget.rows.map((row) => {
            const rowLabel = firstIsLabel ? (row.label ?? row.key) : formatDay(row.key, i18n.language);
            return (
              <Table.Tr key={row.key}>
                {widget.columns.map((c, i) => {
                  if (i === 0) {
                    return (
                      <Table.Td key={c.key} className="ei-report-cell-label">
                        {rowLabel}
                      </Table.Td>
                    );
                  }
                  const value = row[c.key];
                  const params = Number(value ?? 0) > 0 ? drillOf(row, c) : null;
                  return (
                    <Table.Td key={c.key} ta="right" className="ei-report-cell-num">
                      <DrillValue params={params} label={`${rowLabel}: ${c.label}`} onDrill={onDrill}>
                        {formatValue(value, c.type, i18n.language, t)}
                      </DrillValue>
                    </Table.Td>
                  );
                })}
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}

function Empty() {
  const { t } = useTranslation();
  return (
    <Text size="sm" c="var(--ei-text-3)" py="lg" ta="center">
      {t('reports.noData')}
    </Text>
  );
}
