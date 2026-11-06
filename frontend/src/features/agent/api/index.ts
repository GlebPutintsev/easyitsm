import { httpTicketsApi } from './httpTicketsApi';
import { mockTicketsApi } from './mock/mockTicketsApi';
import type { TicketsApi } from './ticketsApi';

/**
 * Источник данных рабочего места: настоящий API модуля tickets.
 * VITE_TICKETS_API=mock включает данные в памяти для работы над интерфейсом без бэкенда (только dev);
 * в сборке для заказчика моков нет.
 */
const useHttp = import.meta.env.PROD || import.meta.env.VITE_TICKETS_API !== 'mock';

export const ticketsApi: TicketsApi = useHttp ? httpTicketsApi : mockTicketsApi;
export const usingMocks = !useHttp;
