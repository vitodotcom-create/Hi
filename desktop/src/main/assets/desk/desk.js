// Оживление рабочего стола: кнопки → Android.action(...), данные ← cv.update(json).
(function () {
  function texts(t) {
    var out = [], w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    while (w.nextNode()) if (w.currentNode.nodeValue.trim() === t) out.push(w.currentNode.parentElement);
    return out;
  }
  function one(t) { return texts(t).filter(function (e) { return e.tagName !== 'text'; })[0]; }
  function act(name) { try { Android.action(name); } catch (e) { console.log('action', name); } }
  function bind(el, name) { if (!el) return; el.classList.add(el.classList.contains('tile') ? 'tile' : 'btn'); el.addEventListener('click', function () { act(name); }); }

  // Плитки: подпись внутри плитки → вся плитка.
  var tiles = { 'CLAUDE': 'claude', 'ЭКРАН ½': 'split', 'CHROME': 'chrome', 'WI-FI': 'wifi', '70MAI': '70mai', 'ДОМОЙ': 'home',
    'РАБОТА': 'work', 'WB': 'fav', 'GEMINI': 'gemini', 'МУЗЫКА': 'music', 'РАДИО': 'radio', 'НАСТРОЙКИ': 'settings' };
  var favLabel = null;
  Object.keys(tiles).forEach(function (k) {
    var l = one(k); if (!l) return;
    var t = l.parentElement; t.classList.add('tile'); t.addEventListener('click', function () { act(tiles[k]); });
    if (k === 'WB') favLabel = l;
  });
  // Боковая панель: кнопка над подписью.
  var dock = { 'МЕНЮ': 'menu', 'НАЗАД': 'back', 'ВСЕ': 'all', 'ЗВУК': 'volume', 'ТЕЛЕФОН': 'phone' };
  Object.keys(dock).forEach(function (k) {
    var l = one(k); if (!l) return;
    [l, l.previousElementSibling].forEach(function (e) { if (e) { e.classList.add('btn'); e.addEventListener('click', function () { act(dock[k]); }); } });
  });
  // Большая кнопка «Система».
  Array.prototype.forEach.call(document.querySelectorAll('div'), function (d) {
    if (d.children.length <= 2 && /CARPLAY/.test(d.textContent) && d.textContent.length < 60) bind(d, 'system');
  });
  // Плеер по пиктограммам.
  [['M6,6h2v12H6z', 'prev'], ['M6,5h4v14H6z', 'play'], ['M6,18l8.5', 'next']].forEach(function (p) {
    Array.prototype.forEach.call(document.querySelectorAll('path'), function (path) {
      var d = path.getAttribute('d') || '';
      if (d.indexOf(p[0]) === 0) { var b = path.closest('.cut'); if (b) bind(b, p[1]); }
    });
  });
  var playPath = null;
  Array.prototype.forEach.call(document.querySelectorAll('.cut path'), function (p) { if ((p.getAttribute('d') || '').indexOf('M6,5h4v14H6z') === 0) playPath = p; });

  // Поля данных.
  var clocks = texts('18:01').filter(function (e) { return e.tagName !== 'text'; });
  var dateEl = document.querySelector('.date');
  var hintEl = document.querySelector('.hint');
  var temps = texts('+11°');
  var wTele = one('ДОЖДЬ · ЕРЕВАН'), wDesc = one('ПАСМУРНО'), wRange = one('ЕРЕВАН · 5…12°');
  var wIcon = one('☁');
  var speeds = texts('0').filter(function (e) { return e.tagName !== 'text'; });
  var course = one('СЗ'), courseDeg = one('315° · GPS');
  var title = one("L'Italiano");
  var card = title ? title.parentElement : null;
  var artist = card ? one('TOTO CUTUGNO ·') : null;
  var appSpan = card ? one('SPOTIFY') : null;
  var t0 = one('0:50'), t1 = one('4:14');
  var bar = null, art = null;
  if (card) Array.prototype.forEach.call(card.children, function (c) {
    var st = c.getAttribute('style') || '';
    if (/width:80px/.test(st)) bar = c;
    if (/radial-gradient\(circle/.test(st)) art = c;
  });
  if (card) bind(card, 'song');

  var DAYS = ['ВОСКРЕСЕНЬЕ', 'ПОНЕДЕЛЬНИК', 'ВТОРНИК', 'СРЕДА', 'ЧЕТВЕРГ', 'ПЯТНИЦА', 'СУББОТА'];
  var MON = ['ЯНВАРЯ', 'ФЕВРАЛЯ', 'МАРТА', 'АПРЕЛЯ', 'МАЯ', 'ИЮНЯ', 'ИЮЛЯ', 'АВГУСТА', 'СЕНТЯБРЯ', 'ОКТЯБРЯ', 'НОЯБРЯ', 'ДЕКАБРЯ'];
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function fmt(ms) { var s = Math.max(0, Math.floor(ms / 1000)); return Math.floor(s / 60) + ':' + pad(s % 60); }
  function clock() {
    var d = new Date(), hm = pad(d.getHours()) + ':' + pad(d.getMinutes());
    clocks.forEach(function (c) { if (c.textContent !== hm) c.textContent = hm; });
    if (dateEl) dateEl.textContent = DAYS[d.getDay()] + ' · ' + d.getDate() + ' ' + MON[d.getMonth()];
  }
  clock(); setInterval(clock, 1000);
  document.body.classList.add('still');

  var media = { position: 0, duration: 0, playing: false, at: Date.now() };
  function progress() {
    var pos = media.position + (media.playing ? Date.now() - media.at : 0);
    if (t0) t0.textContent = media.duration ? fmt(pos) : '';
    if (t1) t1.textContent = media.duration ? fmt(media.duration) : '';
    if (bar) bar.style.width = (media.duration ? Math.min(1, pos / media.duration) * 284 : 0) + 'px';
  }
  setInterval(progress, 1000);

  var DIRS = ['С', 'СВ', 'В', 'ЮВ', 'Ю', 'ЮЗ', 'З', 'СЗ'];
  window.cv = {
    update: function (json) {
      var d = typeof json === 'string' ? JSON.parse(json) : json;
      if (d.temp) { temps.forEach(function (e) { e.textContent = d.temp; }); }
      if (d.wdesc !== undefined) {
        if (wTele) wTele.textContent = (d.wdesc || '').toUpperCase() + ' · ЕРЕВАН';
        if (wDesc) wDesc.textContent = (d.wdesc || '').toUpperCase();
        if (wRange && d.tmin !== undefined) wRange.textContent = 'ЕРЕВАН · ' + d.tmin + '…' + d.tmax + '°';
        if (wIcon && d.wicon) wIcon.textContent = d.wicon;
      }
      if (d.speed !== undefined) {
        speeds.forEach(function (e) { e.textContent = d.speed; });
        document.body.classList.toggle('moving', d.speed >= 5);
        document.body.classList.toggle('still', d.speed < 5);
        var k = Math.min(1, d.speed / 100);
        var tr = document.getElementById('trails'), st = document.getElementById('steam');
        if (tr && d.speed >= 5) tr.style.opacity = 0.35 + 0.65 * k;
        if (st && d.speed >= 5) st.style.opacity = 0.3 + 0.6 * k;
        rainSpeed = 1 + 2 * k;
      }
      if (d.bearing !== undefined && course) {
        course.textContent = DIRS[Math.round(d.bearing / 45) % 8];
        if (courseDeg) courseDeg.textContent = d.bearing + '° · GPS';
      }
      if (hintEl) {
        if (d.heard) hintEl.innerHTML = '<span>●</span> «' + esc(d.heard) + '» — ' + esc(d.say || '');
        else hintEl.innerHTML = '<span>●</span> Нажмите шайбу или «Claude»';
      }
      if (favLabel && d.fav) favLabel.textContent = d.fav.toUpperCase();
      if (title) {
        if (d.mediaAccess === false) { title.textContent = 'Нажмите — показывать песню'; if (artist) artist.firstChild.nodeValue = 'НУЖЕН ДОСТУП К УВЕДОМЛЕНИЯМ'; if (appSpan) appSpan.textContent = ''; }
        else if (!d.title) { title.textContent = 'Музыка не играет'; if (artist) artist.firstChild.nodeValue = 'НАЖМИТЕ «МУЗЫКА» '; if (appSpan) appSpan.textContent = ''; }
        else {
          title.textContent = d.title;
          if (artist) artist.firstChild.nodeValue = (d.artist || '').toUpperCase() + (d.app ? ' · ' : '');
          if (appSpan) appSpan.textContent = (d.app || '').toUpperCase();
        }
      }
      if (art && d.art !== undefined) {
        if (!art.dataset.orig) art.dataset.orig = art.style.background;
        if (d.art) { art.style.background = 'url(' + d.art + ') center/cover'; }
        else art.style.background = art.dataset.orig;
      }
      if (playPath) playPath.setAttribute('d', d.playing ? 'M6,5h4v14H6zM14,5h4v14h-4z' : 'M8,5v14l11,-7z');
      media = { position: d.position || 0, duration: d.duration || 0, playing: !!d.playing, at: Date.now() };
      progress();
    }
  };
  function esc(s) { return String(s).replace(/[&<>]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]; }); }

  // Дождь поверх фона (лёгкий, 30 кадров в секунду; пауза, когда экран не виден).
  var rainSpeed = 1, cvs = document.getElementById('rain');
  if (cvs) {
    var ctx = cvs.getContext('2d'), drops = [];
    for (var i = 0; i < 140; i++) drops.push({ x: Math.random() * 1920, y: Math.random() * 720, l: 10 + Math.random() * 18, v: 9 + Math.random() * 8, a: 0.12 + Math.random() * 0.25 });
    var last = 0;
    (function frame(t) {
      requestAnimationFrame(frame);
      if (document.hidden || t - last < 33) return;
      last = t;
      ctx.clearRect(0, 0, 1920, 720);
      ctx.lineWidth = 1.2;
      for (var j = 0; j < drops.length; j++) {
        var p = drops[j];
        ctx.strokeStyle = 'rgba(210,225,255,' + p.a + ')';
        ctx.beginPath(); ctx.moveTo(p.x, p.y); ctx.lineTo(p.x - p.l * 0.25, p.y + p.l); ctx.stroke();
        p.y += p.v * rainSpeed; p.x -= p.v * 0.25 * rainSpeed;
        if (p.y > 720) { p.y = -20; p.x = Math.random() * 2100; }
      }
    })(0);
  }
})();
