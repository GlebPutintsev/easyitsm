import { Button, Card, Group, Stack, Text, Title } from '@mantine/core';
import { IconDeviceLaptop, IconLicense } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { ListState } from '@/shared/ui/ListState';
import { useMyAssets } from './api';
import { AssetStateBadge, SectionLabel, formatDate } from './ui';
import classes from './assets.module.css';

/** Портал → «Моё оборудование» (AST-05): что закреплено за сотрудником, со ссылкой на обращение. */
export function MyAssetsPage() {
  const { t } = useTranslation();
  const mine = useMyAssets();
  const assets = mine.data?.assets ?? [];
  const licenses = mine.data?.licenses ?? [];
  return (
    <div className="ep-page" data-width="medium">
      <Group justify="space-between" align="flex-start" wrap="wrap">
        <div>
          <Title order={1} size="h2">
            {t('assets.mine.title')}
          </Title>
          <Text size="sm" c="var(--ei-text-3)">
            {t('assets.mine.subtitle')}
          </Text>
        </div>
        <Button component={Link} to="/portal/report" variant="default">
          {t('assets.mine.report')}
        </Button>
      </Group>
      {mine.isPending || mine.error ? (
        <ListState state={mine.error ? 'error' : 'loading'} error={mine.error} onRetry={() => mine.refetch()} rows={3} />
      ) : assets.length === 0 && licenses.length === 0 ? (
        <ListState state="empty" title={t('assets.mine.empty')} />
      ) : (
        <>
          {assets.length > 0 && (
            <Stack gap="xs">
              <SectionLabel>{t('assets.mine.hardware')}</SectionLabel>
              {assets.map((a) => (
                <Card key={a.id} withBorder radius="lg" padding="md" className={classes.tableCard}>
                  <Group justify="space-between" wrap="nowrap" align="flex-start">
                    <Group gap="sm" wrap="nowrap" align="flex-start">
                      <IconDeviceLaptop size={20} aria-hidden style={{ color: 'var(--ei-text-3)', marginTop: 2 }} />
                      <div>
                        <Text fw={600}>{a.name}</Text>
                        <Text size="sm" className={classes.muted}>
                          <span className="ei-mono">{a.tag}</span>
                          {a.model ? ` · ${a.model}` : ''}
                          {a.warrantyTo ? ` · ${t('assets.field.warrantyTo')} ${formatDate(a.warrantyTo)}` : ''}
                        </Text>
                      </div>
                    </Group>
                    <AssetStateBadge state={a.state} />
                  </Group>
                </Card>
              ))}
            </Stack>
          )}
          {licenses.length > 0 && (
            <Stack gap="xs">
              <SectionLabel>{t('assets.mine.software')}</SectionLabel>
              {licenses.map((l) => (
                <Group key={l.id} gap="sm" wrap="nowrap">
                  <IconLicense size={18} aria-hidden style={{ color: 'var(--ei-text-3)' }} />
                  <Text size="sm">{l.product}</Text>
                </Group>
              ))}
            </Stack>
          )}
        </>
      )}
    </div>
  );
}
