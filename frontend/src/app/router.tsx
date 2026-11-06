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
import { AutomationPage } from '@/features/automation/AutomationPage';
import { RuleEditorPage } from '@/features/automation/RuleEditorPage';
import { DictionariesPage } from '@/features/admin/DictionariesPage';
import { GroupsPage } from '@/features/admin/GroupsPage';
import { ImportPage } from '@/features/admin/ImportPage';
import { JournalPage } from '@/features/admin/JournalPage';
import { SetupWizard } from '@/features/admin/SetupWizard';
import { UsersPage } from '@/features/admin/UsersPage';
import { MailSettingsPage } from '@/features/mail/MailSettingsPage';
import { TemplatesPage } from '@/features/notifications/TemplatesPage';
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
import { ReportsPage } from '@/features/reports/ReportsPage';
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
      { path: 'agent/reports', element: <ReportsPage /> },
      { path: 'agent/reports/:dashboard', element: <ReportsPage /> },
      { path: 'admin', element: <AdminHome /> },
      { path: 'admin/audit', element: <AuditPage /> },
      { path: 'admin/automation', element: <AutomationPage /> },
      { path: 'admin/automation/rules/:id', element: <RuleEditorPage /> },
      { path: 'admin/setup', element: <SetupWizard /> },
      { path: 'admin/users', element: <UsersPage /> },
      { path: 'admin/groups', element: <GroupsPage /> },
      { path: 'admin/dictionaries', element: <DictionariesPage /> },
      { path: 'admin/import', element: <ImportPage /> },
      { path: 'admin/journal', element: <JournalPage /> },
      { path: 'admin/mail', element: <MailSettingsPage /> },
      { path: 'admin/notification-templates', element: <TemplatesPage /> },
      { path: 'profile/security', element: <SecurityPage /> },
      { path: '*', element: <NotFound /> },
    ],
  },
]);
