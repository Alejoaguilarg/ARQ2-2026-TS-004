import { useState, type ReactNode } from 'react';
import { useAuth } from '../auth/AuthContext';
import { Brand } from '../components';
import { navigate, PATHS } from '../router';

export function AppShell({ children }: { children: ReactNode }) {
  const { user, session } = useAuth();
  const [leaving, setLeaving] = useState(false);
  const auditor = user?.roles.includes('AUDITOR') ?? false;
  const path = window.location.pathname;

  async function logout() {
    setLeaving(true);
    await session.logout();
    navigate(PATHS.signIn, true);
  }

  return (
    <div className="page">
      <header className="topbar">
        <div className="topbar-inner">
          <Brand />
          {auditor && (
            <nav aria-label="Sections" className="tabs">
              <a href={PATHS.wallet} aria-current={path === PATHS.wallet ? 'page' : undefined}
                onClick={(e) => { e.preventDefault(); navigate(PATHS.wallet); }}>Wallet</a>
              <a href={PATHS.activity} aria-current={path === PATHS.activity ? 'page' : undefined}
                onClick={(e) => { e.preventDefault(); navigate(PATHS.activity); }}>Activity</a>
            </nav>
          )}
          <div className="topbar-user">
            <span className="avatar-dot" aria-hidden="true">{user?.email.charAt(0).toUpperCase()}</span>
            <span>{user?.email}</span>
            <button type="button" className="button ghost" onClick={logout} disabled={leaving}>
              {leaving ? 'Signing out…' : 'Sign out'}
            </button>
          </div>
        </div>
      </header>
      <main className="content">{children}</main>
    </div>
  );
}
