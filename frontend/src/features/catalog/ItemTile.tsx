import { ActionIcon, Button, Tooltip } from '@mantine/core';
import { IconStar, IconStarFilled } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { type ItemCard, useToggleFavorite } from './api';
import { ServiceIcon } from './icons';

/**
 * Карточка услуги (макет «Каталог услуг»): значок, звезда избранного, название, описание в две строки,
 * срок, цена и команда, кнопка «Заказать». Стили — features/portal/portal.css.
 */
export function ItemTile({ item }: { item: ItemCard }) {
  const { t, i18n } = useTranslation();
  const favorite = useToggleFavorite();
  const to = `/portal/catalog/${item.id}`;
  const price =
    item.cost != null
      ? new Intl.NumberFormat(i18n.language, { style: 'currency', currency: item.currency ?? 'RUB', maximumFractionDigits: 0 }).format(item.cost)
      : null;
  const favLabel = item.favorite ? t('catalog.unfavorite') : t('catalog.favorite');

  return (
    <article className="ep-service">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <ServiceIcon name={item.icon} kind={item.kind} size={40} />
        <Tooltip label={favLabel}>
          <ActionIcon
            className="ep-fav"
            variant="subtle"
            color="gray"
            size={36}
            aria-label={favLabel}
            aria-pressed={item.favorite}
            onClick={() => favorite.mutate({ id: item.id, on: !item.favorite })}
            loading={favorite.isPending}
          >
            {item.favorite ? <IconStarFilled size={18} /> : <IconStar size={18} />}
          </ActionIcon>
        </Tooltip>
      </div>
      <h3>
        <Link to={to}>{item.name}</Link>
      </h3>
      {item.summary && <p>{item.summary}</p>}
      <div className="ep-meta">
        {item.leadTimeHours != null && <span>{t('portal.leadTime', { time: leadTime(t, item.leadTimeHours) })}</span>}
        {price && <span>{price}</span>}
        {item.team && <span>{item.team}</span>}
      </div>
      <Button variant="default" fullWidth component={Link} to={to}>
        {item.kind === 'incident' ? t('catalog.report') : t('catalog.order')}
      </Button>
    </article>
  );
}

type TFn = (key: string, options?: Record<string, unknown>) => string;

export function leadTime(t: TFn, hours: number): string {
  if (hours < 24) return t('catalog.leadHours', { count: hours });
  const days = Math.round(hours / 24);
  return t('catalog.leadDays', { count: days });
}
