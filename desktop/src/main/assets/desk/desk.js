// Оживление рабочего стола: кнопки → Android.action(...), данные ← cv.update(json).
(function () {
  function texts(t) {
    var out = [], w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    while (w.nextNode()) if (w.currentNode.nodeValue.trim() === t) out.push(w.currentNode.parentElement);
    return out;
  }
  function one(t) { return texts(t).filter(function (e) { return e.tagName !== 'text'; })[0]; }
  function act(name) {
    if (name === 'volume') { showVolume(); return; }
    if (name === 'mode') { window.nextMode && window.nextMode(); return; }
    try { Android.action(name); } catch (e) { console.log('action', name); }
  }
  // Окно громкости: − / без звука / +, уровень полоской. Закрывается само через 5 секунд.
  var volBox = null, volTimer = null;
  function volCall(cmd) { try { return JSON.parse(Android.volume(cmd)); } catch (e) { return { level: 7, max: 15, muted: false }; } }
  function paintVol(v) {
    volBox.querySelector('.vbar').style.width = (v.muted ? 0 : Math.round(100 * v.level / v.max)) + '%';
    volBox.querySelector('.vnum').textContent = v.muted ? 'БЕЗ ЗВУКА' : v.level + ' / ' + v.max;
  }
  function showVolume() {
    if (!volBox) {
      volBox = document.createElement('div');
      volBox.style.cssText = 'position:absolute;left:112px;top:420px;width:520px;height:120px;z-index:20;clip-path:polygon(0 0,100% 0,100% 72%,94% 100%,0 100%);' +
        'background:linear-gradient(160deg,rgba(30,34,44,.85),rgba(8,8,10,.9));backdrop-filter:blur(14px);border-top:2px solid #4FC3F7;color:#fff;font-weight:800;font-style:italic';
      volBox.innerHTML = '<div style="position:absolute;left:24px;top:14px;font-size:12px;letter-spacing:3px;color:#9aa3b2">ГРОМКОСТЬ</div>' +
        '<div class="vnum" style="position:absolute;right:28px;top:12px;font-size:16px"></div>' +
        '<div style="position:absolute;left:24px;right:24px;top:44px;height:6px;background:rgba(255,255,255,.15)"><div class="vbar" style="height:6px;background:linear-gradient(90deg,#4FC3F7,#ff2a2a);box-shadow:0 0 8px #4FC3F7"></div></div>' +
        '<div style="position:absolute;left:24px;right:24px;top:62px;display:flex;gap:12px">' +
        '<div class="btn vb" data-c="down" style="flex:1;height:44px;display:flex;align-items:center;justify-content:center;background:rgba(255,255,255,.1);font-size:28px">−</div>' +
        '<div class="btn vb" data-c="mute" style="flex:1.4;height:44px;display:flex;align-items:center;justify-content:center;background:rgba(255,42,42,.35);font-size:14px;letter-spacing:2px">БЕЗ ЗВУКА</div>' +
        '<div class="btn vb" data-c="up" style="flex:1;height:44px;display:flex;align-items:center;justify-content:center;background:rgba(255,255,255,.1);font-size:28px">+</div></div>';
      document.body.appendChild(volBox);
      Array.prototype.forEach.call(volBox.querySelectorAll('.vb'), function (b) {
        b.addEventListener('click', function (e) { e.stopPropagation(); paintVol(volCall(b.getAttribute('data-c'))); arm(); });
      });
    }
    volBox.style.display = 'block';
    paintVol(volCall('get'));
    arm();
  }
  function arm() { clearTimeout(volTimer); volTimer = setTimeout(function () { volBox.style.display = 'none'; }, 5000); }
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
  // Новые кнопки вместо «Назад» и «Все» (они дублировали «Меню»).
  var NEWDOCK = {
    'НАЗАД': ['РЕЖИМ', 'M12,4C6.48,4 2,8.48 2,14c0,1.85 .51,3.58 1.39,5.06L5.12,18A8,8 0 1,1 18.88,18l1.73,1.06C21.49,17.58 22,15.85 22,14 22,8.48 17.52,4 12,4zM13.4,15.4L17,8l-7.4,3.6a2,2 0 1,0 3.8,3.8z'],
    'ВСЕ': ['НЕДАВНИЕ', 'M3,5h14v14H3zM19,7h2v10h-2zM5,7v10h10V7z']
  };
  Object.keys(NEWDOCK).forEach(function (k) {
    var l = one(k); if (!l) return;
    var btn = l.previousElementSibling, path = btn && btn.querySelector('path');
    if (path) path.setAttribute('d', NEWDOCK[k][1]);
    l.textContent = NEWDOCK[k][0];
  });
  var dock = { 'МЕНЮ': 'menu', 'РЕЖИМ': 'mode', 'НЕДАВНИЕ': 'recents', 'ЗВУК': 'volume', 'ТЕЛЕФОН': 'phone' };
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
  // ---- Крутящееся колесо: вырез колеса из фото, растянут в круг, вращается, сжат обратно в эллипс ----
  var wheel = null, wheelIn = null;
  (function () {
    var car = document.getElementById('car'); if (!car) return;
    var K = 1408 / 2608, CX = 2003 * K, CY = 650 * K, RX = 80 * K, RY = 142 * K, S = RY / RX;
    wheel = document.createElement('div');
    wheel.style.cssText = 'position:absolute;left:' + (CX - RY) + 'px;top:' + (CY - RY) + 'px;width:' + (2 * RY) + 'px;height:' + (2 * RY) +
      'px;border-radius:50%;overflow:hidden;transform:scaleX(' + (1 / S) + ');pointer-events:none';
    wheelIn = document.createElement('div');
    wheelIn.style.cssText = 'position:absolute;inset:0;background:url(rain.jpg) no-repeat;background-size:' + (1408 * S) + 'px 510px;' +
      'background-position:' + (-(CX - RX) * S) + 'px ' + (-(CY - RY)) + 'px;animation:spin 1s linear infinite;animation-play-state:paused';
    wheel.appendChild(wheelIn); car.appendChild(wheel);
    var st = document.createElement('style');
    st.textContent = '@keyframes spin{from{transform:rotate(0)}to{transform:rotate(-360deg)}}';
    document.head.appendChild(st);
  })();
  function spinWheel(kmh) {
    if (!wheelIn) return;
    if (kmh < 3) { wheelIn.style.animationPlayState = 'paused'; wheelIn.style.filter = ''; return; }
    wheelIn.style.animationDuration = Math.max(0.12, 1.6 - kmh / 60) + 's';
    wheelIn.style.animationPlayState = 'running';
    wheelIn.style.filter = 'blur(' + Math.min(2.5, kmh / 40) + 'px)';
  }

  // ---- Wi-Fi и Bluetooth слева сверху (как в строке состояния бокса) ----
  var stat = document.createElement('div');
  stat.style.cssText = 'position:absolute;left:14px;top:68px;width:84px;display:flex;justify-content:center;gap:10px;z-index:5';
  stat.innerHTML = '<svg id="sWifi" viewBox="0 0 24 24" style="width:22px;height:22px"><path d="M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z"/></svg>' +
    '<svg id="sBt" viewBox="0 0 24 24" style="width:22px;height:22px"><path d="M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71 -4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z"/></svg>';
  document.body.appendChild(stat);
  var ssidEl = document.createElement('div');
  ssidEl.style.cssText = 'position:absolute;left:14px;top:92px;width:84px;text-align:center;font-size:9px;letter-spacing:.5px;color:#9fd8ff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;z-index:5';
  document.body.appendChild(ssidEl);
  function paintIcon(el, state, onColor) {
    if (!el) return;
    var c = state === 'on' ? onColor : state === 'idle' ? 'rgba(255,255,255,.55)' : 'rgba(255,255,255,.18)';
    el.querySelector('path').setAttribute('fill', c);
    el.style.filter = state === 'on' ? 'drop-shadow(0 0 4px ' + onColor + ')' : '';
  }
  paintIcon(document.getElementById('sWifi'), 'off', '#4FC3F7');
  paintIcon(document.getElementById('sBt'), 'off', '#6A9BFF');

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
      if (d.wkind && window.setWeather && (d.wkind !== wx.kind || d.isDay !== wx.day)) { wx.kind = d.wkind; wx.day = !!d.isDay; setWeather(d.wkind, !!d.isDay); }
      if (d.speed !== undefined) spinWheel(d.speed);
      if (d.wifi !== undefined) { paintIcon(document.getElementById('sWifi'), d.wifi, '#4FC3F7'); ssidEl.textContent = d.ssid || ''; }
      if (d.bt !== undefined) paintIcon(document.getElementById('sBt'), d.bt, '#6A9BFF');
      if (playPath) playPath.setAttribute('d', d.playing ? 'M6,5h4v14H6zM14,5h4v14h-4z' : 'M8,5v14l11,-7z');
      media = { position: d.position || 0, duration: d.duration || 0, playing: !!d.playing, at: Date.now() };
      progress();
    }
  };
  function esc(s) { return String(s).replace(/[&<>]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]; }); }

  // ===== Погода на картинке: дождь, гроза, снег, пасмурно, туман, ясно (день/ночь) =====
  var rainSpeed = 1, cvs = document.getElementById('rain'), wx = { kind: 'rain', day: false, intensity: 1 };
  // Слои между фото и интерфейсом: цветокоррекция (backdrop-filter), дымка, солнце, вспышки молний.
  var grade = document.createElement('div'), haze = document.createElement('div'), sun = document.createElement('div'), flash = document.createElement('div');
  [grade, haze, sun, flash].forEach(function (e) { e.style.cssText = 'position:absolute;left:0;top:0;width:1920px;height:720px;pointer-events:none;transition:opacity 2s,backdrop-filter 2s,background 2s'; });
  sun.innerHTML = '<svg width="1920" height="720" viewBox="0 0 1920 720">' +
    '<defs><radialGradient id="sg"><stop offset="0" stop-color="#fffbe8"/><stop offset=".08" stop-color="#fff2c0" stop-opacity=".95"/><stop offset=".3" stop-color="#ffd27a" stop-opacity=".35"/><stop offset="1" stop-color="#ffb040" stop-opacity="0"/></radialGradient>' +
    '<linearGradient id="ray" x1="0" x2="1"><stop offset="0" stop-color="#fff3c8" stop-opacity="0"/><stop offset=".5" stop-color="#fff3c8" stop-opacity=".55"/><stop offset="1" stop-color="#fff3c8" stop-opacity="0"/></linearGradient></defs>' +
    '<circle cx="1160" cy="40" r="420" fill="url(#sg)"/>' +
    '<g opacity=".5" style="mix-blend-mode:screen"><polygon points="1160,40 700,720 820,720" fill="url(#ray)"/><polygon points="1160,40 980,720 1060,720" fill="url(#ray)"/><polygon points="1160,40 1300,720 1380,720" fill="url(#ray)"/></g>' +
    '<circle cx="1010" cy="250" r="26" fill="rgba(255,220,150,.18)"/><circle cx="900" cy="410" r="14" fill="rgba(160,220,255,.2)"/><circle cx="800" cy="560" r="44" fill="rgba(255,190,120,.10)"/></svg>';
  sun.style.mixBlendMode = 'screen';
  flash.style.background = 'rgba(220,230,255,.85)'; flash.style.opacity = 0; flash.style.transition = 'opacity .08s';
  var after = cvs || document.getElementById('car');
  [grade, haze, sun, flash].reverse().forEach(function (e) { after.parentNode.insertBefore(e, after.nextSibling); });

  var LOOK = {
    //            цвет фото (backdrop-filter)                                  дымка                                                    солнце  осадки
    rain:   { f: 'none',                                                  haze: 'none',                                                       sun: 0, p: 'rain', n: 140, v: 1 },
    storm:  { f: 'brightness(.85) contrast(1.1)',                         haze: 'none',                                                       sun: 0, p: 'rain', n: 230, v: 1.5 },
    snow:   { f: 'saturate(.55) brightness(1.15) hue-rotate(-8deg)',      haze: 'linear-gradient(180deg,rgba(220,232,255,.10),rgba(235,242,255,.28))', sun: 0, p: 'snow', n: 170, v: 1 },
    cloudy: { f: 'saturate(.6) brightness(1.08) contrast(.92)',           haze: 'linear-gradient(180deg,rgba(150,160,175,.22),rgba(120,128,140,.12))', sun: 0, p: '', n: 0, v: 0 },
    fog:    { f: 'saturate(.45) brightness(1.12) contrast(.8) blur(1px)', haze: 'linear-gradient(180deg,rgba(200,208,218,.25),rgba(215,222,230,.5))', sun: 0, p: '', n: 0, v: 0 },
    clearDay:  { f: 'brightness(1.45) saturate(1.25) contrast(.95) sepia(.18)', haze: 'linear-gradient(180deg,rgba(150,200,255,.30),rgba(255,236,200,.12) 55%,rgba(255,220,170,.08))', sun: 1, p: '', n: 0, v: 0 },
    clearNight:{ f: 'contrast(1.05) saturate(1.1)',                       haze: 'none',                                                       sun: 0, p: 'stars', n: 0, v: 0 },
    cloudyDay: { f: 'saturate(.7) brightness(1.3) contrast(.9)',          haze: 'linear-gradient(180deg,rgba(190,200,215,.32),rgba(170,178,190,.14))', sun: 0, p: '', n: 0, v: 0 }
  };
  var parts = [];
  function seed(kind, n) {
    parts = [];
    for (var i = 0; i < n; i++) parts.push(kind === 'snow'
      ? { x: Math.random() * 1920, y: Math.random() * 720, r: 1 + Math.random() * 2.6, v: .6 + Math.random() * 1.4, w: Math.random() * 6.28, a: .35 + Math.random() * .5 }
      : { x: Math.random() * 1920, y: Math.random() * 720, l: 10 + Math.random() * 18, v: 9 + Math.random() * 8, a: .12 + Math.random() * .25 });
  }
  var look = LOOK.rain; seed('rain', look.n); sun.style.opacity = 0;
  function setWeather(kind, day) {
    var key = kind === 'clear' ? (day ? 'clearDay' : 'clearNight') : (kind === 'cloudy' && day ? 'cloudyDay' : kind);
    look = LOOK[key] || LOOK.rain;
    var photo = kind === 'snow' ? 'snow' : (day ? 'day' : 'night');
    if (window.setPhoto) setPhoto(photo);
    var f = look.f;
    if (photo === 'day') f = { clearDay: 'brightness(1.12) saturate(1.2) contrast(1.02) sepia(.12)', cloudyDay: 'saturate(.85)', rain: 'none', storm: 'brightness(.8) contrast(1.1)', fog: 'saturate(.6) brightness(1.08) contrast(.85)' }[key] || 'none';
    if (photo === 'snow') f = day ? 'none' : 'brightness(.5) saturate(.8) hue-rotate(15deg)';
    look = Object.assign({}, look, { f: f, haze: photo === 'day' && key === 'clearDay' ? 'linear-gradient(180deg,rgba(255,236,200,.10),rgba(255,220,170,.05))' : (photo === 'snow' ? 'none' : look.haze) });
    grade.style.backdropFilter = look.f; grade.style.webkitBackdropFilter = look.f;
    haze.style.background = look.haze;
    sun.style.opacity = look.sun;
    seed(look.p, look.n);
    document.body.setAttribute('data-wx', key);
  }
  window.setWeather = setWeather;
  // Молнии в грозу.
  setInterval(function () {
    if (document.body.getAttribute('data-wx') !== 'storm' || Math.random() > .25) return;
    flash.style.opacity = .55; setTimeout(function () { flash.style.opacity = 0; }, 90);
    setTimeout(function () { flash.style.opacity = .35; setTimeout(function () { flash.style.opacity = 0; }, 70); }, 180);
  }, 2500);

  if (cvs) {
    var ctx = cvs.getContext('2d'), last = 0;
    (function frame(t) {
      requestAnimationFrame(frame);
      if (document.hidden || t - last < 33) return;
      last = t;
      ctx.clearRect(0, 0, 1920, 720);
      var sp = rainSpeed * (look.v || 1);
      if (look.p === 'rain') {
        ctx.lineWidth = 1.2;
        for (var j = 0; j < parts.length; j++) {
          var p = parts[j];
          ctx.strokeStyle = 'rgba(210,225,255,' + p.a + ')';
          ctx.beginPath(); ctx.moveTo(p.x, p.y); ctx.lineTo(p.x - p.l * 0.25, p.y + p.l); ctx.stroke();
          p.y += p.v * sp; p.x -= p.v * 0.25 * sp;
          if (p.y > 720) { p.y = -20; p.x = Math.random() * 2100; }
        }
      } else if (look.p === 'snow') {
        for (var k = 0; k < parts.length; k++) {
          var q = parts[k];
          q.w += .03; q.y += q.v * (0.6 + rainSpeed * .5); q.x += Math.sin(q.w) * .6 - (rainSpeed - 1) * 1.5;
          if (q.y > 725) { q.y = -6; q.x = Math.random() * 2000; }
          ctx.fillStyle = 'rgba(255,255,255,' + q.a + ')';
          ctx.beginPath(); ctx.arc(q.x, q.y, q.r, 0, 6.283); ctx.fill();
        }
      }
    })(0);
  }

  // ===== Режим: COMFORT (спокойно) / SPORT (как есть) / RACE (максимум эффектов) =====
  var MODES = ['COMFORT', 'SPORT', 'RACE'], mode = 1;
  try { mode = Math.max(0, MODES.indexOf(localStorage.getItem('mode') || 'SPORT')); } catch (e) {}
  var modeTag = document.createElement('div');
  modeTag.style.cssText = 'position:absolute;left:760px;top:24px;width:400px;text-align:center;font-weight:900;font-style:italic;letter-spacing:8px;font-size:22px;color:#fff;opacity:0;transition:opacity .4s;pointer-events:none;text-shadow:0 0 12px #ff2a2a';
  document.body.appendChild(modeTag);
  function applyMode(show) {
    document.body.setAttribute('data-mode', MODES[mode]);
    if (show) { modeTag.textContent = MODES[mode]; modeTag.style.opacity = 1; setTimeout(function () { modeTag.style.opacity = 0; }, 1500); }
  }
  var ms = document.createElement('style');
  ms.textContent = 'body[data-mode=COMFORT] #trails,body[data-mode=COMFORT] #steam{opacity:0 !important}' +
    'body[data-mode=COMFORT] .tile>div:nth-child(4){opacity:.25}' +
    'body[data-mode=RACE] #trails{opacity:1 !important;filter:saturate(1.6) brightness(1.2)}' +
    'body[data-mode=RACE] #steam{opacity:.9 !important}';
  document.head.appendChild(ms);
  applyMode(false);
  window.nextMode = function () { mode = (mode + 1) % 3; try { localStorage.setItem('mode', MODES[mode]); } catch (e) {} applyMode(true); };

  // ===== Фото под погоду: ночь (дождь), день (пасмурно/дождь), снег. Машина каждый раз встаёт на то же место. =====
  var PHOTOS = {
    night: { src: 'rain.jpg', W: 2608, H: 944, box: [1174, 209, 2452, 795], wheel: [2003, 650, 80, 142], tail: [2440, 410] },
    day:   { src: 'day.jpg',  W: 1724, H: 624, box: [770, 140, 1625, 525],  wheel: [1330, 425, 50, 97],  tail: [1612, 262] },
    snow:  { src: 'snow.jpg', W: 1724, H: 624, box: [770, 145, 1520, 525],  wheel: [1236, 430, 43, 95],  tail: [1505, 250] }
  };
  var carDiv = document.getElementById('car'), carImg = carDiv ? carDiv.querySelector('img') : null;
  var bgImgs = Array.prototype.filter.call(document.querySelectorAll('img'), function (i) { return /rain\.jpg$/.test(i.getAttribute('src')) && !carDiv.contains(i); });
  var trailsEl = document.getElementById('trails'), steamEl = document.getElementById('steam');
  var NIGHT_TAIL = [1345, 330];   // где на экране задний фонарь на ночном фото
  var curPhoto = 'night';
  function setPhoto(key) {
    var P = PHOTOS[key]; if (!P || !carDiv || key === curPhoto) return;
    curPhoto = key;
    var bw = P.box[2] - P.box[0], bh = P.box[3] - P.box[1];
    var K = 690 / bw;                       // ширина машины на экране — как на ночном фото
    var L = 664 - P.box[0] * K, T = 540 - P.box[3] * K;   // левый край машины и низ колёс — на тех же местах
    var w = P.W * K, h = P.H * K;
    carDiv.style.left = L + 'px'; carDiv.style.top = T + 'px'; carDiv.style.width = w + 'px'; carDiv.style.height = h + 'px';
    var mx = (P.box[0] + bw / 2) / P.W * 100, my = (P.box[1] + bh / 2) / P.H * 100;
    var rx = bw * 0.86 / P.W * 100, ry = bh * 0.89 / P.H * 100;
    carDiv.style.webkitMaskImage = 'radial-gradient(ellipse ' + rx + '% ' + ry + '% at ' + mx + '% ' + my + '%,#000 62%,transparent 100%)';
    carImg.src = P.src; carImg.style.width = w + 'px'; carImg.style.height = h + 'px';
    bgImgs.forEach(function (i) { i.src = P.src; });
    buildWheel(P, K, w, h);
    // След фар, огонь и пар — за задним фонарём этого фото.
    var tx = L + P.tail[0] * K - NIGHT_TAIL[0] - (key === 'night' ? 0 : 0), ty = T + P.tail[1] * K - NIGHT_TAIL[1];
    if (key === 'night') { tx = 0; ty = 0; }
    [trailsEl, steamEl].forEach(function (e) { if (e) e.style.transform = 'translate(' + tx + 'px,' + ty + 'px)'; });
    if (steamEl) steamEl.style.filter = key === 'snow' ? 'brightness(1.3)' : 'brightness(1.15)';
  }
  function buildWheel(P, K, w, h) {
    if (wheel) wheel.remove();
    var CX = P.wheel[0] * K, CY = P.wheel[1] * K, RX = P.wheel[2] * K, RY = P.wheel[3] * K, S = RY / RX;
    wheel = document.createElement('div');
    wheel.style.cssText = 'position:absolute;left:' + (CX - RY) + 'px;top:' + (CY - RY) + 'px;width:' + (2 * RY) + 'px;height:' + (2 * RY) +
      'px;border-radius:50%;overflow:hidden;transform:scaleX(' + (1 / S) + ');pointer-events:none';
    var run = wheelIn ? wheelIn.style.animationPlayState : 'paused', dur = wheelIn ? wheelIn.style.animationDuration : '1s', flt = wheelIn ? wheelIn.style.filter : '';
    wheelIn = document.createElement('div');
    wheelIn.style.cssText = 'position:absolute;inset:0;background:url(' + P.src + ') no-repeat;background-size:' + (w * S) + 'px ' + h + 'px;' +
      'background-position:' + (-(CX - RX) * S) + 'px ' + (-(CY - RY)) + 'px;animation:spin 1s linear infinite';
    wheelIn.style.animationPlayState = run; wheelIn.style.animationDuration = dur; wheelIn.style.filter = flt;
    wheel.appendChild(wheelIn); carDiv.appendChild(wheel);
  }
  var shade = document.createElement('div');
  shade.style.cssText = 'position:absolute;left:0;top:0;width:1920px;height:720px;pointer-events:none;opacity:0;transition:opacity 2s;' +
    'background:linear-gradient(90deg,rgba(6,8,12,.72),rgba(6,8,12,.5) 34%,rgba(6,8,12,0) 44%,rgba(6,8,12,0) 70%,rgba(6,8,12,.55) 76%,rgba(6,8,12,.75))';
  if (cvs) cvs.parentNode.insertBefore(shade, cvs.nextSibling);
  var _set = setPhoto;
  setPhoto = function (k) { _set(k); shade.style.opacity = k === 'night' ? 0 : 1; };
  window.setPhoto = setPhoto;
})();
