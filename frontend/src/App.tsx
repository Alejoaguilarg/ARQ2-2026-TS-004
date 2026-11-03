import { Backdrop, Brand } from './components';

// The screens (sign-in, wallet, activity) arrive in the next branches; this is the shell they render in.
export function App() {
  return (
    <>
      <Backdrop />
      <div className="page" style={{ display: 'grid', placeItems: 'center', padding: 24 }}>
        <main className="glass" style={{ padding: 32, maxWidth: 420, textAlign: 'center' }}>
          <Brand />
          <p style={{ color: 'var(--muted)' }}>Tu billetera está casi lista.</p>
        </main>
      </div>
    </>
  );
}
