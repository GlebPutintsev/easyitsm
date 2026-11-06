import { Anchor, Badge, Button, Group, Modal, MultiSelect, NumberInput, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { IconArrowLeft, IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { usePeopleSearch } from '@/shared/api/people';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { PersonPicker } from '@/shared/ui/PersonPicker';
import { type Meeting, useCreateMeeting, useMeetings } from './api';
import classes from './changes.module.css';
import { Person } from './ChangesPage';
import { fromLocalInput } from './model';

export const MEETING_COLOR: Record<Meeting['state'], string> = {
  planned: 'blue',
  in_progress: 'orange',
  completed: 'green',
  cancelled: 'gray',
};

/** CAB (CHG-07): заседания по дате, создание нового. */
export function CabPage() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const meetings = useMeetings();
  const [creating, setCreating] = useState(false);
  const list = meetings.data ?? [];
  const state = listStateOf(meetings, list.length);
  return (
    <Stack gap="md" maw={1100}>
      <Button variant="subtle" leftSection={<IconArrowLeft size={16} />} component={Link} to="/agent/changes" w="fit-content" px={0}>
        {t('changes.title')}
      </Button>
      <Group justify="space-between" wrap="wrap">
        <div>
          <Title order={1} fz={24}>
            {t('changes.cab.title')}
          </Title>
          <Text size="sm" c="dimmed">
            {t('changes.cab.subtitle')}
          </Text>
        </div>
        <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>
          {t('changes.cab.create')}
        </Button>
      </Group>
      {state ? (
        <ListState state={state} title={state === 'empty' ? t('changes.cab.empty') : undefined} error={meetings.error} onRetry={() => void meetings.refetch()} />
      ) : (
        <div className={classes.tableScroll}>
          <Table highlightOnHover verticalSpacing="sm" miw={720}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('changes.cab.columns.title')}</Table.Th>
                <Table.Th w={180}>{t('changes.cab.columns.startsAt')}</Table.Th>
                <Table.Th w={180}>{t('changes.cab.columns.chair')}</Table.Th>
                <Table.Th w={110}>{t('changes.cab.columns.items')}</Table.Th>
                <Table.Th w={140}>{t('changes.cab.columns.state')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {list.map((m) => (
                <Table.Tr key={m.id} style={{ cursor: 'pointer' }} onClick={() => navigate(`/agent/changes/cab/${m.id}`)}>
                  <Table.Td>
                    <Anchor component={Link} to={`/agent/changes/cab/${m.id}`} fw={600} onClick={(e) => e.stopPropagation()}>
                      {m.title}
                    </Anchor>
                    {m.location && (
                      <Text size="xs" c="dimmed">
                        {m.location}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>{new Date(m.startsAt).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' })}</Table.Td>
                  <Table.Td>
                    <Person id={m.chairId} />
                  </Table.Td>
                  <Table.Td>{m.items}</Table.Td>
                  <Table.Td>
                    <Badge variant="light" color={MEETING_COLOR[m.state]}>
                      {t(`changes.cab.meetingState.${m.state}`)}
                    </Badge>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </div>
      )}
      {creating && <NewMeetingDialog onClose={() => setCreating(false)} />}
    </Stack>
  );
}

function NewMeetingDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const create = useCreateMeeting();
  const [title, setTitle] = useState(t('changes.cab.defaultTitle'));
  const [startsAt, setStartsAt] = useState('');
  const [duration, setDuration] = useState<number | string>(60);
  const [location, setLocation] = useState('');
  const [chairId, setChairId] = useState<string | null>(null);
  const [memberIds, setMemberIds] = useState<string[]>([]);
  const [search, setSearch] = useState('');
  const people = usePeopleSearch(search);
  const options = new Map<string, string>();
  for (const p of people.data ?? []) options.set(p.id, p.displayName);
  for (const id of memberIds) if (!options.has(id)) options.set(id, memberLabels[id] ?? id);
  const iso = fromLocalInput(startsAt);
  return (
    <Modal opened onClose={onClose} title={t('changes.cab.create')} centered size="lg">
      <Stack gap="sm">
        <TextInput label={t('changes.cab.columns.title')} withAsterisk value={title} onChange={(e) => setTitle(e.currentTarget.value)} />
        <Group grow>
          <TextInput type="datetime-local" label={t('changes.cab.columns.startsAt')} withAsterisk value={startsAt} onChange={(e) => setStartsAt(e.currentTarget.value)} />
          <NumberInput label={t('changes.cab.duration')} min={5} max={1440} value={duration} onChange={setDuration} suffix={` ${t('changes.duration.min')}`} />
        </Group>
        <TextInput label={t('changes.cab.location')} value={location} onChange={(e) => setLocation(e.currentTarget.value)} />
        <PersonPicker label={t('changes.cab.columns.chair')} value={chairId} onChange={setChairId} />
        <MultiSelect
          label={t('changes.cab.members')}
          searchable
          searchValue={search}
          onSearchChange={setSearch}
          filter={({ options: o }) => o}
          data={[...options].map(([value, label]) => ({ value, label }))}
          value={memberIds}
          onChange={(ids) => {
            for (const id of ids) if (options.has(id)) memberLabels[id] = options.get(id)!;
            setMemberIds(ids);
          }}
          nothingFoundMessage={search.trim().length < 2 ? t('changes.cab.typeName') : t('changes.ci.nothing')}
        />
        <ErrorLine error={create.error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('actions.cancel')}
          </Button>
          <Button
            disabled={!title.trim() || !iso}
            loading={create.isPending}
            onClick={() =>
              create.mutate(
                { title: title.trim(), startsAt: iso!, durationMinutes: Number(duration) || 60, location: location.trim() || null, chairId, memberIds },
                { onSuccess: (m) => navigate(`/agent/changes/cab/${m.id}`) },
              )
            }
          >
            {t('changes.cab.create')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

/** Имена выбранных участников, чтобы они не превращались в id, когда поиск меняется. */
const memberLabels: Record<string, string> = {};
