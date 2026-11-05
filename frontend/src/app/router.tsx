import { createBrowserRouter, Navigate } from 'react-router';
import { AppLayout } from './layout/AppLayout';
import { PortalHome } from '@/features/portal/PortalHome';
import { AgentHome } from '@/features/agent/AgentHome';
import { AdminHome } from '@/features/admin/AdminHome';
import { NotFound } from './NotFound';

/**
 * Пространства (раздел 10): портал, рабочее место агента, управление. Вход добавит модуль identity.
 * Каждый модуль добавляет свои маршруты в children нужного пространства.
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <Navigate to="/portal" replace /> },
      { path: 'portal', element: <PortalHome /> },
      { path: 'agent', element: <AgentHome /> },
      { path: 'admin', element: <AdminHome /> },
      { path: '*', element: <NotFound /> },
    ],
  },
]);
