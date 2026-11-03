import { useEffect } from 'react';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { LoginPage } from './auth/LoginPage';
import type { Session } from './auth/session';
import { Backdrop } from './components';
import { AppShell } from './layout/AppShell';
import { navigate, PATHS, usePath } from './router';
import { WalletPage } from './wallet/WalletPage';

function Screens() {
  const { status, user } = useAuth();
  const path = usePath();
  const auditor = user?.roles.includes('AUDITOR') ?? false;
  const home = auditor ? PATHS.activity : PATHS.wallet;

  // Signed out: only the sign-in screen. Signed in: never the sign-in screen, and Actividad only for auditors.
  const target = status === 'signed-out' ? PATHS.signIn
    : status === 'signed-in' && (path === PATHS.signIn || path === '/' || (path === PATHS.activity && !auditor)
      || (path !== PATHS.wallet && path !== PATHS.activity)) ? home : null;
  useEffect(() => {
    if (target) {
      navigate(target, true);
    }
  }, [target]);

  if (status === 'loading') {
    return <div className="page center" role="status" aria-label="Cargando" />;
  }
  if (status === 'signed-out') {
    return <LoginPage />;
  }
  return (
    <AppShell>
      {path === PATHS.activity ? (
        <section className="glass placeholder"><h1>Actividad de envíos</h1></section>
      ) : (
        <WalletPage />
      )}
    </AppShell>
  );
}

export function App({ session }: { session?: Session }) {
  return (
    <AuthProvider session={session}>
      <Backdrop />
      <Screens />
    </AuthProvider>
  );
}
