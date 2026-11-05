import { createBrowserRouter, Navigate } from 'react-router';
import { AppLayout } from './layout/AppLayout';
import { PortalHome } from '@/features/portal/PortalHome';
import { CatalogPage } from '@/features/catalog/CatalogPage';
import { ApprovalsPage } from '@/features/catalog/ApprovalsPage';
import { CatalogAdminPage } from '@/features/catalog/admin/CatalogAdminPage';
import { ItemEditorPage } from '@/features/catalog/admin/ItemEditorPage';
import { MyTicketsPage } from '@/features/portal/MyTicketsPage';
import { OrderPage, ReportProblemPage } from '@/features/portal/OrderPage';
import { TicketPage } from '@/features/portal/TicketPage';
import { AgentHome } from '@/features/agent/AgentHome';
import { AdminHome } from '@/features/admin/AdminHome';
import { ArticleEditor } from '@/features/knowledge/ArticleEditor';
import { ArticleHistory } from '@/features/knowledge/ArticleHistory';
import { ArticlePage } from '@/features/knowledge/ArticlePage';
import { KnowledgePortal } from '@/features/knowledge/KnowledgePortal';
import { KnowledgeWorkspace } from '@/features/knowledge/KnowledgeWorkspace';
import { NewArticle } from '@/features/knowledge/NewArticle';
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
      { path: 'portal/catalog', element: <CatalogPage /> },
      { path: 'portal/catalog/:itemId', element: <OrderPage /> },
      { path: 'portal/report', element: <ReportProblemPage /> },
      { path: 'portal/tickets', element: <MyTicketsPage /> },
      { path: 'portal/tickets/:ref', element: <TicketPage /> },
      { path: 'portal/approvals', element: <ApprovalsPage /> },
      { path: 'agent/approvals', element: <ApprovalsPage /> },
      { path: 'admin/catalog', element: <CatalogAdminPage /> },
      { path: 'admin/catalog/items/:id', element: <ItemEditorPage /> },
      { path: 'portal/kb', element: <KnowledgePortal /> },
      { path: 'portal/kb/:id', element: <ArticlePage space="portal" /> },
      { path: 'agent', element: <AgentHome /> },
      { path: 'agent/kb', element: <KnowledgeWorkspace /> },
      { path: 'agent/kb/new', element: <NewArticle /> },
      { path: 'agent/kb/:id', element: <ArticlePage space="agent" /> },
      { path: 'agent/kb/:id/edit', element: <ArticleEditor /> },
      { path: 'agent/kb/:id/history', element: <ArticleHistory /> },
      { path: 'admin', element: <AdminHome /> },
      { path: '*', element: <NotFound /> },
    ],
  },
]);
