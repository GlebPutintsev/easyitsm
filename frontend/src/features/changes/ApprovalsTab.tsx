import { Anchor, Badge, Button, Group, Stack, Text, Textarea } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { type Approval, type Change, useChangeAction } from './api';
import classes from './changes.module.css';
import { Person } from './ChangesPage';
import { Section } from './OverviewTab';

const DECISION_COLOR: Record<Approval['decision'], string> = {
  pending: 'violet',
  approved: 'green',
  rejected: 'red',
  superseded: 'gray',
};

/** «Согласования» (CHG-04, CHG-07, CHG-12): слоты маршрута по раундам и пункты повестки CAB. */
export function ApprovalsTab({ change: c }: { change: Change }) {
  const { t, i18n } = useTranslation();
  const rounds = [...new Set(c.approvals.map((a) => a.round))].sort((a, b) => b - a);
  return (
    <Stack gap="md" maw={960}>
      {c.approvals.length === 0 && (
        <Text size="sm" c="dimmed">
          {c.type === 'standard' ? t('changes.approvals.standard') : t('changes.approvals.none')}
        </Text>
      )}
      {c.postApprovalDueAt && (
        <Text size="sm" c={c.postApprovalOverdue ? 'var(--ei-danger)' : 'dimmed'}>
          {t('changes.approvals.postDue', {
            date: new Date(c.postApprovalDueAt).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' }),
          })}
        </Text>
      )}
      {rounds.map((r) => (
        <Section key={r} title={t('changes.approvals.round', { n: r })}>
          {c.approvals
            .filter((a) => a.round === r)
            .map((a) => (
              <ApprovalRow key={a.id} change={c} approval={a} />
            ))}
        </Section>
      ))}
      {c.cab.length > 0 && (
        <Section title={t('changes.cab.title')}>
          {c.cab.map((e) => (
            <Group key={e.itemId} justify="space-between" wrap="wrap">
              <div>
                <Anchor component={Link} to={`/agent/changes/cab/${e.meetingId}`} fw={600}>
                  {e.meetingTitle}
                </Anchor>
                <Text size="xs" c="dimmed">
                  {new Date(e.startsAt).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' })} ·{' '}
                  {t(`changes.cab.meetingState.${e.meetingState}`)}
                </Text>
                {e.comment && <Text size="sm">{e.comment}</Text>}
              </div>
              <Badge variant="light" color={e.decision === 'approved' ? 'green' : e.decision === 'rejected' ? 'red' : 'violet'}>
                {t(`changes.cab.decision.${e.decision}`)}
              </Badge>
            </Group>
          ))}
        </Section>
      )}
    </Stack>
  );
}

function ApprovalRow({ change, approval: a }: { change: Change; approval: Approval }) {
  const { t, i18n } = useTranslation();
  const action = useChangeAction(change);
  const [comment, setComment] = useState('');
  const decide = (decision: 'approve' | 'reject') =>
    action.mutate({ path: `/approvals/${a.id}/decision`, body: { decision, comment: comment.trim() || undefined } });
  return (
    <div className={classes.card} style={{ padding: '12px 16px' }}>
      <Stack gap={6}>
        <Group justify="space-between" wrap="wrap">
          <Group gap="xs">
            <Text fw={600}>{t(`changes.approvals.kind.${a.kind}`)}</Text>
            {a.approverId && <Person id={a.approverId} />}
            {a.postFactum && (
              <Badge size="sm" variant="outline" color="orange">
                {t('changes.approvals.postFactum')}
              </Badge>
            )}
          </Group>
          <Badge variant="light" color={DECISION_COLOR[a.decision]}>
            {t(`changes.approvals.decision.${a.decision}`)}
          </Badge>
        </Group>
        {a.decidedAt && (
          <Group gap={6}>
            <Person id={a.decidedBy} />
            <Text size="xs" c="dimmed">
              {new Date(a.decidedAt).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' })}
            </Text>
          </Group>
        )}
        {a.comment && <Text size="sm">{a.comment}</Text>}
        {a.kind === 'cab' && a.decision === 'pending' && (
          <Text size="sm" c="dimmed">
            {t('changes.approvals.cabHint')}
          </Text>
        )}
        {a.canDecide && (
          <Stack gap={6}>
            <Textarea placeholder={t('changes.approvals.comment')} aria-label={t('changes.approvals.comment')} autosize minRows={2} value={comment} onChange={(e) => setComment(e.currentTarget.value)} />
            <ErrorLine error={action.error} />
            <Group gap="xs">
              <Button loading={action.isPending} onClick={() => decide('approve')}>
                {t('changes.approvals.approve')}
              </Button>
              <Button variant="default" c="var(--ei-danger)" disabled={!comment.trim()} title={!comment.trim() ? t('changes.approvals.rejectNeedsComment') : undefined} onClick={() => decide('reject')}>
                {t('changes.approvals.reject')}
              </Button>
            </Group>
          </Stack>
        )}
      </Stack>
    </div>
  );
}
