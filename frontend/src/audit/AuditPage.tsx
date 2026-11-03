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
      setFormError('Pega el identificador completo del envío (36 caracteres).');
      return;
    }
    const query: Search = { userId: userId || undefined, transferId: id || undefined,
      from: dayBound(fromDay, false), to: dayBound(toDay, true) };
    if (!query.userId && !query.transferId && !query.from) {
      setFormError('Elige un cliente, pega un envío o indica desde qué fecha buscar.');
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
        <h1>Actividad de envíos</h1>
        <p className="muted">Todo lo que pasó con cada envío, incluso los intentos que no se completaron.</p>
      </div>

      <section className="glass verify" aria-labelledby="verify-title">
        <div>
          <h2 id="verify-title">¿El registro está intacto?</h2>
          <p className="muted small">Comprobamos que ningún movimiento haya sido modificado ni borrado.</p>
        </div>
        <button type="button" className="button" onClick={check} disabled={verifying}>{verifying ? 'Revisando…' : 'Revisar ahora'}</button>
        {verdict && (
          <p role="status" className={`notice ${verdict.valid ? 'ok' : 'bad'}`}>
            {verdict.valid
              ? <><strong>Todo en orden.</strong> Revisamos {verdict.checkedRecords.toLocaleString('es-CO')} movimientos y ninguno fue modificado.</>
              : <><strong>Atención: encontramos un movimiento alterado.</strong> El movimiento n.º {verdict.firstInvalidSeq?.toLocaleString('es-CO')} no coincide con lo que se guardó. Repórtalo al equipo de seguridad.</>}
          </p>
        )}
        {verifyProblem && <p role="alert" className="notice bad"><strong>{verifyProblem.title}.</strong> {verifyProblem.text}</p>}
      </section>

      <form className="glass audit-search" onSubmit={submit} aria-label="Buscar movimientos" noValidate>
        <label className="field">
          Cliente
          <select value={userId} onChange={(e) => setUserId(e.target.value)}>
            <option value="">Cualquiera</option>
            {CUSTOMERS.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
        <label className="field">
          Envío
          <input value={transferId} onChange={(e) => setTransferId(e.target.value)} placeholder="Identificador completo" spellCheck={false} />
        </label>
        <label className="field">
          Desde
          <input type="date" value={fromDay} onChange={(e) => setFromDay(e.target.value)} />
        </label>
        <label className="field">
          Hasta
          <input type="date" value={toDay} onChange={(e) => setToDay(e.target.value)} />
        </label>
        <button type="submit" className="button primary">Buscar</button>
        {formError && <p role="alert" className="notice bad">{formError}</p>}
      </form>

      <div className="audit-body">
        <section className="glass audit-list" aria-label="Resultados" aria-busy={loading}>
          <div className="row">
            <span className="muted small">
              {loading ? 'Buscando…' : page ? (page.total === 0 ? 'Sin resultados' : `${page.total} movimientos`) : ''}
            </span>
            <div className="pager">
              <button type="button" className="icon-button" aria-label="Página anterior" disabled={loading || pageNo === 0}
                onClick={() => setPageNo((n) => n - 1)}>‹</button>
              <span className="small">{pageNo + 1} de {pages}</span>
              <button type="button" className="icon-button" aria-label="Página siguiente" disabled={loading || pageNo + 1 >= pages}
                onClick={() => setPageNo((n) => n + 1)}>›</button>
            </div>
          </div>
          {problem && <p role="alert" className="notice bad"><strong>{problem.title}.</strong> {problem.text}</p>}
          {page && page.items.length === 0 && <p className="muted">No encontramos movimientos con esa búsqueda.</p>}
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
    ['N.º de registro', String(record.seq)],
    ['Evento', `${record.source} · ${record.stage} · ${record.outcome}`],
    ['Código', record.reason ?? '—'],
    ['Respuesta HTTP', record.httpStatus ? String(record.httpStatus) : '—'],
    ['Usuario', record.userId ?? '—'],
    ['Transferencia', record.transferId ?? '—'],
    ['Clave del envío', record.idempotencyKey ?? '—'],
    ['Correlación', record.correlationId],
    ['Registrado', record.recordedAt],
    ['Huella anterior', record.prevHash],
    ['Huella', record.hash],
  ];
  return (
    <aside className="glass audit-detail" aria-labelledby="detail-title">
      <span className={`badge ${reading.tone}`}>{reading.badge}</span>
      <h2 id="detail-title">{reading.what}</h2>
      <p className="muted">{reading.explain}</p>
      <dl className="summary">
        <div><dt>Cliente</dt><dd>{customer(record.userId)}</dd></div>
        <div><dt>Monto</dt><dd>{record.amount ? `$${formatMoney(record.amount)} COP` : 'Sin monto'}</dd></div>
        <div><dt>Comprobante</dt><dd>{record.transferId ? receipt(record.transferId) : '—'}</dd></div>
        <div><dt>Fecha</dt><dd>{when(record.occurredAt)}</dd></div>
      </dl>
      <button type="button" className="button" disabled={!record.transferId} onClick={() => record.transferId && onTransfer(record.transferId)}>
        Ver todo este envío
      </button>
      <details>
        <summary>Datos técnicos para soporte</summary>
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
    : new Intl.DateTimeFormat('es-CO', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(date);
}
