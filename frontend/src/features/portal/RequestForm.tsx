import {
  Alert,
  Button,
  Checkbox,
  Code,
  Group,
  Modal,
  SegmentedControl,
  Skeleton,
  Stack,
  Text,
  TextInput,
  Textarea,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconAlertCircle, IconCircleCheck, IconClock, IconLock, IconShieldCheck } from '@tabler/icons-react';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { PersonPicker } from '@/shared/ui/PersonPicker';
import { ApiError, newIdempotencyKey } from '@/shared/api/client';
import { type ItemDetail, type OrderResult, useOrder } from '@/features/catalog/api';
import { DynamicForm } from '@/features/catalog/form/DynamicForm';
import { initialValues, validateForm } from '@/features/catalog/form/validate';
import type { FieldErrors, FormValues } from '@/features/catalog/form/types';
import { ServiceIcon } from '@/features/catalog/icons';
import { leadTime } from '@/features/catalog/ItemTile';
import { clearDraft, loadDraft, saveDraft, useAutosaveDraft } from './drafts';
import { ArticleSuggestions } from '@/features/knowledge';
import { type SecretFinding, useCreateTicket } from '@/features/tickets/api';
import { scanSecrets } from './requester';
import './portal.css';

type Impact = 'only_me' | 'department' | 'everyone';

interface Draft {
  forOther: boolean;
  forUserId: string | null;
  title: string;
  description: string;
  impact: Impact;
  values: FormValues;
}

interface Created {
  ticketId: string;
  number: string;
  expectedBy: string | null;
  awaitingApproval: boolean;
}

const IMPACT_LEVEL = { only_me: 'low', department: 'medium', everyone: 'high' } as const;

/**
 * Форма обращения (10.5). С услугой каталога — заказ через /catalog/items/{id}/order с полями услуги,
 * без услуги — инцидент «Сообщить о проблеме» через /tickets.
 */
export function RequestForm({ item }: { item?: ItemDetail }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const kind = item?.kind ?? 'incident';
  const draftKey = item ? `catalog.${item.id}` : 'incident';
  const schema = useMemo(() => item?.form ?? { fields: [] }, [item]);

  const [draft, setDraft] = useState<Draft>(() => {
    const saved = loadDraft<Draft>(draftKey);
    return (
      saved ?? {
        forOther: false,
        forUserId: null,
        title: item && kind === 'request' ? item.name : '',
        description: '',
        impact: 'only_me',
        values: initialValues(schema),
      }
    );
  });
  const [errors, setErrors] = useState<FieldErrors>({});
  const [serverError, setServerError] = useState<ApiError | null>(null);
  const [secrets, setSecrets] = useState<{ findings: SecretFinding[]; masked: string } | null>(null);
  const [created, setCreated] = useState<Created | null>(null);
  const [idemKey, setIdemKey] = useState(newIdempotencyKey);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const order = useOrder(item?.id ?? '', idemKey);
  const createTicket = useCreateTicket();
  const pending = order.isPending || createTicket.isPending;
  const dirty = !!(draft.description.trim() || Object.keys(draft.values).length || (draft.title && draft.title !== item?.name));

  useAutosaveDraft(draftKey, draft, !created);

  const update = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((d) => ({ ...d, [key]: value }));

  const validate = (): FormValues | null => {
    const e: FieldErrors = {};
    const title = draft.title.trim();
    if (title.length < 5 || title.length > 255) e.title = title ? 'length' : 'required';
    if (kind === 'incident' && !draft.description.trim()) e.description = 'required';
    if (draft.forOther && !draft.forUserId) e.forUser = 'required';
    const form = validateForm(schema, draft.values);
    setErrors({ ...e, ...form.errors });
    return Object.keys(e).length || Object.keys(form.errors).length ? null : form.values;
  };

  const submit = async (maskSecrets = false) => {
    setServerError(null);
    const values = validate();
    if (!values) return;
    let description = draft.description.trim();
    if (!maskSecrets && !secrets) {
      const scan = await scanSecrets(description);
      if (scan?.findings.length) {
        setSecrets(scan);
        return;
      }
    }
    if (maskSecrets && secrets) description = secrets.masked;
    setSecrets(null);
    const forUserId = draft.forOther ? draft.forUserId : null;
    try {
      if (item) {
        const r: OrderResult = await order.mutateAsync({
          forUserId,
          title: draft.title.trim(),
          description,
          impact: draft.impact,
          values,
        });
        setCreated({ ticketId: r.ticketId, number: r.number, expectedBy: r.expectedBy, awaitingApproval: r.awaitingApproval });
      } else {
        const r = await createTicket.mutateAsync({
          key: idemKey,
          input: {
            type: 'incident',
            title: draft.title.trim(),
            description,
            impact: IMPACT_LEVEL[draft.impact],
            createdForId: forUserId ?? undefined,
            channel: 'portal',
            secrets: maskSecrets ? 'mask' : 'warn',
          },
        });
        setCreated({ ticketId: r.id, number: r.number, expectedBy: r.dueAt ?? r.slaDueAt, awaitingApproval: false });
      }
      clearDraft(draftKey);
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.code === 'form_invalid' && e.problem.errors) setErrors(e.problem.errors);
        else if (e.code === 'secrets_detected') setSecrets({ findings: (e.problem.findings as SecretFinding[]) ?? [], masked: description });
        else setServerError(e);
      } else {
        setServerError(new ApiError({ status: 0, detail: t('states.errorText') }));
      }
    }
  };

  const leave = () => {
    clearDraft(draftKey);
    navigate(item ? '/portal/catalog' : '/portal');
  };
  // Отмена с введённым текстом требует подтверждения (10.5).
  const cancel = () => (dirty ? setConfirmCancel(true) : leave());

  if (created) {
    return (
      <Success
        created={created}
        onMore={() => {
          setCreated(null);
          setIdemKey(newIdempotencyKey());
          setDraft({ forOther: false, forUserId: null, title: item && kind === 'request' ? item.name : '', description: '', impact: 'only_me', values: initialValues(schema) });
          setErrors({});
        }}
      />
    );
  }

  return (
    <form
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
    <div className="ep-page" data-width="narrow">
      <nav className="ep-crumbs" aria-label={t('portal.breadcrumbs')}>
        <Link to="/portal">{t('nav.home')}</Link> /{' '}
        {item ? (
          <>
            <Link to="/portal/catalog">{t('catalog.title')}</Link> / {item.name}
          </>
        ) : (
          t('portal.reportProblem')
        )}
      </nav>
      <Group gap="md" wrap="nowrap" align="flex-start">
        <ServiceIcon name={item?.icon} kind={kind} size={48} />
        <Stack gap={4}>
          <h1 className="ep-h1">{item?.name ?? t('portal.reportProblem')}</h1>
          {item?.summary && <p className="ep-lead">{item.summary}</p>}
          <Group gap={6}>
            {item?.leadTimeHours != null && (
              <span className="ep-chip">
                <IconClock size={12} style={{ marginRight: 4 }} aria-hidden="true" />
                {t('request.leadTime', { time: leadTime(t, item.leadTimeHours) })}
              </span>
            )}
            {item?.approvalFlowId && (
              <span className="ep-chip" data-tone="wait">
                <IconShieldCheck size={12} style={{ marginRight: 4 }} aria-hidden="true" />
                {t('request.needsApproval')}
              </span>
            )}
          </Group>
        </Stack>
      </Group>
      <div className="ep-card" style={{ padding: 24 }}>
      <Stack gap="lg">
      {item?.description && (
        <p className="ep-msg-text" style={{ color: 'var(--ei-text-2)' }}>
          {item.description}
        </p>
      )}

      {(!item || item.allowOnBehalf) && (
        <Stack gap="xs">
          <Checkbox
            label={t('request.forOther')}
            checked={draft.forOther}
            onChange={(e) => update('forOther', e.currentTarget.checked)}
          />
          {draft.forOther && (
            <PersonPicker
              label={t('request.forWhom')}
              description={t('request.forWhomHint')}
              value={draft.forUserId}
              onChange={(id) => update('forUserId', id)}
              error={errors.forUser ? t('catalog.form.errors.required') : undefined}
            />
          )}
        </Stack>
      )}

      <TextInput
        label={t('request.title')}
        required
        maxLength={255}
        value={draft.title}
        onChange={(e) => update('title', e.currentTarget.value)}
        error={errors.title ? t(errors.title === 'required' ? 'catalog.form.errors.required' : 'request.titleLength') : undefined}
      />
      <ArticleSuggestions text={draft.title} />

      <Textarea
        label={t('request.description')}
        description={kind === 'incident' ? t('request.descriptionHint') : undefined}
        required={kind === 'incident'}
        autosize
        minRows={4}
        maxRows={16}
        value={draft.description}
        onChange={(e) => update('description', e.currentTarget.value)}
        error={errors.description ? t('catalog.form.errors.required') : undefined}
      />

      {kind === 'incident' && (
        <Stack gap={4}>
          <Text size="sm" fw={500} id="impact-label">
            {t('request.impact')}
          </Text>
          <SegmentedControl
            aria-labelledby="impact-label"
            value={draft.impact}
            onChange={(v) => update('impact', v as Impact)}
            data={(['only_me', 'department', 'everyone'] as const).map((v) => ({ value: v, label: t(`request.impacts.${v}`) }))}
          />
        </Stack>
      )}

      {schema.fields.length > 0 && (
        <div style={{ borderTop: '1px solid var(--ei-border)', paddingTop: 16 }}>
          <DynamicForm
            schema={schema}
            values={draft.values}
            errors={errors}
            disabled={pending}
            onChange={(key, value) => setDraft((d) => ({ ...d, values: { ...d.values, [key]: value } }))}
          />
        </div>
      )}

      {secrets && (
        <Alert color="orange" icon={<IconLock />} title={t('request.secretsTitle')} role="alert">
          <Stack gap="xs">
            <Text size="sm">{t('request.secretsText', { count: secrets.findings.length })}</Text>
            <Group gap="xs">
              {secrets.findings.map((f, i) => (
                <Code key={i}>{t(`request.secretKinds.${f.kind}`, { defaultValue: f.kind })}</Code>
              ))}
            </Group>
            <Group gap="xs">
              <Button size="xs" color="orange" onClick={() => void submit(true)} loading={pending}>
                {t('request.maskAndSend')}
              </Button>
              <Button size="xs" variant="subtle" onClick={() => setSecrets(null)}>
                {t('request.editText')}
              </Button>
            </Group>
          </Stack>
        </Alert>
      )}

      {serverError && (
        <Alert color="red" icon={<IconAlertCircle />} title={t('request.sendFailed')} role="alert">
          <Text size="sm">{serverError.message}</Text>
          {serverError.traceId && (
            <Text size="xs" c="dimmed" mt={4}>
              {t('states.traceId')}: <Code>{serverError.traceId}</Code>
            </Text>
          )}
        </Alert>
      )}

      <Modal opened={confirmCancel} onClose={() => setConfirmCancel(false)} title={t('request.cancelTitle')} centered>
        <Text size="sm">{t('request.cancelText')}</Text>
        <Group justify="flex-end" mt="md">
          <Button variant="default" onClick={() => setConfirmCancel(false)}>
            {t('request.keepEditing')}
          </Button>
          <Button color="red" onClick={leave}>
            {t('request.cancelConfirm')}
          </Button>
        </Group>
      </Modal>

      <Group justify="space-between">
        <Button variant="subtle" color="gray" onClick={cancel} disabled={pending}>
          {t('request.cancel')}
        </Button>
        <Group gap="xs">
          <Button
            variant="default"
            disabled={pending}
            onClick={() => {
              const ok = saveDraft(draftKey, draft);
              notifications.show({ message: ok ? t('request.draftSaved') : t('request.draftUnavailable'), color: ok ? 'teal' : 'gray' });
            }}
          >
            {t('request.saveDraft')}
          </Button>
          <Button type="submit" loading={pending} disabled={!!secrets}>
            {t('request.submit')}
          </Button>
        </Group>
      </Group>
      </Stack>
      </div>
    </div>
    </form>
  );
}

function Success({ created, onMore }: { created: Created; onMore: () => void }) {
  const { t, i18n } = useTranslation();
  const fmt = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'long', timeStyle: 'short' });
  return (
    <Stack maw={560} mx="auto" py={48} px="md" align="center" gap="md" ta="center" role="status">
      <span className="ep-icon" style={{ width: 64, height: 64, borderRadius: 999, background: 'var(--ei-ok-soft)', color: 'var(--ei-ok)' }} aria-hidden="true">
        <IconCircleCheck size={40} />
      </span>
      <h1 className="ep-h1">{t('request.created', { number: created.number })}</h1>
      {created.awaitingApproval && <Text>{t('request.createdApproval')}</Text>}
      {created.expectedBy && <Text c="dimmed">{t('request.expectedBy', { date: fmt.format(new Date(created.expectedBy)) })}</Text>}
      <Group>
        <Button component={Link} to={`/portal/tickets/${created.number}`}>
          {t('request.open')}
        </Button>
        <Button variant="default" onClick={onMore}>
          {t('request.more')}
        </Button>
      </Group>
    </Stack>
  );
}

export function RequestFormSkeleton() {
  return (
    <div className="ep-page" data-width="narrow" aria-busy="true">
      <Skeleton height={48} width="60%" />
      <Skeleton height={36} />
      <Skeleton height={120} />
      <Skeleton height={36} />
    </div>
  );
}
