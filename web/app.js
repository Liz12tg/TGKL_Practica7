'use strict';

const API = '/api/v1';
const MAX_BOLETOS = 8;
const VISTAS = ['boletos', 'mis-boletos', 'cajas', 'en-vivo'];
const TITULOS = { 'boletos': 'Concierto de ópera', 'mis-boletos': 'Mis boletos', 'cajas': 'Cajas', 'en-vivo': 'Concierto de ópera' };

const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];

function h(tag, props, ...hijos) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'vars') for (const [n, val] of Object.entries(v)) e.style.setProperty(n, val);   // CSSOM: permitido por la CSP
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of hijos.flat()) {
    if (c == null || c === false) continue;
    e.append(c.nodeType ? c : document.createTextNode(String(c)));
  }
  return e;
}
const color = i => 'var(--v' + (i % 8) + ')';
const icono = id => {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('class', 'ico'); s.setAttribute('aria-hidden', 'true');
  const u = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  u.setAttribute('href', '#' + id); s.append(u);
  return s;
};

/* almacenamiento local */
const Guardado = {
  leer(clave, defecto) {
    try { const t = localStorage.getItem('boleteria.' + clave); return t == null ? defecto : JSON.parse(t); } catch (e) { return defecto; }
  },
  escribir(clave, valor) {
    try { localStorage.setItem('boleteria.' + clave, JSON.stringify(valor)); } catch (e) { /* sin espacio o bloqueado */ }
  },
};

/* estado */
const S = {
  vista: 'boletos',
  cantidad: 2,
  conexion: 'conectando',              
  evento: null,
  cajas: [],
  compras: new Map(),                   
  eventosSig: 0,
  mias: Guardado.leer('mias', []),      
  snapshots: Guardado.leer('snapshots', {}),    
  cola: Guardado.leer('cola', []),      
  anunciadas: new Set(Guardado.leer('anunciadas', [])),
  seguimiento: null,                   
  foliosVistos: new Set(),
  instancia: null,
};
let reg = null;                          
let promptInstalar = null;
let animandoCifra = 0;

/* API */
async function api(ruta, opciones) {
  const r = await fetch(API + ruta, opciones);
  let datos = null;
  try { datos = await r.json(); } catch (e) { /* 304 o sin cuerpo */ }
  return { ok: r.ok, status: r.status, datos, desdeCache: r.headers.get('X-Desde-Cache') === '1' };
}

function claveSnap(c) { return S.instancia + ':' + c; }

function misIdsVigentes() {
  return S.mias.filter(m => m.instancia === S.instancia).map(m => m.id);
}

/* avisos pequeños */
function toast(texto, accion) {
  const el = h('div', { class: 'toast', role: 'status' }, h('span', {}, texto),
    accion ? h('button', { type: 'button', onclick: () => { accion.fn(); el.remove(); } }, accion.texto) : null);
  $('#toasts').append(el);
  setTimeout(() => el.remove(), accion ? 9000 : 3500);
}
function vibrar(patron) { try { if (navigator.vibrate) navigator.vibrate(patron); } catch (e) { /* no disponible */ } }

/* navegación entre vistas */
function ir(vista, { empujar = true } = {}) {
  if (!VISTAS.includes(vista)) vista = 'boletos';
  S.vista = vista;
  for (const v of VISTAS) {
    const sec = $('#v-' + v);
    if (sec) sec.hidden = v !== vista;
  }
  for (const t of $$('.tab')) {
    if (t.dataset.ir === vista) t.setAttribute('aria-current', 'page'); else t.removeAttribute('aria-current');
  }
  $('#app').classList.toggle('con-accion', vista === 'boletos');
  $('#barra-sub').textContent = TITULOS[vista];
  if (empujar && location.hash !== '#' + vista) history.pushState(null, '', '#' + vista);
  window.scrollTo(0, 0);
  if (vista === 'en-vivo') { S.eventosSig = 0; $('#bitacora').replaceChildren(); }
  pintar();
  ciclo();
}
document.addEventListener('click', e => {
  const b = e.target.closest('[data-ir]');
  if (b) ir(b.dataset.ir);
});
window.addEventListener('popstate', () => ir(location.hash.slice(1), { empujar: false }));

/* conexión */
function pintarConexion() {
  const aviso = $('#aviso');
  let msg = '';
  if (S.conexion === 'sin') msg = 'Sin conexión. Puedes ver tus boletos y dejar compras en cola.';
  else if (S.conexion === 'guardado') msg = 'No se pudo llegar al servidor. Estás viendo los últimos datos guardados.';
  if (msg && S.cola.length) msg += ' En cola: ' + S.cola.length + '.';
  aviso.hidden = !msg;
  $('#aviso-texto').textContent = msg;
}

/* ciclo de consulta */
let cicloActivo = false;
let temporizador = 0;

async function ciclo() {
  clearTimeout(temporizador);
  if (cicloActivo) return;
  cicloActivo = true;
  try {
    const mias = misIdsVigentes();
    const quiereBitacora = S.vista === 'en-vivo';
    const falla = () => ({ ok: false, status: 0, datos: null, desdeCache: false, red: true });
    const [ev, ca, co, mi, bi] = await Promise.all([
      api('/evento').catch(falla),
      api('/cajas').catch(falla),
      api('/compras?limite=40').catch(falla),
      mias.length ? api('/compras?ids=' + mias.join(',')).catch(falla) : null,
      quiereBitacora ? api('/eventos?desde=' + S.eventosSig).catch(falla) : null,
    ]);

    const anterior = S.conexion;
    if (ev.ok) {
      S.conexion = ev.desdeCache ? 'guardado' : 'ok';
      if (S.instancia !== null && ev.datos.instancia !== S.instancia) { S.compras.clear(); S.eventosSig = 0; $('#bitacora').replaceChildren(); }
      S.instancia = ev.datos.instancia;
      S.evento = ev.datos;
    } else {
      S.conexion = 'sin';
    }
    if (ca.ok) S.cajas = ca.datos.cajas;
    if (co.ok) for (const c of co.datos.compras) S.compras.set(c.id, c);
    if (mi && mi.ok) for (const c of mi.datos.compras) S.compras.set(c.id, c);
    if (bi && bi.ok) pintarBitacora(bi.datos);

    if (S.conexion === 'ok' && anterior === 'sin') toast('De nuevo en línea');
    if (S.conexion === 'ok' && S.cola.length) vaciarCola();
    guardarSnapshots();
    revisarSeguimiento();
    pintar();
  } finally {
    cicloActivo = false;
    const rapido = S.seguimiento !== null;
    temporizador = setTimeout(ciclo, document.hidden ? 5000 : rapido ? 500 : 1000);
  }
}

function guardarSnapshots() {
  let cambio = false;
  for (const m of S.mias) {
    if (m.instancia !== S.instancia) continue;
    const c = S.compras.get(m.id);
    if (!c) continue;
    const k = claveSnap(m.id), nuevo = JSON.stringify(c);
    if (JSON.stringify(S.snapshots[k]) !== nuevo) { S.snapshots[k] = c; cambio = true; }
  }
  if (cambio) Guardado.escribir('snapshots', S.snapshots);
}

function comprasMias() {
  return S.mias.map(m => {
    const viva = m.instancia === S.instancia ? S.compras.get(m.id) : null;
    const snap = S.snapshots[m.instancia + ':' + m.id];
    return viva ? { c: viva, antigua: false } : snap ? { c: snap, antigua: m.instancia !== S.instancia } : null;
  }).filter(Boolean).sort((a, b) => b.c.creada - a.c.creada);
}

/* compra */
async function enviarCompra(item) {
  const r = await fetch(API + '/compras', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'app', 'Idempotency-Key': item.llave },
    body: JSON.stringify({ nombre: item.nombre, boletos: item.boletos }),
  });
  let datos = null;
  try { datos = await r.json(); } catch (e) { /* sin cuerpo */ }
  return { status: r.status, datos };
}

function registrarMia(compra) {
  if (!S.mias.some(m => m.id === compra.id && m.instancia === S.instancia)) {
    S.mias.unshift({ id: compra.id, instancia: S.instancia });
    Guardado.escribir('mias', S.mias.slice(0, 60));
  }
  S.compras.set(compra.id, compra);
  S.seguimiento = compra.id;
}

function guardarCola() { Guardado.escribir('cola', S.cola); }

async function comprar(ev) {
  ev.preventDefault();
  const nombre = $('#nombre').value.trim();
  const campo = $('#nombre');
  campo.classList.toggle('invalida', !nombre);
  $('#error-nombre').textContent = nombre ? '' : 'Escribe tu nombre para ponerlo en los boletos.';
  if (!nombre) { campo.focus(); return; }
  Guardado.escribir('nombre', nombre);

  const item = { llave: crypto.randomUUID(), nombre, boletos: S.cantidad, creada: Date.now() };
  $('#btn-comprar').disabled = true;
  try {
    const r = await enviarCompra(item);
    if (r.status === 202 || r.status === 200) {
      registrarMia(r.datos);
      vibrar(20);
      pintar();
      ciclo();
    } else if (r.status === 429) {
      toast('Hay muchas compras en curso. Intenta en unos segundos.');
    } else {
      toast((r.datos && r.datos.error) || 'No se pudo hacer la compra.');
    }
  } catch (e) {
    S.cola.push(item); guardarCola();
    pedirSincronizacion();
    toast('Sin conexión: tu compra quedó en cola.', { texto: 'Ver', fn: () => ir('mis-boletos') });
    vibrar([20, 40, 20]);
    pintarConexion();
    pintar();
  } finally {
    pintar();
  }
}

let vaciando = false;
async function vaciarCola() {
  if (vaciando || !S.cola.length) return;
  vaciando = true;
  try {
    while (S.cola.length) {
      const item = S.cola[0];
      let r;
      try { r = await enviarCompra(item); } catch (e) { break; }          
      if (r.status === 202 || r.status === 200) {
        S.cola.shift(); guardarCola();
        registrarMia(r.datos);
        toast('Tu compra en cola se envió.');
      } else if (r.status === 429 || r.status >= 500) {
        break;
      } else {
        S.cola.shift(); guardarCola();                                
        toast((r.datos && r.datos.error) || 'Se descartó una compra en cola.');
      }
    }
  } finally {
    vaciando = false;
    pintarConexion();
    pintar();
  }
}

function pedirSincronizacion() {
  if (reg && 'sync' in reg) reg.sync.register('enviar-cola').catch(() => { /* no disponible */ });
}

function revisarSeguimiento() {
  const id = S.seguimiento;
  if (id == null) return;
  const c = S.compras.get(id);
  if (!c || c.estado !== 'terminada') return;
  S.seguimiento = null;
  if (S.anunciadas.has(c.id + '@' + S.instancia)) return;
  S.anunciadas.add(c.id + '@' + S.instancia);
  Guardado.escribir('anunciadas', [...S.anunciadas].slice(-100));
  vibrar(c.comprados ? [30, 50, 30] : 80);
  if (document.hidden) notificar(c); else mostrarHoja(c);
}

/* hoja de resultado y notificaciones */
function textoResultado(c) {
  if (c.resultado === 'completa') return { t: c.boletos === 1 ? '¡Listo! Tienes tu boleto' : '¡Listo! Compraste ' + c.boletos + ' boletos', d: 'Ya están en “Mis boletos”.' };
  if (c.resultado === 'parcial') return { t: 'Compraste ' + c.comprados + ' de ' + c.boletos, d: 'Se agotaron los boletos antes de completar tu compra.' };
  return { t: 'No alcanzaron los boletos', d: 'La función se agotó antes de que te atendieran.' };
}

function mostrarHoja(c) {
  const r = textoResultado(c);
  $('#hoja-titulo').textContent = r.t;
  $('#hoja-texto').textContent = r.d;
  $('#hoja-folios').replaceChildren(...c.folios.map(f => h('span', { class: 'folio-chip' }, 'N.º ' + String(f).padStart(2, '0'))));
  $('.hoja-check').hidden = !c.comprados;
  $('#hoja-ver').hidden = !c.comprados;
  $('#hoja-fondo').hidden = false;
  $('#hoja').focus();
}
function cerrarHoja() { $('#hoja-fondo').hidden = true; }

async function notificar(c) {
  if (!Guardado.leer('avisos', false) || !('Notification' in window) || Notification.permission !== 'granted' || !reg) return;
  const r = textoResultado(c);
  try { await reg.showNotification(r.t, { body: r.d, icon: '/icons/icon-192.png', tag: 'compra-' + c.id, data: { vista: 'mis-boletos' } }); } catch (e) { /* sin permiso */ }
}

/* pintar: Boletos */
function nombreCaja(direccion) {
  const c = S.cajas.find(x => x.direccion === direccion);
  return c ? c.id : direccion;
}

function pintarCartel() {
  const e = S.evento;
  if (!e) return;
  $('#cartel-nombre').textContent = e.nombre;
  $('#cartel-total').textContent = e.total;
  $('#cartel-vendidos').textContent = e.vendidos;
  $('#progreso').style.width = (e.total ? 100 * e.vendidos / e.total : 0) + '%';
  const abiertas = S.cajas.filter(c => c.enLinea).length;
  $('#cartel-cajas').textContent = abiertas + ' de ' + S.cajas.length + ' cajas abiertas';
  $('#cartel-agotado').hidden = !(e.enLinea && e.disponibles <= 0);
  $('#cartel').classList.toggle('agotado', e.disponibles <= 0);
  $('#cifra-texto').textContent = e.enLinea ? (e.disponibles === 1 ? 'boleto disponible' : 'boletos disponibles') : 'el sistema no responde';
  $('#barra-sub').textContent = TITULOS[S.vista];
  animarCifra(e.enLinea ? e.disponibles : null);
}

function animarCifra(hasta) {
  const el = $('#cifra-disponibles');
  if (hasta === null) { el.textContent = '—'; return; }
  const desde = parseInt(el.textContent, 10);
  if (Number.isNaN(desde) || desde === hasta || matchMedia('(prefers-reduced-motion: reduce)').matches) { el.textContent = hasta; return; }
  const id = ++animandoCifra, ini = performance.now(), dur = 450;
  (function paso(t) {
    if (id !== animandoCifra) return;
    const k = Math.min(1, (t - ini) / dur);
    el.textContent = Math.round(desde + (hasta - desde) * (1 - Math.pow(1 - k, 3)));
    if (k < 1) requestAnimationFrame(paso);
  })(ini);
}

function pintarCompraActiva() {
  const tarjeta = $('#compra-activa');
  const mia = S.seguimiento != null ? S.compras.get(S.seguimiento) : null;
  const enCurso = comprasMias().map(x => x.c).find(c => c.estado !== 'terminada' && S.compras.has(c.id));
  const c = mia || enCurso;
  tarjeta.hidden = !c;
  if (!c) return;
  const texto = c.estado === 'en_fila' ? 'Todas las cajas están llenas. Estás en la fila…'
    : c.estado === 'en_caja' ? 'Te atiende la caja ' + nombreCaja(c.caja) + '…'
    : 'Buscando una caja disponible…';
  $('#activa-titulo').textContent = c.comprados + ' de ' + c.boletos + ' boletos';
  $('#activa-mensaje').textContent = texto;
  $('#activa-color').style.setProperty('--c', color(c.color));
  const pasos = $('#activa-pasos');
  pasos.style.setProperty('--c', color(c.color));
  if (pasos.children.length !== c.boletos) pasos.replaceChildren(...Array.from({ length: c.boletos }, () => h('li')));
  [...pasos.children].forEach((li, i) => li.classList.toggle('hecho', i < c.comprados));
}

function pintarAccion() {
  const e = S.evento;
  const agotado = !!(e && e.enLinea && e.disponibles <= 0);
  const ocupado = S.seguimiento != null;
  const n = S.cantidad;
  $('#cantidad').textContent = n;
  $('#menos').disabled = n <= 1;
  $('#mas').disabled = n >= MAX_BOLETOS;
  $('#btn-comprar').disabled = agotado || ocupado;
  $('#btn-comprar').textContent = ocupado ? 'Comprando…' : S.conexion === 'sin' ? 'Dejar en cola' : 'Comprar';
  $('#ayuda-cantidad').textContent = (e && e.enLinea && !agotado && e.disponibles < n)
    ? 'Solo quedan ' + e.disponibles + '; se comprarán los que alcancen.'
    : 'Máximo ' + MAX_BOLETOS + ' por persona.';
}

/*- pintar: Mis boletos */
const formatoFecha = new Intl.DateTimeFormat('es-MX', { dateStyle: 'medium', timeStyle: 'short' });

function tarjetaBoleto(c, folio, antigua) {
  const nuevo = !S.foliosVistos.has(c.id + ':' + folio);
  S.foliosVistos.add(c.id + ':' + folio);
  return h('article', { class: 'boleto' + (nuevo && S.vista === 'mis-boletos' && !antigua ? ' nuevo' : ''), vars: { '--c': color(c.color) } },
    h('div', { class: 'boleto-cuerpo' },
      h('div', { class: 'boleto-evento' }, S.evento ? S.evento.nombre : 'Concierto de ópera'),
      h('div', { class: 'boleto-dato' }, 'Función única'),
      h('div', { class: 'boleto-nombre' }, c.nombre),
      h('div', { class: 'boleto-dato' }, formatoFecha.format(new Date(c.creada)) + (antigua ? ' - sesión anterior' : ''))),
    h('div', { class: 'boleto-talon' }, h('small', {}, 'Boleto'), h('span', { class: 'boleto-folio' }, String(folio).padStart(2, '0'))));
}

function pintarMisBoletos() {
  const lista = $('#mis-lista'), cola = $('#cola-lista');
  const mias = comprasMias();
  cola.replaceChildren(...S.cola.map(item => h('article', { class: 'tarjeta pendiente' },
    h('div', { class: 'tarjeta-fila' },
      icono('i-reloj'),
      h('div', { class: 'tarjeta-texto' },
        h('h3', {}, item.boletos + (item.boletos === 1 ? ' boleto' : ' boletos') + ' en cola'),
        h('p', {}, 'Para ' + item.nombre + '. Se comprarán cuando vuelva la conexión.')),
      h('button', { type: 'button', class: 'btn btn-claro', onclick: () => { S.cola = S.cola.filter(x => x.llave !== item.llave); guardarCola(); pintar(); pintarConexion(); } }, 'Quitar')))));

  const piezas = [];
  for (const { c, antigua } of mias) {
    if (c.estado !== 'terminada' && !antigua) {
      piezas.push(h('article', { class: 'tarjeta pendiente' },
        h('div', { class: 'tarjeta-fila' },
          h('span', { class: 'insignia', vars: { '--c': color(c.color) } }),
          h('div', { class: 'tarjeta-texto' }, h('h3', {}, 'Comprando… ' + c.comprados + ' de ' + c.boletos),
            h('p', {}, c.estado === 'en_fila' ? 'Estás en la fila.' : c.estado === 'en_caja' ? 'Te atiende la caja ' + nombreCaja(c.caja) + '.' : 'Buscando caja.')))));
    }
    for (const f of c.folios) piezas.push(tarjetaBoleto(c, f, antigua));
  }
  lista.replaceChildren(...piezas);
  const total = mias.reduce((n, x) => n + x.c.folios.length, 0);
  $('#mis-vacio').hidden = piezas.length > 0 || S.cola.length > 0;
  const ins = $('#insignia-mis');
  ins.hidden = total === 0;
  ins.textContent = total;
  try { if (navigator.setAppBadge) { total ? navigator.setAppBadge(total) : navigator.clearAppBadge(); } } catch (e) { /* no disponible */ }
}

/* pintar: Cajas */
function clientesEnCaja(direccion) {
  return [...S.compras.values()].filter(c => c.estado === 'en_caja' && c.caja === direccion);
}

function pintarCajas() {
  const cont = $('#cajas');
  cont.replaceChildren(...S.cajas.map(c => {
    const quienes = clientesEnCaja(c.direccion);
    const n = Math.max(c.maxClientes, 1), ocupados = Math.min(c.sesiones, n);
    const huecos = Array.from({ length: n }, (_, i) => {
      const q = i < ocupados ? quienes[i] : null;
      return h('span', { class: 'hueco' + (i < ocupados ? ' ocupado' : ''), vars: q ? { '--c': color(q.color) } : {}, title: q ? q.nombre : '' },
        q ? q.nombre.trim().charAt(0).toUpperCase() : '');
    });
    return h('article', { class: 'caja' + (c.enLinea ? '' : ' apagada') },
      h('div', { class: 'caja-cab' },
        h('div', {}, h('div', { class: 'caja-nombre' }, c.id), h('div', { class: 'caja-dir' }, c.direccion)),
        h('span', { class: 'estado' + (c.enLinea ? '' : ' mal') }, c.enLinea ? 'Abierta' : 'Cerrada')),
      c.enLinea ? h('div', { class: 'ventanillas', role: 'img', 'aria-label': 'Atendiendo a ' + c.sesiones + ' de ' + c.maxClientes }, huecos) : h('p', { class: 'ayuda' }, 'Inicia este servidor para usarla.'),
      c.enLinea ? h('p', { class: 'ayuda' }, 'Atendiendo a ' + c.sesiones + ' de ' + c.maxClientes + (c.maxClientes === 1 ? ' cliente' : ' clientes')) : null,
      c.enLinea ? h('div', { class: 'caja-pie' }, h('span', {}, 'Vendió ', h('b', {}, c.ventas), c.ventas === 1 ? ' boleto' : ' boletos'), h('span', {}, 'reloj ', h('b', {}, c.reloj))) : null);
  }));
}

/* pintar: En vivo */
function pintarEnVivo() {
  const e = S.evento;
  if (e) {
    $('#vivo-sobran').textContent = 'Sobran ' + e.disponibles + ' de ' + e.total;
    const dueño = new Map();
    for (const c of S.compras.values()) for (const f of c.folios) dueño.set(f, c);
    const cont = $('#butacas');
    cont.style.setProperty('--b', e.total > 120 ? '14px' : e.total > 60 ? '20px' : '26px');
    while (cont.children.length > e.total) cont.lastChild.remove();
    while (cont.children.length < e.total) cont.append(h('div', { class: 'butaca' }));
    [...cont.children].forEach((b, i) => {
      const folio = i + 1;
      const c = folio <= e.vendidos ? dueño.get(folio) : null;
      const estado = folio > e.vendidos ? 'libre' : c ? 'vendida' : 'antes';
      const clave = estado + (c ? c.id : '');
      if (b.dataset.clave === clave) return;
      const nueva = b.dataset.clave !== undefined && estado === 'vendida';
      b.dataset.clave = clave;
      b.className = 'butaca' + (estado === 'vendida' ? ' vendida' : estado === 'antes' ? ' antes' : '');
      b.style.setProperty('--c', c ? color(c.color) : 'transparent');
      b.title = estado === 'libre' ? 'Libre' : c ? 'Vendido a ' + c.nombre : 'Vendido antes';
      if (nueva) { b.classList.add('gira'); b.addEventListener('animationend', () => b.classList.remove('gira'), { once: true }); }
    });
  }
  const todos = [...S.compras.values()].sort((a, b) => b.id - a.id);
  const enFila = todos.filter(c => c.estado === 'en_fila').length;
  const chip = $('#vivo-fila');
  chip.hidden = enFila === 0;
  chip.textContent = 'En la fila: ' + enFila;
  const misIds = new Set(misIdsVigentes());
  const estadoTexto = c => c.estado === 'terminada' ? (c.resultado === 'completa' ? 'Compró todos sus boletos' : c.comprados ? 'Compró ' + c.comprados + ' de ' + c.boletos : 'No alcanzó boletos')
    : c.estado === 'en_fila' ? 'En la fila' : c.estado === 'en_caja' ? 'Comprando en ' + nombreCaja(c.caja) : 'Buscando caja';
  $('#clientes').replaceChildren(...(todos.length ? todos.slice(0, 40).map(c => h('article', { class: 'cliente' + (misIds.has(c.id) ? ' mio' : ''), vars: { '--c': color(c.color) } },
    h('div', { class: 'cliente-nombre' }, c.nombre + (misIds.has(c.id) ? ' (tú)' : '')),
    h('div', { class: 'cliente-estado' }, estadoTexto(c)),
    h('div', { class: 'cliente-cuenta' }, c.comprados + '/' + c.boletos),
    h('div', { class: 'cliente-barra' }, Array.from({ length: c.boletos }, (_, i) => h('i', { class: i < c.comprados ? 'lleno' : '' }))))) :
    [h('p', { class: 'ayuda' }, 'Todavía no hay clientes.')]));
}

function pintarBitacora(d) {
  const cont = $('#bitacora');
  if (d.reinicio) cont.replaceChildren();
  S.eventosSig = d.siguiente;
  if (d.eventos.length === 0 && !cont.firstChild) { cont.append(h('div', { class: 'sin-eventos' }, 'Aquí aparecerán los eventos cuando llegue un cliente.')); return; }
  if (d.eventos.length) cont.querySelector('.sin-eventos')?.remove();
  const abajo = cont.scrollHeight - cont.scrollTop - cont.clientHeight < 40;
  for (const e of d.eventos) {
    const sinReloj = ['intento', 'reintento', 'fila', 'fin'].includes(e.tipo);
    const c = S.compras.get(e.compra);
    cont.append(h('div', { class: 'fila ' + (e.tipo === 'fila' ? 'espera' : e.tipo), vars: { '--c': color(c ? c.color : e.compra - 1) } },
      h('div', { class: 'f-reloj' }, sinReloj ? '' : e.reloj),
      h('div', { class: 'f-quien', title: e.nombre }, e.nombre),
      h('div', { class: 'f-texto' }, e.texto)));
  }
  while (cont.children.length > 300) cont.firstChild.remove();
  if (abajo) cont.scrollTop = cont.scrollHeight;
}

/* pintar todo */
function pintar() {
  pintarConexion();
  pintarCartel();
  pintarAccion();
  pintarCompraActiva();
  if (S.vista === 'mis-boletos') pintarMisBoletos(); else pintarInsignia();
  if (S.vista === 'cajas') pintarCajas();
  if (S.vista === 'en-vivo') pintarEnVivo();
}
function pintarInsignia() {
  const total = comprasMias().reduce((n, x) => n + x.c.folios.length, 0);
  const ins = $('#insignia-mis');
  ins.hidden = total === 0;
  ins.textContent = total;
}

/* simulador de fila */
const NOMBRES = ['Ana', 'Beto', 'Carla', 'Dani', 'Eva', 'Fer', 'Gael', 'Hugo', 'Inés', 'Juan', 'Karla', 'Luis'];
async function simularFila() {
  const boton = $('#btn-simular'), error = $('#error-simular');
  error.textContent = '';
  boton.disabled = true;
  try {
    const base = S.compras.size;
    const respuestas = await Promise.all(Array.from({ length: 8 }, (_, i) => enviarCompra({
      llave: crypto.randomUUID(),
      nombre: NOMBRES[(base + i) % NOMBRES.length] + (base + i >= NOMBRES.length ? ' ' + (Math.floor((base + i) / NOMBRES.length) + 1) : ''),
      boletos: 3,
    })));
    const mal = respuestas.find(r => r.status !== 202 && r.status !== 200);
    if (mal) error.textContent = (mal.datos && mal.datos.error) || 'No se pudo mandar a todos los clientes.';
    ciclo();
  } catch (e) {
    error.textContent = 'Sin conexión: no se pudo iniciar la prueba.';
  } finally {
    boton.disabled = false;
  }
}

/* instalar la app */
const esStandalone = () => matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
const esIOS = () => /iphone|ipad|ipod/i.test(navigator.userAgent) && !window.MSStream;

function pintarInstalar() {
  const instalada = esStandalone();
  const puede = !!promptInstalar && !instalada;
  $('#btn-instalar').hidden = !puede;
  const oculta = Guardado.leer('instalarOculto', false);
  const ios = esIOS() && !instalada;
  const tarjeta = $('#instalar-tarjeta');
  tarjeta.hidden = instalada || oculta || !(puede || ios);
  if (ios) {
    $('#instalar-ayuda').textContent = 'En Safari toca Compartir y luego “Agregar a pantalla de inicio”.';
    $('#instalar-ahora').hidden = true;
    $('#instalar-despues').textContent = 'Entendido';
  }
}

async function instalar() {
  if (!promptInstalar) return;
  promptInstalar.prompt();
  const { outcome } = await promptInstalar.userChoice;
  promptInstalar = null;
  if (outcome === 'accepted') toast('Instalando la app…');
  pintarInstalar();
}

window.addEventListener('beforeinstallprompt', e => { e.preventDefault(); promptInstalar = e; pintarInstalar(); });
window.addEventListener('appinstalled', () => { promptInstalar = null; toast('App instalada'); pintarInstalar(); });

/* service worker */
function registrarServiceWorker() {
  if (!('serviceWorker' in navigator)) return;
  let recargando = false;
  navigator.serviceWorker.addEventListener('controllerchange', () => { if (!recargando) { recargando = true; location.reload(); } });
  navigator.serviceWorker.addEventListener('message', e => {
    if (e.data && e.data.tipo === 'VACIAR_COLA') vaciarCola();
    if (e.data && e.data.tipo === 'IR') ir(e.data.vista);
  });
  navigator.serviceWorker.register('/sw.js').then(r => {
    reg = r;
    const hayNueva = w => w && navigator.serviceWorker.controller && toast('Hay una versión nueva de la app.', { texto: 'Actualizar', fn: () => w.postMessage({ tipo: 'ACTIVAR' }) });
    if (r.waiting) hayNueva(r.waiting);
    r.addEventListener('updatefound', () => {
      const w = r.installing;
      w.addEventListener('statechange', () => { if (w.state === 'installed') hayNueva(r.waiting || w); });
    });
    document.addEventListener('visibilitychange', () => { if (!document.hidden) r.update().catch(() => { /* sin red */ }); });
  }).catch(() => { /* la app funciona igual, solo sin modo sin conexión */ });
}

/* arranque */
function iniciar() {
  const nombre = Guardado.leer('nombre', '');
  if (nombre) $('#nombre').value = nombre;
  $('#form-compra').addEventListener('submit', comprar);
  $('#nombre').addEventListener('input', () => { $('#nombre').classList.remove('invalida'); $('#error-nombre').textContent = ''; });
  $('#menos').addEventListener('click', () => { S.cantidad = Math.max(1, S.cantidad - 1); vibrar(8); pintarAccion(); });
  $('#mas').addEventListener('click', () => { S.cantidad = Math.min(MAX_BOLETOS, S.cantidad + 1); vibrar(8); pintarAccion(); });
  $('#btn-simular').addEventListener('click', simularFila);
  $('#btn-instalar').addEventListener('click', instalar);
  $('#instalar-ahora').addEventListener('click', instalar);
  $('#instalar-despues').addEventListener('click', () => { Guardado.escribir('instalarOculto', true); pintarInstalar(); });
  $('#hoja-cerrar').addEventListener('click', cerrarHoja);
  $('#hoja-fondo').addEventListener('click', e => { if (e.target.id === 'hoja-fondo') cerrarHoja(); });
  $('#hoja-ver').addEventListener('click', () => { cerrarHoja(); ir('mis-boletos'); });
  document.addEventListener('keydown', e => { if (e.key === 'Escape' && !$('#hoja-fondo').hidden) cerrarHoja(); });
  window.addEventListener('online', () => { ciclo(); vaciarCola(); });
  window.addEventListener('offline', () => { S.conexion = 'sin'; pintar(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) ciclo(); });

  pintarInstalar();
  registrarServiceWorker();
  ir(location.hash.slice(1) || 'boletos', { empujar: false });
}
iniciar();
