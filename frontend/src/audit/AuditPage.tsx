import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { asApiError } from '../api/client';
import { friendly, type FriendlyMessage } from '../api/messages';
import { useAuth } from '../auth/AuthContext';
import { formatMoney, receipt } from '../wallet/wallet';
import {
  CUSTOMERS, customer, dayBound, PAGE_SIZE, read, records, UUID, verify, type AuditPage as Page, type AuditRecord,
  type Search, type Verification,
} from './audit';

const isoDay = (date: Date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;

const weekAgo = () => isoDay(new Date(Date.now() - 6 * 24 * 3600 * 1000));

export function AuditPage() {
  const { session } = useAuth();
  const [userId, setUserId] = useState('');
  const [transferId, setTransferId] = useState('');
  const [fromDay, setFromDay] = useState(weekAgo);
  const [toDay, setToDay] = useState('');
  const [formError, setFormError] = useState('');

  const [search, setSearch] = useState<Search>(() => ({ from: dayBound(weekAgo(), false) }));
  const [pageNo, setPageNo] = useState(0);
  const [page, setPage] = useState<Page | null>(null);
  const [loading, setLoading] = useState(false);
  const [problem, setProblem] = useState<FriendlyMessage | null>(null);
  const [selected, setSelected] = useState<AuditRecord | null>(null);

  const [verifying, setVerifying] = useState(false);
  const [verdict, setVerdict] = useState<Verification | null>(null);
  const [verifyProblem, setVerifyProblem] = useState<FriendlyMessage | null>(null);

  const load = useCallback(async (query: Search, number: number) => {
    setLoading(true);
    setProblem(null);
    try {
      const result = await records(session, query, number);
      setPage(result);
      setSelected(result.items[0] ?? null);
    } catch (e) {
      setProblem(friendly(asApiError(e)));
      setPage(null);
      setSelected(null);
    } finally {
      setLoading(false);
    }
  }, [session]);

  useEffect(() => {
    void load(search, pageNo);
  }, [load, search, pageNo]);

  function submit(event: FormEvent) {
    event.preventDefault();
    const id = transferId.trim();
    if (id && !UUID.test(id)) {
      setFormError('Paste the full transfer identifier (36 characters).');
      return;
    }
    const query: Search = { userId: userId || undefined, transferId: id || undefined,
      from: dayBound(fromDay, false), to: dayBound(toDay, true) };
    if (!query.userId && !query.transferId && !query.from) {
      setFormError('Choose a customer, paste a transfer or give a start date.');
      return;
    }
    setFormError('');
    setPageNo(0);
    setSearch(query);
  }

  function wholeTransfer(id: string) {
    setUserId('');
    setTransferId(id);
    setFromDay('');
    setToDay('');
    setFormError('');
    setPageNo(0);
    setSearch({ transferId: id });
  }

  async function check() {
    setVerifying(true);
    setVerifyProblem(null);
    try {
      setVerdict(await verify(session));
    } catch (e) {
      setVerdict(null);
      setVerifyProblem(friendly(asApiError(e)));
    } finally {
      setVerifying(false);
    }
  }

  const pages = page ? Math.max(1, Math.ceil(page.total / PAGE_SIZE)) : 1;

  return (
    <div className="audit">
      <div className="audit-head">
        <h1>Transfer activity</h1>
        <p className="muted">Everything that happened to each transfer, including the attempts that did not complete.</p>
      </div>

      <section className="glass verify" aria-labelledby="verify-title">
        <div>
          <h2 id="verify-title">Is the record intact?</h2>
          <p className="muted small">We check that no record was changed or deleted.</p>
        </div>
        <button type="button" className="button" onClick={check} disabled={verifying}>{verifying ? 'Checking…' : 'Check now'}</button>
        {verdict && (
          <p role="status" className={`notice ${verdict.valid ? 'ok' : 'bad'}`}>
            {verdict.valid
              ? <><strong>All good.</strong> We checked {verdict.checkedRecords.toLocaleString('en-US')} records and none was changed.</>
              : <><strong>Warning: we found an altered record.</strong> Record no. {verdict.firstInvalidSeq?.toLocaleString('en-US')} does not match what was stored. Report it to the security team.</>}
          </p>
        )}
        {verifyProblem && <p role="alert" className="notice bad"><strong>{verifyProblem.title}.</strong> {verifyProblem.text}</p>}
      </section>

      <form className="glass audit-search" onSubmit={submit} aria-label="Search records" noValidate>
        <label className="field">
          Customer
          <select value={userId} onChange={(e) => setUserId(e.target.value)}>
            <option value="">Any</option>
            {CUSTOMERS.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
        <label className="field">
          Transfer
          <input value={transferId} onChange={(e) => setTransferId(e.target.value)} placeholder="Full identifier" spellCheck={false} />
        </label>
        <label className="field">
          From
          <input type="date" value={fromDay} onChange={(e) => setFromDay(e.target.value)} />
        </label>
        <label className="field">
          To
          <input type="date" value={toDay} onChange={(e) => setToDay(e.target.value)} />
        </label>
        <button type="submit" className="button primary">Search</button>
        {formError && <p role="alert" className="notice bad">{formError}</p>}
      </form>

      <div className="audit-body">
        <section className="glass audit-list" aria-label="Resultados" aria-busy={loading}>
          <div className="row">
            <span className="muted small">
              {loading ? 'Looking up…' : page ? (page.total === 0 ? 'No results' : `${page.total} records`) : ''}
            </span>
            <div className="pager">
              <button type="button" className="icon-button" aria-label="Previous page" disabled={loading || pageNo === 0}
                onClick={() => setPageNo((n) => n - 1)}>‹</button>
              <span className="small">{pageNo + 1} of {pages}</span>
              <button type="button" className="icon-button" aria-label="Next page" disabled={loading || pageNo + 1 >= pages}
                onClick={() => setPageNo((n) => n + 1)}>›</button>
            </div>
          </div>
          {problem && <p role="alert" className="notice bad"><strong>{problem.title}.</strong> {problem.text}</p>}
          {page && page.items.length === 0 && <p className="muted">No records match that search.</p>}
          <ul>
            {page?.items.map((r) => {
              const reading = read(r);
              return (
                <li key={r.seq}>
                  <button type="button" className="audit-row" aria-pressed={selected?.seq === r.seq} onClick={() => setSelected(r)}>
                    <span className="who">
                      <strong>{reading.what}</strong>
                      <span>{when(r.occurredAt)}{r.transferId ? ` · ${receipt(r.transferId)}` : ''}</span>
                    </span>
                    <span className="how">
                      {r.amount && <strong>${formatMoney(r.amount)}</strong>}
                      <span className={`badge ${reading.tone}`}>{reading.badge}</span>
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </section>

        {selected && <Detail record={selected} onTransfer={wholeTransfer} />}
      </div>
    </div>
  );
}

function Detail({ record, onTransfer }: { record: AuditRecord; onTransfer: (id: string) => void }) {
  const reading = read(record);
  const tech: [string, string][] = [
    ['Record no.', String(record.seq)],
    ['Event', `${record.source} · ${record.stage} · ${record.outcome}`],
    ['Code', record.reason ?? '—'],
    ['HTTP status', record.httpStatus ? String(record.httpStatus) : '—'],
    ['User', record.userId ?? '—'],
    ['Transfer', record.transferId ?? '—'],
    ['Idempotency key', record.idempotencyKey ?? '—'],
    ['Correlation', record.correlationId],
    ['Recorded', record.recordedAt],
    ['Previous hash', record.prevHash],
    ['Hash', record.hash],
  ];
  return (
    <aside className="glass audit-detail" aria-labelledby="detail-title">
      <span className={`badge ${reading.tone}`}>{reading.badge}</span>
      <h2 id="detail-title">{reading.what}</h2>
      <p className="muted">{reading.explain}</p>
      <dl className="summary">
        <div><dt>Customer</dt><dd>{customer(record.userId)}</dd></div>
        <div><dt>Amount</dt><dd>{record.amount ? `$${formatMoney(record.amount)} COP` : 'No amount'}</dd></div>
        <div><dt>Receipt</dt><dd>{record.transferId ? receipt(record.transferId) : '—'}</dd></div>
        <div><dt>Date</dt><dd>{when(record.occurredAt)}</dd></div>
      </dl>
      <button type="button" className="button" disabled={!record.transferId} onClick={() => record.transferId && onTransfer(record.transferId)}>
        See this whole transfer
      </button>
      <details>
        <summary>Technical data for support</summary>
        <dl className="summary tech">
          {tech.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}
        </dl>
      </details>
    </aside>
  );
}

function when(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? ''
    : new Intl.DateTimeFormat('en-US', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(date);
}
