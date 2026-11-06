import { Alert, Button, Group, Modal, MultiSelect, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { IconFlame } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { hasRole, useMe } from '@/features/auth/api';
import { useDirectory } from '@/features/agent/api/hooks';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { PersonPicker } from '@/shared/ui/PersonPicker';
import { majorApi, useMajorAction, useMajorForTicket } from './api';
import classes from './major.module.css';

const SUPPORT = ['AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'CFG', 'AST'];

export interface DeclareTicket {
  id: string;
  number: string;
  title: string;
  serviceId: string | null;
}

/**
 * Кнопка в шапке карточки инцидента (MIM-01): «Объявить крупным» с красной рамкой, а если крупный инцидент
 * уже объявлен — переход в командный центр.
 */
export function MajorTicketAction({ ticket, closed }: { ticket: DeclareTicket; closed: boolean }) {
  const { t } = useTranslation();
  const me = useMe().data;
  const major = useMajorForTicket(ticket.id);
  const [open, setOpen] = useState(false);
  const current = major.data && ['requested', 'active', 'restored'].includes(major.data.state) ? major.data : null;
  if (current) {
    return (
      <Button component={Link} to={`/agent/major/${current.id}`} className={classes.declare} variant="default" leftSection={<IconFlame size={16} />}>
        {t(current.state === 'requested' ? 'major.ticket.requested' : 'major.ticket.open')}
      </Button>
    );
  }
  if (closed || major.isPending || !hasRole(me, ...SUPPORT)) return null;
  return (
    <>
      <Button className={classes.declare} variant="default" leftSection={<IconFlame size={16} />} onClick={() => setOpen(true)} visibleFrom="sm">
        {t('major.declare.button')}
      </Button>
      <DeclareDialog opened={open} onClose={() => setOpen(false)} ticket={ticket} />
    </>
  );
}

export function DeclareDialog({ opened, onClose, ticket }: { opened: boolean; onClose: () => void; ticket: DeclareTicket }) {
  const { t } = useTranslation();
  const me = useMe().data;
  const navigate = useNavigate();
  const directory = useDirectory().data;
  const direct = hasRole(me, 'LEAD', 'MIM');
  const [reason, setReason] = useState('');
  const [title, setTitle] = useState(ticket.title);
  const [managerId, setManagerId] = useState<string | null>(direct ? (me?.id ?? null) : null);
  const [serviceIds, setServiceIds] = useState<string[]>(ticket.serviceId ? [ticket.serviceId] : []);
  const declare = useMajorAction(majorApi.declare);
  const submit = () =>
    declare.mutate(
      { ticketId: ticket.id, reason: reason.trim(), managerId, serviceIds, publicTitle: title.trim() || undefined },
      { onSuccess: (m) => navigate(`/agent/major/${m.id}`) },
    );
  return (
    <Modal opened={opened} onClose={onClose} title={t('major.declare.title', { number: ticket.number })} size="lg">
      <Stack gap="sm">
        <Alert color={direct ? 'red' : 'yellow'} variant="light">
          {t(direct ? 'major.declare.directHint' : 'major.declare.requestHint')}
        </Alert>
        <Textarea
          label={t('major.declare.reason')}
          description={t('major.declare.reasonHint')}
          value={reason}
          onChange={(e) => setReason(e.currentTarget.value)}
          autosize
          minRows={3}
          required
          data-autofocus
        />
        <TextInput
          label={t('major.fields.publicTitle')}
          description={t('major.fields.publicTitleHint')}
          value={title}
          onChange={(e) => setTitle(e.currentTarget.value)}
          maxLength={255}
        />
        <PersonPicker label={t('major.declare.manager')} description={t('major.declare.managerHint')} value={managerId} onChange={setManagerId} />
        <MultiSelect
          label={t('major.fields.services')}
          data={(directory?.services ?? []).map((s) => ({ value: s.id, label: s.name }))}
          value={serviceIds}
          onChange={setServiceIds}
          searchable
          clearable
        />
        {declare.error && <ErrorLine error={declare.error} />}
        <Group justify="space-between">
          <Text size="xs" c="dimmed">
            {t('major.declare.p1')}
          </Text>
          <Group gap="xs">
            <Button variant="default" onClick={onClose}>
              {t('actions.cancel')}
            </Button>
            <Button color="red" onClick={submit} loading={declare.isPending} disabled={!reason.trim()} leftSection={<IconFlame size={16} />}>
              {t(direct ? 'major.declare.submit' : 'major.declare.request')}
            </Button>
          </Group>
        </Group>
      </Stack>
    </Modal>
  );
}
