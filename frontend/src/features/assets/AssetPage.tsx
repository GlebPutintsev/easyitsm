import { ActionIcon, Alert, Anchor, Button, Group, Modal, Select, Stack, Text, TextInput, Textarea, Title } from '@mantine/core';
import {
  IconArrowBackUp,
  IconArrowLeft,
  IconArrowsExchange,
  IconPencil,
  IconPrinter,
  IconTool,
  IconTrash,
  IconUserCheck,
  IconX,
} from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { hasRole, useMe } from '@/features/auth/api';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState } from '@/shared/ui/ListState';
import { PersonPicker } from '@/shared/ui/PersonPicker';
import {
  type Asset,
  type AssetAction,
  type AssetEvent,
  useAsset,
  useAssetAction,
  useAssetHistory,
  useAssetLicenses,
  useAssetTickets,
  useLinkTicket,
  useLocations,
  useUnlinkTicket,
} from './api';
import { AssetForm } from './AssetForm';
import { printAssetLabel } from './printLabel';
import { AssetStateBadge, Fields, SectionLabel, formatDate, formatDateTime, formatMoney } from './ui';
import classes from './assets.module.css';

/** Карточка актива (10.14): сведения, закрепление, ПО, заявки, история и действия по состоянию. */
export function AssetPage() {
  const { id } = useParams();
  const { t } = useTranslation();
  const asset = useAsset(id);
  const me = useMe();
  const canManage = hasRole(me.data, 'AST', 'ADM');
  const canLink = hasRole(me.data, 'AST', 'ADM', 'AGT', 'LEAD');

  if (!asset.data) {
    return (
      <Stack maw={1280}>
        <BackLink />
        <ListState state={asset.error ? 'error' : 'loading'} error={asset.error} onRetry={() => asset.refetch()} />
      </Stack>
    );
  }
  const a = asset.data;
  return (
    <Stack gap="md" maw={1280}>
      <BackLink />
      <Header asset={a} canManage={canManage} />
      {a.state === 'retired' && (
        <Alert color="gray" role="status">
          {t('assets.card.retired', { date: formatDate(a.retiredAt), reason: a.retireReason })}
        </Alert>
      )}
      <div className={classes.layout}>
        <Stack gap="md">
          <section className={classes.panel}>
            <Stack gap="sm">
              <SectionLabel>{t('assets.card.details')}</SectionLabel>
              <Fields
                items={[
                  [t('assets.field.kind'), t(`assets.kind.${a.kind}`)],
                  [t('assets.field.manufacturer'), a.manufacturer ?? '—'],
                  [t('assets.field.model'), a.model ?? '—'],
                  [t('assets.field.serial'), a.serial ? <span className="ei-mono">{a.serial}</span> : '—'],
                  [t('assets.field.notes'), a.notes || '—'],
                ]}
              />
            </Stack>
          </section>
          <Software assetId={a.id} />
          <Tickets asset={a} canLink={canLink && a.state !== 'retired'} />
          <History assetId={a.id} />
        </Stack>
        <Stack gap="md">
          <section className={classes.panel}>
            <Stack gap="sm">
              <SectionLabel>{t('assets.card.assignment')}</SectionLabel>
              <Fields
                compact
                items={[
                  [t('assets.field.holder'), a.holder?.name ?? t('assets.card.notIssued')],
                  [t('assets.field.location'), a.location?.name ?? '—'],
                  [
                    t('assets.field.ci'),
                    a.ci ? (a.ci.name ?? <span className="ei-mono">{t('assets.card.ciUnknown', { id: a.ci.id.slice(0, 8) })}</span>) : '—',
                  ],
                ]}
              />
            </Stack>
          </section>
          <section className={classes.panel}>
            <Stack gap="sm">
              <SectionLabel>{t('assets.card.purchase')}</SectionLabel>
              <Fields
                compact
                items={[
                  [t('assets.field.vendor'), a.vendor?.name ?? '—'],
                  [t('assets.field.contract'), a.contract ? `№ ${a.contract.number}` : '—'],
                  [t('assets.field.purchasedOn'), formatDate(a.purchasedOn)],
                  [
                    t('assets.field.warrantyTo'),
                    <span key="w">
                      {formatDate(a.warrantyTo)}
                      {a.warrantyExpired && (
                        <Text span size="xs" c="var(--ei-warn)" ml={6}>
                          {t('assets.card.warrantyExpired')}
                        </Text>
                      )}
                    </span>,
                  ],
                  [
                    t('assets.field.endOfLife'),
                    <span key="e">
                      {formatDate(a.endOfLife)}
                      {a.endOfLifeReached && (
                        <Text span size="xs" c="var(--ei-warn)" ml={6}>
                          {t('assets.card.endOfLifeReached')}
                        </Text>
                      )}
                    </span>,
                  ],
                  [t('assets.field.cost'), formatMoney(a.cost, a.currency)],
                ]}
              />
            </Stack>
          </section>
        </Stack>
      </div>
    </Stack>
  );
}

function BackLink() {
  const { t } = useTranslation();
  return (
    <Anchor component={Link} to="/agent/assets" size="sm" c="var(--ei-link)">
      <Group gap={4}>
        <IconArrowLeft size={14} />
        {t('assets.card.back')}
      </Group>
    </Anchor>
  );
}

/** Какие действия доступны в каждом состоянии (как в Asset.java на сервере). */
const ACTIONS: Record<Asset['state'], AssetAction[]> = {
  in_stock: ['issue', 'repair', 'retire'],
  in_use: ['transfer', 'return', 'repair', 'retire'],
  in_repair: ['issue', 'return', 'retire'],
  retired: [],
};

const ICONS = {
  issue: IconUserCheck,
  transfer: IconArrowsExchange,
  return: IconArrowBackUp,
  repair: IconTool,
  retire: IconTrash,
} as const;

function Header({ asset, canManage }: { asset: Asset; canManage: boolean }) {
  const { t } = useTranslation();
  const [action, setAction] = useState<AssetAction | null>(null);
  const [editing, setEditing] = useState(false);
  const actions = ACTIONS[asset.state];
  return (
    <Stack gap="xs">
      <Group gap="xs">
        <AssetStateBadge state={asset.state} />
        <Text className="ei-mono" c="var(--ei-text-2)">
          {asset.tag}
        </Text>
        <Text size="sm" c="var(--ei-text-3)">
          {t(`assets.kind.${asset.kind}`)}
        </Text>
      </Group>
      <Title order={1} size="h2">
        {asset.name}
      </Title>
      <Group gap="xs" wrap="wrap">
        {canManage &&
          actions.map((a, i) => {
            const Icon = ICONS[a];
            return (
              <Button
                key={a}
                size="sm"
                variant={i === 0 ? 'filled' : 'default'}
                color={a === 'retire' ? 'red' : undefined}
                leftSection={<Icon size={16} />}
                onClick={() => setAction(a)}
              >
                {t(`assets.action.${a}`)}
              </Button>
            );
          })}
        {canManage && asset.state !== 'retired' && (
          <Button size="sm" variant="default" leftSection={<IconPencil size={16} />} onClick={() => setEditing(true)}>
            {t('assets.card.edit')}
          </Button>
        )}
        <Button size="sm" variant="subtle" leftSection={<IconPrinter size={16} />} onClick={() => void printAssetLabel(asset)}>
          {t('assets.action.print')}
        </Button>
      </Group>
      <ActionModal asset={asset} action={action} onClose={() => setAction(null)} />
      <AssetForm opened={editing} onClose={() => setEditing(false)} asset={asset} />
    </Stack>
  );
}

function ActionModal({ asset, action, onClose }: { asset: Asset; action: AssetAction | null; onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <Modal opened={!!action} onClose={onClose} title={action ? t(`assets.action.${action}Title`) : ''}>
      {action && <ActionBody asset={asset} action={action} onClose={onClose} />}
    </Modal>
  );
}

function ActionBody({ asset, action, onClose }: { asset: Asset; action: AssetAction; onClose: () => void }) {
  const { t } = useTranslation();
  const mutation = useAssetAction(asset);
  const locations = useLocations();
  const needsHolder = action === 'issue' || action === 'transfer';
  const [holderId, setHolderId] = useState<string | null>(action === 'issue' ? asset.holder?.id ?? null : null);
  const [locationId, setLocationId] = useState<string | null>(null);
  const [comment, setComment] = useState('');
  const ready = action === 'retire' ? comment.trim().length > 0 : !needsHolder || !!holderId;

  const submit = () => {
    const body =
      action === 'retire'
        ? { reason: comment.trim() }
        : { holderId: needsHolder ? holderId ?? undefined : undefined, locationId: locationId ?? undefined, comment: comment.trim() || undefined };
    mutation.mutate({ action, body }, { onSuccess: onClose });
  };

  return (
    <Stack gap="sm">
      {action === 'retire' && (
        <Alert color="red" role="note" p="xs">
          {t('assets.action.retireWarning')}
        </Alert>
      )}
      {needsHolder && (
        <PersonPicker
          label={t('assets.field.holder')}
          value={holderId}
          onChange={setHolderId}
          exclude={action === 'transfer' && asset.holder ? [asset.holder.id] : []}
        />
      )}
      {action !== 'retire' && (
        <Select
          label={t('assets.field.location')}
          placeholder={asset.location?.name ?? undefined}
          clearable
          searchable
          value={locationId}
          onChange={setLocationId}
          data={(locations.data ?? []).filter((l) => l.active).map((l) => ({ value: l.id, label: l.name }))}
        />
      )}
      <Textarea
        label={t(action === 'retire' ? 'assets.field.reason' : 'assets.field.comment')}
        required={action === 'retire'}
        autosize
        minRows={2}
        value={comment}
        onChange={(e) => setComment(e.currentTarget.value)}
      />
      <ErrorLine error={mutation.error} />
      <Group justify="flex-end" gap="xs">
        <Button variant="default" onClick={onClose}>
          {t('assets.form.cancel')}
        </Button>
        <Button color={action === 'retire' ? 'red' : undefined} disabled={!ready} loading={mutation.isPending} onClick={submit}>
          {t(`assets.action.${action}`)}
        </Button>
      </Group>
    </Stack>
  );
}

function Software({ assetId }: { assetId: string }) {
  const { t } = useTranslation();
  const licenses = useAssetLicenses(assetId);
  return (
    <section className={classes.panel}>
      <Stack gap="sm">
        <SectionLabel>{t('assets.card.software')}</SectionLabel>
        {(licenses.data ?? []).length === 0 ? (
          <Text size="sm" className={classes.muted}>
            {t('assets.card.softwareEmpty')}
          </Text>
        ) : (
          (licenses.data ?? []).map((l) => (
            <Group key={l.id} justify="space-between">
              <Anchor component={Link} to={`/agent/assets/licenses/${l.licenseId}`} size="sm" c="var(--ei-link)">
                {l.product}
              </Anchor>
              <Text size="xs" className={classes.muted}>
                {formatDate(l.assignedAt)}
              </Text>
            </Group>
          ))
        )}
      </Stack>
    </section>
  );
}

function Tickets({ asset, canLink }: { asset: Asset; canLink: boolean }) {
  const { t } = useTranslation();
  const tickets = useAssetTickets(asset.id);
  const link = useLinkTicket(asset.id);
  const unlink = useUnlinkTicket(asset.id);
  const [number, setNumber] = useState('');
  return (
    <section className={classes.panel}>
      <Stack gap="sm">
        <SectionLabel>{t('assets.card.tickets')}</SectionLabel>
        {(tickets.data ?? []).length === 0 ? (
          <Text size="sm" className={classes.muted}>
            {t('assets.card.ticketsEmpty')}
          </Text>
        ) : (
          (tickets.data ?? []).map((tk) => (
            <Group key={tk.id} justify="space-between" wrap="nowrap">
              <Group gap="xs" wrap="nowrap" style={{ minWidth: 0 }}>
                <Anchor component={Link} to={`/agent/tickets/${tk.number}`} className="ei-mono" size="sm" c="var(--ei-link)">
                  {tk.number}
                </Anchor>
                <Text size="sm" truncate>
                  {tk.title}
                </Text>
              </Group>
              {canLink && (
                <ActionIcon
                  variant="subtle"
                  color="gray"
                  aria-label={t('assets.card.unlink', { number: tk.number })}
                  onClick={() => unlink.mutate(tk.id)}
                >
                  <IconX size={16} />
                </ActionIcon>
              )}
            </Group>
          ))
        )}
        {canLink && (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (number.trim()) link.mutate({ number: number.trim() }, { onSuccess: () => setNumber('') });
            }}
          >
            <Group gap="xs" align="flex-end">
              <TextInput
                label={t('assets.card.ticketNumber')}
                placeholder={t('assets.card.ticketNumberPlaceholder')}
                value={number}
                onChange={(e) => setNumber(e.currentTarget.value)}
                classNames={{ input: 'ei-mono' }}
                style={{ flex: 1 }}
              />
              <Button type="submit" variant="default" loading={link.isPending} disabled={!number.trim()}>
                {t('assets.card.link')}
              </Button>
            </Group>
          </form>
        )}
        <ErrorLine error={link.error ?? unlink.error} />
      </Stack>
    </section>
  );
}

function History({ assetId }: { assetId: string }) {
  const { t } = useTranslation();
  const history = useAssetHistory(assetId);
  return (
    <section className={classes.panel}>
      <Stack gap="sm">
        <SectionLabel>{t('assets.card.history')}</SectionLabel>
        <ol className={classes.timeline}>
          {(history.data ?? []).map((e) => (
            <li key={e.id}>
              <Text size="sm">{eventText(t, e)}</Text>
              {e.comment && (
                <Text size="sm" c="var(--ei-text-2)">
                  {e.comment}
                </Text>
              )}
              <Text size="xs" className={classes.muted}>
                {formatDateTime(e.at)}
                {e.actor?.name ? ` · ${e.actor.name}` : ''}
              </Text>
            </li>
          ))}
        </ol>
      </Stack>
    </section>
  );
}

function eventText(t: (key: string, opts?: Record<string, unknown>) => string, e: AssetEvent): string {
  const ticket = e.ticket?.number ?? t('assets.event.hiddenTicket');
  return t(`assets.event.${e.kind}`, {
    from: e.fromHolder?.name ?? '—',
    to: e.toHolder?.name ?? '—',
    ticket,
  });
}
