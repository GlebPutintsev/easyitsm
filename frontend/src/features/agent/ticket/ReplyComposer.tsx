import {
  ActionIcon,
  Alert,
  Box,
  Button,
  Group,
  Kbd,
  Menu,
  Modal,
  Paper,
  SegmentedControl,
  Stack,
  Text,
  Textarea,
  Tooltip,
  UnstyledButton,
} from '@mantine/core';
import { useLocalStorage } from '@mantine/hooks';
import {
  IconAt,
  IconBold,
  IconChevronDown,
  IconCode,
  IconItalic,
  IconLink,
  IconList,
  IconLock,
  IconSend,
  IconShieldExclamation,
  IconTemplate,
  IconWorld,
} from '@tabler/icons-react';
import { forwardRef, useImperativeHandle, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/shared/api/client';
import { ticketsApi } from '../api';
import { useAddActivity, useDirectory, useTemplates } from '../api/hooks';
import type { SecretFinding, SecretsPolicy, Ticket } from '../api/model';
import { fillTemplate } from '../ui/templates';
import { showError } from '../ui/undoToast';

export type ComposerMode = 'reply' | 'note';

export interface ReplyComposerHandle {
  focus: (mode?: ComposerMode) => void;
}

interface Props {
  ticket: Ticket;
  /** «Отправить и решить»: открыть диалог решения после отправки. */
  onSendAndResolve?: () => void;
}

/**
 * Редактор ответа (10.7). По умолчанию внутренняя заметка, цвет фона показывает режим,
 * публичный ответ помечен получателями. Черновик хранится локально (UI-05), Ctrl+Enter отправляет,
 * найденный секрет показывает предупреждение (TCK-17).
 */
export const ReplyComposer = forwardRef<ReplyComposerHandle, Props>(function ReplyComposer({ ticket, onSendAndResolve }, ref) {
  const { t } = useTranslation();
  const canNote = ticket.permissions.internalNotes;
  const [mode, setMode] = useState<ComposerMode>(canNote ? 'note' : 'reply');
  // Черновики ответа и заметки хранятся раздельно: переключение режима не теряет текст (UI-05).
  const [replyDraft, setReplyDraft] = useLocalStorage<string>({ key: `easyitsm.draft.${ticket.id}.reply`, defaultValue: '', getInitialValueInEffect: false });
  const [noteDraft, setNoteDraft] = useLocalStorage<string>({ key: `easyitsm.draft.${ticket.id}.note`, defaultValue: '', getInitialValueInEffect: false });
  const text = mode === 'reply' ? replyDraft : noteDraft;
  const setText = mode === 'reply' ? setReplyDraft : setNoteDraft;
  const area = useRef<HTMLTextAreaElement>(null);
  const directory = useDirectory().data;
  const templates = useTemplates().data ?? [];
  const add = useAddActivity(ticket);
  const [secrets, setSecrets] = useState<{ findings: SecretFinding[]; masked: string; resolve: boolean } | null>(null);
  const [mention, setMention] = useState<{ query: string; start: number; index: number } | null>(null);
  const idempotencyKey = useRef(crypto.randomUUID());

  useImperativeHandle(ref, () => ({
    focus: (m) => {
      if (m && (m === 'reply' || canNote)) setMode(m);
      requestAnimationFrame(() => area.current?.focus());
    },
  }));

  const mentionable = useMemo(
    () =>
      (directory?.agents ?? []).filter(
        (a) => !mention || a.name.toLowerCase().split(' ').some((part) => part.startsWith(mention.query.toLowerCase())),
      ).slice(0, 6),
    [directory, mention],
  );

  const recipients = [ticket.requester, ...ticket.watchers.filter((w) => w.id !== ticket.requester.id)];

  // ——— вставка разметки ———
  const wrap = (before: string, after = before, placeholder = '') => {
    const el = area.current;
    if (!el) return;
    const { selectionStart: s, selectionEnd: e } = el;
    const selected = text.slice(s, e) || placeholder;
    const next = text.slice(0, s) + before + selected + after + text.slice(e);
    setText(next);
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(s + before.length, s + before.length + selected.length);
    });
  };
  const insert = (value: string) => {
    const el = area.current;
    const s = el?.selectionStart ?? text.length;
    const e = el?.selectionEnd ?? text.length;
    const next = text.slice(0, s) + value + text.slice(e);
    setText(next);
    requestAnimationFrame(() => {
      el?.focus();
      el?.setSelectionRange(s + value.length, s + value.length);
    });
  };
  const listify = () => {
    const el = area.current;
    if (!el) return;
    const { selectionStart: s, selectionEnd: e } = el;
    const lineStart = text.lastIndexOf('\n', s - 1) + 1;
    const block = text.slice(lineStart, e).split('\n').map((l) => (l.startsWith('- ') ? l : `- ${l}`)).join('\n');
    setText(text.slice(0, lineStart) + block + text.slice(e));
    requestAnimationFrame(() => el.focus());
  };

  const pickMention = (name: string) => {
    if (!mention) return;
    const el = area.current;
    const caret = el?.selectionStart ?? text.length;
    const next = `${text.slice(0, mention.start)}@${name} ${text.slice(caret)}`;
    setText(next);
    setMention(null);
    const pos = mention.start + name.length + 2;
    requestAnimationFrame(() => {
      el?.focus();
      el?.setSelectionRange(pos, pos);
    });
  };

  const detectMention = (value: string, caret: number) => {
    const m = /(^|\s)@([\p{L}]*)$/u.exec(value.slice(0, caret));
    setMention(m ? { query: m[2] ?? '', start: caret - (m[2]?.length ?? 0) - 1, index: 0 } : null);
  };

  // ——— отправка ———
  const send = async (policy: SecretsPolicy = 'warn', thenResolve = false) => {
    const body = text.trim();
    if (!body) return;
    if (policy === 'warn') {
      // Быстрая проверка до отправки; сервер проверит ещё раз и вернёт 422, если что-то пропущено.
      const scan = await ticketsApi.secretScan(body).catch(() => null);
      if (scan && scan.findings.length) {
        setSecrets({ ...scan, resolve: thenResolve });
        return;
      }
    }
    const mentions = (directory?.agents ?? []).filter((a) => body.includes(`@${a.name}`)).map((a) => a.id);
    add.mutate(
      { activity: { kind: mode, body, mentions, secrets: policy }, idempotencyKey: idempotencyKey.current },
      {
        onSuccess: () => {
          setText('');
          setSecrets(null);
          idempotencyKey.current = crypto.randomUUID();
          if (thenResolve) onSendAndResolve?.();
        },
        onError: (e) => {
          if (e instanceof ApiError && e.code === 'secrets_detected') {
            const findings = (e as ApiError & { findings?: SecretFinding[] }).findings ?? [];
            setSecrets({ findings, masked: body, resolve: thenResolve });
          } else showError(e);
        },
      },
    );
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (mention && mentionable.length) {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        const d = e.key === 'ArrowDown' ? 1 : -1;
        setMention({ ...mention, index: (mention.index + d + mentionable.length) % mentionable.length });
        return;
      }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault();
        const pick = mentionable[mention.index];
        if (pick) pickMention(pick.name);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setMention(null);
        return;
      }
    }
    const mod = e.ctrlKey || e.metaKey;
    if (mod && e.key === 'Enter') {
      e.preventDefault();
      void send('warn', e.shiftKey);
    } else if (mod && e.key.toLowerCase() === 'b') {
      e.preventDefault();
      wrap('**', '**', t('workspace.composer.boldPlaceholder'));
    } else if (mod && e.key.toLowerCase() === 'i') {
      e.preventDefault();
      wrap('*', '*', t('workspace.composer.italicPlaceholder'));
    } else if (mod && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      wrap('[', '](https://)', t('workspace.composer.linkPlaceholder'));
    } else if (e.key === 'Escape') {
      area.current?.blur();
    }
  };

  const isNote = mode === 'note';

  return (
    <Paper withBorder radius="md" className={isNote ? 'ew-composer ew-composer-note' : 'ew-composer ew-composer-reply'} p="sm">
      <Stack gap={8}>
        <Group justify="space-between" wrap="wrap" gap="xs">
          <SegmentedControl
            size="xs"
            value={mode}
            onChange={(v) => setMode(v as ComposerMode)}
            data={[
              {
                value: 'reply',
                label: (
                  <Group gap={4} wrap="nowrap">
                    <IconWorld size={14} />
                    {t('workspace.composer.reply')}
                  </Group>
                ),
              },
              {
                value: 'note',
                disabled: !canNote,
                label: (
                  <Group gap={4} wrap="nowrap">
                    <IconLock size={14} />
                    {t('workspace.composer.note')}
                  </Group>
                ),
              },
            ]}
          />
          <Text size="xs" c="dimmed" lineClamp={1} style={{ flex: 1, minWidth: 160 }}>
            {isNote
              ? t('workspace.composer.noteHint')
              : t('workspace.composer.to', { names: recipients.map((r) => (r.email ? `${r.name} <${r.email}>` : r.name)).join(', ') })}
          </Text>
        </Group>

        <Group gap={2} wrap="wrap" role="toolbar" aria-label={t('workspace.composer.toolbar')}>
          <ToolButton label={t('workspace.composer.bold')} kbd="Ctrl+B" onClick={() => wrap('**', '**', t('workspace.composer.boldPlaceholder'))}>
            <IconBold size={16} />
          </ToolButton>
          <ToolButton label={t('workspace.composer.italic')} kbd="Ctrl+I" onClick={() => wrap('*', '*', t('workspace.composer.italicPlaceholder'))}>
            <IconItalic size={16} />
          </ToolButton>
          <ToolButton label={t('workspace.composer.list')} onClick={listify}>
            <IconList size={16} />
          </ToolButton>
          <ToolButton label={t('workspace.composer.link')} kbd="Ctrl+K" onClick={() => wrap('[', '](https://)', t('workspace.composer.linkPlaceholder'))}>
            <IconLink size={16} />
          </ToolButton>
          <ToolButton label={t('workspace.composer.code')} onClick={() => wrap('`', '`', 'code')}>
            <IconCode size={16} />
          </ToolButton>
          <ToolButton
            label={t('workspace.composer.mention')}
            onClick={() => {
              insert('@');
              const caret = (area.current?.selectionStart ?? text.length) + 1;
              setMention({ query: '', start: caret - 1, index: 0 });
            }}
          >
            <IconAt size={16} />
          </ToolButton>
          <Menu position="bottom-start" width={280}>
            <Menu.Target>
              <Button variant="subtle" color="gray" size="compact-sm" leftSection={<IconTemplate size={16} />} rightSection={<IconChevronDown size={12} />}>
                {t('workspace.composer.templates')}
              </Button>
            </Menu.Target>
            <Menu.Dropdown>
              {templates.length === 0 && <Menu.Label>{t('workspace.composer.noTemplates')}</Menu.Label>}
              {templates.map((tpl) => (
                <Menu.Item key={tpl.id} onClick={() => insert(fillTemplate(tpl.body, ticket, directory?.me))}>
                  <Text size="sm">{tpl.name}</Text>
                  <Text size="xs" c="dimmed" lineClamp={1}>
                    {fillTemplate(tpl.body, ticket, directory?.me).replace(/\s+/g, ' ')}
                  </Text>
                </Menu.Item>
              ))}
            </Menu.Dropdown>
          </Menu>
        </Group>

        <Box pos="relative">
          <Textarea
            ref={area}
            id="reply-composer"
            value={text}
            onChange={(e) => {
              setText(e.currentTarget.value);
              detectMention(e.currentTarget.value, e.currentTarget.selectionStart);
            }}
            onKeyDown={onKeyDown}
            onBlur={() => setTimeout(() => setMention(null), 150)}
            placeholder={t(isNote ? 'workspace.composer.notePlaceholder' : 'workspace.composer.replyPlaceholder')}
            aria-label={t(isNote ? 'workspace.composer.note' : 'workspace.composer.reply')}
            autosize
            minRows={4}
            maxRows={18}
            variant="unstyled"
            styles={{ input: { padding: 0 } }}
          />
          {mention && mentionable.length > 0 && (
            <Paper withBorder shadow="md" p={4} pos="absolute" left={0} bottom="100%" w={280} style={{ zIndex: 10 }} role="listbox" aria-label={t('workspace.composer.mention')}>
              {mentionable.map((a, i) => (
                <UnstyledButton
                  key={a.id}
                  role="option"
                  aria-selected={i === mention.index}
                  className="ew-mention-option"
                  data-active={i === mention.index || undefined}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    pickMention(a.name);
                  }}
                >
                  <Text size="sm">{a.name}</Text>
                  <Text size="xs" c="dimmed">
                    {directory?.groups.filter((g) => a.groupIds.includes(g.id)).map((g) => g.name).join(', ')}
                  </Text>
                </UnstyledButton>
              ))}
            </Paper>
          )}
        </Box>

        <Group justify="space-between" wrap="wrap" gap="xs">
          <Text size="xs" c="dimmed">
            {text.trim() ? t('workspace.composer.draftSaved') : ''}
          </Text>
          <Group gap={0} wrap="nowrap">
            <Button
              onClick={() => void send()}
              loading={add.isPending}
              disabled={!text.trim()}
              color={isNote ? 'yellow' : undefined}
              c={isNote ? 'dark' : undefined}
              leftSection={<IconSend size={16} />}
              style={{ borderTopRightRadius: 0, borderBottomRightRadius: 0 }}
            >
              {t(isNote ? 'workspace.composer.sendNote' : 'workspace.composer.send')}
              <Kbd size="xs" ml={8} visibleFrom="sm">
                Ctrl+↵
              </Kbd>
            </Button>
            <Menu position="top-end">
              <Menu.Target>
                <ActionIcon
                  size={36}
                  color={isNote ? 'yellow' : undefined}
                  c={isNote ? 'dark' : undefined}
                  variant="filled"
                  disabled={!text.trim() || !onSendAndResolve}
                  aria-label={t('workspace.composer.more')}
                  style={{ borderTopLeftRadius: 0, borderBottomLeftRadius: 0, borderLeft: '1px solid rgba(255,255,255,0.3)' }}
                >
                  <IconChevronDown size={16} />
                </ActionIcon>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Item onClick={() => void send('warn', true)} rightSection={<Text size="xs" c="dimmed">Ctrl+Shift+↵</Text>}>
                  {t('workspace.composer.sendAndResolve')}
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
          </Group>
        </Group>
      </Stack>

      <Modal opened={secrets !== null} onClose={() => setSecrets(null)} title={t('workspace.secrets.title')} size="md">
        {secrets && (
          <Stack>
            <Alert color="orange" icon={<IconShieldExclamation />}>
              {t('workspace.secrets.text', { count: secrets.findings.length })}
            </Alert>
            <Stack gap={4}>
              {secrets.findings.map((f, i) => (
                <Text key={i} size="sm">
                  {t(`workspace.secrets.kind.${f.kind}`, { defaultValue: f.kind })}: <code>{f.masked}</code>
                </Text>
              ))}
            </Stack>
            <Group justify="flex-end" wrap="wrap">
              <Button variant="default" onClick={() => setSecrets(null)}>
                {t('workspace.secrets.edit')}
              </Button>
              <Button variant="light" color="red" onClick={() => void send('allow', secrets.resolve)}>
                {t('workspace.secrets.sendAsIs')}
              </Button>
              <Button onClick={() => void send('mask', secrets.resolve)} data-autofocus>
                {t('workspace.secrets.mask')}
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </Paper>
  );
});

function ToolButton({ label, kbd, onClick, children }: { label: string; kbd?: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <Tooltip label={kbd ? `${label} · ${kbd}` : label} openDelay={300}>
      <ActionIcon variant="subtle" color="gray" onClick={onClick} aria-label={label} onMouseDown={(e) => e.preventDefault()}>
        {children}
      </ActionIcon>
    </Tooltip>
  );
}
