import type { RouteObject } from 'react-router';
import { AgentHome } from './AgentHome';
import { AgentWorkspace } from './AgentWorkspace';
import { QueuePage } from './queue/QueuePage';
import { TicketPage } from './ticket/TicketPage';

/** Маршруты рабочего места агента (10.6, 10.7). Подключаются в app/router.tsx одной строкой. */
export const agentRoutes: RouteObject = {
  path: 'agent',
  element: <AgentWorkspace />,
  children: [
    { index: true, element: <AgentHome /> },
    { path: 'my', element: <QueuePage key="my" fixedView="mine" /> },
    { path: 'queues', element: <QueuePage /> },
    { path: 'queues/:viewId', element: <QueuePage /> },
    { path: 'tickets/:number', element: <TicketPage /> },
  ],
};
