import type { RouteObject } from 'react-router';
import { CabMeetingPage } from './CabMeetingPage';
import { CabPage } from './CabPage';
import { CalendarPage } from './CalendarPage';
import { ChangePage } from './ChangePage';
import { ChangeSettingsPage } from './ChangeSettingsPage';
import { ChangesPage } from './ChangesPage';
import { NewChangePage } from './NewChangePage';

/** Маршруты изменений и CAB в рабочем месте агента (10.11). */
export const changeRoutes: RouteObject[] = [
  { path: 'agent/changes', element: <ChangesPage /> },
  { path: 'agent/changes/new', element: <NewChangePage /> },
  { path: 'agent/changes/calendar', element: <CalendarPage /> },
  { path: 'agent/changes/cab', element: <CabPage /> },
  { path: 'agent/changes/cab/:id', element: <CabMeetingPage /> },
  { path: 'agent/changes/:id', element: <ChangePage /> },
];

/** «Управление → Изменения». */
export const changeAdminRoutes: RouteObject[] = [{ path: 'admin/changes', element: <ChangeSettingsPage /> }];
