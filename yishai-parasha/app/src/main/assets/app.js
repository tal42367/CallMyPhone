const el = id => document.getElementById(id);
const setupCard = el('setupCard');
const interviewCard = el('interviewCard');
const finishCard = el('finishCard');
const preview = el('preview');
const captionBox = el('captionBox');

let questions = [];
let index = 0;
let stream = null;
let recorder = null;
let chunks = [];
let clips = [];
let recognition = null;
let currentTranscript = '';
let sessionName = '';

function buildQuestions(parasha, count) {
  const pool = [
    `ישי, קודם כל, אתה יכול לספר לנו בקצרה מה קורה ב${parasha}?`,
    `ישי, מה לדעתך האירוע הכי מעניין או הכי חשוב ב${parasha}?`,
    `איזו דמות ב${parasha} הכי מעניינת אותך, ולמה?`,
    `למה לדעתך התורה מספרת לנו את הסיפור המרכזי של ${parasha}?`,
    `מה אנחנו יכולים ללמוד מ${parasha} לחיים שלנו היום?`,
    `אם היית צריך לבחור דבר אחד מ${parasha} שאתה רוצה לזכור השבוע, מה היית בוחר?`
  ];
  return pool.slice(0, count);
}

function speak(text) {
  if (window.Android && typeof window.Android.speak === 'function') {
    window.Android.speak(text);
    return;
  }
  if (!('speechSynthesis' in window)) {
    alert('הדפדפן הזה לא תומך כרגע בהקראת קול. נסה Chrome באנדרואיד.');
    return;
  }
  speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(text);
  u.lang = 'he-IL';
  u.rate = 0.94;
  u.pitch = 0.94;
  const voices = speechSynthesis.getVoices();
  const he = voices.find(v => (v.lang || '').toLowerCase().startsWith('he'));
  if (he) u.voice = he;
  speechSynthesis.speak(u);
}

async function ensureCamera() {
  if (stream) return;
  stream = await navigator.mediaDevices.getUserMedia({
    video: { facingMode: 'user', width: { ideal: 1080 }, height: { ideal: 1920 } },
    audio: true
  });
  preview.srcObject = stream;
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
    captionBox.textContent = (currentTranscript + interim).trim() || 'ישי מדבר...';
  };
  r.onerror = () => {};
  return r;
}

function showQuestion() {
  el('progressText').textContent = `שאלה ${index + 1} מתוך ${questions.length}`;
  el('questionText').textContent = questions[index];
  el('nextBtn').disabled = true;
  el('startRecordBtn').disabled = false;
  el('stopRecordBtn').disabled = true;
  captionBox.textContent = 'הכתוביות של ישי יופיעו כאן בזמן הדיבור';
  currentTranscript = '';
  speak(questions[index]);
}

el('prepareBtn').onclick = async () => {
  const parasha = el('parasha').value.trim();
  if (!parasha) return alert('כתוב קודם את שם הפרשה.');
  const count = Number(el('questionCount').value);
  sessionName = parasha;
  questions = buildQuestions(parasha, count);
  index = 0;
  clips = [];
  try {
    await ensureCamera();
  } catch (e) {
    alert('צריך לאשר גישה למצלמה ולמיקרופון כדי להתחיל.');
    return;
  }
  setupCard.classList.add('hidden');
  interviewCard.classList.remove('hidden');
  showQuestion();
};

el('speakBtn').onclick = () => speak(questions[index]);

el('startRecordBtn').onclick = () => {
  chunks = [];
  currentTranscript = '';
  captionBox.textContent = 'ישי מדבר...';
  const mime = MediaRecorder.isTypeSupported('video/webm;codecs=vp9,opus')
    ? 'video/webm;codecs=vp9,opus'
    : 'video/webm';
  recorder = new MediaRecorder(stream, { mimeType: mime });
  recorder.ondataavailable = e => { if (e.data && e.data.size) chunks.push(e.data); };
  recorder.onstop = () => {
    const blob = new Blob(chunks, { type: recorder.mimeType });
    const url = URL.createObjectURL(blob);
    clips.push({
      question: questions[index],
      transcript: currentTranscript.trim(),
      url,
      blob,
      type: recorder.mimeType
    });
    el('nextBtn').disabled = false;
    el('recordState').textContent = 'התשובה נשמרה';
  };
  recorder.start();
  recognition = setupRecognition();
  try { recognition?.start(); } catch (_) {}
  el('recordState').textContent = 'מקליט';
  el('startRecordBtn').disabled = true;
  el('stopRecordBtn').disabled = false;
};

el('stopRecordBtn').onclick = () => {
  try { recognition?.stop(); } catch (_) {}
  if (recorder && recorder.state !== 'inactive') recorder.stop();
  el('stopRecordBtn').disabled = true;
};

el('nextBtn').onclick = () => {
  if (index < questions.length - 1) {
    index++;
    showQuestion();
  } else {
    interviewCard.classList.add('hidden');
    finishCard.classList.remove('hidden');
    renderClips();
    speak('תודה רבה ישי. היה מעניין מאוד לשמוע אותך. שבת שלום לכולם.');
  }
};

function renderClips() {
  const wrap = el('clipsList');
  wrap.innerHTML = '';
  clips.forEach((clip, i) => {
    const div = document.createElement('div');
    div.className = 'clip';
    const a = document.createElement('a');
    a.href = clip.url;
    a.download = `yishai_answer_${i + 1}.webm`;
    a.textContent = '⬇️ הורד את הקליפ';
    div.innerHTML = `<strong>תשובה ${i + 1}</strong><p>${escapeHtml(clip.transcript || 'לא זוהו כתוביות אוטומטיות — אפשר לערוך ידנית בשלב העריכה.')}</p>`;
    div.appendChild(a);
    wrap.appendChild(div);
  });
}

function escapeHtml(s) {
  return s.replace(/[&<>'"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));
}

el('exportProjectBtn').onclick = () => {
  const data = {
    title: 'פשוט פרשה עם ישי',
    parasha: sessionName,
    created_at: new Date().toISOString(),
    format: 'vertical-reel-9x16',
    subtitle_language: 'he-IL',
    interviewer_voice_language: 'he-IL',
    answers: clips.map((c,i) => ({ number:i+1, question:c.question, transcript:c.transcript, clip_filename:`yishai_answer_${i+1}.webm` }))
  };
  const blob = new Blob([JSON.stringify(data,null,2)], {type:'application/json'});
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'yishai_parasha_project.json';
  a.click();
};

el('restartBtn').onclick = () => location.reload();
