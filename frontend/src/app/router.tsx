import { createBrowserRouter, Navigate } from 'react-router';
import { AppLayout } from './layout/AppLayout';
import { PortalHome } from '@/features/portal/PortalHome';
import { agentRoutes } from '@/features/agent/routes';
import { AdminHome } from '@/features/admin/AdminHome';
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
      { path: 'portal', element: <PortalHome /> },
      { path: 'portal/kb', element: <KnowledgePortal /> },
      { path: 'portal/kb/:id', element: <ArticlePage space="portal" /> },
      agentRoutes,
      { path: 'agent/kb', element: <KnowledgeWorkspace /> },
      { path: 'agent/kb/new', element: <NewArticle /> },
      { path: 'agent/kb/:id', element: <ArticlePage space="agent" /> },
      { path: 'agent/kb/:id/edit', element: <ArticleEditor /> },
      { path: 'agent/kb/:id/history', element: <ArticleHistory /> },
      { path: 'admin', element: <AdminHome /> },
      { path: 'profile/security', element: <SecurityPage /> },
      { path: '*', element: <NotFound /> },
    ],
  },
]);
