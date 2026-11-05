import { Badge, Button, CloseButton, Group, MultiSelect, Popover, Stack, TextInput } from '@mantine/core';
import { useDebouncedCallback } from '@mantine/hooks';
import { IconFilter, IconSearch, IconX } from '@tabler/icons-react';
import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { Directory, TicketFilter } from '../api/model';
import { filterCount, LIST_KEYS, type ListFilterKey } from './filters';

interface Props {
  filter: TicketFilter;
  directory: Directory | undefined;
  onChange: (filter: TicketFilter) => void;
}

const STATUS = ['new', 'approval', 'assigned', 'in_progress', 'pending', 'resolved', 'closed', 'cancelled'];
const PRIORITY = ['P1', 'P2', 'P3', 'P4'];
const TYPE = ['incident', 'request', 'problem', 'change', 'task'];
const SLA = ['breached', 'at_risk'];

/** Поиск, кнопка «Фильтр» и чипы выбранных условий (10.6). */
export function FilterBar({ filter, directory, onChange }: Props) {
  const { t } = useTranslation();
  const [q, setQ] = useState(filter.q ?? '');
  const searchRef = useRef<HTMLInputElement>(null);
  const pushQ = useDebouncedCallback((value: string) => onChange({ ...filter, q: value || undefined }), 300);

  // Поиск меняется и снаружи (сброс фильтров, переход по ссылке): поле подхватывает новое значение.
  const [seenQ, setSeenQ] = useState(filter.q);
  if (seenQ !== filter.q) {
    setSeenQ(filter.q);
    setQ(filter.q ?? '');
  }

  const options = useFilterOptions(directory);
  const set = (key: ListFilterKey, value: string[]) => onChange({ ...filter, [key]: value.length ? value : undefined });
  const count = filterCount(filter);

  return (
    <Stack gap={8}>
      <Group gap="xs" wrap="wrap">
        <TextInput
          ref={searchRef}
          id="queue-search"
          value={q}
          onChange={(e) => {
            setQ(e.currentTarget.value);
            pushQ(e.currentTarget.value);
          }}
          onKeyDown={(e) => {
            if (e.key === 'Escape') {
              setQ('');
              onChange({ ...filter, q: undefined });
              searchRef.current?.blur();
            }
          }}
          placeholder={t('workspace.queue.searchPlaceholder')}
          aria-label={t('workspace.queue.searchPlaceholder')}
          leftSection={<IconSearch size={16} />}
          rightSection={q ? <CloseButton size="sm" aria-label={t('workspace.actions.clear')} onClick={() => { setQ(''); onChange({ ...filter, q: undefined }); }} /> : null}
          style={{ flex: '1 1 240px', maxWidth: 380 }}
        />
        <Popover width={340} position="bottom-start" shadow="md" trapFocus>
          <Popover.Target>
            <Button
              variant={count ? 'light' : 'default'}
              leftSection={<IconFilter size={16} />}
              rightSection={count ? <Badge size="xs" circle>{count}</Badge> : null}
            >
              {t('workspace.queue.filter')}
            </Button>
          </Popover.Target>
          <Popover.Dropdown>
            <Stack gap="sm">
              {LIST_KEYS.map((key) => (
                <MultiSelect
                  key={key}
                  label={t(`workspace.filter.${key}`)}
                  data={options[key]}
                  value={filter[key] ?? []}
                  onChange={(v) => set(key, v)}
                  searchable
                  clearable
                  comboboxProps={{ withinPortal: false }}
                  placeholder={filter[key]?.length ? undefined : t('workspace.filter.any')}
                />
              ))}
              {count > 0 && (
                <Button variant="subtle" size="xs" leftSection={<IconX size={14} />} onClick={() => onChange({ q: filter.q })}>
                  {t('actions.resetFilters')}
                </Button>
              )}
            </Stack>
          </Popover.Dropdown>
        </Popover>
        {LIST_KEYS.filter((key) => filter[key]?.length).map((key) => {
          const values = filter[key] ?? [];
          const labels = values.map((value) => options[key].find((o) => o.value === value)?.label ?? value);
          const shown = labels.slice(0, 2).join(', ') + (labels.length > 2 ? ` +${labels.length - 2}` : '');
          return (
            <Popover key={key} position="bottom-start" shadow="md" width={300}>
              <Popover.Target>
                <Badge
                  component="button"
                  type="button"
                  variant="outline"
                  color="gray"
                  size="lg"
                  radius="sm"
                  style={{ textTransform: 'none', fontWeight: 500, cursor: 'pointer' }}
                  title={labels.join(', ')}
                  rightSection={
                    <CloseButton
                      size="xs"
                      component="span"
                      aria-label={t('workspace.filter.remove', { value: t(`workspace.filter.${key}`) })}
                      onClick={(e) => {
                        e.stopPropagation();
                        set(key, []);
                      }}
                    />
                  }
                >
                  {t(`workspace.filter.${key}`)}: {shown}
                </Badge>
              </Popover.Target>
              <Popover.Dropdown>
                <MultiSelect
                  label={t(`workspace.filter.${key}`)}
                  data={options[key]}
                  value={values}
                  onChange={(v) => set(key, v)}
                  searchable
                  comboboxProps={{ withinPortal: false }}
                />
              </Popover.Dropdown>
            </Popover>
          );
        })}
      </Group>
    </Stack>
  );
}

function useFilterOptions(directory: Directory | undefined): Record<ListFilterKey, { value: string; label: string }[]> {
  const { t } = useTranslation();
  return {
    status: STATUS.map((v) => ({ value: v, label: t(`workspace.status.${v}`) })),
    priority: PRIORITY.map((v) => ({ value: v, label: `${v} · ${t(`workspace.priority.${v}`)}` })),
    type: TYPE.map((v) => ({ value: v, label: t(`workspace.type.${v}`) })),
    groupId: (directory?.groups ?? []).map((g) => ({ value: g.id, label: g.name })),
    assignee: [
      { value: 'me', label: t('workspace.filter.me') },
      { value: 'none', label: t('workspace.unassigned') },
      ...(directory?.agents ?? []).filter((a) => a.id !== directory?.me.id).map((a) => ({ value: a.id, label: a.name })),
    ],
    sla: SLA.map((v) => ({ value: v, label: t(`workspace.filter.slaValue.${v}`) })),
    serviceId: (directory?.services ?? []).map((s) => ({ value: s.id, label: s.name })),
  };
}
