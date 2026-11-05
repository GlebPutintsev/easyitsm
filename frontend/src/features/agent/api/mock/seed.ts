import { priorityOf } from '../domain';
import type {
  Activity,
  Channel,
  Directory,
  GroupRef,
  Level,
  Ref,
  ResponseTemplate,
  StatusCategory,
  TicketType,
  UserRef,
} from '../model';

/** Детерминированный генератор, чтобы демо-данные и скриншоты не менялись от запуска к запуску. */
export function seededRandom(seed: number) {
  let a = seed;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const uid = (n: number) => `01930000-0000-7000-8000-${n.toString(16).padStart(12, '0')}`;

export const STATUS_NAMES: Record<StatusCategory, string> = {
  draft: 'Черновик',
  new: 'Новая',
  approval: 'На согласовании',
  assigned: 'Назначена',
  in_progress: 'В работе',
  pending: 'Ожидание',
  resolved: 'Решена',
  closed: 'Закрыта',
  cancelled: 'Отменена',
};

export const me: UserRef = { id: uid(0xaa), name: 'Анна Смирнова', email: 'a.smirnova@example.ru' };

export const groups: GroupRef[] = [
  { id: uid(0x101), name: 'Service Desk, 1-я линия' },
  { id: uid(0x102), name: 'Сетевая инфраструктура' },
  { id: uid(0x103), name: 'Рабочие места' },
  { id: uid(0x104), name: '1С и бизнес-приложения' },
  { id: uid(0x105), name: 'Информационная безопасность' },
];

const [sd, net, ws, apps, sec] = groups as [GroupRef, GroupRef, GroupRef, GroupRef, GroupRef];

export const agents: Directory['agents'] = [
  { ...me, groupIds: [sd.id, ws.id], openTickets: 7, available: true },
  { id: uid(0xab), name: 'Игорь Волков', email: 'i.volkov@example.ru', groupIds: [sd.id], openTickets: 11, available: true },
  { id: uid(0xac), name: 'Мария Кузнецова', email: 'm.kuznetsova@example.ru', groupIds: [sd.id, apps.id], openTickets: 5, available: true },
  { id: uid(0xad), name: 'Дмитрий Орлов', email: 'd.orlov@example.ru', groupIds: [net.id], openTickets: 9, available: false },
  { id: uid(0xae), name: 'Ольга Лебедева', email: 'o.lebedeva@example.ru', groupIds: [ws.id], openTickets: 4, available: true },
  { id: uid(0xaf), name: 'Сергей Никитин', email: 's.nikitin@example.ru', groupIds: [apps.id], openTickets: 6, available: true },
  { id: uid(0xb0), name: 'Елена Морозова', email: 'e.morozova@example.ru', groupIds: [sec.id], openTickets: 3, available: true },
];

export const requesters: (UserRef & { department: string; phone: string; vip: boolean })[] = [
  { id: uid(0x201), name: 'Павел Соколов', email: 'p.sokolov@example.ru', department: 'Бухгалтерия', phone: '+7 495 100-20-01', vip: false },
  { id: uid(0x202), name: 'Татьяна Иванова', email: 't.ivanova@example.ru', department: 'Отдел кадров', phone: '+7 495 100-20-02', vip: false },
  { id: uid(0x203), name: 'Алексей Попов', email: 'a.popov@example.ru', department: 'Продажи', phone: '+7 495 100-20-03', vip: false },
  { id: uid(0x204), name: 'Наталья Васильева', email: 'n.vasilieva@example.ru', department: 'Финансовая дирекция', phone: '+7 495 100-20-04', vip: true },
  { id: uid(0x205), name: 'Михаил Петров', email: 'm.petrov@example.ru', department: 'Склад', phone: '+7 495 100-20-05', vip: false },
  { id: uid(0x206), name: 'Юлия Новикова', email: 'y.novikova@example.ru', department: 'Маркетинг', phone: '+7 495 100-20-06', vip: false },
  { id: uid(0x207), name: 'Андрей Фёдоров', email: 'a.fedorov@example.ru', department: 'Генеральная дирекция', phone: '+7 495 100-20-07', vip: true },
  { id: uid(0x208), name: 'Екатерина Козлова', email: 'e.kozlova@example.ru', department: 'Юридический отдел', phone: '+7 495 100-20-08', vip: false },
  { id: uid(0x209), name: 'Роман Зайцев', email: 'r.zaitsev@example.ru', department: 'Логистика', phone: '+7 495 100-20-09', vip: false },
  { id: uid(0x20a), name: 'Светлана Павлова', email: 's.pavlova@example.ru', department: 'Бухгалтерия', phone: '+7 495 100-20-10', vip: false },
];

export const services: Ref[] = [
  { id: uid(0x301), name: 'Электронная почта' },
  { id: uid(0x302), name: 'Сеть и VPN' },
  { id: uid(0x303), name: 'Рабочее место' },
  { id: uid(0x304), name: '1С:Бухгалтерия' },
  { id: uid(0x305), name: 'Печать' },
  { id: uid(0x306), name: 'Учётные записи и доступ' },
];

export const categories: Ref[] = [
  { id: uid(0x401), name: 'Не работает' },
  { id: uid(0x402), name: 'Медленно работает' },
  { id: uid(0x403), name: 'Ошибка' },
  { id: uid(0x404), name: 'Доступ' },
  { id: uid(0x405), name: 'Консультация' },
  { id: uid(0x406), name: 'Оборудование' },
];

export const resolutionCodes: Ref[] = [
  { id: 'fixed', name: 'Устранено' },
  { id: 'workaround', name: 'Обходное решение' },
  { id: 'consultation', name: 'Консультация' },
  { id: 'no_repro', name: 'Не воспроизводится' },
  { id: 'duplicate', name: 'Дубликат' },
];

export const pendingReasons: Ref[] = [
  { id: 'requester', name: 'Ожидание ответа заявителя' },
  { id: 'vendor', name: 'Ожидание поставщика' },
  { id: 'change', name: 'Ожидание изменения' },
  { id: 'delivery', name: 'Ожидание поставки оборудования' },
];

export const cancelReasons: Ref[] = [
  { id: 'duplicate', name: 'Дубликат' },
  { id: 'requester', name: 'Отозвана заявителем' },
  { id: 'mistake', name: 'Создана по ошибке' },
];

export const templates: ResponseTemplate[] = [
  {
    id: 'tpl-hello',
    name: 'Приняли в работу',
    body: 'Здравствуйте, {{requester.name}}!\n\nВаша заявка {{ticket.number}} принята в работу. Сообщу, как только появятся новости.\n\n{{agent.name}}',
  },
  {
    id: 'tpl-info',
    name: 'Нужны подробности',
    body: 'Здравствуйте, {{requester.name}}!\n\nЧтобы разобраться быстрее, пришлите, пожалуйста:\n- снимок экрана с ошибкой;\n- время, когда она появилась;\n- имя компьютера (на наклейке или в «Параметры → Система»).\n\n{{agent.name}}',
  },
  {
    id: 'tpl-restart',
    name: 'Перезагрузка',
    body: 'Здравствуйте, {{requester.name}}!\n\nПопробуйте, пожалуйста, перезагрузить компьютер и проверить ещё раз. Если ошибка останется, ответьте на это письмо, и я подключусь удалённо.\n\n{{agent.name}}',
  },
  {
    id: 'tpl-done',
    name: 'Решено, проверьте',
    body: 'Здравствуйте, {{requester.name}}!\n\nПроблема по заявке {{ticket.number}} устранена. Проверьте, пожалуйста, что всё работает. Если нет, просто ответьте на это письмо, и заявка вернётся в работу.\n\n{{agent.name}}',
  },
];

interface Scenario {
  type: TicketType;
  title: string;
  description: string;
  serviceIdx: number;
  categoryIdx: number;
  groupIdx: number;
  impact: Level;
  urgency: Level;
  channel: Channel;
  replies?: string[];
}

const scenarios: Scenario[] = [
  { type: 'incident', title: 'Не отправляются письма во внешние домены', description: 'С утра письма на внешние адреса возвращаются с ошибкой 550. Внутри компании почта ходит.', serviceIdx: 0, categoryIdx: 0, groupIdx: 0, impact: 'high', urgency: 'high', channel: 'email', replies: ['У коллег из продаж то же самое, клиенты не получают счета.'] },
  { type: 'incident', title: 'VPN отключается каждые 10 минут', description: 'При работе из дома VPN-подключение рвётся примерно каждые 10 минут, приходится переподключаться.', serviceIdx: 1, categoryIdx: 0, groupIdx: 1, impact: 'medium', urgency: 'high', channel: 'portal' },
  { type: 'incident', title: '1С: ошибка при проведении документа реализации', description: 'При проведении документа «Реализация товаров» появляется ошибка «Нарушение прав доступа». Вчера всё работало.', serviceIdx: 3, categoryIdx: 2, groupIdx: 3, impact: 'medium', urgency: 'high', channel: 'portal', replies: ['Прикладываю снимок экрана с ошибкой. Закрытие месяца в пятницу.'] },
  { type: 'request', title: 'Доступ к папке «Договоры 2026» на файловом сервере', description: 'Прошу выдать доступ на чтение и запись к папке \\\\fs01\\legal\\Договоры 2026. Согласовано с руководителем.', serviceIdx: 5, categoryIdx: 3, groupIdx: 0, impact: 'low', urgency: 'medium', channel: 'portal' },
  { type: 'incident', title: 'Принтер на 3 этаже печатает пустые листы', description: 'HP LaserJet у переговорной 305 печатает пустые страницы. Картридж меняли на прошлой неделе.', serviceIdx: 4, categoryIdx: 0, groupIdx: 2, impact: 'medium', urgency: 'low', channel: 'portal' },
  { type: 'incident', title: 'Очень медленно открывается Outlook', description: 'Outlook запускается 5–7 минут, папка «Входящие» долго обновляется.', serviceIdx: 0, categoryIdx: 1, groupIdx: 2, impact: 'low', urgency: 'medium', channel: 'email' },
  { type: 'request', title: 'Новый ноутбук для сотрудника с 10 ноября', description: 'Выходит новый менеджер по продажам, нужен ноутбук со стандартным набором программ и учётная запись.', serviceIdx: 2, categoryIdx: 5, groupIdx: 2, impact: 'low', urgency: 'medium', channel: 'portal' },
  { type: 'incident', title: 'Не работает Wi-Fi в переговорной «Москва»', description: 'Ноутбуки видят сеть Corp, но не получают адрес. Через 30 минут встреча с клиентом.', serviceIdx: 1, categoryIdx: 0, groupIdx: 1, impact: 'medium', urgency: 'high', channel: 'chat' },
  { type: 'incident', title: 'Заблокирована учётная запись после смены пароля', description: 'Сменила пароль по требованию системы, теперь не могу войти ни в почту, ни в компьютер.', serviceIdx: 5, categoryIdx: 3, groupIdx: 0, impact: 'low', urgency: 'high', channel: 'agent' },
  { type: 'incident', title: 'Сервер печати недоступен', description: 'Мониторинг: узел print01 не отвечает на ping более 5 минут.', serviceIdx: 4, categoryIdx: 0, groupIdx: 1, impact: 'high', urgency: 'medium', channel: 'monitoring' },
  { type: 'request', title: 'Установить Visio на рабочее место', description: 'Для работы со схемами процессов нужен Microsoft Visio. Лицензия есть в отделе.', serviceIdx: 2, categoryIdx: 4, groupIdx: 2, impact: 'low', urgency: 'low', channel: 'portal' },
  { type: 'incident', title: 'Подозрительное письмо с вложением от «бухгалтерии»', description: 'Пришло письмо «Акт сверки» с архивом и паролем в тексте. Отправитель похож на нашего бухгалтера, но адрес чужой.', serviceIdx: 0, categoryIdx: 2, groupIdx: 4, impact: 'medium', urgency: 'high', channel: 'email' },
  { type: 'incident', title: 'Не открывается отчёт в 1С по остаткам', description: 'Отчёт «Остатки товаров» формируется бесконечно, через час 1С зависает.', serviceIdx: 3, categoryIdx: 1, groupIdx: 3, impact: 'medium', urgency: 'medium', channel: 'portal' },
  { type: 'request', title: 'Подключить второй монитор', description: 'Прошу выдать и подключить второй монитор, работаю с большими таблицами.', serviceIdx: 2, categoryIdx: 5, groupIdx: 2, impact: 'low', urgency: 'low', channel: 'portal' },
  { type: 'incident', title: 'Телефония: не слышно собеседника', description: 'На IP-телефоне при входящих звонках не слышно собеседника, исходящие работают.', serviceIdx: 1, categoryIdx: 0, groupIdx: 1, impact: 'low', urgency: 'medium', channel: 'portal' },
  { type: 'incident', title: 'Не синхронизируется почта на телефоне', description: 'После обновления iOS почта на телефоне перестала обновляться, пишет «Нет подключения к серверу».', serviceIdx: 0, categoryIdx: 0, groupIdx: 0, impact: 'low', urgency: 'low', channel: 'email' },
  { type: 'request', title: 'Создать почтовый ящик для отдела закупок', description: 'Нужен общий ящик zakupki@ с доступом для четырёх сотрудников отдела.', serviceIdx: 0, categoryIdx: 3, groupIdx: 0, impact: 'low', urgency: 'medium', channel: 'portal' },
  { type: 'incident', title: 'Сканер не отправляет документы в папку', description: 'МФУ Kyocera на 2 этаже сканирует, но файлы не появляются в сетевой папке Scan.', serviceIdx: 4, categoryIdx: 2, groupIdx: 2, impact: 'medium', urgency: 'low', channel: 'portal' },
  { type: 'incident', title: 'Сайт контрагента не открывается из офиса', description: 'Портал поставщика открывается из дома, но не из офисной сети: «Соединение сброшено».', serviceIdx: 1, categoryIdx: 0, groupIdx: 1, impact: 'low', urgency: 'medium', channel: 'portal' },
  { type: 'incident', title: 'Ноутбук не включается после обновления BIOS', description: 'После вчерашнего обновления ноутбук показывает чёрный экран, индикатор питания горит.', serviceIdx: 2, categoryIdx: 5, groupIdx: 2, impact: 'low', urgency: 'high', channel: 'agent' },
  { type: 'request', title: 'Выдать права на справочник номенклатуры в 1С', description: 'Прошу добавить права на редактирование справочника «Номенклатура» для двух сотрудников склада.', serviceIdx: 3, categoryIdx: 3, groupIdx: 3, impact: 'low', urgency: 'low', channel: 'portal' },
  { type: 'incident', title: 'Общая папка бухгалтерии недоступна', description: 'Сетевой диск B: не подключается у всех сотрудников бухгалтерии: «Сетевой путь не найден».', serviceIdx: 2, categoryIdx: 0, groupIdx: 1, impact: 'high', urgency: 'high', channel: 'portal', replies: ['Срочно, сегодня сдаём отчётность.'] },
  { type: 'incident', title: 'Не приходят коды MFA по СМС', description: 'При входе в VPN не приходит СМС с кодом, ждала 10 минут.', serviceIdx: 5, categoryIdx: 3, groupIdx: 4, impact: 'low', urgency: 'high', channel: 'chat' },
  { type: 'request', title: 'Консультация: как настроить автоответ в отпуске', description: 'Ухожу в отпуск с понедельника, подскажите, как включить автоответ в Outlook.', serviceIdx: 0, categoryIdx: 4, groupIdx: 0, impact: 'low', urgency: 'low', channel: 'portal' },
  { type: 'incident', title: 'Торможения терминального сервера', description: 'На ts02 очень медленно работает всё: 1С, браузер, проводник. Жалуются 15 человек.', serviceIdx: 3, categoryIdx: 1, groupIdx: 3, impact: 'high', urgency: 'medium', channel: 'monitoring' },
  { type: 'incident', title: 'Клавиатура залита кофе', description: 'Пролила кофе на клавиатуру ноутбука, часть клавиш не работает.', serviceIdx: 2, categoryIdx: 5, groupIdx: 2, impact: 'low', urgency: 'medium', channel: 'portal' },
  { type: 'request', title: 'Пропуск на парковку для подрядчика', description: 'Нужен временный доступ в гостевую сеть Wi-Fi для подрядчика на неделю.', serviceIdx: 1, categoryIdx: 3, groupIdx: 1, impact: 'low', urgency: 'low', channel: 'portal' },
  { type: 'incident', title: 'Ошибка сертификата при входе в корпоративный портал', description: 'Браузер пишет «Подключение не защищено» при открытии intranet.example.ru.', serviceIdx: 1, categoryIdx: 2, groupIdx: 4, impact: 'medium', urgency: 'medium', channel: 'portal' },
];

/** Распределение статусов в демо-очереди: больше открытых, немного решённых и закрытых. */
const statusPlan: StatusCategory[] = [
  'new', 'new', 'in_progress', 'assigned', 'new', 'in_progress', 'pending', 'new', 'in_progress', 'new',
  'assigned', 'in_progress', 'pending', 'new', 'resolved', 'in_progress', 'new', 'assigned', 'in_progress', 'pending',
  'new', 'in_progress', 'new', 'resolved', 'in_progress', 'closed', 'new', 'assigned',
];

export interface SeedTicket {
  scenario: Scenario;
  number: number;
  status: StatusCategory;
  requester: (typeof requesters)[number];
  assignee: UserRef | null;
  group: GroupRef;
  ageMin: number;
}

export function buildSeed(): SeedTicket[] {
  const rnd = seededRandom(42);
  const pick = <T,>(arr: readonly T[]): T => arr[Math.floor(rnd() * arr.length)] as T;
  // Повторяем сценарии дважды, чтобы в очереди было больше одной страницы на маленьком экране.
  const all = [...scenarios, ...scenarios.slice(0, 18)];
  return all.map((scenario, i) => {
    const status = statusPlan[i % statusPlan.length] as StatusCategory;
    const group = groups[scenario.groupIdx] as GroupRef;
    const groupAgents = agents.filter((a) => a.groupIds.includes(group.id));
    const assignee =
      status === 'new' ? null : i % 3 === 0 && group.id !== sec.id ? me : (pick(groupAgents.length ? groupAgents : agents) as UserRef);
    return {
      scenario,
      number: 1180 + i,
      status,
      requester: pick(requesters),
      assignee: assignee ? { id: assignee.id, name: assignee.name, email: assignee.email } : null,
      group,
      // Старые заявки — в конце списка; самые свежие созданы несколько минут назад.
      ageMin: Math.round((all.length - i) * (4 + rnd() * 14)),
    };
  });
}

export function seedActivity(seed: SeedTicket, ticketId: string, createdAt: number): Activity[] {
  const acts: Activity[] = [];
  let at = createdAt;
  const step = () => (at += 7 * 60 * 1000 + Math.round(((seed.number * 37) % 20) * 60 * 1000));
  const id = () => `${ticketId}-a${acts.length}`;
  const base = { fromRequester: false, body: '' };
  acts.push({ ...base, id: id(), kind: 'system', visibility: 'public', author: null, event: { code: 'created', channel: seed.scenario.channel }, createdAt: new Date(at).toISOString() });
  if (seed.status !== 'new') {
    acts.push({ ...base, id: id(), kind: 'change', visibility: 'internal', author: null, changes: [{ field: 'group', from: null, to: seed.group.name }], createdAt: new Date(step()).toISOString() });
  }
  if (seed.assignee && seed.status !== 'new' && seed.status !== 'assigned') {
    acts.push({ ...base, id: id(), kind: 'reply', visibility: 'public', author: seed.assignee, body: 'Здравствуйте! Взяли заявку в работу, скоро вернёмся с новостями.', createdAt: new Date(step()).toISOString() });
    acts.push({ ...base, id: id(), kind: 'note', visibility: 'internal', author: seed.assignee, body: 'Проверили журнал: ошибка повторяется с **09:40**. Похоже на последствия ночного обновления, уточняю у дежурного.\n\n- сервер `mail02` перезагружен в 03:10\n- у остальных пользователей отдела то же самое', createdAt: new Date(step()).toISOString() });
  }
  for (const reply of seed.scenario.replies ?? []) {
    acts.push({ id: id(), kind: 'reply', visibility: 'public', author: seed.requester, fromRequester: true, body: reply, createdAt: new Date(step()).toISOString() });
  }
  return acts;
}

export { priorityOf };
