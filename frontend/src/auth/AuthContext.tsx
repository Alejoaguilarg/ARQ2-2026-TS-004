import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Session, type User } from './session';

type Status = 'loading' | 'signed-out' | 'signed-in';

interface Auth {
  status: Status;
  user: User | null;
  session: Session;
}

const AuthContext = createContext<Auth | null>(null);

export function AuthProvider({ session, children }: { session?: Session; children: ReactNode }) {
  const instance = useMemo(() => session ?? new Session(), [session]);
  const [user, setUser] = useState<User | null>(instance.user);
  const [status, setStatus] = useState<Status>(instance.user ? 'signed-in' : 'loading');

  useEffect(() => {
    const unsubscribe = instance.subscribe((next) => {
      setUser(next);
      setStatus(next ? 'signed-in' : 'signed-out');
    });
    if (!instance.user) {
      instance.restore().then((restored) => {
        setUser(restored);
        setStatus(restored ? 'signed-in' : 'signed-out');
      });
    }
    return unsubscribe;
  }, [instance]);

  const value = useMemo(() => ({ status, user, session: instance }), [status, user, instance]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): Auth {
  const auth = useContext(AuthContext);
  if (!auth) {
    throw new Error('useAuth outside AuthProvider');
  }
  return auth;
}
