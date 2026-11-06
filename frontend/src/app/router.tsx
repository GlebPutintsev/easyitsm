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
import { agentRoutes } from '@/features/agent/routes';
import { AdminHome } from '@/features/admin/AdminHome';
import { AuditPage } from '@/features/audit/AuditPage';
import { LoginPage } from '@/features/auth/LoginPage';
import { ForgotPasswordPage, ResetPasswordPage } from '@/features/auth/PasswordPages';
import { RequireAuth } from '@/features/auth/RequireAuth';
import { SecurityPage } from '@/features/profile/SecurityPage';
import { ArticleEditor } from '@/features/knowledge/ArticleEditor';
import { ArticleHistory } from '@/features/knowledge/ArticleHistory';
import { ArticlePage } from '@/features/knowledge/ArticlePage';
import { KnowledgePortal } from '@/features/knowledge/KnowledgePortal';
import { KnowledgeWorkspace } from '@/features/knowledge/KnowledgeWorkspace';
import { NewArticle } from '@/features/knowledge/NewArticle';
import { NotFound } from './NotFound';

/**
 * Пространства (раздел 10): портал, рабочее место агента, управление. Всё, кроме экранов входа,
 * доступно только после входа (RequireAuth).
 * Каждый модуль добавляет свои маршруты в children нужного пространства.
 */
/** Экран сам задаёт поля и ширину содержимого (портал «Ясность»). */
const bleed = { bleed: true };

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  { path: '/forgot-password', element: <ForgotPasswordPage /> },
  { path: '/reset-password', element: <ResetPasswordPage /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Navigate to="/portal" replace /> },
      { path: 'portal', element: <PortalHome />, handle: bleed },
      { path: 'portal/catalog', element: <CatalogPage />, handle: bleed },
      { path: 'portal/catalog/:itemId', element: <OrderPage />, handle: bleed },
      { path: 'portal/report', element: <ReportProblemPage />, handle: bleed },
      { path: 'portal/tickets', element: <MyTicketsPage />, handle: bleed },
      { path: 'portal/tickets/:ref', element: <TicketPage />, handle: bleed },
      { path: 'portal/approvals', element: <ApprovalsPage />, handle: bleed },
      { path: 'agent/approvals', element: <ApprovalsPage /> },
      { path: 'admin/catalog', element: <CatalogAdminPage /> },
      { path: 'admin/catalog/items/:id', element: <ItemEditorPage /> },
      { path: 'portal/kb', element: <KnowledgePortal /> },
      { path: 'portal/kb/:id', element: <ArticlePage space="portal" /> },
      agentRoutes,
      { path: 'agent/kb', element: <KnowledgeWorkspace /> },
      { path: 'agent/kb/new', element: <NewArticle /> },
      { path: 'agent/kb/:id', element: <ArticlePage space="agent" /> },
      { path: 'agent/kb/:id/edit', element: <ArticleEditor /> },
      { path: 'agent/kb/:id/history', element: <ArticleHistory /> },
      { path: 'admin', element: <AdminHome /> },
      { path: 'admin/audit', element: <AuditPage /> },
      { path: 'profile/security', element: <SecurityPage /> },
      { path: '*', element: <NotFound /> },
    ],
  },
]);
