import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import {
  AlertCircle, ArrowLeftRight, ArrowUpRight, CheckCircle2, ChevronDown, Clock3,
  CreditCard, Home, LogOut, Plus, Send, ShieldCheck, ShoppingBag, Wallet,
} from 'lucide-react';
import { useAuth } from './auth/auth-provider';
import { Account, AccountPage, ApiError, apiRequest, loadAccountActivity, Transaction, Transfer, Authorization } from './api/client';

type View = 'overview' | 'transfer' | 'checkout';
type Notice = { tone: 'success' | 'info'; title: string; detail: string };

const paymentScenarios = [
  { label: 'Approved test token', value: 'tok_demo_approved_001' },
  { label: 'Declined — do not honor', value: 'tok_demo_decline_dnh_001' },
  { label: 'Pending — provider timeout', value: 'tok_demo_timeout_001' },
];

function formatMoney(money: { amount: string; currency: string }) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: money.currency }).format(Number(money.amount));
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric' }).format(new Date(value));
}

export function App() {
  const { user, loading: authLoading, error: authError, signIn, signOut } = useAuth();
  const [view, setView] = useState<View>('overview');
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [accountId, setAccountId] = useState('');
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [loadingData, setLoadingData] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [apiError, setApiError] = useState<ApiError | null>(null);
  const [transferAmount, setTransferAmount] = useState('');
  const [transferMemo, setTransferMemo] = useState('');
  const [destinationId, setDestinationId] = useState('');
  const [paymentAmount, setPaymentAmount] = useState('');
  const [paymentToken, setPaymentToken] = useState(paymentScenarios[0].value);
  const [pendingAuthorizationId, setPendingAuthorizationId] = useState<string | null>(null);
  const selectedAccount = useMemo(() => accounts.find((account) => account.id === accountId) ?? null, [accounts, accountId]);
  const destinations = useMemo(() => accounts.filter((account) => account.id !== accountId
    && account.balance.ledger.currency === selectedAccount?.balance.ledger.currency), [accounts, accountId, selectedAccount]);
  const initials = (user?.profile.preferred_username ?? 'PF').slice(0, 2).toUpperCase();

  const refreshAccounts = useCallback(async () => {
    if (!user) return;
    const page = await apiRequest<AccountPage>(user, '/accounts?limit=20');
    setAccounts(page.items);
    setAccountId((current) => page.items.some((account) => account.id === current) ? current : page.items[0]?.id ?? '');
  }, [user]);

  useEffect(() => {
    if (!user) return;
    let active = true;
    setLoadingData(true);
    setApiError(null);
    apiRequest<AccountPage>(user, '/accounts?limit=20')
      .then((page) => {
        if (!active) return;
        setAccounts(page.items);
        setAccountId((current) => page.items.some((account) => account.id === current) ? current : page.items[0]?.id ?? '');
      })
      .catch((error: unknown) => { if (active) setApiError(error instanceof ApiError ? error : new ApiError(0, {})); })
      .finally(() => { if (active) setLoadingData(false); });
    return () => { active = false; };
  }, [user]);

  useEffect(() => {
    if (!user || !accountId) return;
    let active = true;
    loadAccountActivity(user, accountId)
      .then(({ transactions: recent }) => { if (active) setTransactions(recent); })
      .catch((error: unknown) => { if (active) setApiError(error instanceof ApiError ? error : new ApiError(0, {})); });
    return () => { active = false; };
  }, [user, accountId]);

  useEffect(() => {
    if (destinations.length && !destinations.some((account) => account.id === destinationId)) {
      setDestinationId(destinations[0].id);
    }
  }, [destinations, destinationId]);

  const handleTransfer = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!user || !selectedAccount || !destinationId) return;
    setBusy(true);
    setApiError(null);
    setNotice(null);
    try {
      const transfer = await apiRequest<Transfer>(user, '/transfers', {
        method: 'POST',
        headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({
          sourceAccountId: selectedAccount.id,
          destinationAccountId: destinationId,
          amount: { amount: transferAmount, currency: selectedAccount.balance.ledger.currency },
          memo: transferMemo || 'ParcelFlow web transfer',
          metadata: { channel: 'web', journey: 'account-transfer' },
        }),
      });
      setNotice({ tone: 'success', title: 'Transfer complete', detail: `${formatMoney(transfer.amount)} was sent. Reference ${transfer.id.slice(0, 8)}.` });
      setTransferAmount('');
      await refreshAccounts();
      const activity = await loadAccountActivity(user, selectedAccount.id);
      setTransactions(activity.transactions);
      setView('overview');
    } catch (error) {
      setApiError(error instanceof ApiError ? error : new ApiError(0, {}));
    } finally {
      setBusy(false);
    }
  };

  const handlePayment = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!user || !selectedAccount) return;
    setBusy(true);
    setApiError(null);
    setNotice(null);
    try {
      const authorization = await apiRequest<Authorization>(user, '/payments/authorizations', {
        method: 'POST',
        headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({
          accountId: selectedAccount.id,
          paymentToken,
          amount: { amount: paymentAmount, currency: selectedAccount.balance.ledger.currency },
          merchantReference: `checkout-${crypto.randomUUID().slice(0, 8)}`,
          captureMode: 'MANUAL',
          metadata: { channel: 'web', journey: 'merchant-checkout' },
        }),
      });
      if (authorization.status === 'AUTHORIZED') {
        const capture = await apiRequest<{ amount: { amount: string; currency: string } }>(
          user, `/payments/authorizations/${authorization.id}/capture`,
          { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } },
        );
        setNotice({ tone: 'success', title: 'Payment captured', detail: `${formatMoney(capture.amount)} was paid using the synthetic simulator.` });
      } else if (authorization.status === 'PENDING') {
        setPendingAuthorizationId(authorization.id);
        setNotice({ tone: 'info', title: 'Payment is pending', detail: 'The simulator returned a delayed result. Release the authorization hold when you are ready.' });
      } else {
        setPendingAuthorizationId(null);
        setNotice({ tone: 'info', title: 'Payment declined', detail: authorization.declineCode?.replaceAll('_', ' ') ?? 'The synthetic payment was declined.' });
      }
      setPaymentAmount('');
      await refreshAccounts();
      const activity = await loadAccountActivity(user, selectedAccount.id);
      setTransactions(activity.transactions);
    } catch (error) {
      setApiError(error instanceof ApiError ? error : new ApiError(0, {}));
    } finally {
      setBusy(false);
    }
  };

  const voidPendingPayment = async () => {
    if (!user || !pendingAuthorizationId) return;
    setBusy(true);
    setApiError(null);
    try {
      await apiRequest(user, `/payments/authorizations/${pendingAuthorizationId}/void`, {
        method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      });
      setPendingAuthorizationId(null);
      setNotice({ tone: 'success', title: 'Authorization released', detail: 'The pending hold was released from your synthetic account.' });
      await refreshAccounts();
      if (selectedAccount) {
        const activity = await loadAccountActivity(user, selectedAccount.id);
        setTransactions(activity.transactions);
      }
    } catch (error) {
      setApiError(error instanceof ApiError ? error : new ApiError(0, {}));
    } finally {
      setBusy(false);
    }
  };

  if (authLoading) {
    return <main className="auth-loading"><span className="spinner" aria-hidden="true" /><p>Connecting to ParcelFlow…</p></main>;
  }

  if (!user) {
    return (
      <main className="welcome-page">
        <section className="welcome-brand">
          <div className="brand-lockup"><span className="brand-mark"><Wallet size={22} /></span><span>parcelflow</span></div>
          <div className="welcome-copy">
            <span className="eyebrow">SYNTHETIC BANKING EXPERIENCE</span>
            <h1>Move money with clarity.</h1>
            <p>A quality engineering showcase for modern banking, merchant payments, and fulfilment journeys.</p>
            <div className="trust-note"><ShieldCheck size={18} /><span>Demo data only · No real funds are moved</span></div>
          </div>
          <div className="welcome-orbit orbit-one" /><div className="welcome-orbit orbit-two" />
        </section>
        <section className="welcome-panel">
          <div className="welcome-panel-inner">
            <span className="brand-mark brand-mark-soft"><Wallet size={21} /></span>
            <span className="eyebrow">YOUR FINANCIAL HOME</span>
            <h2>Welcome back</h2>
            <p>Sign in to explore account balances, transfers, and a simulated payment checkout.</p>
            {authError && <div className="inline-error" role="alert"><AlertCircle size={17} />{authError}</div>}
            <button className="button button-primary button-wide" onClick={() => void signIn()}>
              Sign in securely <ArrowUpRight size={17} />
            </button>
            <p className="demo-credentials">Local demo: <strong>demo-customer-1</strong> / <strong>demo-customer-pass</strong></p>
          </div>
          <span className="welcome-footer">Protected with OpenID Connect · PKCE</span>
        </section>
      </main>
    );
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand-lockup" href="#overview" onClick={() => setView('overview')} aria-label="ParcelFlow home">
          <span className="brand-mark"><Wallet size={21} /></span><span>parcelflow</span>
        </a>
        <span className="side-label">WORKSPACE</span>
        <nav className="side-nav" aria-label="Main navigation">
          <button className={view === 'overview' ? 'active' : ''} aria-current={view === 'overview' ? 'page' : undefined} onClick={() => setView('overview')}><Home size={18} />Overview</button>
          <button className={view === 'transfer' ? 'active' : ''} aria-current={view === 'transfer' ? 'page' : undefined} onClick={() => setView('transfer')}><ArrowLeftRight size={18} />Transfers</button>
          <button className={view === 'checkout' ? 'active' : ''} aria-current={view === 'checkout' ? 'page' : undefined} onClick={() => setView('checkout')}><ShoppingBag size={18} />Merchant checkout</button>
        </nav>
        <div className="sidebar-note"><span className="note-icon"><ShieldCheck size={17} /></span><div><strong>Built for trust</strong><p>Every journey uses synthetic accounts and simulated payment rails.</p></div></div>
        <button className="sidebar-profile" onClick={() => void signOut()}>
          <span className="avatar">{initials}</span><span className="profile-text"><strong>{user.profile.preferred_username ?? 'Customer'}</strong><small>Customer account</small></span><LogOut size={17} />
        </button>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div><span className="eyebrow">PERSONAL BANKING</span><h1>{view === 'overview' ? 'Overview' : view === 'transfer' ? 'Move money' : 'Merchant checkout'}</h1></div>
          <div className="topbar-actions">
            <span className="secure-pill"><span />Secure session</span>
            <button className="avatar avatar-button" onClick={() => void signOut()} aria-label="Sign out">{initials}</button>
          </div>
        </header>

        {apiError && <div className="alert alert-error" role="alert"><AlertCircle size={18} /><span>{apiError.message}{apiError.code ? ` (${apiError.code})` : ''}{apiError.traceId ? <small>Trace {apiError.traceId}</small> : null}{apiError.status === 401 && <button className="alert-action" onClick={() => void signIn()}>Sign in again</button>}</span><button onClick={() => setApiError(null)} aria-label="Dismiss error">×</button></div>}
        {notice && <div className={`alert ${notice.tone === 'success' ? 'alert-success' : 'alert-info'}`} role="status"><CheckCircle2 size={18} /><span><strong>{notice.title}</strong><small>{notice.detail}</small>{pendingAuthorizationId && <button className="alert-action" disabled={busy} onClick={() => void voidPendingPayment()}>Release hold</button>}</span><button onClick={() => setNotice(null)} aria-label="Dismiss message">×</button></div>}

        {view === 'overview' && (
          <>
            <section className="welcome-row">
              <div><p className="greeting">Good day, {user.profile.given_name ?? user.profile.preferred_username ?? 'there'}</p><p className="muted">Here’s what’s happening with your money today.</p></div>
              <button className="button button-primary" onClick={() => setView('transfer')}><Plus size={17} /> New transfer</button>
            </section>
            {loadingData ? <div className="loading-card"><span className="spinner" />Loading your accounts…</div> : (
              <>
                <div className="balance-grid">
                  <article className="balance-card balance-primary">
                    <div className="balance-card-top"><span className="balance-icon"><Wallet size={19} /></span><span className="account-select-wrap"><label className="sr-only" htmlFor="account-picker">Choose account</label><select id="account-picker" value={accountId} onChange={(event) => setAccountId(event.target.value)}>{accounts.map((account) => <option key={account.id} value={account.id}>{account.displayName}</option>)}</select><ChevronDown size={15} /></span></div>
                    {selectedAccount ? <><p className="balance-label">Available balance</p><p className="balance-amount">{formatMoney(selectedAccount.balance.available)}</p><div className="account-meta"><span>{selectedAccount.type.toLowerCase()} · {selectedAccount.maskedNumber}</span><span className="account-state"><i />{selectedAccount.status.toLowerCase()}</span></div></> : <p className="muted">No account available.</p>}
                  </article>
                  <article className="metric-card"><span className="metric-icon metric-soft"><CreditCard size={18} /></span><span className="metric-label">Ledger balance</span><strong>{selectedAccount ? formatMoney(selectedAccount.balance.ledger) : '—'}</strong><span className="metric-sub">Before pending activity</span></article>
                  <article className="metric-card"><span className="metric-icon metric-amber"><Clock3 size={18} /></span><span className="metric-label">Pending debit</span><strong>{selectedAccount ? formatMoney(selectedAccount.balance.pendingDebit) : '—'}</strong><span className="metric-sub">Held or processing</span></article>
                </div>
                <section className="activity-card">
                  <div className="section-heading"><div><span className="eyebrow">ACCOUNT ACTIVITY</span><h2>Recent transactions</h2></div><button className="text-button" onClick={() => setView('transfer')}>Manage money <ArrowUpRight size={15} /></button></div>
                  <TransactionList transactions={transactions} />
                </section>
                <section className="quick-actions">
                  <button onClick={() => setView('transfer')}><span className="quick-icon quick-purple"><ArrowLeftRight size={19} /></span><span><strong>Transfer money</strong><small>Between your accounts</small></span><ArrowUpRight size={17} /></button>
                  <button onClick={() => setView('checkout')}><span className="quick-icon quick-green"><ShoppingBag size={19} /></span><span><strong>Try merchant checkout</strong><small>Explore a simulated payment</small></span><ArrowUpRight size={17} /></button>
                </section>
              </>
            )}
          </>
        )}

        {view === 'transfer' && (
          <section className="form-layout">
            <div className="form-intro"><span className="form-icon"><ArrowLeftRight size={21} /></span><span className="eyebrow">INTERNAL TRANSFER</span><h2>Move money between accounts</h2><p>Transfers post immediately in this synthetic environment. The API checks available funds and protects retries with an idempotency key.</p><div className="form-security"><ShieldCheck size={17} />Encrypted session · Customer-owned accounts only</div></div>
            <form className="action-form" onSubmit={(event) => void handleTransfer(event)}>
              <div className="form-heading"><h3>Transfer details</h3><span className="required-note">Complete the details below</span></div>
              <label>From account <select required value={accountId} onChange={(event) => setAccountId(event.target.value)}>{accounts.map((account) => <option key={account.id} value={account.id}>{account.displayName} · {account.maskedNumber} · {formatMoney(account.balance.available)}</option>)}</select></label>
              <label>To account <select required value={destinationId} onChange={(event) => setDestinationId(event.target.value)}><option value="">Choose an account</option>{destinations.map((account) => <option key={account.id} value={account.id}>{account.displayName} · {account.maskedNumber}</option>)}</select></label>
              <label>Amount <span className="input-affix"><span>{selectedAccount?.balance.ledger.currency ?? 'USD'}</span><input required type="number" min="0.01" step="0.01" max={selectedAccount?.balance.available.amount} value={transferAmount} onChange={(event) => setTransferAmount(event.target.value)} placeholder="0.00" /></span></label>
              <label>Memo <input maxLength={140} value={transferMemo} onChange={(event) => setTransferMemo(event.target.value)} placeholder="What is this transfer for?" /></label>
              <div className="form-summary"><span>Available to transfer</span><strong>{selectedAccount ? formatMoney(selectedAccount.balance.available) : '—'}</strong></div>
              <button className="button button-primary button-wide" type="submit" disabled={busy || !destinationId}>{busy ? <><span className="button-spinner" />Sending transfer…</> : <>Review and send <Send size={16} /></>}</button>
              <p className="fine-print">No real funds are moved. This action updates synthetic demo accounts.</p>
            </form>
          </section>
        )}

        {view === 'checkout' && (
          <section className="form-layout checkout-layout">
            <div className="form-intro"><span className="form-icon form-icon-green"><ShoppingBag size={21} /></span><span className="eyebrow">PARCELFLOW MARKETPLACE</span><h2>Try a payment journey</h2><p>Use a simulator token to exercise authorization, deterministic declines, delayed responses, and capture—without card data or a payment provider.</p><div className="checkout-order"><div className="order-art"><ShoppingBag size={22} /></div><div><strong>ParcelFlow essentials</strong><small>Demo merchant · Digital goods</small></div><span>Checkout</span></div><div className="form-security"><ShieldCheck size={17} />Synthetic tokens only · No PAN or CVV accepted</div></div>
            <form className="action-form" onSubmit={(event) => void handlePayment(event)}>
              <div className="form-heading"><h3>Payment details</h3><span className="demo-badge">SIMULATOR</span></div>
              <label>Pay from <select required value={accountId} onChange={(event) => setAccountId(event.target.value)}>{accounts.map((account) => <option key={account.id} value={account.id}>{account.displayName} · {account.maskedNumber}</option>)}</select></label>
              <label>Payment outcome <select value={paymentToken} onChange={(event) => setPaymentToken(event.target.value)}>{paymentScenarios.map((scenario) => <option key={scenario.value} value={scenario.value}>{scenario.label}</option>)}</select></label>
              <label>Order total <span className="input-affix"><span>{selectedAccount?.balance.ledger.currency ?? 'USD'}</span><input required type="number" min="0.01" step="0.01" max={selectedAccount?.balance.available.amount} value={paymentAmount} onChange={(event) => setPaymentAmount(event.target.value)} placeholder="0.00" /></span></label>
              <div className="form-summary"><span>Payment method</span><strong><CreditCard size={15} /> {selectedAccount?.maskedNumber ?? 'Select an account'}</strong></div>
              <button className="button button-primary button-wide" type="submit" disabled={busy || !selectedAccount}>{busy ? <><span className="button-spinner" />Processing…</> : <>Authorize and pay <ArrowUpRight size={16} /></>}</button>
              <p className="fine-print">By continuing, you run a simulated authorization and capture journey.</p>
            </form>
          </section>
        )}
        <footer className="page-footer"><span>© 2026 ParcelFlow Quality Engineering</span><span><ShieldCheck size={14} /> Synthetic environment</span></footer>
      </main>
    </div>
  );
}

function TransactionList({ transactions }: { transactions: Transaction[] }) {
  if (!transactions.length) {
    return <div className="empty-state"><span><Wallet size={21} /></span><strong>No transactions yet</strong><p>Once you move money or try checkout, activity will appear here.</p></div>;
  }
  return (
    <div className="transaction-list" role="table" aria-label="Recent transactions">
      <div className="transaction-header" role="row"><span role="columnheader">DESCRIPTION</span><span role="columnheader">DATE</span><span role="columnheader">STATUS</span><span className="amount-heading" role="columnheader">AMOUNT</span></div>
      {transactions.map((transaction) => {
        const outgoing = ['TRANSFER_DEBIT', 'PAYMENT_AUTHORIZATION', 'PAYMENT_CAPTURE'].includes(transaction.type);
        return <div className="transaction-row" role="row" key={transaction.id}>
          <span className="transaction-description" role="cell"><i className={outgoing ? 'transaction-icon outgoing' : 'transaction-icon incoming'}>{outgoing ? <ArrowUpRight size={16} /> : <ArrowLeftRight size={16} />}</i><span><strong>{transaction.description ?? transaction.type.replaceAll('_', ' ')}</strong><small>{transaction.type.replaceAll('_', ' ').toLowerCase()}</small></span></span>
          <span className="transaction-date" role="cell">{formatDate(transaction.createdAt)}</span>
          <span role="cell"><i className={`status-chip status-${transaction.status.toLowerCase()}`}><i />{transaction.status.toLowerCase()}</i></span>
          <strong className={`transaction-amount ${outgoing ? 'negative' : ''}`} role="cell">{outgoing ? '−' : '+'}{formatMoney(transaction.amount)}</strong>
        </div>;
      })}
    </div>
  );
}
