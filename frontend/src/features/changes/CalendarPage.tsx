import { ActionIcon, Button, Group, SegmentedControl, Select, Stack, Text, Title, Tooltip } from '@mantine/core';
import { IconArrowLeft, IconChevronLeft, IconChevronRight, IconDownload } from '@tabler/icons-react';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { type ChangeItem, downloadGet, useCalendar } from './api';
import classes from './changes.module.css';
import { monthGrid, overlapsDay, weekGrid } from './model';

type View = 'month' | 'week';

/**
 * Календарь изменений (CHG-05): окна работ по дням, заморозки штриховкой, фильтр по риску и типу,
 * выгрузка в iCal для календаря Outlook или Thunderbird.
 */
export function CalendarPage() {
  const { t, i18n } = useTranslation();
  const [view, setView] = useState<View>('month');
  const [anchor, setAnchor] = useState(() => new Date());
  const [risk, setRisk] = useState<string | null>(null);
  const [type, setType] = useState<string | null>(null);
  const grid = useMemo(() => (view === 'month' ? monthGrid(anchor) : weekGrid(anchor)), [view, anchor]);
  const from = grid.from.toISOString();
  const to = grid.to.toISOString();
  const calendar = useCalendar(from, to);
  const changes = (calendar.data?.changes ?? []).filter((c) => (!risk || c.riskLevel === risk) && (!type || c.type === type));
  const freezes = calendar.data?.freezes ?? [];
  const today = new Date().toDateString();

  const shift = (dir: -1 | 1) => {
    const next = new Date(anchor);
    if (view === 'month') next.setMonth(anchor.getMonth() + dir, 1);
    else next.setDate(anchor.getDate() + dir * 7);
    setAnchor(next);
  };
  const heading =
    view === 'month'
      ? anchor.toLocaleDateString(i18n.language, { month: 'long', year: 'numeric' })
      : `${grid.days[0]!.toLocaleDateString(i18n.language, { day: 'numeric', month: 'short' })} – ${grid.days[6]!.toLocaleDateString(i18n.language, { day: 'numeric', month: 'short', year: 'numeric' })}`;
  const weekdays = grid.days.slice(0, 7).map((d) => d.toLocaleDateString(i18n.language, { weekday: 'short' }));

  return (
    <Stack gap="md" maw={1400}>
      <Button variant="subtle" leftSection={<IconArrowLeft size={16} />} component={Link} to="/agent/changes" w="fit-content" px={0}>
        {t('changes.title')}
      </Button>
      <Group justify="space-between" wrap="wrap">
        <Title order={1} fz={24}>
          {t('changes.calendar.title')}
        </Title>
        <Button
          variant="default"
          leftSection={<IconDownload size={16} />}
          onClick={() => void downloadGet(`/changes/calendar.ics?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`, 'changes.ics')}
        >
          {t('changes.calendar.ical')}
        </Button>
      </Group>
      <Group justify="space-between" wrap="wrap">
        <Group gap="xs">
          <ActionIcon variant="default" size="lg" aria-label={t('changes.calendar.prev')} onClick={() => shift(-1)}>
            <IconChevronLeft size={18} />
          </ActionIcon>
          <Button variant="default" onClick={() => setAnchor(new Date())}>
            {t('changes.calendar.today')}
          </Button>
          <ActionIcon variant="default" size="lg" aria-label={t('changes.calendar.next')} onClick={() => shift(1)}>
            <IconChevronRight size={18} />
          </ActionIcon>
          <Text fw={700} fz={18} tt="capitalize" ml="xs" aria-live="polite">
            {heading}
          </Text>
        </Group>
        <Group gap="xs">
          <Select placeholder={t('changes.filters.risk')} aria-label={t('changes.filters.risk')} clearable value={risk} onChange={setRisk} w={150}
            data={(['low', 'medium', 'high'] as const).map((v) => ({ value: v, label: t(`changes.risk.level.${v}`) }))} />
          <Select placeholder={t('changes.filters.type')} aria-label={t('changes.filters.type')} clearable value={type} onChange={setType} w={170}
            data={(['standard', 'normal', 'emergency'] as const).map((v) => ({ value: v, label: t(`changes.type.${v}`) }))} />
          <SegmentedControl value={view} onChange={(v) => setView(v as View)} data={[{ value: 'month', label: t('changes.calendar.month') }, { value: 'week', label: t('changes.calendar.week') }]} />
        </Group>
      </Group>
      <ErrorLine error={calendar.error} />
      <div className={`${classes.calendar} ${view === 'week' ? classes.week : ''}`} aria-busy={calendar.isPending}>
        {weekdays.map((d) => (
          <div key={d} className={classes.dayHead}>
            {d}
          </div>
        ))}
        {grid.days.map((day) => {
          const other = view === 'month' && day.getMonth() !== anchor.getMonth();
          const dayFreezes = freezes.filter((f) => overlapsDay(f.startsAt, f.endsAt, day));
          const dayChanges = changes.filter((c) => overlapsDay(c.windowStart, c.windowEnd, day));
          const limit = view === 'month' ? 3 : 20;
          return (
            <div
              key={day.toISOString()}
              className={`${classes.day} ${other ? classes.dayOther : ''} ${day.toDateString() === today ? classes.dayToday : ''}`}
            >
              <span className={classes.dayNumber}>{day.getDate()}</span>
              {dayFreezes.map((f) => (
                <Tooltip key={f.id} label={f.reason || f.title} withinPortal>
                  <div className={classes.freezeBar}>
                    {t('changes.calendar.freeze')}: {f.title}
                  </div>
                </Tooltip>
              ))}
              {dayChanges.slice(0, limit).map((c) => (
                <CalendarEvent key={c.id} change={c} />
              ))}
              {dayChanges.length > limit && (
                <Text size="xs" c="dimmed">
                  {t('changes.calendar.more', { n: dayChanges.length - limit })}
                </Text>
              )}
            </div>
          );
        })}
      </div>
      <Group gap="md">
        <Legend />
      </Group>
    </Stack>
  );
}

function CalendarEvent({ change: c }: { change: ChangeItem }) {
  const { t, i18n } = useTranslation();
  const time = c.windowStart ? new Date(c.windowStart).toLocaleTimeString(i18n.language, { timeStyle: 'short' }) : '';
  const label = `${c.number} ${c.title} · ${t(`changes.state.${c.state}`)}${c.riskLevel ? ` · ${t(`changes.risk.level.${c.riskLevel}`)}` : ''}`;
  return (
    <Link to={`/agent/changes/${c.id}`} className={classes.event} data-risk={c.riskLevel ?? undefined} data-type={c.type} data-state={c.state} title={label} aria-label={label}>
      {time} {c.number} {c.title}
    </Link>
  );
}

function Legend() {
  const { t } = useTranslation();
  return (
    <Text size="xs" c="dimmed">
      {t('changes.calendar.legend')}
    </Text>
  );
}
