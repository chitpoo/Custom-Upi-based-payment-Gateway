/* UPI Payment Verification Gateway demo UI.
 * Countdown is display-only: server truth always comes from GET /orders/{id}. */
'use strict';

const EXPIRY_MS = 5 * 60 * 1000;
const EXPIRED_MESSAGE = 'Payment failed — time expired. Please create a new order and pay again.';

let currentOrder = null;
let countdownTimer = null;
let statusTimer = null;

const $ = (id) => document.getElementById(id);

function shortId(id) {
  return id && id.length > 8 ? id.slice(0, 8) + '…' : (id || '-');
}

function verdictClass(verdict) {
  return (verdict || '').toLowerCase();
}

async function api(path, options) {
  const res = await fetch(path, options);
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = { message: text }; }
  if (!res.ok) {
    const msg = (body && (body.message || body.reason)) || ('Request failed: ' + res.status);
    throw new Error(msg);
  }
  return body;
}

function stopTimers() {
  if (countdownTimer) { clearInterval(countdownTimer); countdownTimer = null; }
  if (statusTimer) { clearInterval(statusTimer); statusTimer = null; }
}

function renderCountdown(expiresAt) {
  const el = $('countdown');
  const tick = () => {
    const left = expiresAt - Date.now();
    if (left <= 0) {
      el.textContent = '00:00 (checking status…)';
      return;
    }
    const m = String(Math.floor(left / 60000)).padStart(2, '0');
    const s = String(Math.floor((left % 60000) / 1000)).padStart(2, '0');
    el.textContent = m + ':' + s + ' left';
  };
  tick();
  countdownTimer = setInterval(tick, 1000);
}

function setBadge(status) {
  const badge = $('orderStatusBadge');
  badge.textContent = status || '-';
  badge.className = 'badge ' + (status || '').toLowerCase();
}

function showExpiredBanner(show) {
  $('expiredBanner').hidden = !show;
}

async function refreshOrderStatus() {
  if (!currentOrder) return;
  try {
    const order = await api('/orders/' + encodeURIComponent(currentOrder.id));
    currentOrder = order;
    setBadge(order.status);
    if (order.status === 'EXPIRED') {
      showExpiredBanner(true);
      setVerdictBanner(EXPIRED_MESSAGE);
    } else {
      showExpiredBanner(false);
    }
    if (order.status !== 'PENDING') stopTimers();
  } catch (e) {
    // Keep the last known state; the next poll or action retries.
  }
}

function showOrder(order) {
  stopTimers();
  currentOrder = order;
  $('resultCard').hidden = false;
  $('createError').hidden = true;
  $('uniqueAmount').textContent = 'Rs.' + order.uniqueAmount;
  $('orderId').textContent = order.id;
  $('upiLink').textContent = order.upiLink;
  $('upiAnchor').href = order.upiLink;
  $('qrImg').src = '/orders/' + encodeURIComponent(order.id) + '/qr';
  setBadge(order.status);
  showExpiredBanner(order.status === 'EXPIRED');
  if (order.status === 'EXPIRED') setVerdictBanner(EXPIRED_MESSAGE);
  if (order.status === 'PENDING') {
    const expiresAt = new Date(order.createdAt).getTime() + EXPIRY_MS;
    renderCountdown(expiresAt);
    statusTimer = setInterval(refreshOrderStatus, 5000);
  }
  refreshLists();
}

function setVerdictBanner(message) {
  $('verdictCard').hidden = false;
  $('verdictReason').textContent = message;
}

function showVerdict(resp) {
  $('verdictCard').hidden = false;
  const chip = $('verdictChip');
  chip.textContent = resp.verdict || '-';
  chip.className = 'chip ' + verdictClass(resp.verdict);
  $('verdictReason').textContent = resp.reason || '';
  $('vAmount').textContent = resp.amount != null ? 'Rs.' + resp.amount : '-';
  $('vUtr').textContent = resp.utr || '-';
  $('vOrder').textContent = resp.matchedOrderId || '-';
  $('vOrderStatus').textContent = resp.orderStatus || '-';
  if (resp.orderStatus === 'EXPIRED') {
    // Server is the source of truth; show banner + refresh the result card.
    $('verdictReason').textContent = EXPIRED_MESSAGE;
    showExpiredBanner(true);
    refreshOrderStatus();
  } else if (resp.matchedOrderId && currentOrder && resp.matchedOrderId === currentOrder.id) {
    refreshOrderStatus();
  }
  refreshLists();
}

$('createForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const err = $('createError');
  err.hidden = true;
  try {
    const order = await api('/orders', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        baseAmount: $('baseAmount').value,
        merchantUpiId: $('merchantUpiId').value.trim()
      })
    });
    showOrder(order);
  } catch (ex) {
    err.textContent = ex.message;
    err.hidden = false;
  }
});

$('verifySmsForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    const resp = await api('/payments/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ smsText: $('smsText').value })
    });
    showVerdict(resp);
  } catch (ex) {
    setVerdictBanner(ex.message);
  }
});

$('verifyImageForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const file = $('imageFile').files[0];
  if (!file) return;
  const form = new FormData();
  form.append('image', file);
  try {
    const resp = await api('/payments/verify-image', { method: 'POST', body: form });
    showVerdict(resp);
  } catch (ex) {
    setVerdictBanner(ex.message);
  }
});

$('copyLinkBtn').addEventListener('click', async () => {
  const link = $('upiLink').textContent;
  try {
    await navigator.clipboard.writeText(link);
    $('copyLinkBtn').textContent = 'Copied!';
  } catch {
    const ta = document.createElement('textarea');
    ta.value = link;
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    ta.remove();
    $('copyLinkBtn').textContent = 'Copied!';
  }
  setTimeout(() => { $('copyLinkBtn').textContent = 'Copy'; }, 2000);
});

$('retryBtn').addEventListener('click', () => {
  if (!currentOrder) return;
  $('baseAmount').value = currentOrder.baseAmount;
  $('merchantUpiId').value = currentOrder.merchantUpiId;
  $('createCard').scrollIntoView({ behavior: 'smooth' });
  $('baseAmount').focus();
});

async function refreshLists() {
  try {
    const orders = await api('/orders');
    $('orderBody').innerHTML = orders.length === 0
      ? '<tr><td colspan="5">No orders yet.</td></tr>'
      : orders.map((o) =>
          '<tr><td><code>' + shortId(o.id) + '</code></td><td>Rs.' + o.uniqueAmount +
          '</td><td>' + escapeHtml(o.merchantUpiId) + '</td>' +
          '<td><span class="badge ' + (o.status || '').toLowerCase() + '">' + o.status + '</span></td>' +
          '<td>' + new Date(o.createdAt).toLocaleString() + '</td></tr>'
        ).join('');
  } catch {
    $('orderBody').innerHTML = '<tr><td colspan="5">Failed to load orders.</td></tr>';
  }
  try {
    const payments = await api('/payments');
    $('paymentBody').innerHTML = payments.length === 0
      ? '<tr><td colspan="6">No payments yet.</td></tr>'
      : payments.map((p) =>
          '<tr><td><code>' + shortId(p.paymentId) + '</code></td>' +
          '<td><span class="chip ' + verdictClass(p.verdict) + '">' + p.verdict + '</span></td>' +
          '<td>' + (p.amount != null ? 'Rs.' + p.amount : '-') + '</td>' +
          '<td>' + (p.utr ? escapeHtml(p.utr) : '-') + '</td>' +
          '<td><code>' + shortId(p.matchedOrderId) + '</code></td>' +
          '<td>' + escapeHtml(p.reason || '') + '</td></tr>'
        ).join('');
  } catch {
    $('paymentBody').innerHTML = '<tr><td colspan="6">Failed to load payments.</td></tr>';
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

refreshLists();
setInterval(refreshLists, 5000);
