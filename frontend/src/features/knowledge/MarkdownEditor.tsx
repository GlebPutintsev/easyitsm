import { ActionIcon, Box, Group, Paper, SegmentedControl, SimpleGrid, Text, Textarea, Tooltip } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import {
  IconBlockquote,
  IconBold,
  IconCode,
  IconH2,
  IconItalic,
  IconLink,
  IconList,
  IconListNumbers,
  IconPhoto,
  IconSourceCode,
  IconTable,
  type Icon,
} from '@tabler/icons-react';
import { useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ArticleMarkdown } from './ArticleMarkdown';
import { applyCommand, type EditCommand } from './markdownEditing';

const TOOLS: { cmd: EditCommand; icon: Icon; hotkey?: string }[] = [
  { cmd: 'heading', icon: IconH2 },
  { cmd: 'bold', icon: IconBold, hotkey: 'Ctrl+B' },
  { cmd: 'italic', icon: IconItalic, hotkey: 'Ctrl+I' },
  { cmd: 'bulletList', icon: IconList },
  { cmd: 'numberedList', icon: IconListNumbers },
  { cmd: 'quote', icon: IconBlockquote },
  { cmd: 'code', icon: IconCode },
  { cmd: 'codeBlock', icon: IconSourceCode },
  { cmd: 'table', icon: IconTable },
  { cmd: 'link', icon: IconLink, hotkey: 'Ctrl+K' },
  { cmd: 'image', icon: IconPhoto },
];

const HOTKEYS: Record<string, EditCommand> = { b: 'bold', i: 'italic', k: 'link' };

interface Props {
  value: string;
  onChange: (value: string) => void;
  /** Подпись над предпросмотром, например для какой аудитории он показан */
  previewNote?: ReactNode;
}

/**
 * Редактор статьи (KB-01): Markdown с панелью инструментов и живым предпросмотром.
 * На широком экране текст и предпросмотр рядом, на узком — переключатель.
 */
export function MarkdownEditor({ value, onChange, previewNote }: Props) {
  const { t } = useTranslation();
  const wide = useMediaQuery('(min-width: 75em)');
  const [mode, setMode] = useState<'write' | 'preview'>('write');
  const ref = useRef<HTMLTextAreaElement>(null);

  const labels = {
    text: t('kb.editor.placeholder.text'),
    code: t('kb.editor.placeholder.code'),
    linkText: t('kb.editor.placeholder.linkText'),
    imageAlt: t('kb.editor.placeholder.imageAlt'),
    column1: t('kb.editor.placeholder.column1'),
    column2: t('kb.editor.placeholder.column2'),
  };

  const run = (cmd: EditCommand) => {
    const el = ref.current;
    if (!el) return;
    const next = applyCommand({ text: value, start: el.selectionStart, end: el.selectionEnd }, cmd, labels);
    onChange(next.text);
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(next.start, next.end);
    });
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    const cmd = (e.ctrlKey || e.metaKey) && !e.shiftKey && !e.altKey ? HOTKEYS[e.key.toLowerCase()] : undefined;
    if (cmd) {
      e.preventDefault();
      run(cmd);
    }
  };

  const showEditor = wide || mode === 'write';
  const showPreview = wide || mode === 'preview';

  return (
    <Paper withBorder radius="md">
      <Group justify="space-between" px="xs" py={6} gap="xs" style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Group gap={2} role="toolbar" aria-label={t('kb.editor.toolbar')}>
          {TOOLS.map(({ cmd, icon: ToolIcon, hotkey }) => {
            const label = t(`kb.editor.tools.${cmd}`) + (hotkey ? ` (${hotkey})` : '');
            return (
              <Tooltip key={cmd} label={label} withArrow>
                <ActionIcon variant="subtle" color="gray" aria-label={label} onClick={() => run(cmd)} disabled={!showEditor}>
                  <ToolIcon size={18} stroke={1.6} />
                </ActionIcon>
              </Tooltip>
            );
          })}
        </Group>
        {!wide && (
          <SegmentedControl
            size="xs"
            value={mode}
            onChange={(v) => setMode(v as 'write' | 'preview')}
            data={[
              { value: 'write', label: t('kb.editor.write') },
              { value: 'preview', label: t('kb.editor.preview') },
            ]}
          />
        )}
      </Group>
      <SimpleGrid cols={wide ? 2 : 1} spacing={0}>
        {showEditor && (
          <Textarea
            ref={ref}
            value={value}
            onChange={(e) => onChange(e.currentTarget.value)}
            onKeyDown={onKeyDown}
            autosize
            minRows={20}
            variant="unstyled"
            aria-label={t('kb.editor.body')}
            placeholder={t('kb.editor.bodyPlaceholder')}
            styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)', fontSize: 14, padding: 16, lineHeight: 1.6 } }}
          />
        )}
        {showPreview && (
          <Box p="md" style={wide ? { borderLeft: '1px solid var(--mantine-color-default-border)' } : undefined}>
            {previewNote && (
              <Text size="xs" c="dimmed" mb="sm">
                {previewNote}
              </Text>
            )}
            {value.trim() ? (
              <ArticleMarkdown>{value}</ArticleMarkdown>
            ) : (
              <Text c="dimmed" size="sm">
                {t('kb.editor.emptyPreview')}
              </Text>
            )}
          </Box>
        )}
      </SimpleGrid>
    </Paper>
  );
}
