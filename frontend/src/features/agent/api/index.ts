import { httpTicketsApi } from './httpTicketsApi';
import { mockTicketsApi } from './mock/mockTicketsApi';
import type { TicketsApi } from './ticketsApi';

/**
 * Источник данных рабочего места. Пока модуль tickets не отдал API, по умолчанию моки.
 * VITE_TICKETS_API=http включает настоящий сервер; в сборке для заказчика моков нет.
 */
const useHttp = import.meta.env.VITE_TICKETS_API === 'http' || (import.meta.env.PROD && import.meta.env.VITE_TICKETS_API !== 'mock');

export const ticketsApi: TicketsApi = useHttp ? httpTicketsApi : mockTicketsApi;
export const usingMocks = !useHttp;
