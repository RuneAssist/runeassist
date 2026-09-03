import React, { useEffect, useMemo, useState } from 'react';

// eslint-disable-next-line no-undef -- injected by vite.config.js `define`, see comment there
console.debug('RuneAssist build', __BUILD_TIME__);

function route() {
  const h = (window.location.hash || '#/login').replace(/^#/, '') || '/login';
  return h.split('?')[0];
}

function hashParams() {
  const h = window.location.hash || '';
  const q = h.includes('?') ? h.slice(h.indexOf('?') + 1) : '';
  return new URLSearchParams(q);
}

async function api(path, opts = {}) {
  const r = await fetch(path, { credentials: 'include', ...opts, headers: { 'Content-Type': 'application/json', ...(opts.headers || {}) } });
  const text = await r.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = { error: text }; }
  if (!r.ok) throw new Error((body && body.error) || `HTTP ${r.status}`);
  return body;
}

function gp(n) {
  const v = Number(n || 0);
  const sign = v < 0 ? '-' : '';
  const a = Math.abs(v);
  if (a >= 1e9) return `${sign}${(a / 1e9).toFixed(2)}B`;
  if (a >= 1e6) return `${sign}${(a / 1e6).toFixed(2)}M`;
  if (a >= 1e3) return `${sign}${(a / 1e3).toFixed(1)}K`;
  return sign + String(a);
}

function when(sec) {
  if (!sec) return '—';
  return new Date(sec * 1000).toLocaleString();
}

export default function App() {
  const [path, setPath] = useState(route());
  useEffect(() => {
    const on = () => setPath(route());
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  if (path.startsWith('/dashboard')) return <Dashboard />;
  if (path.startsWith('/pair')) return <PairPage />;
  if (path.startsWith('/feedback')) return <FeedbackPage />;
  if (path.startsWith('/admin/feedback')) return <AdminFeedbackPage />;
  if (path.startsWith('/admin/trades')) return <AdminTradesPage />;
  if (path.startsWith('/admin/ml')) return <AdminMlOverviewPage />;
  return <Login />;
}

function Login() {
  const err = hashParams().get('error');
  const [email, setEmail] = useState('');
  const [msg, setMsg] = useState(err === 'expired' ? 'That sign-in link expired. Request a new one.' : '');
  const [devLink, setDevLink] = useState('');
  const [code, setCode] = useState((hashParams().get('code') || '').toUpperCase());
  const [busy, setBusy] = useState(false);

  async function requestLink(e) {
    e.preventDefault();
    setBusy(true); setMsg(''); setDevLink('');
    try {
      const out = await api('/v1/auth/request-link', { method: 'POST', body: JSON.stringify({ email }) });
      setMsg(out.emailed ? 'Check your email for a sign-in link.' : 'No mail sender configured — use the link below.');
      if (out.devLink) setDevLink(out.devLink);
    } catch (ex) { setMsg(ex.message); }
    finally { setBusy(false); }
  }

  async function redeem(e) {
    e.preventDefault();
    setBusy(true); setMsg('');
    try {
      await api('/v1/auth/pair/redeem', { method: 'POST', body: JSON.stringify({ code, email }) });
      window.location.hash = '/dashboard';
    } catch (ex) { setMsg(ex.message); }
    finally { setBusy(false); }
  }

  return (
    <div className="wrap">
      <div className="brand">RuneAssist</div>
      <h1>Sign in</h1>

      <div className="card" style={{ marginBottom: 20 }}>
        <div className="k">First time here? Link your plugin</div>
        <p className="muted" style={{ marginTop: 6 }}>
          This connects your existing flip history to a login — do this first, or signing in
          by email alone starts a brand-new, empty account with none of your data.
        </p>
        <ol style={{ margin: '10px 0 14px 20px', padding: 0, color: 'var(--text)' }}>
          <li>In RuneLite: open RuneAssist's settings (gear icon) → <b>Link a website login</b></li>
          <li>It shows an 8-character code — enter it below, with the email you want to use</li>
        </ol>
        <form className="stack" onSubmit={redeem}>
          <input type="email" required placeholder="you@example.com" value={email} onChange={(e) => setEmail(e.target.value)} />
          <input placeholder="Pairing code from the plugin" value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} />
          <button className="primary" disabled={busy} type="submit">Link and sign in</button>
        </form>
      </div>

      <div className="card">
        <div className="k">Already linked once? Sign in again</div>
        <p className="muted" style={{ marginTop: 6 }}>
          For a login you've used before — we'll email a 15-minute sign-in link. If this is
          your first visit, use the linking step above instead.
        </p>
        <form className="stack" onSubmit={requestLink}>
          <input type="email" required placeholder="you@example.com" value={email} onChange={(e) => setEmail(e.target.value)} />
          <button disabled={busy} type="submit">Email me a link</button>
        </form>
        {devLink && <p><a href={devLink}>Open magic link</a></p>}
      </div>

      {msg && <p className="error" style={{ marginTop: 16 }}>{msg}</p>}
    </div>
  );
}

function PairPage() {
  const [code, setCode] = useState('');
  const [err, setErr] = useState('');
  useEffect(() => {
    api('/v1/account/pair/start', { method: 'POST', body: '{}' })
      .then((o) => setCode(o.code))
      .catch((e) => setErr(e.message));
  }, []);
  return (
    <div className="wrap">
      <div className="top"><div className="brand">RuneAssist</div><a href="#/dashboard">Back</a></div>
      <h1>Link the plugin</h1>
      <p className="muted">In RuneLite Preferences, click Enter pairing code and type this. Expires in 10 minutes.</p>
      <div className="card"><div className="v">{code || '…'}</div></div>
      {err && <p className="error">{err}</p>}
    </div>
  );
}

function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(',')[1] || '');
    reader.onerror = () => reject(new Error('could not read file'));
    reader.readAsDataURL(file);
  });
}

function FeedbackPage() {
  const [message, setMessage] = useState('');
  const [screenshot, setScreenshot] = useState(null); // File
  const [reports, setReports] = useState([]);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');

  async function load() {
    try {
      const out = await api('/v1/account/feedback');
      setReports(out.reports || []);
    } catch (ex) {
      if (String(ex.message).includes('unauthorized')) { window.location.hash = '/login'; return; }
      setErr(ex.message);
    }
  }

  useEffect(() => { load(); }, []);

  async function submit(e) {
    e.preventDefault();
    if (!message.trim()) return;
    setBusy(true); setMsg(''); setErr('');
    try {
      const payload = { message: message.trim() };
      if (screenshot) payload.screenshot = await fileToBase64(screenshot);
      await api('/v1/account/feedback', { method: 'POST', body: JSON.stringify(payload) });
      setMessage('');
      setScreenshot(null);
      setMsg('Thanks — logged.');
      await load();
    } catch (ex) { setErr(ex.message); }
    finally { setBusy(false); }
  }

  return (
    <div className="wrap">
      <div className="top"><div className="brand">RuneAssist</div><a href="#/dashboard">Back</a></div>
      <h1>Report a bug</h1>
      <p className="muted">Describe what happened — what you expected, what RuneAssist did instead.</p>
      <form className="stack" onSubmit={submit}>
        <textarea
          rows={5}
          required
          placeholder="e.g. Suggested a MODIFY on Bagged plant while I had a manual Avantoe sell offer open..."
          value={message}
          onChange={(e) => setMessage(e.target.value)}
        />
        <input
          type="file"
          accept="image/*"
          onChange={(e) => setScreenshot(e.target.files && e.target.files[0] ? e.target.files[0] : null)}
        />
        {screenshot && <p className="muted" style={{ fontSize: 12 }}>Attached: {screenshot.name}</p>}
        <button className="primary" disabled={busy} type="submit">Submit</button>
      </form>
      {msg && <p className="muted" style={{ marginTop: 10 }}>{msg}</p>}
      {err && <p className="error" style={{ marginTop: 10 }}>{err}</p>}

      {reports.length > 0 && (
        <div style={{ marginTop: 28 }}>
          <div className="k">Previous reports</div>
          <div className="stack" style={{ marginTop: 10 }}>
            {reports.map((r) => (
              <div className="card" key={r.id}>
                <div className="muted" style={{ fontSize: 12 }}>{when(r.createdAt)}</div>
                <div style={{ marginTop: 4, whiteSpace: 'pre-wrap' }}>{r.message}</div>
                {r.hasScreenshot && (
                  <img
                    src={`/v1/account/feedback/${r.id}/screenshot`}
                    alt="Screenshot"
                    style={{ marginTop: 8, maxWidth: '100%', borderRadius: 4 }}
                  />
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function AdminFeedbackPage() {
  const [reports, setReports] = useState([]);
  const [err, setErr] = useState('');

  useEffect(() => {
    api('/v1/account/admin/feedback')
      .then((out) => setReports(out.reports || []))
      .catch((ex) => {
        if (String(ex.message).includes('unauthorized')) { window.location.hash = '/login'; return; }
        setErr(ex.message);
      });
  }, []);

  return (
    <div className="wrap">
      <AdminNav />
      <h1>All bug reports</h1>
      {err && <p className="error">{err}</p>}
      <div className="stack" style={{ marginTop: 10 }}>
        {reports.map((r) => (
          <div className="card" key={r.id}>
            <div className="muted" style={{ fontSize: 12 }}>{r.reporterEmail} — {r.displayName || '(no RSN)'} — {when(r.createdAt)}</div>
            <div style={{ marginTop: 4, whiteSpace: 'pre-wrap' }}>{r.message}</div>
            {r.hasScreenshot && (
              <img
                src={`/v1/account/feedback/${r.id}/screenshot`}
                alt="Screenshot"
                style={{ marginTop: 8, maxWidth: '100%', borderRadius: 4 }}
              />
            )}
          </div>
        ))}
        {reports.length === 0 && !err && <p className="muted">No reports yet.</p>}
      </div>
    </div>
  );
}

function AdminNav() {
  return (
    <div className="top">
      <div className="brand">RuneAssist</div>
      <div>
        <a href="#/dashboard">Dashboard</a>{' '}
        <a href="#/admin/trades">All trades</a>{' '}
        <a href="#/admin/ml">ML overview</a>{' '}
        <a href="#/admin/feedback">All reports</a>
      </div>
    </div>
  );
}

function AdminTradesPage() {
  const [data, setData] = useState(null);
  const [page, setPage] = useState(1);
  const [err, setErr] = useState('');

  useEffect(() => {
    api(`/v1/account/admin/trades?page=${page}&pageSize=100`)
      .then(setData)
      .catch((ex) => {
        if (String(ex.message).includes('unauthorized')) { window.location.hash = '/login'; return; }
        setErr(ex.message);
      });
  }, [page]);

  return (
    <div className="wrap">
      <AdminNav />
      <h1>All trades</h1>
      {err && <p className="error">{err}</p>}
      {data && (
        <>
          <div className="cards">
            <Stat k="Total profit" v={`${gp(data.stats.profit)} gp`} n={data.stats.profit} />
            <Stat k="Gross" v={`${gp(data.stats.gross)} gp`} />
            <Stat k="Tax paid" v={`${gp(data.stats.taxPaid)} gp`} />
            <Stat k="Flips made" v={String(data.stats.flipsMade)} />
            <Stat k="Accounts" v={String(data.accounts.length)} />
          </div>
          <div style={{ overflowX: 'auto', marginTop: 16 }}>
            <table>
              <thead>
                <tr>
                  <th>Closed</th><th>Owner</th><th>Account</th><th>Item</th>
                  <th>Qty</th><th>Avg buy</th><th>Avg sell</th><th>Profit</th>
                </tr>
              </thead>
              <tbody>
                {data.flips.map((f) => (
                  <tr key={f.id}>
                    <td>{when(f.closedTime)}</td>
                    <td>{f.ownerEmail}</td>
                    <td>{f.displayName}</td>
                    <td>{f.itemName || f.itemId}</td>
                    <td>{f.qty}</td>
                    <td>{gp(f.avgBuy)}</td>
                    <td>{gp(f.avgSell)}</td>
                    <td style={{ color: f.profit >= 0 ? 'var(--good)' : 'var(--bad)' }}>{gp(f.profit)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="toolbar" style={{ marginTop: 10 }}>
            <button disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>Prev</button>
            <span className="muted">Page {data.page} — {data.total} total</span>
            <button disabled={page * data.pageSize >= data.total} onClick={() => setPage((p) => p + 1)}>Next</button>
          </div>
        </>
      )}
    </div>
  );
}

function pct(x) { return x == null ? '—' : (x * 100).toFixed(0) + '%'; }
function ms(x) {
  if (x == null) return '—';
  if (x < 60000) return (x / 1000).toFixed(0) + 's';
  if (x < 3600000) return (x / 60000).toFixed(1) + 'm';
  return (x / 3600000).toFixed(1) + 'h';
}

function AdminMlOverviewPage() {
  const [overview, setOverview] = useState(null);
  const [note, setNote] = useState('');
  const [err, setErr] = useState('');

  useEffect(() => {
    api('/v1/account/admin/ml-overview')
      .then((out) => { setOverview(out.overview); setNote(out.note || ''); })
      .catch((ex) => {
        if (String(ex.message).includes('unauthorized')) { window.location.hash = '/login'; return; }
        setErr(ex.message);
      });
  }, []);

  return (
    <div className="wrap">
      <AdminNav />
      <h1>ML overview</h1>
      <p className="muted">Reconstructed GE offer lifecycles from uploaded telemetry (place → fill/cancel, reprices merged into one attempt).</p>
      {err && <p className="error">{err}</p>}
      {note && <p className="muted">{note}</p>}
      {overview && (
        <>
          <p className="muted">{overview.daysAnalyzed} day(s) analyzed ({overview.dateRange[0]} to {overview.dateRange[1]}), {overview.accounts} account(s), {overview.episodes.chains} flip attempts ({pct(overview.episodes.repriceRate)} were reprices merged into an earlier attempt).</p>

          <div className="k" style={{ marginTop: 16 }}>Outcomes</div>
          <div className="cards" style={{ marginTop: 8 }}>
            <Stat k="Fully filled" v={`${overview.outcomes.fullyFilled} (${pct(overview.outcomes.fullyFilled / overview.outcomes.total)})`} />
            <Stat k="Partial then cancelled" v={`${overview.outcomes.partialThenCancelled} (${pct(overview.outcomes.partialThenCancelled / overview.outcomes.total)})`} />
            <Stat k="Zero-fill cancelled" v={`${overview.outcomes.zeroFillCancelled} (${pct(overview.outcomes.zeroFillCancelled / overview.outcomes.total)})`} />
            <Stat k="Still open" v={String(overview.outcomes.stillOpen)} />
          </div>

          <div className="k" style={{ marginTop: 20 }}>Timing</div>
          <div className="cards" style={{ marginTop: 8 }}>
            <Stat k="Median time to full fill" v={ms(overview.timing.medianFullFillMs)} />
            <Stat k="Median time to first partial fill" v={ms(overview.timing.medianFirstFillMs)} s={`n=${overview.timing.firstFillSampleSize}`} />
          </div>

          <div className="card" style={{ marginTop: 12 }}>
            <div className="k">By side</div>
            <div style={{ overflowX: 'auto', marginTop: 8 }}>
              <table>
                <thead><tr><th>Side</th><th>n</th><th>Fill rate</th><th>5m</th><th>15m</th><th>30m</th><th>60m</th></tr></thead>
                <tbody>
                  {['buy', 'sell'].map((side) => (
                    <tr key={side}>
                      <td style={{ textTransform: 'capitalize' }}>{side}</td>
                      <td>{overview.bySide[side].n}</td>
                      <td>{pct(overview.bySide[side].fillRate)}</td>
                      {[5, 15, 30, 60].map((m) => <td key={m}>{pct(overview.bySide[side].fractionFilledByWindow[m])}</td>)}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="muted" style={{ fontSize: 12, marginTop: 6 }}>Median fraction of the original order filled within N minutes of placement.</p>
          </div>

          <div className="k" style={{ marginTop: 20 }}>Does repricing (MODIFY) help?</div>
          <div className="cards" style={{ marginTop: 8 }}>
            <Stat k="Never repriced" v={pct(overview.repriceEffect.neverRepriced.fillRate)} s={`n=${overview.repriceEffect.neverRepriced.n}`} />
            <Stat k="Repriced ≥1x" v={pct(overview.repriceEffect.repricedOnceOrMore.fillRate)} s={`n=${overview.repriceEffect.repricedOnceOrMore.n}`} />
          </div>
          <p className="muted" style={{ fontSize: 12, marginTop: 6 }}>Selection bias: MODIFY only fires on offers already stuck, so a lower rate here isn't evidence reprices hurt.</p>

          <div className="card" style={{ marginTop: 12 }}>
            <div className="k">Per-item fill rate (items with ≥3 attempts)</div>
            <div style={{ overflowX: 'auto', marginTop: 8 }}>
              <table>
                <thead><tr><th>Item</th><th>Attempts</th><th>Fill rate</th></tr></thead>
                <tbody>
                  {overview.perItem.map((r) => (
                    <tr key={r.itemId}><td>{r.itemName || `#${r.itemId}`}</td><td>{r.n}</td><td>{pct(r.fillRate)}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function Dashboard() {
  const [me, setMe] = useState(null);
  const [acct, setAcct] = useState('');
  const [range, setRange] = useState('all');
  const [gran, setGran] = useState('cumulative');
  const [q, setQ] = useState('');
  const [summary, setSummary] = useState(null);
  const [flips, setFlips] = useState([]);
  const [positions, setPositions] = useState([]);
  const [series, setSeries] = useState([]);
  const [err, setErr] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);

  const qs = useMemo(() => {
    const p = new URLSearchParams();
    if (acct) p.set('osrsAccountId', acct);
    p.set('range', range);
    return p.toString();
  }, [acct, range]);

  async function load() {
    setErr('');
    try {
      const who = await api('/v1/account/me');
      setMe(who);
      const s = await api(`/v1/account/summary?${qs}`);
      setSummary(s);
      const f = await api(`/v1/account/flips?${qs}&page=${page}&pageSize=50&q=${encodeURIComponent(q)}`);
      setFlips(f.flips || []);
      setTotal(f.total || 0);
      const pos = await api(`/v1/account/positions?${acct ? `osrsAccountId=${acct}` : ''}`);
      setPositions(pos.positions || []);
      const ser = await api(`/v1/account/profit-series?${qs}&granularity=${gran}`);
      setSeries(ser.points || []);
    } catch (ex) {
      if (String(ex.message).includes('unauthorized')) {
        window.location.hash = '/login';
        return;
      }
      setErr(ex.message);
    }
  }

  useEffect(() => { load(); }, [qs, gran, page]);

  async function logout() {
    await api('/v1/auth/logout', { method: 'POST', body: '{}' }).catch(() => {});
    window.location.hash = '/login';
  }

  const accounts = (summary && summary.osrsAccounts) || (me && me.osrsAccounts) || [];

  return (
    <div className="wrap">
      <div className="top">
        <div className="brand">RuneAssist</div>
        <div>
          <span className="muted">{me && me.email}</span>{' '}
          <a href="#/pair">Pair plugin</a>{' '}
          <a href="#/feedback">Report a bug</a>{' '}
          {me && me.email === 'tom@tpharrison.co.uk' && <>
            <a href="#/admin/trades">All trades</a>{' '}
            <a href="#/admin/ml">ML overview</a>{' '}
            <a href="#/admin/feedback">All reports</a>{' '}
          </>}
          <button onClick={logout}>Log out</button>
        </div>
      </div>
      {err && <p className="error">{err}</p>}
      <div className="toolbar">
        <select value={acct} onChange={(e) => { setAcct(e.target.value); setPage(1); }}>
          <option value="">All accounts</option>
          {accounts.map((a) => <option key={a.id} value={a.id}>{a.displayName}</option>)}
        </select>
        <select value={range} onChange={(e) => { setRange(e.target.value); setPage(1); }}>
          <option value="30d">30d</option>
          <option value="90d">90d</option>
          <option value="all">All</option>
        </select>
        <select value={gran} onChange={(e) => setGran(e.target.value)}>
          <option value="cumulative">Cumulative</option>
          <option value="daily">Daily</option>
        </select>
        <form onSubmit={(e) => { e.preventDefault(); setPage(1); load(); }}>
          <input placeholder="Filter item / account" value={q} onChange={(e) => setQ(e.target.value)} />
        </form>
      </div>
      {summary && (
        <div className="cards">
          <Stat k="Total profit" v={gp(summary.profit)} n={summary.profit} />
          <Stat k="Flips" v={String(summary.flipsMade)} s={`${summary.openCount} open`} />
          <Stat k="Win rate" v={`${Math.round((summary.winRate || 0) * 100)}%`} s={`best: ${gp(summary.bestProfit)}`} />
          <Stat k="Tax paid" v={gp(summary.taxPaid)} />
          <Stat k="Portfolio" v={gp(summary.portfolioValue)} />
        </div>
      )}
      <div className="card" style={{ marginBottom: 16 }}>
        <div className="k">Profit over time</div>
        <ProfitChart points={series} />
      </div>
      <div className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
        <div className="k">Flip history</div>
        <table>
          <thead>
            <tr>
              <th>Closed</th><th>Account</th><th>Item</th><th>Status</th>
              <th>Qty</th><th>Avg buy</th><th>Avg sell</th><th>Tax</th><th>Profit</th><th>Ea.</th>
            </tr>
          </thead>
          <tbody>
            {flips.map((f) => (
              <tr key={f.id}>
                <td>{when(f.closedTime)}</td>
                <td>{f.displayName || '—'}</td>
                <td>{f.itemName || f.itemId}</td>
                <td><span className={`pill ${f.status}`}>{String(f.status || '').toLowerCase()}</span></td>
                <td>{f.qty}</td>
                <td>{gp(f.avgBuy)}</td>
                <td>{gp(f.avgSell)}</td>
                <td>{gp(f.taxPaid)}</td>
                <td className={(f.profit || 0) >= 0 ? 'pos' : 'neg'}>{gp(f.profit)}</td>
                <td className={(f.profitPerUnit || 0) >= 0 ? 'pos' : 'neg'}>{gp(f.profitPerUnit)}</td>
              </tr>
            ))}
            {!flips.length && <tr><td colSpan="10" className="muted">No closed flips yet.</td></tr>}
          </tbody>
        </table>
        <div className="toolbar">
          <button disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>Prev</button>
          <span className="muted">Page {page} · {total} flips</span>
          <button disabled={page * 50 >= total} onClick={() => setPage((p) => p + 1)}>Next</button>
        </div>
      </div>
      <div className="card" style={{ overflowX: 'auto' }}>
        <div className="k">Open positions</div>
        <table>
          <thead>
            <tr><th>Account</th><th>Item</th><th>Status</th><th>Opened</th><th>Remaining</th><th>Spent</th><th>Unrealized</th></tr>
          </thead>
          <tbody>
            {positions.map((p) => (
              <tr key={p.id}>
                <td>{p.displayName || '—'}</td>
                <td>{p.itemName || p.itemId}</td>
                <td><span className={`pill ${p.status}`}>{String(p.status || '').toLowerCase()}</span></td>
                <td>{p.openedQuantity}</td>
                <td>{(p.openedQuantity || 0) - (p.closedQuantity || 0)}</td>
                <td>{gp(p.spent)}</td>
                <td className={(p.profit || 0) >= 0 ? 'pos' : 'neg'}>{gp(p.profit)}</td>
              </tr>
            ))}
            {!positions.length && <tr><td colSpan="7" className="muted">Nothing held.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Stat({ k, v, s, n }) {
  const cls = typeof n === 'number' ? (n >= 0 ? 'pos' : 'neg') : '';
  return (
    <div className="card">
      <div className="k">{k}</div>
      <div className={`v ${cls}`}>{v}</div>
      {s && <div className="s">{s}</div>}
    </div>
  );
}

function ProfitChart({ points }) {
  const w = 1000, h = 180, pad = 8;
  if (!points || points.length < 2) {
    return <svg className="chart" viewBox={`0 0 ${w} ${h}`}><text x="12" y="24" fill="#9a9a9a">Not enough closed flips for a chart.</text></svg>;
  }
  const ys = points.map((p) => p.profit);
  const min = Math.min(0, ...ys);
  const max = Math.max(0, ...ys);
  const span = max - min || 1;
  const d = points.map((p, i) => {
    const x = pad + (i / (points.length - 1)) * (w - pad * 2);
    const y = pad + (1 - (p.profit - min) / span) * (h - pad * 2);
    return `${i ? 'L' : 'M'}${x},${y}`;
  }).join(' ');
  const zeroY = pad + (1 - (0 - min) / span) * (h - pad * 2);
  return (
    <svg className="chart" viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none">
      <line x1="0" y1={zeroY} x2={w} y2={zeroY} stroke="#2a2a2a" />
      <path d={d} fill="none" stroke="#c4a35a" strokeWidth="2" />
    </svg>
  );
}
