const el = id => document.getElementById(id);
const screens = Array.from(document.querySelectorAll('.screen'));
const preview = el('preview');
const captionBox = el('captionBox');

let questions = [];
let clips = [];
let currentIndex = 0;
let sessionName = '';
let stream = null;
let recorder = null;
let recorderChunks = [];
let recognition = null;
let currentTranscript = '';
let segmentStartPerf = 0;
let answerStartSec = 0;
let currentFinalBlob = null;
let currentFinalUrl = null;
let ttsDoneCallback = null;
let ttsFallbackTimer = null;
let nativePermissionResolve = null;
let nativePermissionTimer = null;

let settings = {
  voiceRate: 0.92,
  subtitleSize: 40,
  showQuestionInVideo: true,
  interviewerReaction: true,
  introSeconds: 2.2
};

function showScreen(id) {
  screens.forEach(s => s.classList.add('hidden'));
  el(id).classList.remove('hidden');
  window.scrollTo(0, 0);
}

function loadSettings() {
  try {
    const saved = JSON.parse(localStorage.getItem('yishai_settings') || '{}');
    settings = Object.assign(settings, saved);
  } catch (_) {}
  el('voiceRate').value = settings.voiceRate;
  el('voiceRateValue').textContent = Number(settings.voiceRate).toFixed(2);
  el('subtitleSize').value = String(settings.subtitleSize);
  el('showQuestionInVideo').checked = !!settings.showQuestionInVideo;
  el('interviewerReaction').checked = !!settings.interviewerReaction;
  el('introSeconds').value = String(settings.introSeconds);
}

function readSettingsFromUi() {
  settings.voiceRate = Number(el('voiceRate').value);
  settings.subtitleSize = Number(el('subtitleSize').value);
  settings.showQuestionInVideo = el('showQuestionInVideo').checked;
  settings.interviewerReaction = el('interviewerReaction').checked;
  settings.introSeconds = Number(el('introSeconds').value);
}

function saveSettings() {
  readSettingsFromUi();
  localStorage.setItem('yishai_settings', JSON.stringify(settings));
}

function goHome() {
  stopCamera();
  showScreen('homeScreen');
}

document.querySelectorAll('.backHome').forEach(b => b.addEventListener('click', goHome));
el('homeStartBtn').onclick = () => showScreen('setupScreen');
el('homeSettingsBtn').onclick = () => { loadSettings(); showScreen('settingsScreen'); };
el('homeVideosBtn').onclick = async () => { showScreen('videosScreen'); await renderSavedVideos(); };
el('backToSetupBtn').onclick = () => showScreen('setupScreen');

el('voiceRate').oninput = () => {
  el('voiceRateValue').textContent = Number(el('voiceRate').value).toFixed(2);
};
el('saveSettingsBtn').onclick = () => {
  saveSettings();
  goHome();
};
el('testVoiceBtn').onclick = () => {
  readSettingsFromUi();
  speak('שלום ישי, זאת בדיקת הקול של המראיין. אם שומעים אותי טוב, אפשר להתחיל.', null);
};

function buildQuestions(parasha, count) {
  const p = parasha.replace(/^פרשת\s+/, '');
  const pool = [
    'ישי, קודם כל, אתה יכול לספר לנו בקצרה מה קורה בפרשת ' + p + '?',
    'מה לדעתך האירוע הכי מעניין או הכי חשוב בפרשת ' + p + '?',
    'איזו דמות בפרשת ' + p + ' הכי מעניינת אותך, ולמה?',
    'למה לדעתך התורה מספרת לנו את הסיפור המרכזי של פרשת ' + p + '?',
    'מה אנחנו יכולים ללמוד מפרשת ' + p + ' לחיים שלנו היום?',
    'אם היית צריך לבחור דבר אחד מפרשת ' + p + ' שאתה רוצה לזכור השבוע, מה היית בוחר?'
  ];
  return pool.slice(0, count);
}

function renderQuestionEditors() {
  const wrap = el('questionsEditor');
  wrap.innerHTML = '';
  questions.forEach((q, i) => {
    const box = document.createElement('div');
    box.className = 'question-edit';

    const head = document.createElement('div');
    head.className = 'q-head';
    const title = document.createElement('strong');
    title.textContent = 'שאלה ' + (i + 1);
    const remove = document.createElement('button');
    remove.className = 'remove-q';
    remove.textContent = 'מחק';
    remove.onclick = () => {
      if (questions.length <= 1) return;
      questions.splice(i, 1);
      renderQuestionEditors();
    };
    head.appendChild(title);
    head.appendChild(remove);

    const ta = document.createElement('textarea');
    ta.value = q;
    ta.oninput = () => { questions[i] = ta.value; };

    box.appendChild(head);
    box.appendChild(ta);
    wrap.appendChild(box);
  });
}

el('prepareQuestionsBtn').onclick = () => {
  const p = el('parasha').value.trim();
  if (!p) return alert('כתוב קודם את שם הפרשה.');
  sessionName = p.startsWith('פרשת') ? p : 'פרשת ' + p;
  questions = buildQuestions(sessionName, Number(el('questionCount').value));
  renderQuestionEditors();
  showScreen('questionsScreen');
};

el('addQuestionBtn').onclick = () => {
  questions.push('ישי, יש עוד משהו מהפרשה שהיית רוצה לספר לנו?');
  renderQuestionEditors();
};

el('detectParashaBtn').onclick = async () => {
  const btn = el('detectParashaBtn');
  const old = btn.textContent;
  btn.disabled = true;
  btn.textContent = 'בודק…';
  try {
    const res = await fetch('https://www.hebcal.com/shabbat?cfg=json&geonameid=281184&lg=he');
    if (!res.ok) throw new Error('network');
    const data = await res.json();
    const item = (data.items || []).find(x => x.category === 'parashat');
    if (item && item.title) {
      el('parasha').value = item.title.replace(/^פרשת\s*/, 'פרשת ');
    } else {
      alert('לא מצאתי פרשה אוטומטית לשבוע הזה. אפשר לרשום ידנית.');
    }
  } catch (_) {
    alert('לא הצלחתי לזהות את הפרשה כרגע. אפשר לרשום אותה ידנית.');
  } finally {
    btn.disabled = false;
    btn.textContent = old;
  }
};

function speak(text, done) {
  ttsDoneCallback = typeof done === 'function' ? done : null;
  if (ttsFallbackTimer) {
    clearTimeout(ttsFallbackTimer);
    ttsFallbackTimer = null;
  }

  if (window.Android && typeof window.Android.speak === 'function') {
    try {
      window.Android.speak(text, Number(settings.voiceRate));
      if (ttsDoneCallback) {
        const ms = Math.max(1600, Math.min(9500, 500 + text.length * 72 / Math.max(0.7, settings.voiceRate)));
        ttsFallbackTimer = setTimeout(() => finishTtsCallback(), ms);
      }
      return;
    } catch (_) {}
  }

  if ('speechSynthesis' in window) {
    speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(text);
    u.lang = 'he-IL';
    u.rate = Number(settings.voiceRate);
    u.pitch = 0.95;
    const voices = speechSynthesis.getVoices();
    const he = voices.find(v => String(v.lang || '').toLowerCase().startsWith('he'));
    if (he) u.voice = he;
    u.onend = () => finishTtsCallback();
    speechSynthesis.speak(u);
    return;
  }

  if (ttsDoneCallback) setTimeout(() => finishTtsCallback(), 500);
}

function finishTtsCallback() {
  if (ttsFallbackTimer) {
    clearTimeout(ttsFallbackTimer);
    ttsFallbackTimer = null;
  }
  const cb = ttsDoneCallback;
  ttsDoneCallback = null;
  if (cb) cb();
}

window.onInterviewerDone = function() {
  finishTtsCallback();
};

function hasNativeMediaPermissions() {
  try {
    if (window.Android && typeof window.Android.hasMediaPermissions === 'function') {
      return !!window.Android.hasMediaPermissions();
    }
  } catch (_) {}
  return true;
}

function requestNativeMediaPermissions() {
  return new Promise(resolve => {
    if (!window.Android || typeof window.Android.requestMediaPermissions !== 'function') {
      resolve(true);
      return;
    }

    if (hasNativeMediaPermissions()) {
      resolve(true);
      return;
    }

    nativePermissionResolve = resolve;
    if (nativePermissionTimer) clearTimeout(nativePermissionTimer);
    nativePermissionTimer = setTimeout(() => {
      if (nativePermissionResolve) {
        const cb = nativePermissionResolve;
        nativePermissionResolve = null;
        cb(false);
      }
    }, 15000);

    try {
      window.Android.requestMediaPermissions();
    } catch (_) {
      if (nativePermissionTimer) clearTimeout(nativePermissionTimer);
      nativePermissionTimer = null;
      nativePermissionResolve = null;
      resolve(false);
    }
  });
}

window.onNativePermissionsResult = function(granted) {
  if (nativePermissionTimer) clearTimeout(nativePermissionTimer);
  nativePermissionTimer = null;
  const cb = nativePermissionResolve;
  nativePermissionResolve = null;
  if (cb) cb(!!granted);
};

async function ensureCamera() {
  if (stream && stream.active) return;

  const nativeOk = await requestNativeMediaPermissions();
  if (!nativeOk) {
    const err = new Error('permission-denied');
    err.name = 'NotAllowedError';
    throw err;
  }

  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    throw new Error('camera-api');
  }

  const attempts = [
    {
      video: {
        facingMode: { ideal: 'user' },
        width: { ideal: 1080 },
        height: { ideal: 1920 }
      },
      audio: true
    },
    {
      video: { facingMode: 'user' },
      audio: true
    },
    {
      video: true,
      audio: true
    }
  ];

  let lastError = null;
  for (const constraints of attempts) {
    try {
      stream = await navigator.mediaDevices.getUserMedia(constraints);
      if (stream && stream.active) break;
    } catch (e) {
      lastError = e;
      stream = null;
    }
  }

  if (!stream || !stream.active) {
    throw lastError || new Error('camera-open-failed');
  }

  preview.srcObject = stream;
  await preview.play().catch(() => {});
}

function stopCamera() {
  if (stream) {
    stream.getTracks().forEach(t => t.stop());
    stream = null;
  }
  if (preview) preview.srcObject = null;
}

function setupRecognition() {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SR) return null;
  const r = new SR();
  r.lang = 'he-IL';
  r.interimResults = true;
  r.continuous = true;
  r.onresult = ev => {
    let interim = '';
    let finalText = '';
    for (let i = ev.resultIndex; i < ev.results.length; i++) {
      const text = ev.results[i][0].transcript.trim();
      if (ev.results[i].isFinal) finalText += text + ' ';
      else interim += text + ' ';
    }
    currentTranscript += finalText;
    const visible = (currentTranscript + interim).trim();
    captionBox.textContent = visible || 'מקשיב…';
  };
  r.onerror = () => {};
  return r;
}

function startRecognitionNow() {
  answerStartSec = Math.max(0, (performance.now() - segmentStartPerf) / 1000);
  el('recordState').textContent = 'ישי עונה';
  el('questionOverlay').style.opacity = '0.28';
  captionBox.textContent = 'דבר עכשיו…';
  recognition = setupRecognition();
  try { if (recognition) recognition.start(); } catch (_) {}
}

function showCurrentQuestion() {
  const q = questions[currentIndex];
  el('progressText').textContent = 'שאלה ' + (currentIndex + 1) + ' מתוך ' + questions.length;
  el('questionText').textContent = q;
  el('questionOverlay').textContent = q;
  el('questionOverlay').style.opacity = '1';
  captionBox.textContent = 'הכתוביות יופיעו כאן בזמן הדיבור';
  el('recordState').textContent = clips[currentIndex] ? 'מצולם' : 'מוכן';
  el('startRecordBtn').disabled = !!clips[currentIndex];
  el('stopRecordBtn').disabled = true;
  el('retakeBtn').disabled = !clips[currentIndex];
  el('nextBtn').disabled = !clips[currentIndex];
  currentTranscript = clips[currentIndex] ? (clips[currentIndex].transcript || '') : '';
}

el('startInterviewBtn').onclick = async () => {
  questions = questions.map(q => q.trim()).filter(Boolean);
  if (!questions.length) return alert('צריך לפחות שאלה אחת.');
  sessionName = sessionName || el('parasha').value.trim();
  clips = [];
  currentIndex = 0;
  try {
    await ensureCamera();
  } catch (e) {
    const code = e && (e.name || e.message) ? String(e.name || e.message) : 'unknown';
    if (code.includes('NotAllowed') || code.includes('permission')) {
      alert('האפליקציה עדיין לא קיבלה הרשאה למצלמה ולמיקרופון. לחץ שוב על התחלת הראיון ואשר את שתי ההרשאות של Android.');
    } else {
      alert('לא הצלחתי לפתוח את המצלמה והמיקרופון (' + code + '). אני מנסה הגדרות מצלמה פשוטות אוטומטית; אם ההודעה חוזרת, שלח לי צילום שלה.');
    }
    return;
  }
  showScreen('interviewScreen');
  showCurrentQuestion();
};

el('speakBtn').onclick = () => speak(questions[currentIndex], null);

el('startRecordBtn').onclick = () => {
  if (!stream || !stream.active) return alert('המצלמה לא פעילה.');
  recorderChunks = [];
  currentTranscript = '';
  answerStartSec = 0;

  const preferred = [
    'video/webm;codecs=vp8,opus',
    'video/webm;codecs=vp9,opus',
    'video/webm'
  ].find(t => window.MediaRecorder && MediaRecorder.isTypeSupported(t)) || '';

  try {
    recorder = preferred ? new MediaRecorder(stream, { mimeType: preferred }) : new MediaRecorder(stream);
  } catch (e) {
    alert('המכשיר הזה לא הצליח להתחיל הקלטת וידאו.');
    return;
  }

  recorder.ondataavailable = e => {
    if (e.data && e.data.size) recorderChunks.push(e.data);
  };

  recorder.onstop = () => {
    const type = recorder.mimeType || 'video/webm';
    const blob = new Blob(recorderChunks, { type: type });
    if (clips[currentIndex] && clips[currentIndex].url) {
      try { URL.revokeObjectURL(clips[currentIndex].url); } catch (_) {}
    }
    clips[currentIndex] = {
      question: questions[currentIndex],
      transcript: currentTranscript.trim(),
      blob: blob,
      url: URL.createObjectURL(blob),
      type: type,
      answerStartSec: answerStartSec || 2.5
    };
    el('recordState').textContent = 'התשובה נשמרה';
    el('startRecordBtn').disabled = true;
    el('retakeBtn').disabled = false;
    el('nextBtn').disabled = false;
  };

  recorder.start(250);
  segmentStartPerf = performance.now();
  el('recordState').textContent = 'המראיין שואל';
  el('startRecordBtn').disabled = true;
  el('stopRecordBtn').disabled = false;
  el('retakeBtn').disabled = true;
  el('nextBtn').disabled = true;
  captionBox.textContent = '';

  const reaction = settings.interviewerReaction && currentIndex > 0 ? 'יפה מאוד ישי. ' : '';
  const spoken = reaction + questions[currentIndex];
  speak(spoken, startRecognitionNow);
};

el('stopRecordBtn').onclick = () => {
  try { if (recognition) recognition.stop(); } catch (_) {}
  recognition = null;
  finishTtsCallback();
  if (recorder && recorder.state !== 'inactive') recorder.stop();
  el('stopRecordBtn').disabled = true;
};

el('retakeBtn').onclick = () => {
  const old = clips[currentIndex];
  if (old && old.url) {
    try { URL.revokeObjectURL(old.url); } catch (_) {}
  }
  clips[currentIndex] = null;
  currentTranscript = '';
  showCurrentQuestion();
};

el('nextBtn').onclick = () => {
  if (!clips[currentIndex]) return;
  if (currentIndex < questions.length - 1) {
    currentIndex++;
    showCurrentQuestion();
  } else {
    finishInterview();
  }
};

el('quitInterviewBtn').onclick = () => {
  if (confirm('לצאת מהראיון? ההקלטות של התוכנית הנוכחית לא יישמרו.')) {
    try { if (recorder && recorder.state !== 'inactive') recorder.stop(); } catch (_) {}
    try { if (recognition) recognition.stop(); } catch (_) {}
    stopCamera();
    goHome();
  }
};

function finishInterview() {
  stopCamera();
  showScreen('finishScreen');
  renderClips();
  speak('תודה רבה ישי. היה מעניין מאוד לשמוע אותך. שבת שלום לכולם.', null);
}

function renderClips() {
  const wrap = el('clipsList');
  wrap.innerHTML = '';
  clips.forEach((clip, i) => {
    if (!clip) return;
    const div = document.createElement('div');
    div.className = 'clip';

    const head = document.createElement('div');
    head.className = 'clip-head';
    const strong = document.createElement('strong');
    strong.textContent = 'תשובה ' + (i + 1);
    const q = document.createElement('span');
    q.className = 'small';
    q.textContent = 'שאלה ' + (i + 1);
    head.appendChild(strong);
    head.appendChild(q);

    const vid = document.createElement('video');
    vid.controls = true;
    vid.playsInline = true;
    vid.src = clip.url;

    const label = document.createElement('label');
    label.textContent = 'כתוביות לתשובה';

    const ta = document.createElement('textarea');
    ta.value = clip.transcript || '';
    ta.placeholder = 'אם לא נוצרו כתוביות אוטומטיות, אפשר להקליד כאן את מה שנאמר.';
    ta.oninput = () => { clip.transcript = ta.value.trim(); };

    const mini = document.createElement('div');
    mini.className = 'mini-actions';
    const listen = document.createElement('button');
    listen.textContent = '🔊 השאלה';
    listen.onclick = () => speak(clip.question, null);
    const remove = document.createElement('button');
    remove.textContent = '↻ חזור לצילום';
    remove.onclick = async () => {
      currentIndex = i;
      try { await ensureCamera(); } catch (_) {
        alert('לא הצלחתי לפתוח את המצלמה.');
        return;
      }
      showScreen('interviewScreen');
      showCurrentQuestion();
    };
    mini.appendChild(listen);
    mini.appendChild(remove);

    div.appendChild(head);
    div.appendChild(vid);
    div.appendChild(label);
    div.appendChild(ta);
    div.appendChild(mini);
    wrap.appendChild(div);
  });
}

function wait(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function roundedRect(ctx, x, y, w, h, r, fillStyle) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
  ctx.fillStyle = fillStyle;
  ctx.fill();
}

function wrapText(ctx, text, maxWidth) {
  const words = String(text || '').trim().split(/\s+/).filter(Boolean);
  const lines = [];
  let line = '';
  words.forEach(word => {
    const test = line ? line + ' ' + word : word;
    if (ctx.measureText(test).width > maxWidth && line) {
      lines.push(line);
      line = word;
    } else {
      line = test;
    }
  });
  if (line) lines.push(line);
  return lines;
}

function drawCenteredLines(ctx, lines, centerY, lineHeight) {
  const startY = centerY - ((lines.length - 1) * lineHeight) / 2;
  lines.forEach((line, i) => ctx.fillText(line, 360, startY + i * lineHeight));
}

function drawTitleCard(ctx, subtitle, footer) {
  const grad = ctx.createLinearGradient(0, 0, 720, 1280);
  grad.addColorStop(0, '#091426');
  grad.addColorStop(1, '#1d1a55');
  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, 720, 1280);

  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = '#ffffff';
  ctx.font = '900 60px sans-serif';
  drawCenteredLines(ctx, wrapText(ctx, 'פשוט פרשה עם ישי', 620), 500, 74);

  ctx.fillStyle = '#c7d2fe';
  ctx.font = '800 40px sans-serif';
  drawCenteredLines(ctx, wrapText(ctx, subtitle, 610), 650, 52);

  if (footer) {
    ctx.fillStyle = '#e2e8f0';
    ctx.font = '700 31px sans-serif';
    drawCenteredLines(ctx, wrapText(ctx, footer, 590), 805, 44);
  }
}

function drawVideoCover(ctx, video) {
  const cw = 720, ch = 1280;
  const vw = video.videoWidth || 720;
  const vh = video.videoHeight || 1280;
  const scale = Math.max(cw / vw, ch / vh);
  const w = vw * scale;
  const h = vh * scale;
  const x = (cw - w) / 2;
  const y = (ch - h) / 2;
  ctx.drawImage(video, x, y, w, h);
}

function captionChunk(text, progress) {
  const words = String(text || '').trim().split(/\s+/).filter(Boolean);
  if (!words.length) return '';
  const perChunk = 7;
  const chunks = [];
  for (let i = 0; i < words.length; i += perChunk) {
    chunks.push(words.slice(i, i + perChunk).join(' '));
  }
  const idx = Math.min(chunks.length - 1, Math.floor(Math.max(0, Math.min(0.999, progress)) * chunks.length));
  return chunks[idx] || '';
}

function drawOverlay(ctx, clip, video) {
  const t = video.currentTime || 0;
  const duration = Math.max(video.duration || 1, 1);
  const answerAt = Math.min(Math.max(clip.answerStartSec || 2.5, 0.8), Math.max(0.8, duration - 0.5));

  if (settings.showQuestionInVideo && t < answerAt) {
    roundedRect(ctx, 45, 55, 630, 170, 26, 'rgba(0,0,0,0.64)');
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = '#ffffff';
    ctx.font = '900 34px sans-serif';
    drawCenteredLines(ctx, wrapText(ctx, clip.question, 565).slice(0, 3), 140, 42);
  }

  if (t >= answerAt && clip.transcript) {
    const p = (t - answerAt) / Math.max(0.5, duration - answerAt);
    const text = captionChunk(clip.transcript, p);
    if (text) {
      ctx.font = '950 ' + settings.subtitleSize + 'px sans-serif';
      const lines = wrapText(ctx, text, 620).slice(0, 2);
      const boxH = Math.max(100, 34 + lines.length * (settings.subtitleSize + 12));
      roundedRect(ctx, 38, 1280 - boxH - 70, 644, boxH, 22, 'rgba(0,0,0,0.58)');
      ctx.fillStyle = '#ffffff';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.shadowColor = '#000000';
      ctx.shadowBlur = 7;
      drawCenteredLines(ctx, lines, 1280 - boxH / 2 - 70, settings.subtitleSize + 12);
      ctx.shadowBlur = 0;
    }
  }

  ctx.fillStyle = 'rgba(0,0,0,0.50)';
  roundedRect(ctx, 26, 1190, 250, 56, 18, 'rgba(0,0,0,0.50)');
  ctx.fillStyle = '#ffffff';
  ctx.font = '800 24px sans-serif';
  ctx.textAlign = 'center';
  ctx.fillText('פשוט פרשה עם ישי', 151, 1218);
}

async function renderCardFor(ctx, ms, subtitle, footer) {
  const start = performance.now();
  return new Promise(resolve => {
    function frame(now) {
      drawTitleCard(ctx, subtitle, footer);
      if (now - start < ms) requestAnimationFrame(frame);
      else resolve();
    }
    requestAnimationFrame(frame);
  });
}

async function renderClipToCanvas(ctx, clip, audioCtx, audioDest) {
  return new Promise(async (resolve, reject) => {
    const video = document.createElement('video');
    video.playsInline = true;
    video.preload = 'auto';
    video.src = clip.url;

    try {
      await new Promise((res, rej) => {
        video.onloadedmetadata = res;
        video.onerror = () => rej(new Error('video-load'));
      });

      const source = audioCtx.createMediaElementSource(video);
      source.connect(audioDest);

      let ended = false;
      video.onended = () => { ended = true; };

      await video.play();

      function draw() {
        if (ended || video.ended) {
          resolve();
          return;
        }
        drawVideoCover(ctx, video);
        drawOverlay(ctx, clip, video);
        requestAnimationFrame(draw);
      }
      requestAnimationFrame(draw);
    } catch (e) {
      reject(e);
    }
  });
}

async function autoEdit() {
  if (!clips.length || clips.some(c => !c)) return alert('חסרה לפחות תשובה אחת.');
  if (!HTMLCanvasElement.prototype.captureStream || !window.MediaRecorder) {
    return alert('המכשיר הזה לא תומך כרגע בעריכה המקומית.');
  }

  showScreen('editingScreen');
  el('editProgress').style.width = '4%';
  el('editStatus').textContent = 'מכין פתיח';

  try {
    const canvas = document.createElement('canvas');
    canvas.width = 720;
    canvas.height = 1280;
    const ctx = canvas.getContext('2d', { alpha: false });

    const canvasStream = canvas.captureStream(30);
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) throw new Error('audio-context');
    const audioCtx = new AC();
    await audioCtx.resume();
    const audioDest = audioCtx.createMediaStreamDestination();

    const finalStream = new MediaStream();
    canvasStream.getVideoTracks().forEach(t => finalStream.addTrack(t));
    audioDest.stream.getAudioTracks().forEach(t => finalStream.addTrack(t));

    const outType = [
      'video/webm;codecs=vp8,opus',
      'video/webm;codecs=vp9,opus',
      'video/webm'
    ].find(t => MediaRecorder.isTypeSupported(t)) || '';

    const parts = [];
    const outRecorder = outType ? new MediaRecorder(finalStream, { mimeType: outType, videoBitsPerSecond: 5000000 }) : new MediaRecorder(finalStream);
    outRecorder.ondataavailable = e => { if (e.data && e.data.size) parts.push(e.data); };
    const stopped = new Promise(resolve => outRecorder.onstop = resolve);
    outRecorder.start(500);

    await renderCardFor(ctx, settings.introSeconds * 1000, sessionName, 'שאלות ותשובות על פרשת השבוע');

    for (let i = 0; i < clips.length; i++) {
      el('editStatus').textContent = 'עורך תשובה ' + (i + 1) + ' מתוך ' + clips.length;
      el('editProgress').style.width = String(12 + Math.round((i / clips.length) * 74)) + '%';
      await renderClipToCanvas(ctx, clips[i], audioCtx, audioDest);
      await wait(120);
    }

    el('editStatus').textContent = 'מוסיף סיום';
    el('editProgress').style.width = '92%';
    await renderCardFor(ctx, 1800, 'שבת שלום!', 'נתראה בפרשה הבאה');

    outRecorder.stop();
    await stopped;
    canvasStream.getTracks().forEach(t => t.stop());
    await audioCtx.close();

    currentFinalBlob = new Blob(parts, { type: outRecorder.mimeType || 'video/webm' });
    if (currentFinalUrl) {
      try { URL.revokeObjectURL(currentFinalUrl); } catch (_) {}
    }
    currentFinalUrl = URL.createObjectURL(currentFinalBlob);
    el('finalVideo').src = currentFinalUrl;
    el('editProgress').style.width = '100%';
    await wait(250);
    showScreen('resultScreen');
  } catch (e) {
    console.error(e);
    showScreen('finishScreen');
    alert('העריכה האוטומטית נעצרה במכשיר הזה. ההקלטות נשמרו במסך הזה ואפשר לנסות שוב.');
  }
}

el('autoEditBtn').onclick = autoEdit;

el('downloadFinalBtn').onclick = () => {
  if (!currentFinalBlob) return;
  const a = document.createElement('a');
  a.href = currentFinalUrl || URL.createObjectURL(currentFinalBlob);
  const clean = (sessionName || 'parasha').replace(/[^א-תA-Za-z0-9_-]+/g, '_');
  a.download = 'Yishai_' + clean + '.webm';
  document.body.appendChild(a);
  a.click();
  a.remove();
};

el('saveFinalBtn').onclick = async () => {
  if (!currentFinalBlob) return;
  try {
    await saveVideoToDb({
      id: 'vid_' + Date.now(),
      title: sessionName || 'פרשת השבוע',
      createdAt: new Date().toISOString(),
      blob: currentFinalBlob
    });
    el('saveFinalBtn').textContent = '✅ נשמר בסרטונים שלי';
    el('saveFinalBtn').disabled = true;
  } catch (_) {
    alert('לא הצלחתי לשמור את הסרטון בתוך האפליקציה.');
  }
};

el('newProgramBtn').onclick = () => {
  clips.forEach(c => { if (c && c.url) try { URL.revokeObjectURL(c.url); } catch (_) {} });
  clips = [];
  questions = [];
  currentIndex = 0;
  currentTranscript = '';
  currentFinalBlob = null;
  if (currentFinalUrl) {
    try { URL.revokeObjectURL(currentFinalUrl); } catch (_) {}
    currentFinalUrl = null;
  }
  el('parasha').value = '';
  showScreen('setupScreen');
};

function openDb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('yishai_parasha_db', 1);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains('videos')) {
        db.createObjectStore('videos', { keyPath: 'id' });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function saveVideoToDb(item) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction('videos', 'readwrite');
    tx.objectStore('videos').put(item);
    tx.oncomplete = () => { db.close(); resolve(); };
    tx.onerror = () => { db.close(); reject(tx.error); };
  });
}

async function listVideosFromDb() {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction('videos', 'readonly');
    const req = tx.objectStore('videos').getAll();
    req.onsuccess = () => {
      const out = req.result || [];
      db.close();
      resolve(out.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))));
    };
    req.onerror = () => { db.close(); reject(req.error); };
  });
}

async function deleteVideoFromDb(id) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction('videos', 'readwrite');
    tx.objectStore('videos').delete(id);
    tx.oncomplete = () => { db.close(); resolve(); };
    tx.onerror = () => { db.close(); reject(tx.error); };
  });
}

async function renderSavedVideos() {
  const wrap = el('savedVideosList');
  wrap.innerHTML = '<p class="small">טוען סרטונים…</p>';
  try {
    const items = await listVideosFromDb();
    wrap.innerHTML = '';
    if (!items.length) {
      wrap.innerHTML = '<div class="notice">עדיין אין סרטונים שמורים. אחרי עריכה אוטומטית לחץ “שמור בסרטונים שלי”.</div>';
      return;
    }

    items.forEach(item => {
      const div = document.createElement('div');
      div.className = 'saved-item';
      const title = document.createElement('strong');
      title.textContent = item.title;
      const meta = document.createElement('div');
      meta.className = 'saved-meta';
      meta.textContent = new Date(item.createdAt).toLocaleString('he-IL');
      const vid = document.createElement('video');
      vid.controls = true;
      vid.playsInline = true;
      const url = URL.createObjectURL(item.blob);
      vid.src = url;

      const actions = document.createElement('div');
      actions.className = 'actions two';
      const dl = document.createElement('button');
      dl.textContent = '⬇️ הורד';
      dl.onclick = () => {
        const a = document.createElement('a');
        a.href = url;
        a.download = 'Yishai_video.webm';
        a.click();
      };
      const del = document.createElement('button');
      del.textContent = '🗑️ מחק';
      del.onclick = async () => {
        if (!confirm('למחוק את הסרטון הזה?')) return;
        await deleteVideoFromDb(item.id);
        URL.revokeObjectURL(url);
        await renderSavedVideos();
      };
      actions.appendChild(dl);
      actions.appendChild(del);

      div.appendChild(title);
      div.appendChild(meta);
      div.appendChild(vid);
      div.appendChild(actions);
      wrap.appendChild(div);
    });
  } catch (_) {
    wrap.innerHTML = '<div class="notice">לא הצלחתי לפתוח את מאגר הסרטונים במכשיר הזה.</div>';
  }
}

loadSettings();
showScreen('homeScreen');
