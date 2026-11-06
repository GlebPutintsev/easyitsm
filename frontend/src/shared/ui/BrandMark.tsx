/**
 * Знак Easy ITSM (дизайн-система «Адмирал»): латунная рамка и строки списка.
 * Рисуется на тёмно-синей раме; цвет строк — текущий цвет текста.
 */
export function BrandMark({ size = 28 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 28 28" aria-hidden="true" focusable="false" style={{ flex: 'none' }}>
      <rect x="1" y="1" width="26" height="26" rx="7" fill="none" stroke="var(--ei-brass)" strokeWidth="1.5" />
      <path d="M9 8h10M9 14h8M9 20h10M9 8v12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}
