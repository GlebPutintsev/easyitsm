import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';
import { ApiError } from '@/shared/api/client';
import { ListState } from '@/shared/ui/ListState';
import { useCatalogItem } from '@/features/catalog/api';
import { RequestForm, RequestFormSkeleton } from './RequestForm';

/** Форма заказа услуги из каталога: /portal/catalog/:itemId. */
export function OrderPage() {
  const { t } = useTranslation();
  const { itemId } = useParams();
  const item = useCatalogItem(itemId);
  if (item.isPending) return <RequestFormSkeleton />;
  if (item.error) {
    const notFound = item.error instanceof ApiError && item.error.status === 404;
    return (
      <div className="ep-page">
      <ListState
        state={notFound ? 'empty' : 'error'}
        title={notFound ? t('catalog.notFound') : undefined}
        error={item.error}
        onRetry={() => void item.refetch()}
      />
      </div>
    );
  }
  // key: при переходе к другой услуге форма начинается заново со своим черновиком.
  return <RequestForm key={item.data.id} item={item.data} />;
}

/** «Сообщить о проблеме»: инцидент без услуги каталога, /portal/report. */
export function ReportProblemPage() {
  return <RequestForm />;
}
