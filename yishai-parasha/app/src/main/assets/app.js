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
let pendingTranscriptions = 0;

let settings = {
  voiceStyle: 'male',
  voiceRate: 0.92,
  subtitleSize: 40,
  showQuestionInVideo: true,
  interviewerReaction: true,
  introSeconds: 2.2,
  captionEffect: 'pop',
  aiCaptions: true,
  translateToHebrew: true,
  openaiApiKey: '',
  backgroundMusic: true,
  musicVolume: 0.06,
  reelEffects: true
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
  if (el('voiceStyle')) el('voiceStyle').value = settings.voiceStyle || 'male';
  el('voiceRate').value = settings.voiceRate;
  el('voiceRateValue').textContent = Number(settings.voiceRate).toFixed(2);
  el('subtitleSize').value = String(settings.subtitleSize);
  el('showQuestionInVideo').checked = !!settings.showQuestionInVideo;
  el('interviewerReaction').checked = !!settings.interviewerReaction;
  el('introSeconds').value = String(settings.introSeconds);
  if (el('captionEffect')) el('captionEffect').value = settings.captionEffect || 'pop';
  if (el('aiCaptions')) el('aiCaptions').checked = settings.aiCaptions !== false;
  if (el('translateToHebrew')) el('translateToHebrew').checked = settings.translateToHebrew !== false;
  if (el('openaiApiKey')) el('openaiApiKey').value = settings.openaiApiKey || '';
  if (el('backgroundMusic')) el('backgroundMusic').checked = settings.backgroundMusic !== false;
  if (el('musicVolume')) el('musicVolume').value = String(settings.musicVolume ?? 0.06);
  if (el('musicVolumeValue')) el('musicVolumeValue').textContent = Number(settings.musicVolume ?? 0.06).toFixed(2);
  if (el('reelEffects')) el('reelEffects').checked = settings.reelEffects !== false;
}

function readSettingsFromUi() {
  if (el('voiceStyle')) settings.voiceStyle = el('voiceStyle').value || 'male';
  settings.voiceRate = Number(el('voiceRate').value);
  settings.subtitleSize = Number(el('subtitleSize').value);
  settings.showQuestionInVideo = el('showQuestionInVideo').checked;
  settings.interviewerReaction = el('interviewerReaction').checked;
  settings.introSeconds = Number(el('introSeconds').value);
  if (el('captionEffect')) settings.captionEffect = el('captionEffect').value || 'pop';
  if (el('aiCaptions')) settings.aiCaptions = !!el('aiCaptions').checked;
  if (el('translateToHebrew')) settings.translateToHebrew = !!el('translateToHebrew').checked;
  if (el('openaiApiKey')) settings.openaiApiKey = el('openaiApiKey').value.trim();
  if (el('backgroundMusic')) settings.backgroundMusic = !!el('backgroundMusic').checked;
  if (el('musicVolume')) settings.musicVolume = Number(el('musicVolume').value);
  if (el('reelEffects')) settings.reelEffects = !!el('reelEffects').checked;
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
if (el('musicVolume')) {
  el('musicVolume').oninput = () => {
    el('musicVolumeValue').textContent = Number(el('musicVolume').value).toFixed(2);
  };
}
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
      window.Android.speak(
        text,
        Number(settings.voiceRate),
        settings.voiceStyle === 'male' ? 0.78 : 0.95
      );
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

  const nativeMode = window.Android && typeof window.Android.startNativeRecording === 'function';
  if (nativeMode) {
    const ok = await requestNativeMediaPermissions();
    if (!ok) {
      alert('צריך לאשר מצלמה ומיקרופון כדי להתחיל.');
      return;
    }
    showScreen('interviewScreen');
    showCurrentQuestion();
    captionBox.textContent = 'המצלמה תיפתח במסך מלא כשתלחץ על התחל צילום';
    return;
  }

  try {
    await ensureCamera();
  } catch (e) {
    const code = e && (e.name || e.message) ? String(e.name || e.message) : 'unknown';
    alert('לא הצלחתי לפתוח את המצלמה (' + code + ').');
    return;
  }
  showScreen('interviewScreen');
  showCurrentQuestion();
};

el('speakBtn').onclick = () => speak(questions[currentIndex], null);

el('startRecordBtn').onclick = () => {
  if (window.Android && typeof window.Android.startNativeRecording === 'function') {
    currentTranscript = '';
    el('recordState').textContent = 'פותח מצלמה…';
    el('startRecordBtn').disabled = true;
    el('retakeBtn').disabled = true;
    el('nextBtn').disabled = true;
    captionBox.textContent = 'עובר למצלמת Android…';
    try {
      window.Android.startNativeRecording(
        currentIndex,
        questions[currentIndex],
        Number(settings.voiceRate),
        settings.voiceStyle === 'male' ? 0.78 : 0.95,
        !!(settings.interviewerReaction && currentIndex > 0)
      );
    } catch (_) {
      el('recordState').textContent = 'מוכן';
      el('startRecordBtn').disabled = false;
      alert('לא הצלחתי לפתוח את מסך הצילום.');
    }
    return;
  }

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


window.onNativeClipRecorded = function(index, url, answerStartSecValue, transcriptText) {
  if (index < 0 || index >= questions.length) return;
  const old = clips[index];
  if (old && old.native && old.url && window.Android && typeof window.Android.deleteRecording === 'function') {
    try { window.Android.deleteRecording(old.url); } catch (_) {}
  }
  clips[index] = {
    question: questions[index],
    transcript: String(transcriptText || '').trim(),
    blob: null,
    url: url,
    type: 'video/mp4',
    native: true,
    answerStartSec: Number(answerStartSecValue) || 2.5
  };
  currentIndex = index;
  el('recordState').textContent = 'התשובה נשמרה';
  el('startRecordBtn').disabled = true;
  el('stopRecordBtn').disabled = true;
  el('retakeBtn').disabled = false;
  el('nextBtn').disabled = false;
  captionBox.textContent = clips[index].transcript
    ? 'כתוביות ראשוניות: ' + clips[index].transcript
    : 'הצילום נשמר.';

  if (settings.aiCaptions && window.Android) {
    pendingTranscriptions++;
    clips[index].transcribing = true;

    const wantsCloudTranslation = !!(
      settings.translateToHebrew &&
      settings.openaiApiKey &&
      typeof window.Android.transcribeClip === 'function'
    );

    try {
      if (wantsCloudTranslation) {
        el('recordState').textContent = 'מתמלל ומתרגם לעברית…';
        captionBox.textContent = 'מזהה את שפת הדיבור ומתרגם לעברית…';
        window.Android.transcribeClip(
          index,
          url,
          settings.openaiApiKey,
          sessionName + '. ' + questions[index],
          true
        );
      } else if (typeof window.Android.transcribeLocalClip === 'function') {
        el('recordState').textContent = 'מכין כתוביות בעברית…';
        captionBox.textContent = 'מכין כתוביות בעברית. בפעם הראשונה תיתכן הורדה של מנוע הכתוביות…';
        window.Android.transcribeLocalClip(
          index,
          url,
          Number(clips[index].answerStartSec || 0)
        );
      } else {
        clips[index].transcribing = false;
        pendingTranscriptions = Math.max(0, pendingTranscriptions - 1);
      }
    } catch (_) {
      clips[index].transcribing = false;
      pendingTranscriptions = Math.max(0, pendingTranscriptions - 1);
    }
  }
};

window.onCloudTranscript = function(index, text) {
  if (index < 0 || index >= clips.length || !clips[index]) return;
  clips[index].transcribing = false;
  clips[index].transcript = String(text || '').trim();
  clips[index].segments = null;
  pendingTranscriptions = Math.max(0, pendingTranscriptions - 1);
  if (currentIndex === index) {
    el('recordState').textContent = settings.translateToHebrew ? 'תרגום לעברית מוכן' : 'כתוביות AI מוכנות';
    captionBox.textContent = clips[index].transcript
      ? (settings.translateToHebrew ? 'כתוביות בעברית: ' : 'כתוביות AI: ') + clips[index].transcript
      : 'לא זוהה טקסט. אפשר לתקן ידנית בסוף.';
  }
};


window.onLocalCaptionProgress = function(index, percent, status) {
  if (index < 0 || index >= clips.length || !clips[index]) return;
  if (currentIndex === index) {
    el('recordState').textContent = String(percent || 0) + '%';
    captionBox.textContent = status || 'מכין כתוביות בעברית…';
  }
};

window.onLocalTranscript = function(index, text, segmentsJson) {
  if (index < 0 || index >= clips.length || !clips[index]) return;
  clips[index].transcribing = false;
  clips[index].transcript = String(text || '').trim();
  try {
    const parsed = JSON.parse(segmentsJson || '[]');
    clips[index].segments = Array.isArray(parsed) ? parsed : [];
  } catch (_) {
    clips[index].segments = [];
  }
  pendingTranscriptions = Math.max(0, pendingTranscriptions - 1);

  if (currentIndex === index) {
    el('recordState').textContent = 'כתוביות בעברית מוכנות';
    captionBox.textContent = clips[index].transcript
      ? 'כתוביות: ' + clips[index].transcript
      : 'לא זוהה טקסט. אפשר לתקן ידנית בסוף.';
  }
};

window.onLocalCaptionError = function(index, message) {
  if (index >= 0 && index < clips.length && clips[index]) {
    clips[index].transcribing = false;
  }
  pendingTranscriptions = Math.max(0, pendingTranscriptions - 1);
  if (currentIndex === index) {
    el('recordState').textContent = 'הצילום נשמר';
    captionBox.textContent = 'לא הצלחתי ליצור כתוביות אוטומטיות. אפשר לתקן ידנית בסוף.';
  }
};

window.onCloudTranscriptError = function(index, message) {
  if (index >= 0 && index < clips.length && clips[index]) {
    clips[index].transcribing = false;
  }
  pendingTranscriptions = Math.max(0, pendingTranscriptions - 1);
  if (currentIndex === index) {
    el('recordState').textContent = 'הצילום נשמר';
    captionBox.textContent = 'כתוביות AI לא נוצרו. אפשר לתקן ידנית בסוף.';
  }
};

window.onNativeClipCancelled = function(index) {
  if (index >= 0 && index < questions.length) currentIndex = index;
  el('recordState').textContent = clips[currentIndex] ? 'מצולם' : 'מוכן';
  el('startRecordBtn').disabled = !!clips[currentIndex];
  el('stopRecordBtn').disabled = true;
  el('retakeBtn').disabled = !clips[currentIndex];
  el('nextBtn').disabled = !clips[currentIndex];
  captionBox.textContent = clips[currentIndex]
    ? 'הצילום הקודם עדיין שמור'
    : 'הצילום בוטל. אפשר לנסות שוב.';
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
    if (old.native && window.Android && typeof window.Android.deleteRecording === 'function') {
      try { window.Android.deleteRecording(old.url); } catch (_) {}
    } else {
      try { URL.revokeObjectURL(old.url); } catch (_) {}
    }
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
      showScreen('interviewScreen');
      showCurrentQuestion();
      if (window.Android && typeof window.Android.startNativeRecording === 'function') {
        captionBox.textContent = 'לחץ על התחל צילום כדי לפתוח שוב את המצלמה';
        return;
      }
      try { await ensureCamera(); } catch (_) {
        alert('לא הצלחתי לפתוח את המצלמה.');
      }
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

function v09CaptionStyle(elm, phase) {
  if (!elm) return;
  const p = Math.max(0, Math.min(1, Number(phase || 0)));
  elm.style.transform = 'translate(-50%, 0) scale(' + (0.96 + 0.04 * Math.sin(p * Math.PI)) + ')';
  elm.style.textShadow = '0 2px 8px rgba(0,0,0,.95), 2px 2px 0 rgba(0,0,0,.8), -2px -2px 0 rgba(0,0,0,.55)';
}
function v09CleanCaptionText(text) {
  return String(text || '').replace(/\s+/g, ' ').replace(/^\s+|\s+$/g, '');
}
function captionChunkInfo(text, progress) {
  const words = String(text || '').trim().split(/\s+/).filter(Boolean);
  if (!words.length) return { text: '', phase: 0, index: 0, count: 0 };
  const perChunk = 6;
  const chunks = [];
  for (let i = 0; i < words.length; i += perChunk) {
    chunks.push(words.slice(i, i + perChunk).join(' '));
  }
  const raw = Math.max(0, Math.min(0.999999, progress)) * chunks.length;
  const idx = Math.min(chunks.length - 1, Math.floor(raw));
  return {
    text: chunks[idx] || '',
    phase: raw - idx,
    index: idx,
    count: chunks.length
  };
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
    const localMs = Math.max(0, (t - answerAt) * 1000);
    let info = null;

    if (Array.isArray(clip.segments) && clip.segments.length) {
      const seg = clip.segments.find(s =>
        localMs >= Number(s.startMs || 0) &&
        localMs <= Number(s.endMs || 0) + 120
      );
      if (seg) {
        const start = Number(seg.startMs || 0);
        const end = Math.max(start + 250, Number(seg.endMs || start + 1000));
        info = {
          text: String(seg.text || '').trim(),
          phase: Math.max(0, Math.min(1, (localMs - start) / (end - start))),
          index: 0,
          count: clip.segments.length
        };
      }
    }

    if (!info) {
      const p = (t - answerAt) / Math.max(0.5, duration - answerAt);
      info = captionChunkInfo(clip.transcript, p);
    }
    const text = info.text;

    if (text) {
      const effect = settings.captionEffect || 'pop';
      const isPop = effect === 'pop';
      const isBounce = effect === 'bounce';
      const introPhase = Math.min(1, info.phase * 5);
      const bounce = isBounce ? Math.sin(introPhase * Math.PI) * 0.14 : 0;
      const scale = isPop ? 1 + (1 - introPhase) * 0.11 : (isBounce ? 1 + bounce : 1);
      const lift = isPop ? (1 - introPhase) * 16 : (isBounce ? -Math.sin(introPhase * Math.PI) * 12 : 0);
      const alpha = isPop ? Math.min(1, 0.45 + introPhase * 0.55) : 1;

      ctx.font = '950 ' + settings.subtitleSize + 'px sans-serif';
      ctx.direction = 'rtl';
      const lines = wrapText(ctx, text, 600).slice(0, 2);
      const lineHeight = settings.subtitleSize + 13;
      const boxH = Math.max(104, 34 + lines.length * lineHeight);
      const boxY = 1280 - boxH - 72;

      roundedRect(ctx, 34, boxY, 652, boxH, 24, 'rgba(0,0,0,0.66)');

      const progressWidth = Math.max(12, 596 * Math.max(0.04, info.phase));
      roundedRect(ctx, 62, boxY + boxH - 13, progressWidth, 5, 3, 'rgba(255,255,255,0.86)');

      ctx.save();
      ctx.globalAlpha = alpha;
      ctx.translate(360, boxY + boxH / 2 - lift);
      ctx.scale(scale, scale);
      ctx.fillStyle = '#ffffff';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.shadowColor = '#000000';
      ctx.shadowBlur = 9;
      const localStartY = -((lines.length - 1) * lineHeight) / 2;
      lines.forEach((line, i) => ctx.fillText(line, 0, localStartY + i * lineHeight));
      ctx.restore();

      ctx.shadowBlur = 0;
      ctx.globalAlpha = 1;
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

async function startBackgroundMusic(audioCtx, audioDest) {
  if (!settings.backgroundMusic || Number(settings.musicVolume) <= 0) {
    return {
      stop: () => {},
      setLevel: () => {}
    };
  }

  const master = audioCtx.createGain();
  master.gain.setValueAtTime(Number(settings.musicVolume), audioCtx.currentTime);
  master.connect(audioDest);

  try {
    const res = await fetch('yishai_music_loop.wav');
    if (!res.ok) throw new Error('music-load');
    const bytes = await res.arrayBuffer();
    const buffer = await audioCtx.decodeAudioData(bytes);

    const source = audioCtx.createBufferSource();
    source.buffer = buffer;
    source.loop = true;
    source.connect(master);
    source.start(0);

    return {
      stop: () => {
        try {
          master.gain.setTargetAtTime(0, audioCtx.currentTime, 0.08);
          source.stop(audioCtx.currentTime + 0.3);
        } catch (_) {}
      },
      setLevel: level => {
        try {
          master.gain.setTargetAtTime(
            Math.max(0, Number(level || 0)),
            audioCtx.currentTime,
            0.15
          );
        } catch (_) {}
      }
    };
  } catch (_) {
    return {
      stop: () => {},
      setLevel: () => {}
    };
  }
}

function playTransitionFx(audioCtx, audioDest) {
  if (!settings.reelEffects) return;
  const osc = audioCtx.createOscillator();
  const gain = audioCtx.createGain();
  osc.type = 'sine';
  osc.frequency.setValueAtTime(540, audioCtx.currentTime);
  osc.frequency.exponentialRampToValueAtTime(920, audioCtx.currentTime + 0.11);
  gain.gain.setValueAtTime(0.0001, audioCtx.currentTime);
  gain.gain.exponentialRampToValueAtTime(0.035, audioCtx.currentTime + 0.025);
  gain.gain.exponentialRampToValueAtTime(0.0001, audioCtx.currentTime + 0.15);
  osc.connect(gain);
  gain.connect(audioDest);
  osc.start();
  osc.stop(audioCtx.currentTime + 0.16);
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
        const d = Math.max(video.duration || 1, 1);
        const zoomEnabled = settings.reelEffects !== false;
        const subtleZoom = zoomEnabled ? 1 + Math.min(0.022, (video.currentTime / d) * 0.022) : 1;
        ctx.save();
        ctx.translate(360, 640);
        ctx.scale(subtleZoom, subtleZoom);
        ctx.translate(-360, -640);
        drawVideoCover(ctx, video);
        ctx.restore();
        drawOverlay(ctx, clip, video);

        if (settings.reelEffects !== false) {
          const fadeWindow = 0.22;
          if (video.currentTime < fadeWindow) {
            const fadeIn = 1 - (video.currentTime / fadeWindow);
            ctx.fillStyle = 'rgba(0,0,0,' + (fadeIn * 0.50) + ')';
            ctx.fillRect(0, 0, 720, 1280);
          }
          if (d - video.currentTime < fadeWindow) {
            const fade = 1 - Math.max(0, d - video.currentTime) / fadeWindow;
            ctx.fillStyle = 'rgba(0,0,0,' + (fade * 0.58) + ')';
            ctx.fillRect(0, 0, 720, 1280);
          }
        }
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
  if (pendingTranscriptions > 0 || clips.some(c => c && c.transcribing)) {
    return alert('עדיין מכין כתוביות AI. חכה כמה שניות ונסה שוב.');
  }
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
    const music = await startBackgroundMusic(audioCtx, audioDest);
    music.setLevel(Number(settings.musicVolume));

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

    music.setLevel(Number(settings.musicVolume) * 0.48);
    for (let i = 0; i < clips.length; i++) {
      if (i > 0) playTransitionFx(audioCtx, audioDest);
      el('editStatus').textContent = 'עורך תשובה ' + (i + 1) + ' מתוך ' + clips.length;
      el('editProgress').style.width = String(12 + Math.round((i / clips.length) * 74)) + '%';
      await renderClipToCanvas(ctx, clips[i], audioCtx, audioDest);
      await wait(120);
    }

    el('editStatus').textContent = 'מוסיף סיום';
    el('editProgress').style.width = '92%';
    music.setLevel(Number(settings.musicVolume) * 0.82);
    await renderCardFor(ctx, 1800, 'שבת שלום!', 'נתראה בפרשה הבאה');

    music.stop();
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
  clips.forEach(c => {
    if (!c || !c.url) return;
    if (c.native && window.Android && typeof window.Android.deleteRecording === 'function') {
      try { window.Android.deleteRecording(c.url); } catch (_) {}
    } else {
      try { URL.revokeObjectURL(c.url); } catch (_) {}
    }
  });
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
