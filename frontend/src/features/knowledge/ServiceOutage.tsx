import { useTranslation } from 'react-i18next';
import { useActiveAnnouncements, type Announcement } from '@/features/portal/announcements';

/** Активные сбои и работы, которые затрагивают услугу статьи: сначала сбои. */
export function affecting(items: Announcement[], serviceId: string | null): Announcement[] {
  if (!serviceId) return [];
  return items
    .filter((a) => a.kind !== 'info' && (a.serviceIds ?? []).includes(serviceId))
    .sort((x, y) => (x.kind === y.kind ? 0 : x.kind === 'outage' ? -1 : 1));
}

/** Баннер над статьёй (макет «Адмирал», статья БЗ): «Сбой» и текст объявления. */
export function ServiceOutage({ serviceId }: { serviceId: string | null }) {
  const { t } = useTranslation();
  const announcements = useActiveAnnouncements(!!serviceId);
  const items = affecting(announcements.data?.items ?? [], serviceId);
  if (items.length === 0) return null;
  return (
    <div className="kb-outages">
      {items.map((a) => (
        <div key={a.id} className="kb-outage" data-kind={a.kind} role={a.kind === 'outage' ? 'alert' : 'status'}>
          <strong className="kb-outage-label">{t(`kb.outage.${a.kind}`)}</strong>
          <span>
            <span className="kb-outage-title">{a.title}</span>
            {a.body && <span className="kb-outage-body">{a.body}</span>}
          </span>
        </div>
      ))}
    </div>
  );
}
