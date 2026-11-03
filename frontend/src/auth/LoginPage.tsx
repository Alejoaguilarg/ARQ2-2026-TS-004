import { useState, type FormEvent } from 'react';
import { ApiFailure } from '../api/client';
import { friendly, type FriendlyMessage } from '../api/messages';
import { Brand } from '../components';
import { useAuth } from './AuthContext';
import { Avatar, type Focus, type Mood } from './Avatar';

// Example accounts seeded by identity-service (V2__demo_users.sql); all use the same password.
const EXAMPLES = [
  { label: 'Ana', email: 'ana@example.com' },
  { label: 'Beto', email: 'beto@example.com' },
  { label: 'Auditor', email: 'auditor@example.com' },
];
const EXAMPLE_PASSWORD = 'Finova2026!';

export function LoginPage({ celebrateMs = 900 }: { celebrateMs?: number }) {
  const { session } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [focus, setFocus] = useState<Focus>('none');
  const [showPassword, setShowPassword] = useState(false);
  const [mood, setMood] = useState<Mood>('idle');
  const [shakes, setShakes] = useState(0);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<FriendlyMessage | null>(null);
  const [greeting, setGreeting] = useState('');

  const typing = (set: (value: string) => void) => (value: string) => {
    set(value);
    if (mood === 'fail') {
      setMood('idle');
    }
  };

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) {
      return;
    }
    setBusy(true);
    setMessage(null);
    setFocus('none');
    try {
      const user = await session.login(email, password);
      setMood('ok');
      setGreeting(EXAMPLES.find((e) => e.email === user.email)?.label ?? '');
      setTimeout(() => session.publish(), celebrateMs);
    } catch (e) {
      setBusy(false);
      setMood('fail');
      setShakes((n) => n + 1);
      setMessage(friendly(e instanceof ApiFailure ? e.error : { status: 0, code: 'NETWORK_ERROR', message: '' }));
    }
  }

  const covering = focus === 'password' && mood === 'idle';
  const heading = mood === 'ok' ? (greeting ? `¡Hola, ${greeting}!` : '¡Hola!')
    : covering && !showPassword ? 'No estoy mirando'
      : covering ? 'Bueno… solo un poquito' : 'Hola de nuevo';
  const subheading = mood === 'ok' ? 'Todo listo. Entrando a tu cuenta…'
    : covering && !showPassword ? 'Tu contraseña es solo tuya.'
      : covering ? 'Estás mostrando la contraseña.' : 'Ingresa para ver tu billetera.';

  return (
    <div className="page login">
      <section className="login-hero">
        <Brand />
        <h1>Tu dinero se mueve rápido. <span>Nunca dos veces.</span></h1>
        <p>Envía pesos a quien quieras en segundos. Confirmas cada envío con tu PIN y, si algo falla en el camino, tu dinero vuelve solo.</p>
        <ul className="pills">
          <li className="glass-pill"><strong>Al instante</strong> entre cuentas Finova</li>
          <li className="glass-pill"><strong>Con tu PIN</strong> confirmas cada envío</li>
          <li className="glass-pill"><strong>Sin cobros dobles</strong> aunque se caiga la conexión</li>
        </ul>
      </section>

      <section className="login-side">
        <div key={mood === 'fail' ? `fail-${shakes}` : 'card'} className={`glass login-card ${mood === 'fail' ? 'shake' : ''}`}>
          <div className={`avatar-wrap ${mood === 'fail' ? 'nope' : ''} ${mood === 'ok' ? 'hop' : ''}`}>
            <Avatar focus={focus} emailLength={email.length} showPassword={showPassword} mood={mood} />
          </div>
          <div className="login-heading" aria-live="polite">
            <h2>{heading}</h2>
            <p>{subheading}</p>
          </div>

          {message && (
            <div role="alert" className="notice bad">
              <strong>{message.title}.</strong> {message.text}
            </div>
          )}

          <form onSubmit={submit} className="stack" noValidate>
            <label className="field">
              Correo
              <input type="email" autoComplete="username" placeholder="tu@correo.com" value={email} required
                onChange={(e) => typing(setEmail)(e.target.value)} onFocus={() => setFocus('email')}
                onBlur={() => setFocus('none')} disabled={mood === 'ok'} />
            </label>
            <div className="field">
              <label htmlFor="sign-in-password">Contraseña</label>
              <span className="with-action">
                <input id="sign-in-password" type={showPassword ? 'text' : 'password'} autoComplete="current-password" value={password} required
                  onChange={(e) => typing(setPassword)(e.target.value)} onFocus={() => setFocus('password')}
                  onBlur={() => setFocus('none')} disabled={mood === 'ok'} />
                <button type="button" className="icon-button" aria-pressed={showPassword}
                  aria-label={showPassword ? 'Ocultar contraseña' : 'Mostrar contraseña'}
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => { setShowPassword(!showPassword); setFocus('password'); }}>
                  <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                    <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z" />
                    <circle cx="12" cy="12" r="3" />
                    {showPassword && <path d="M4 4l16 16" />}
                  </svg>
                </button>
              </span>
            </div>
            <button type="submit" className="button primary" disabled={busy || !email || !password}>
              {mood === 'ok' ? 'Entrando…' : busy ? 'Verificando…' : 'Ingresar'}
            </button>
          </form>

          <div className="examples">
            <span>Prueba con una cuenta de ejemplo</span>
            <div>
              {EXAMPLES.map((example) => (
                <button key={example.email} type="button" className="chip" disabled={busy}
                  onClick={() => { setEmail(example.email); setPassword(EXAMPLE_PASSWORD); setMood('idle'); setMessage(null); }}>
                  {example.label}
                </button>
              ))}
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
