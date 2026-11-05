import { Center, Loader } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, type ReactNode } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { setAuthHandlers } from '@/shared/api/client';
import { ListState } from '@/shared/ui/ListState';
import { meKey, useMe } from './api';
import { ReauthModal, useReauthController } from './ReauthModal';

/**
 * Пускает в приложение только после входа. Скрытие разделов в интерфейсе — удобство,
 * права проверяет сервер на каждом запросе (ACC-01).
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const me = useMe();
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const reauth = useReauthController();

  useEffect(() => {
    setAuthHandlers({
      reauthenticate: reauth.request,
      sessionExpired: () => {
        queryClient.setQueryData(meKey, null);
        const next = window.location.pathname + window.location.search;
        navigate(`/login?next=${encodeURIComponent(next)}`, { replace: true });
      },
    });
  }, [navigate, queryClient, reauth.request]);

  if (me.isPending) {
    return (
      <Center mih="100dvh">
        <Loader />
      </Center>
    );
  }
  if (me.isError) {
    return <ListState state="error" error={me.error} onRetry={() => void me.refetch()} />;
  }
  if (!me.data) {
    return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;
  }
  return (
    <>
      {children}
      <ReauthModal controller={reauth} me={me.data} />
    </>
  );
}
