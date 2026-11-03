import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { asApiError } from '../api/client';
import { friendly, type FriendlyMessage } from '../api/messages';
import { useAuth } from '../auth/AuthContext';
import {
  balance as readBalance, confirmWithPin, findRecipient, formatMoney, isFinal, progress, receipt, saveSent, sendMoney,
  sentList, toAmount, transfer as readTransfer, type Balance, type SentItem,
} from './wallet';

// Example accounts seeded by identity-service, so a demo can send without asking anyone for a PIN.
const EXAMPLES = [
  { name: 'Ana', email: 'ana@example.com', pin: '4821' },
  { name: 'Beto', email: 'beto@example.com', pin: '1357' },
  { name: 'Auditor', email: 'auditor@example.com', pin: '2468' },
];

type View = 'home' | 'send' | 'pin' | 'track';

// One send intent: the key and financial session stay together until the data changes, so a retry after a lost
// answer can never charge twice.
interface Intent {
  key: string;
  recipientId?: string;
  financialSession?: string;
}

const newIntent = (): Intent => ({ key: crypto.randomUUID() });
const nameOf = (email: string) => EXAMPLES.find((e) => e.email === email.toLowerCase())?.name ?? email;

export function WalletPage({ pollMs = 1500 }: { pollMs?: number }) {
  const { session, user } = useAuth();
  const userId = user?.userId ?? '';
  const me = user?.email ?? '';

  const [wallet, setWallet] = useState<Balance | null>(null);
  const [walletError, setWalletError] = useState<FriendlyMessage | null>(null);
  const [loadingBalance, setLoadingBalance] = useState(false);
  const [sent, setSent] = useState<SentItem[]>(() => sentList(userId));

  const [view, setView] = useState<View>('home');
  const [email, setEmail] = useState('');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [intent, setIntent] = useState<Intent>(newIntent);
  const [pin, setPin] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<FriendlyMessage | null>(null);
  const [emailError, setEmailError] = useState('');
  const [tracking, setTracking] = useState<string | null>(null);

  const refreshBalance = useCallback(async () => {
    setLoadingBalance(true);
    try {
      setWallet(await readBalance(session));
      setWalletError(null);
    } catch (e) {
      setWalletError(friendly(asApiError(e)));
    } finally {
      setLoadingBalance(false);
    }
  }, [session]);

  useEffect(() => {
    void refreshBalance();
  }, [refreshBalance]);

  const remember = useCallback((item: SentItem) => setSent(saveSent(userId, item)), [userId]);

  // Follow the open send until it ends, then show the new balance.
  const current = sent.find((i) => i.transferId === tracking) ?? null;
  const latest = useRef(current);
  latest.current = current;
  useEffect(() => {
    if (!tracking) {
      return undefined;
    }
    let stop = false;
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      const known = latest.current;
      if (!known || stop) {
        return;
      }
      try {
        const view = await readTransfer(session, tracking);
        if (stop) {
          return;
        }
        remember({ ...known, status: view.status, failureReason: view.failureReason });
        if (isFinal(view.status)) {
          void refreshBalance();
          return;
        }
      } catch {
        // A lost poll is tried again on the next tick.
      }
      timer = setTimeout(tick, pollMs);
    };
    void tick();
    return () => {
      stop = true;
      clearTimeout(timer);
    };
  }, [tracking, session, pollMs, remember, refreshBalance]);

  const parsedAmount = toAmount(amount);
  const amountError = amount && !parsedAmount ? 'Enter an amount greater than 0 with at most 2 decimals.' : '';
  const emailLooksValid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim());
  const ownEmail = email.trim().toLowerCase() === me;
  const shownEmailError = ownEmail ? 'That email is yours.' : emailError;

  // Any change to who, how much or why is a new intent.
  const edit = (set: (value: string) => void) => (value: string) => {
    set(value);
    setIntent(newIntent());
    setEmailError('');
    setProblem(null);
  };

  function startSend(to = '') {
    setEmail(to);
    setAmount('');
    setDescription('');
    setIntent(newIntent());
    setPin('');
    setProblem(null);
    setEmailError('');
    setView('send');
  }

  async function toPin(event: FormEvent) {
    event.preventDefault();
    if (busy || !parsedAmount || !emailLooksValid || ownEmail) {
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      const recipientId = intent.recipientId ?? await findRecipient(session, email);
      if (recipientId === userId) {
        setEmailError('That email is yours.');
        return;
      }
      setIntent((i) => ({ ...i, recipientId }));
      setPin('');
      setView('pin');
    } catch (e) {
      const error = asApiError(e);
      if (error.code === 'USER_NOT_FOUND') {
        setEmailError('We could not find a Finova account with that email.');
      } else {
        setProblem(friendly(error));
      }
    } finally {
      setBusy(false);
    }
  }

  async function confirm(event: FormEvent) {
    event.preventDefault();
    if (busy || !intent.recipientId || !parsedAmount) {
      return;
    }
    setBusy(true);
    setProblem(null);
    let financialSession = intent.financialSession;
    try {
      if (!financialSession) {
        financialSession = await confirmWithPin(session, pin);
        const confirmed = financialSession;
        setIntent((i) => ({ ...i, financialSession: confirmed }));
      }
      const accepted = await sendMoney(session,
        { recipientUserId: intent.recipientId, amount: parsedAmount, description: description.trim() },
        intent.key, financialSession);
      remember({
        transferId: accepted.transferId, to: email.trim().toLowerCase(), amount: parsedAmount,
        description: description.trim(), createdAt: accepted.createdAt, status: accepted.status,
      });
      setTracking(accepted.transferId);
      setView('track');
    } catch (e) {
      const error = asApiError(e);
      const code = error.code;
      // No answer to the send itself: retrying is safe, because the same key is replayed and never charged twice.
      const unanswered = financialSession && (error.status === 0 || error.status >= 500);
      setProblem(unanswered
        ? { title: 'We do not know whether your transfer went out', text: 'Try again: if it already went out, you will not be charged again.' }
        : friendly(error));
      if (code.startsWith('FINANCIAL_SESSION_') || code === 'STEP_UP_FAILED') {
        // The PIN is asked again; the key stays, so it is still the same send.
        setIntent((i) => ({ ...i, financialSession: undefined }));
        setPin('');
      }
    } finally {
      setBusy(false);
    }
  }

  const recipients = [...new Set([...sent.map((i) => i.to), ...EXAMPLES.map((e) => e.email)])]
    .filter((to) => to !== me).slice(0, 4);
  const demoPin = EXAMPLES.find((e) => e.email === me)?.pin;

  return (
    <div className="wallet">
      <div className="wallet-side">
        <section className="glass balance-card" aria-labelledby="balance-title">
          <div className="row">
            <h2 id="balance-title">Available balance</h2>
            <button type="button" className="icon-button" aria-label="Refresh balance" onClick={refreshBalance} disabled={loadingBalance}>
              <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M20 11a8 8 0 1 0-2.3 5.7M20 5v6h-6" />
              </svg>
            </button>
          </div>
          {walletError ? (
            <div role="alert" className="notice warn"><strong>{walletError.title}.</strong> {walletError.text}</div>
          ) : (
            <p className="balance" aria-live="polite" aria-busy={!wallet}>
              {wallet ? <>${formatMoney(wallet.available)} <span>{wallet.currency}</span></> : 'Loading…'}
            </p>
          )}
          <button type="button" className="button primary" onClick={() => startSend()} disabled={!!walletError}>Send money</button>
        </section>

        <section className="glass sent" aria-labelledby="sent-title">
          <h2 id="sent-title">Transfers from this device</h2>
          {sent.length === 0 ? (
            <p className="muted">No transfers yet. When you make one, follow it from here.</p>
          ) : (
            <ul>
              {sent.map((item) => {
                const p = progress(item.status, item.failureReason, nameOf(item.to));
                return (
                  <li key={item.transferId}>
                    <button type="button" className="sent-item" onClick={() => { setTracking(item.transferId); setView('track'); }}>
                      <span className="initial" aria-hidden="true">{nameOf(item.to).charAt(0).toUpperCase()}</span>
                      <span className="who">
                        <strong>{nameOf(item.to)}</strong>
                        <span>{when(item.createdAt)}{item.description ? ` · ${item.description}` : ''}</span>
                      </span>
                      <span className="how">
                        <strong>−${formatMoney(item.amount)}</strong>
                        <span className={`badge ${p.tone}`}>{p.badge}</span>
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      </div>

      <section className="glass wallet-panel" aria-live="polite">
        {view === 'home' && (
          <div className="stack">
            <h1>Who are you sending to today?</h1>
            <p className="muted">Pick a contact or type their email. Before sending we will ask for your PIN, and you can follow your transfer step by step.</p>
            <div className="chips">
              {recipients.map((to) => (
                <button key={to} type="button" className="chip" onClick={() => startSend(to)} disabled={!!walletError}>{nameOf(to)}</button>
              ))}
            </div>
          </div>
        )}

        {view === 'send' && (
          <form className="stack" onSubmit={toPin} noValidate>
            <div className="row">
              <h1>New transfer</h1>
              <button type="button" className="button ghost" onClick={() => setView('home')}>Cancel</button>
            </div>
            <div className="field">
              <label htmlFor="send-email">Recipient email</label>
              <input id="send-email" type="email" value={email} placeholder="name@email.com" spellCheck={false} autoComplete="off"
                aria-invalid={!!shownEmailError} aria-describedby={shownEmailError ? 'send-email-error' : undefined}
                onChange={(e) => edit(setEmail)(e.target.value)} />
              {shownEmailError && <span id="send-email-error" className="field-error">{shownEmailError}</span>}
            </div>
            <div className="field">
              <label htmlFor="send-amount">Amount in pesos</label>
              <input id="send-amount" inputMode="decimal" value={amount} placeholder="0.00" aria-invalid={!!amountError}
                aria-describedby={amountError ? 'send-amount-error' : undefined}
                onChange={(e) => edit(setAmount)(e.target.value)} />
              {amountError && <span id="send-amount-error" className="field-error">{amountError}</span>}
            </div>
            <label className="field">
              Description (optional)
              <input value={description} maxLength={140} placeholder="Dinner, rent, gift…"
                onChange={(e) => edit(setDescription)(e.target.value)} />
            </label>
            <p className="notice calm">If the connection drops, you can safely try again: we never charge the same transfer twice.</p>
            {problem && <div role="alert" className="notice bad"><strong>{problem.title}.</strong> {problem.text}</div>}
            <button type="submit" className="button primary" disabled={busy || !parsedAmount || !emailLooksValid || ownEmail}>
              {busy ? 'Looking up…' : 'Continue'}
            </button>
          </form>
        )}

        {view === 'pin' && (
          <form className="stack" onSubmit={confirm} noValidate>
            <div className="row">
              <h1>Confirm with your PIN</h1>
              <button type="button" className="button ghost" onClick={() => { setProblem(null); setView('send'); }} disabled={busy}>Edit transfer</button>
            </div>
            <dl className="summary">
              <div><dt>To</dt><dd>{nameOf(email.trim())}</dd></div>
              <div><dt>Amount</dt><dd>${parsedAmount && formatMoney(parsedAmount)} COP</dd></div>
            </dl>
            {!intent.financialSession && (
              <label className="field">
                4-digit PIN
                <input type="password" inputMode="numeric" autoComplete="one-time-code" maxLength={4} value={pin}
                  onChange={(e) => setPin(e.target.value.replace(/\D/g, '').slice(0, 4))} />
              </label>
            )}
            {problem && <div role="alert" className="notice bad"><strong>{problem.title}.</strong> {problem.text}</div>}
            {demoPin && !intent.financialSession && <p className="muted small">Example account: your PIN is {demoPin}.</p>}
            <button type="submit" className="button primary" disabled={busy || (!intent.financialSession && pin.length !== 4)}>
              {busy ? 'Sending…' : intent.financialSession ? 'Try again' : 'Confirm and send'}
            </button>
          </form>
        )}

        {view === 'track' && current && <Tracking item={current} onAgain={() => startSend()} onDone={() => setView('home')} />}
      </section>
    </div>
  );
}

function Tracking({ item, onAgain, onDone }: { item: SentItem; onAgain: () => void; onDone: () => void }) {
  const p = progress(item.status, item.failureReason, nameOf(item.to));
  return (
    <div className="stack">
      <div className="row">
        <div>
          <span className="muted small">Transfer to {nameOf(item.to)}</span>
          <h1>${formatMoney(item.amount)} <span className="currency">COP</span></h1>
        </div>
        <span className={`badge ${p.tone}`}>{p.badge}</span>
      </div>
      <p className={`notice ${p.tone}`}>{p.headline}</p>
      <ol className="steps">
        {p.steps.map((step) => (
          <li key={step.label} className={step.kind}>
            <span className="dot" aria-hidden="true" />
            <span><strong>{step.label}</strong><span>{step.detail}</span></span>
          </li>
        ))}
      </ol>
      <dl className="summary">
        <div><dt>Description</dt><dd>{item.description || 'No description'}</dd></div>
        <div><dt>Time</dt><dd>{when(item.createdAt)}</dd></div>
        <div><dt>Receipt</dt><dd>{receipt(item.transferId)}</dd></div>
      </dl>
      <div className="row">
        <button type="button" className="button" onClick={onAgain}>Make another transfer</button>
        <button type="button" className="button primary" onClick={onDone}>Done</button>
      </div>
    </div>
  );
}

function when(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? ''
    : new Intl.DateTimeFormat('en-US', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }).format(date);
}
