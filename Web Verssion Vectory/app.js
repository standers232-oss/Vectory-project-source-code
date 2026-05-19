import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-app.js";
import {
  getAuth,
  createUserWithEmailAndPassword,
  signInWithEmailAndPassword,
  signOut,
  onAuthStateChanged
} from "https://www.gstatic.com/firebasejs/10.12.5/firebase-auth.js";
import {
  getFirestore,
  doc,
  getDoc,
  setDoc,
  updateDoc,
  collection,
  addDoc,
  query,
  where,
  orderBy,
  startAt,
  endAt,
  limit,
  limitToLast,
  onSnapshot,
  serverTimestamp
} from "https://www.gstatic.com/firebasejs/10.12.5/firebase-firestore.js";
import { getStorage, ref, uploadBytes, getDownloadURL } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-storage.js";
import { getMessaging, getToken, onMessage } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-messaging.js";

const firebaseConfig = {
  apiKey: "AIzaSyB4hBCbmicPOm-soqfHAuLGFTV3jXc2ndY",
  authDomain: "massangetr.firebaseapp.com",
  databaseURL: "https://massangetr-default-rtdb.firebaseio.com",
  projectId: "massangetr",
  storageBucket: "massangetr.firebasestorage.app",
  messagingSenderId: "1039297801904",
  appId: "1:1039297801904:web:b52b3b37ebd4528d745575",
  measurementId: "G-RYK3XR64KB"
};

// Для настоящих web-push уведомлений вставьте VAPID key из Firebase Console → Cloud Messaging.
const WEB_PUSH_VAPID_KEY = "";

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const db = getFirestore(app);
const storage = getStorage(app);
let messaging = null;
try { messaging = getMessaging(app); } catch (_) {}

const main = document.getElementById("main");
const sidebar = document.getElementById("sidebar");
const chatList = document.getElementById("chatList");
const meLine = document.getElementById("meLine");
const newChatBtn = document.getElementById("newChatBtn");
const settingsBtn = document.getElementById("settingsBtn");

let me = null;
let chatUnsub = null;
let msgUnsub = null;
let notifyUnsub = null;
let currentChatId = null;
let skipFirstNotify = true;
let authMode = "login";

const usernameToEmail = (username) => `${username.trim().toLowerCase()}@mass.local`;
const validUsername = (u) => /^[a-z0-9_.]{3,24}$/.test(u);
const chatId = (a, b) => [a, b].sort().join("_");
const escapeHtml = (s) => String(s).replace(/[&<>'"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","'":"&#39;",'"':"&quot;"}[c]));
const fmtTime = (ts) => {
  const d = ts?.toDate ? ts.toDate() : new Date();
  return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
};
function toast(text) { alert(text); }
function avatarHtml(nick, url, big = false) {
  const letter = (nick || "N").trim().charAt(0).toUpperCase() || "N";
  return `<div class="avatar ${big ? "big" : ""}">${url ? `<img src="${escapeHtml(url)}" alt="">` : escapeHtml(letter)}</div>`;
}
function stopUiListeners() {
  chatUnsub?.(); msgUnsub?.(); chatUnsub = null; msgUnsub = null;
}

onAuthStateChanged(auth, async (user) => {
  if (!user) {
    me = null;
    stopUiListeners(); notifyUnsub?.(); notifyUnsub = null;
    sidebar.classList.add("hidden");
    showAuth("login");
    return;
  }
  sidebar.classList.remove("hidden");
  await loadProfile(user.uid);
});

async function loadProfile(uid) {
  main.innerHTML = `<section class="empty-state"><div class="notice">Загружаем профиль…</div></section>`;
  const snap = await getDoc(doc(db, "users", uid));
  const data = snap.data() || {};
  me = {
    uid,
    username: data.username || auth.currentUser.email.split("@")[0],
    nick: data.nick || data.username || "User",
    avatarUrl: data.avatarUrl || ""
  };
  meLine.textContent = `@${me.username}`;
  await setupMessaging();
  startIncomingNotifications();
  showEmpty();
  watchChats();
}

function showAuth(mode) {
  authMode = mode;
  const tpl = document.getElementById("authTemplate").content.cloneNode(true);
  const title = tpl.getElementById("authTitle");
  const sub = tpl.getElementById("authSub");
  const fields = tpl.getElementById("authFields");
  const submit = tpl.getElementById("authSubmit");
  const sw = tpl.getElementById("authSwitch");
  title.textContent = mode === "login" ? "Добро пожаловать" : "Создать аккаунт";
  sub.textContent = mode === "login" ? "Войдите в Nexus Mass" : "Юзернейм — для входа, ник видят собеседники";
  fields.innerHTML = mode === "login" ? `
    <input class="input" id="username" placeholder="Юзернейм" autocomplete="username">
    <input class="input" id="password" placeholder="Пароль" type="password" autocomplete="current-password">
  ` : `
    <input class="input" id="username" placeholder="Юзернейм" autocomplete="username">
    <input class="input" id="nick" placeholder="Ник" autocomplete="nickname">
    <input class="input" id="password" placeholder="Пароль" type="password" autocomplete="new-password">
    <input class="input" id="repeat" placeholder="Повтор пароля" type="password" autocomplete="new-password">
  `;
  submit.textContent = mode === "login" ? "Войти" : "Зарегистрироваться";
  sw.textContent = mode === "login" ? "Нет аккаунта? Создать профиль" : "Уже есть аккаунт? Войти";
  sw.onclick = () => showAuth(mode === "login" ? "register" : "login");
  submit.onclick = () => mode === "login" ? login() : register();
  main.innerHTML = "";
  main.append(tpl);
}

async function login() {
  const username = document.getElementById("username").value.trim().toLowerCase();
  const password = document.getElementById("password").value;
  if (!validUsername(username)) return toast("Юзернейм: 3–24 символа, латиница/цифры/_/.");
  if (password.length < 6) return toast("Пароль минимум 6 символов");
  await signInWithEmailAndPassword(auth, usernameToEmail(username), password).catch(e => toast(`Ошибка входа: ${e.message}`));
}

async function register() {
  const username = document.getElementById("username").value.trim().toLowerCase();
  const nick = document.getElementById("nick").value.trim();
  const password = document.getElementById("password").value;
  const repeat = document.getElementById("repeat").value;
  if (!validUsername(username)) return toast("Юзернейм: 3–24 символа, латиница/цифры/_/.");
  if (nick.length < 2 || nick.length > 32) return toast("Ник от 2 до 32 символов");
  if (password.length < 6) return toast("Пароль минимум 6 символов");
  if (password !== repeat) return toast("Пароли не совпадают");
  try {
    const res = await createUserWithEmailAndPassword(auth, usernameToEmail(username), password);
    const data = { uid: res.user.uid, username, usernameLower: username, nick, avatarUrl: "", createdAt: serverTimestamp(), updatedAt: serverTimestamp() };
    await Promise.all([
      setDoc(doc(db, "users", res.user.uid), data),
      setDoc(doc(db, "usernames", username), data)
    ]);
  } catch (e) {
    toast(`Ошибка регистрации: ${e.message}`);
  }
}

function showEmpty() {
  currentChatId = null;
  msgUnsub?.(); msgUnsub = null;
  const tpl = document.getElementById("emptyTemplate").content.cloneNode(true);
  tpl.getElementById("emptySearchBtn").onclick = showSearch;
  main.innerHTML = "";
  main.append(tpl);
}

function watchChats() {
  chatUnsub?.();
  const q = query(collection(db, "chats"), where("members", "array-contains", me.uid));
  chatUnsub = onSnapshot(q, snap => {
    const chats = snap.docs.map(d => chatPreview(d.id, d.data())).filter(Boolean)
      .sort((a,b) => (b.updatedAt?.toMillis?.() || 0) - (a.updatedAt?.toMillis?.() || 0));
    chatList.innerHTML = chats.length ? "" : `<div class="notice">Чатов пока нет</div>`;
    chats.forEach(c => {
      const row = document.createElement("button");
      row.className = `chat-row ${currentChatId === c.id ? "active" : ""}`;
      row.innerHTML = `${avatarHtml(c.other.nick, c.other.avatarUrl)}
        <div class="row-main"><div class="row-title">${escapeHtml(c.other.nick)}</div><div class="row-sub">@${escapeHtml(c.other.username)}</div><div class="row-last">${escapeHtml(c.lastMessage || "Откройте чат")}</div></div>
        <div class="row-time">${c.updatedAt ? fmtTime(c.updatedAt) : "›"}</div>`;
      row.onclick = () => openChat(c.other);
      chatList.append(row);
    });
  });
}

function chatPreview(id, data) {
  const otherUid = (data.members || []).find(uid => uid !== me.uid);
  if (!otherUid) return null;
  const info = data.memberInfo?.[otherUid] || {};
  return {
    id,
    other: { uid: otherUid, username: info.username || "user", nick: info.nick || "Пользователь", avatarUrl: info.avatarUrl || "" },
    lastMessage: data.lastMessage || "",
    lastSenderUid: data.lastSenderUid || "",
    updatedAt: data.updatedAt || null
  };
}

async function openChat(other) {
  stopUiListeners();
  currentChatId = chatId(me.uid, other.uid);
  const cRef = doc(db, "chats", currentChatId);
  await setDoc(cRef, {
    members: [me.uid, other.uid].sort(),
    memberInfo: {
      [me.uid]: { uid: me.uid, username: me.username, nick: me.nick, avatarUrl: me.avatarUrl || "" },
      [other.uid]: { uid: other.uid, username: other.username, nick: other.nick, avatarUrl: other.avatarUrl || "" }
    },
    updatedAt: serverTimestamp()
  }, { merge: true });

  main.innerHTML = `
    <div class="chat-header">
      <button class="back-btn" id="mobileChats">☰</button>
      ${avatarHtml(other.nick, other.avatarUrl)}
      <div><h2>${escapeHtml(other.nick)}</h2><p>@${escapeHtml(other.username)}</p></div>
    </div>
    <div class="messages" id="messages"><div class="notice">Открываем чат…</div></div>
    <form class="composer" id="composer"><input class="input" id="messageInput" placeholder="Сообщение…" maxlength="1000"><button class="send-btn">➤</button></form>`;
  document.getElementById("mobileChats").onclick = () => sidebar.classList.toggle("mobile-open");
  document.getElementById("composer").onsubmit = async (e) => {
    e.preventDefault();
    const input = document.getElementById("messageInput");
    const text = input.value.trim();
    if (!text) return;
    input.value = "";
    await addDoc(collection(db, "chats", currentChatId, "messages"), {
      text,
      senderUid: me.uid,
      senderUsername: me.username,
      senderNick: me.nick,
      senderAvatarUrl: me.avatarUrl || "",
      createdAt: serverTimestamp()
    });
    await setDoc(cRef, {
      members: [me.uid, other.uid].sort(),
      memberInfo: {
        [me.uid]: { uid: me.uid, username: me.username, nick: me.nick, avatarUrl: me.avatarUrl || "" },
        [other.uid]: { uid: other.uid, username: other.username, nick: other.nick, avatarUrl: other.avatarUrl || "" }
      },
      lastMessage: text,
      lastSenderUid: me.uid,
      updatedAt: serverTimestamp()
    }, { merge: true });
  };

  const q = query(collection(db, "chats", currentChatId, "messages"), orderBy("createdAt"), limitToLast(200));
  msgUnsub = onSnapshot(q, snap => {
    const box = document.getElementById("messages");
    if (!box) return;
    box.innerHTML = snap.empty ? `<div class="notice">Сообщений пока нет. Начните диалог ✨</div>` : "";
    snap.docs.forEach(d => {
      const m = d.data();
      const mine = m.senderUid === me.uid;
      box.insertAdjacentHTML("beforeend", `<div class="bubble-wrap ${mine ? "mine" : ""}"><div class="bubble">
        <div class="bubble-author">${mine ? "Вы" : `${escapeHtml(m.senderNick || "Пользователь")} @${escapeHtml(m.senderUsername || "user")}`}</div>
        <div class="bubble-text">${escapeHtml(m.text || "")}</div>
        <div class="bubble-time">${fmtTime(m.createdAt)}</div>
      </div></div>`);
    });
    box.scrollTop = box.scrollHeight;
  });
}

function showSearch() {
  currentChatId = null;
  msgUnsub?.(); msgUnsub = null;
  main.innerHTML = `<section class="panel"><div class="panel-card"><h2>Поиск пользователей</h2><p>Введите юзернейм или начало юзернейма.</p>
    <div class="stack"><input class="input" id="searchInput" placeholder="например alex"><button class="primary-btn" id="searchBtn">Искать</button></div>
    <div class="search-results" id="searchResults"><div class="notice">Результаты появятся здесь</div></div></div></section>`;
  document.getElementById("searchBtn").onclick = async () => {
    const qText = document.getElementById("searchInput").value.trim().toLowerCase();
    const res = document.getElementById("searchResults");
    if (qText.length < 2) return toast("Минимум 2 символа");
    res.innerHTML = `<div class="notice">Ищем…</div>`;
    const q = query(collection(db, "usernames"), orderBy("usernameLower"), startAt(qText), endAt(qText + "\uf8ff"), limit(30));
    const unsub = onSnapshot(q, snap => {
      res.innerHTML = "";
      const users = snap.docs.map(d => d.data()).filter(u => u.uid !== me.uid);
      if (!users.length) res.innerHTML = `<div class="notice">Никого не нашли</div>`;
      users.forEach(u => {
        const btn = document.createElement("button");
        btn.className = "user-row";
        btn.innerHTML = `${avatarHtml(u.nick, u.avatarUrl)}<div class="row-main"><div class="row-title">${escapeHtml(u.nick)}</div><div class="row-sub">@${escapeHtml(u.username)}</div></div><b>Написать</b>`;
        btn.onclick = () => { unsub(); openChat({ uid: u.uid, username: u.username, nick: u.nick, avatarUrl: u.avatarUrl || "" }); };
        res.append(btn);
      });
    }, e => res.innerHTML = `<div class="notice">Ошибка: ${escapeHtml(e.message)}</div>`);
  };
}

function showSettings() {
  currentChatId = null;
  msgUnsub?.(); msgUnsub = null;
  main.innerHTML = `<section class="panel"><div class="panel-card stack">
    <div style="display:grid;place-items:center;gap:12px">${avatarHtml(me.nick, me.avatarUrl, true)}<input id="avatarFile" type="file" accept="image/*"><button class="secondary-btn" id="uploadAvatar">Загрузить аватар</button></div>
    <label>Юзернейм<input class="input" value="@${escapeHtml(me.username)}" disabled></label>
    <label>Ник<input class="input" id="nickInput" value="${escapeHtml(me.nick)}"></label>
    <button class="primary-btn" id="saveNick">Сохранить ник</button>
    <button class="secondary-btn" id="notifyBtn">Разрешить уведомления</button>
    <button class="danger-btn" id="logoutBtn">Выйти</button>
  </div></section>`;
  document.getElementById("saveNick").onclick = async () => {
    const nick = document.getElementById("nickInput").value.trim();
    if (nick.length < 2 || nick.length > 32) return toast("Ник от 2 до 32 символов");
    await Promise.all([
      updateDoc(doc(db, "users", me.uid), { nick, updatedAt: serverTimestamp() }),
      updateDoc(doc(db, "usernames", me.username), { nick, updatedAt: serverTimestamp() })
    ]);
    me.nick = nick; toast("Ник обновлён"); showSettings();
  };
  document.getElementById("uploadAvatar").onclick = uploadAvatar;
  document.getElementById("notifyBtn").onclick = requestNotifications;
  document.getElementById("logoutBtn").onclick = () => signOut(auth);
}

async function uploadAvatar() {
  const file = document.getElementById("avatarFile").files?.[0];
  if (!file) return toast("Выберите файл");
  const r = ref(storage, `avatars/${me.uid}.jpg`);
  await uploadBytes(r, file);
  const avatarUrl = await getDownloadURL(r);
  await Promise.all([
    updateDoc(doc(db, "users", me.uid), { avatarUrl, updatedAt: serverTimestamp() }),
    updateDoc(doc(db, "usernames", me.username), { avatarUrl, updatedAt: serverTimestamp() })
  ]);
  me.avatarUrl = avatarUrl;
  toast("Аватар обновлён");
  showSettings();
}

async function requestNotifications() {
  if (!("Notification" in window)) return toast("Браузер не поддерживает уведомления");
  const perm = await Notification.requestPermission();
  toast(perm === "granted" ? "Уведомления разрешены" : "Уведомления не разрешены");
  await setupMessaging();
}

function showBrowserNotification(title, body) {
  if (!("Notification" in window) || Notification.permission !== "granted") return;
  new Notification(title, { body, icon: undefined });
}

function startIncomingNotifications() {
  notifyUnsub?.();
  skipFirstNotify = true;
  const q = query(collection(db, "chats"), where("members", "array-contains", me.uid));
  notifyUnsub = onSnapshot(q, snap => {
    if (skipFirstNotify) { skipFirstNotify = false; return; }
    snap.docChanges().forEach(ch => {
      if (!["added", "modified"].includes(ch.type)) return;
      const c = chatPreview(ch.doc.id, ch.doc.data());
      if (!c || c.lastSenderUid === me.uid) return;
      if (currentChatId === c.id) return;
      showBrowserNotification(c.other.nick, c.lastMessage || "Новое сообщение");
    });
  });
}

async function setupMessaging() {
  if (!messaging || !me) return;
  try {
    if ("serviceWorker" in navigator) await navigator.serviceWorker.register("./firebase-messaging-sw.js");
    onMessage(messaging, payload => showBrowserNotification(payload.notification?.title || "Nexus Mass", payload.notification?.body || "Новое сообщение"));
    if (WEB_PUSH_VAPID_KEY && Notification.permission === "granted") {
      const token = await getToken(messaging, { vapidKey: WEB_PUSH_VAPID_KEY });
      if (token) await updateDoc(doc(db, "users", me.uid), { webFcmToken: token, tokenUpdatedAt: serverTimestamp() });
    }
  } catch (e) { console.warn("Messaging disabled", e); }
}

newChatBtn.onclick = showSearch;
settingsBtn.onclick = showSettings;
if ("serviceWorker" in navigator) navigator.serviceWorker.register("./firebase-messaging-sw.js").catch(() => {});
