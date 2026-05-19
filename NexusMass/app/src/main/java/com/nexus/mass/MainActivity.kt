package com.nexus.mass

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private var profile: UserProfile? = null
    private var screen = Screen.AUTH
    private var chatListListener: ListenerRegistration? = null
    private var messagesListener: ListenerRegistration? = null
    private var announcementListener: ListenerRegistration? = null
    private var currentChatId: String? = null
    private val imageCache = ConcurrentHashMap<String, Bitmap>()

    private val telegramBlue = Color.rgb(42, 171, 238)
    private val telegramDark = Color.rgb(23, 33, 43)
    private val telegramBg = Color.rgb(229, 235, 239)
    private val panel = Color.WHITE
    private val text = Color.rgb(17, 24, 39)
    private val muted = Color.rgb(107, 114, 128)
    private val line = Color.rgb(226, 232, 240)
    private val outgoing = Color.rgb(220, 248, 198)
    private val incoming = Color.WHITE
    private val danger = Color.rgb(239, 68, 68)
    private val adminUsername = "creator"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = telegramBlue
        window.navigationBarColor = telegramDark
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        try { db.firestoreSettings = FirebaseFirestoreSettings.Builder().setPersistenceEnabled(true).build() } catch (_: Exception) {}
        NexusNotifier.ensureChannel(this)
        requestNotificationPermissionIfNeeded()
        route()
    }

    override fun onResume() {
        super.onResume()
        setOnline(true)
    }

    override fun onPause() {
        setOnline(false)
        super.onPause()
    }

    override fun onDestroy() {
        stopUiListeners()
        announcementListener?.remove()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.CHAT, Screen.SEARCH, Screen.SETTINGS, Screen.ADMIN -> showHome()
            Screen.HOME -> moveTaskToBack(true)
            Screen.AUTH, Screen.LOADING -> super.onBackPressed()
        }
    }

    private fun route() {
        auth.currentUser?.let { loadProfile(it.uid) } ?: showLogin()
    }

    private fun loadProfile(uid: String) {
        screen = Screen.LOADING
        setAnimatedContent(loadingView("Загрузка Vectory…"))
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val username = doc.getString("username") ?: auth.currentUser?.email?.substringBefore('@') ?: "user"
                val nick = doc.getString("nick") ?: username
                val avatarUrl = doc.getString("avatarUrl") ?: defaultAvatar(username)
                val status = doc.getString("status") ?: "в сети"
                val banned = doc.getBoolean("banned") ?: false
                var isAdmin = doc.getString("role") == "admin" || username == adminUsername
                if (banned && username != adminUsername) {
                    toast("Аккаунт заблокирован администратором")
                    auth.signOut()
                    showLogin()
                    return@addOnSuccessListener
                }
                profile = UserProfile(uid, username, nick, avatarUrl, status, isAdmin, banned)
                if (username == adminUsername) bootstrapAdmin(uid, username)
                updateFcmToken()
                setOnline(true)
                showHome()
            }
            .addOnFailureListener {
                toast("Профиль не загружен: ${it.localizedMessage}")
                auth.signOut()
                showLogin()
            }
    }

    private fun bootstrapAdmin(uid: String, username: String) {
        val data = mapOf("uid" to uid, "username" to username, "role" to "admin", "createdAt" to FieldValue.serverTimestamp())
        db.collection("admins").document(uid).set(data, SetOptions.merge())
        db.collection("users").document(uid).set(mapOf("role" to "admin", "banned" to false), SetOptions.merge())
    }

    // Auth
    private fun showLogin() {
        screen = Screen.AUTH
        stopUiListeners()
        announcementListener?.remove()
        currentChatId = null
        val username = input("Юзернейм", false)
        val password = input("Пароль", true).apply { imeOptions = EditorInfo.IME_ACTION_DONE }
        val login = blueButton("Войти")
        login.setOnClickListener {
            val name = username.text.toString().trim().lowercase(Locale.ROOT)
            val pass = password.text.toString()
            if (!validUsername(name)) { username.error = "3–24 символа: a-z, 0-9, _ или ."; return@setOnClickListener }
            if (pass.length < 6) { password.error = "Минимум 6 символов"; return@setOnClickListener }
            setBusy(login, true, "Вход…")
            auth.signInWithEmailAndPassword(usernameToEmail(name), pass)
                .addOnSuccessListener { loadProfile(it.user!!.uid) }
                .addOnFailureListener { setBusy(login, false, "Войти"); toast("Ошибка: ${friendlyAuthError(it)}") }
        }
        val switch = linkText("Создать аккаунт")
        switch.setOnClickListener { showRegister() }
        setAnimatedContent(authLayout("Vectory", "Быстрый Firebase-мессенджер", listOf(username, password, login, switch)))
    }

    private fun showRegister() {
        screen = Screen.AUTH
        val username = input("Юзернейм", false)
        val nick = input("Имя", false)
        val password = input("Пароль", true)
        val repeat = input("Повтор пароля", true)
        val register = blueButton("Зарегистрироваться")
        register.setOnClickListener {
            val name = username.text.toString().trim().lowercase(Locale.ROOT)
            val displayNick = nick.text.toString().trim()
            val pass = password.text.toString()
            if (!validUsername(name)) { username.error = "3–24 символа: a-z, 0-9, _ или ."; return@setOnClickListener }
            if (displayNick.length !in 2..32) { nick.error = "Имя 2–32 символа"; return@setOnClickListener }
            if (pass.length < 6) { password.error = "Минимум 6 символов"; return@setOnClickListener }
            if (pass != repeat.text.toString()) { repeat.error = "Пароли не совпадают"; return@setOnClickListener }
            setBusy(register, true, "Создаём…")
            auth.createUserWithEmailAndPassword(usernameToEmail(name), pass)
                .addOnSuccessListener { result ->
                    val uid = result.user!!.uid
                    val isAdmin = name == adminUsername
                    val data = hashMapOf(
                        "uid" to uid,
                        "username" to name,
                        "usernameLower" to name,
                        "nick" to displayNick,
                        "avatarUrl" to defaultAvatar(name),
                        "status" to "в сети",
                        "role" to if (isAdmin) "admin" else "user",
                        "banned" to false,
                        "notificationsEnabled" to true,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    val batch = db.batch()
                    batch.set(db.collection("users").document(uid), data)
                    batch.set(db.collection("usernames").document(name), data)
                    if (isAdmin) batch.set(db.collection("admins").document(uid), mapOf("uid" to uid, "username" to name, "role" to "admin", "createdAt" to FieldValue.serverTimestamp()))
                    batch.commit().addOnSuccessListener { loadProfile(uid) }
                        .addOnFailureListener { e -> setBusy(register, false, "Зарегистрироваться"); toast("Профиль не сохранён: ${e.localizedMessage}") }
                }
                .addOnFailureListener { setBusy(register, false, "Зарегистрироваться"); toast("Ошибка: ${friendlyAuthError(it)}") }
        }
        val switch = linkText("Уже есть аккаунт? Войти")
        switch.setOnClickListener { showLogin() }
        setAnimatedContent(authLayout("Новый аккаунт", "Аватар загрузится из интернета автоматически", listOf(username, nick, password, repeat, register, switch)))
    }

    // Home
    private fun showHome() {
        val p = profile ?: return route()
        screen = Screen.HOME
        currentChatId = null
        stopUiListeners()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 247, 250))
            layoutParams = LinearLayout.LayoutParams(match(), match())
        }
        root.addView(topBar("Vectory", p.nick + if (p.isAdmin) "  • ADMIN" else "", p.avatarUrl, "⚙") { showSettings() })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
            setBackgroundColor(panel)
        }
        val searchBtn = blueButton("🔎 Найти").apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) }; setOnClickListener { showSearch() } }
        val adminBtn = if (p.isAdmin) blueOutlineButton("🛡 Админ").apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f); setOnClickListener { showAdminPanel() } } else blueOutlineButton("Профиль").apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f); setOnClickListener { showSettings() } }
        actions.addView(searchBtn); actions.addView(adminBtn); root.addView(actions)

        val announcement = TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            setTextColor(this@MainActivity.text)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(Color.rgb(232, 245, 255), dp(16), Color.rgb(181, 226, 255), 1)
            layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply { setMargins(dp(12), dp(10), dp(12), dp(4)) }
        }
        root.addView(announcement)

        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(match(), 0, 1f) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(18)); layoutParams = FrameLayout.LayoutParams(match(), wrap()) }
        scroll.addView(list); root.addView(scroll)
        setAnimatedContent(root)

        watchAnnouncement(announcement)
        addInfo(list, "Загрузка чатов…")
        chatListListener = db.collection("chats").whereArrayContains("members", p.uid).addSnapshotListener { snapshot, error ->
            list.removeAllViews()
            if (error != null) { addInfo(list, "Ошибка: ${error.localizedMessage}"); return@addSnapshotListener }
            val chats = snapshot?.documents.orEmpty().mapNotNull { chatPreviewFromDoc(it.id, it.data ?: emptyMap(), p.uid) }.sortedByDescending { it.updatedAtMillis }
            if (chats.isEmpty()) addEmpty(list, "Нет чатов", "Нажмите «Найти», чтобы начать диалог.")
            chats.forEachIndexed { i, chat -> addChatRow(list, chat, i) }
        }
    }

    private fun watchAnnouncement(view: TextView) {
        announcementListener?.remove()
        announcementListener = db.collection("announcements").document("current").addSnapshotListener { doc, _ ->
            val msg = doc?.getString("text") ?: ""
            if (msg.isBlank()) view.visibility = View.GONE else { view.visibility = View.VISIBLE; view.text = "📌 $msg"; animateIn(view, 0) }
        }
    }

    private fun addChatRow(parent: LinearLayout, chat: ChatPreview, index: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(panel, dp(18), line, 1)
            layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply { bottomMargin = dp(8) }
            setOnClickListener { openChat(UserProfile(chat.otherUid, chat.otherUsername, chat.otherNick, chat.otherAvatarUrl, chat.otherStatus, chat.otherRole == "admin", false)) }
        }
        pressEffect(row)
        row.addView(avatar(chat.otherAvatarUrl, chat.otherNick, 56))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f).apply { leftMargin = dp(12) } }
        mid.addView(TextView(this).apply { text = chat.otherNick + if (chat.otherRole == "admin") "  ADMIN" else ""; textSize = 17f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(this@MainActivity.text); maxLines = 1 })
        mid.addView(TextView(this).apply { text = chat.lastMessage.ifBlank { "Открыть чат" }; textSize = 14f; setTextColor(muted); maxLines = 1; setPadding(0, dp(4), 0, 0) })
        row.addView(mid)
        row.addView(TextView(this).apply { text = if (chat.updatedAtMillis > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(chat.updatedAtMillis)) else "›"; textSize = 12f; setTextColor(muted) })
        parent.addView(row); animateIn(row, index * 35L)
    }

    // Search
    private fun showSearch() {
        val me = profile ?: return route()
        screen = Screen.SEARCH
        stopUiListeners()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(245,247,250)); layoutParams = LinearLayout.LayoutParams(match(), match()) }
        root.addView(simpleTop("Поиск", "по юзернейму", "‹") { showHome() })
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); layoutParams = LinearLayout.LayoutParams(match(), match()) }
        val query = input("Юзернейм", false)
        val find = blueButton("Искать")
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(match(), 0, 1f) }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0); layoutParams = FrameLayout.LayoutParams(match(), wrap()) }
        scroll.addView(results); content.addView(query); content.addView(space(10)); content.addView(find); content.addView(scroll); root.addView(content)
        setAnimatedContent(root)
        addInfo(results, "Можно искать по началу юзернейма")
        find.setOnClickListener {
            val q = query.text.toString().trim().lowercase(Locale.ROOT)
            if (q.length < 2) { query.error = "Минимум 2 символа"; return@setOnClickListener }
            results.removeAllViews(); addInfo(results, "Ищем…")
            db.collection("usernames").orderBy("usernameLower").startAt(q).endAt(q + "\uf8ff").limit(40).get()
                .addOnSuccessListener { snap ->
                    results.removeAllViews()
                    val users = snap.documents.mapNotNull { d ->
                        val uid = d.getString("uid") ?: return@mapNotNull null
                        if (uid == me.uid) return@mapNotNull null
                        val username = d.getString("username") ?: d.id
                        UserProfile(uid, username, d.getString("nick") ?: username, d.getString("avatarUrl") ?: defaultAvatar(username), d.getString("status") ?: "в сети", d.getString("role") == "admin", d.getBoolean("banned") ?: false)
                    }
                    if (users.isEmpty()) addEmpty(results, "Никого не нашли", "Попробуйте другой юзернейм")
                    users.forEachIndexed { i, u -> addUserRow(results, u, i) }
                }
                .addOnFailureListener { results.removeAllViews(); addInfo(results, "Ошибка: ${it.localizedMessage}") }
        }
    }

    private fun addUserRow(parent: LinearLayout, user: UserProfile, index: Int) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(panel, dp(18), line, 1); layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply { bottomMargin = dp(8) }; setOnClickListener { openChat(user) } }
        pressEffect(row); row.addView(avatar(user.avatarUrl, user.nick, 56))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f).apply { leftMargin = dp(12) } }
        mid.addView(TextView(this).apply { text = user.nick + if (user.isAdmin) "  ADMIN" else ""; textSize = 17f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(this@MainActivity.text) })
        mid.addView(TextView(this).apply { text = "@${user.username} • ${user.status}"; textSize = 13f; setTextColor(muted); maxLines = 1 })
        row.addView(mid); row.addView(TextView(this).apply { text = "Написать"; setTextColor(telegramBlue); setTypeface(Typeface.DEFAULT, Typeface.BOLD); textSize = 13f })
        parent.addView(row); animateIn(row, index * 35L)
    }

    // Chat
    private fun openChat(other: UserProfile) {
        val me = profile ?: return route()
        screen = Screen.CHAT
        stopUiListeners()
        val chatId = chatId(me.uid, other.uid)
        currentChatId = chatId
        saveChatMetadata(chatId, me, other, null)
        markChatRead(chatId, me.uid)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(telegramBg); layoutParams = LinearLayout.LayoutParams(match(), match()) }
        root.addView(chatTop(other))
        val scroll = ScrollView(this).apply { isFillViewport = true; layoutParams = LinearLayout.LayoutParams(match(), 0, 1f) }
        val messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(12), dp(10), dp(12)); layoutParams = FrameLayout.LayoutParams(match(), wrap()) }
        scroll.addView(messages); root.addView(scroll)
        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(panel); layoutParams = LinearLayout.LayoutParams(match(), wrap()) }
        val input = EditText(this).apply { hint = "Сообщение"; textSize = 16f; maxLines = 4; setTextColor(this@MainActivity.text); setHintTextColor(muted); background = rounded(Color.rgb(241,245,249), dp(22), line, 1); setPadding(dp(16), dp(10), dp(16), dp(10)); imeOptions = EditorInfo.IME_ACTION_SEND; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f).apply { rightMargin = dp(8) } }
        val send = Button(this).apply { text = "➤"; textSize = 20f; setTextColor(Color.WHITE); background = rounded(telegramBlue, dp(22)); layoutParams = LinearLayout.LayoutParams(dp(54), dp(48)) }
        composer.addView(input); composer.addView(send); root.addView(composer); setAnimatedContent(root)
        val sendAction = {
            val value = input.text.toString().trim()
            if (value.isNotBlank()) { input.setText(""); sendPrivateMessage(chatId, other, value, input) }
        }
        send.setOnClickListener { sendAction() }
        input.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_SEND) { sendAction(); true } else false }

        addInfo(messages, "Загрузка сообщений…")
        messagesListener = db.collection("chats").document(chatId).collection("messages").orderBy("createdAt", Query.Direction.ASCENDING).limitToLast(250).addSnapshotListener { snapshot, error ->
            messages.removeAllViews()
            if (error != null) { addInfo(messages, "Ошибка: ${error.localizedMessage}"); return@addSnapshotListener }
            val docs = snapshot?.documents.orEmpty()
            if (docs.isEmpty()) addEmpty(messages, "Диалог пуст", "Напишите первое сообщение")
            docs.forEachIndexed { i, doc ->
                val value = doc.getString("text") ?: return@forEachIndexed
                val uid = doc.getString("senderUid") ?: ""
                val senderNick = doc.getString("senderNick") ?: "Пользователь"
                val senderUsername = doc.getString("senderUsername") ?: "user"
                val readBy = doc.get("readBy") as? List<*> ?: emptyList<Any>()
                addMessage(messages, chatId, doc.id, value, senderNick, senderUsername, uid == me.uid, readBy.contains(other.uid), doc.getTimestamp("createdAt"), i)
            }
            markChatRead(chatId, me.uid)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun chatTop(other: UserProfile): View {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(10), dp(12), dp(10)); background = rounded(telegramBlue, 0); layoutParams = LinearLayout.LayoutParams(match(), dp(86)) }
        top.addView(TextView(this).apply { text = "‹"; textSize = 38f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(dp(42), dp(56)); setOnClickListener { showHome() } })
        top.addView(avatar(other.avatarUrl, other.nick, 52))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f).apply { leftMargin = dp(12) } }
        titles.addView(TextView(this).apply { text = other.nick + if (other.isAdmin) "  ADMIN" else ""; textSize = 18f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(Color.WHITE); maxLines = 1 })
        titles.addView(TextView(this).apply { text = "@${other.username} • ${other.status}"; textSize = 12f; setTextColor(Color.argb(225,255,255,255)); maxLines = 1 })
        top.addView(titles)
        return top
    }

    private fun sendPrivateMessage(chatId: String, other: UserProfile, value: String, input: EditText) {
        val me = profile ?: return
        val chatDoc = db.collection("chats").document(chatId)
        val msg = hashMapOf(
            "text" to value,
            "senderUid" to me.uid,
            "senderUsername" to me.username,
            "senderNick" to me.nick,
            "recipientUid" to other.uid,
            "chatId" to chatId,
            "readBy" to listOf(me.uid),
            "createdAt" to FieldValue.serverTimestamp()
        )
        chatDoc.collection("messages").add(msg)
            .addOnSuccessListener { saveChatMetadata(chatId, me, other, value) }
            .addOnFailureListener { e -> toast("Не отправлено: ${e.localizedMessage}"); input.setText(value); input.setSelection(input.text.length) }
    }

    private fun addMessage(parent: LinearLayout, chatId: String, messageId: String, value: String, nick: String, username: String, mine: Boolean, read: Boolean, timestamp: Timestamp?, index: Int) {
        val row = LinearLayout(this).apply { gravity = if (mine) Gravity.END else Gravity.START; layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply { bottomMargin = dp(7) } }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(7))
            background = rounded(if (mine) outgoing else incoming, dp(18), Color.argb(35,0,0,0), 1)
            layoutParams = LinearLayout.LayoutParams(wrap(), wrap()).apply { leftMargin = if (mine) dp(70) else 0; rightMargin = if (mine) 0 else dp(70) }
            setOnLongClickListener { messageActions(chatId, messageId, value, mine); true }
        }
        if (!mine) bubble.addView(TextView(this).apply { text = "$nick  @$username"; textSize = 12f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(telegramBlue) })
        bubble.addView(TextView(this).apply { text = value; textSize = 16f; setTextColor(this@MainActivity.text); maxWidth = resources.displayMetrics.widthPixels - dp(105) })
        bubble.addView(TextView(this).apply { text = "${timestamp?.toDate()?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(it) } ?: "…"}${if (mine) "   ${if (read) "✓✓" else "✓"}" else ""}"; textSize = 11f; gravity = Gravity.END; setTextColor(if (mine && read) telegramBlue else muted) })
        row.addView(bubble); parent.addView(row); animateIn(row, if (index < 14) index * 18L else 0L)
    }

    private fun messageActions(chatId: String, messageId: String, value: String, mine: Boolean) {
        val isAdmin = profile?.isAdmin == true
        val items = if (isAdmin || mine) arrayOf("Копировать", "Удалить сообщение") else arrayOf("Копировать")
        AlertDialog.Builder(this).setTitle("Сообщение").setItems(items) { _, which ->
            if (items[which].startsWith("Коп")) copyText(value)
            else db.collection("chats").document(chatId).collection("messages").document(messageId).delete().addOnSuccessListener { toast("Удалено") }.addOnFailureListener { toast("Ошибка удаления: ${it.localizedMessage}") }
        }.show()
    }

    private fun markChatRead(chatId: String, uid: String) {
        db.collection("chats").document(chatId).collection("messages").whereEqualTo("recipientUid", uid).limit(50).get().addOnSuccessListener { snap ->
            snap.documents.forEach { it.reference.set(mapOf("readBy" to FieldValue.arrayUnion(uid)), SetOptions.merge()) }
        }
    }

    private fun saveChatMetadata(chatId: String, me: UserProfile, other: UserProfile, last: String?) {
        val data = mutableMapOf<String, Any>(
            "members" to listOf(me.uid, other.uid).sorted(),
            "memberInfo" to mapOf(
                me.uid to mapOf("uid" to me.uid, "username" to me.username, "nick" to me.nick, "avatarUrl" to me.avatarUrl, "status" to me.status, "role" to if (me.isAdmin) "admin" else "user"),
                other.uid to mapOf("uid" to other.uid, "username" to other.username, "nick" to other.nick, "avatarUrl" to other.avatarUrl, "status" to other.status, "role" to if (other.isAdmin) "admin" else "user")
            ),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (last != null) { data["lastMessage"] = last; data["lastSenderUid"] = me.uid }
        db.collection("chats").document(chatId).set(data, SetOptions.merge())
    }

    // Settings/admin
    private fun showSettings() {
        val p = profile ?: return route()
        screen = Screen.SETTINGS
        stopUiListeners()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(245,247,250)); layoutParams = LinearLayout.LayoutParams(match(), match()) }
        root.addView(simpleTop("Профиль", "настройки Vectory", "‹") { showHome() })
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(match(), 0, 1f) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(20)); layoutParams = FrameLayout.LayoutParams(match(), wrap()) }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(18), dp(16), dp(16)); background = rounded(panel, dp(20), line, 1); layoutParams = LinearLayout.LayoutParams(match(), wrap()) }
        card.addView(avatar(p.avatarUrl, p.nick, 92)); card.addView(space(12)); card.addView(label("Юзернейм")); card.addView(readOnly("@${p.username}${if (p.isAdmin) "  • ADMIN" else ""}")); card.addView(space(10)); card.addView(label("Имя")); val nick = input("Имя", false).apply { setText(p.nick) }; card.addView(nick); card.addView(space(10)); card.addView(label("Статус")); val status = input("Статус", false).apply { setText(p.status) }; card.addView(status); card.addView(space(10)); card.addView(label("Ссылка на аватар из интернета")); val avatarUrl = input("https://...jpg/png", false).apply { setText(p.avatarUrl) }; card.addView(avatarUrl); card.addView(space(12)); val save = blueButton("Сохранить"); card.addView(save)
        save.setOnClickListener {
            val newNick = nick.text.toString().trim(); val newStatus = status.text.toString().trim().ifBlank { "в сети" }; val newAvatar = avatarUrl.text.toString().trim().ifBlank { defaultAvatar(p.username) }
            if (newNick.length !in 2..32) { nick.error = "2–32 символа"; return@setOnClickListener }
            val updates = mapOf("nick" to newNick, "status" to newStatus.take(60), "avatarUrl" to newAvatar, "updatedAt" to FieldValue.serverTimestamp())
            val batch = db.batch(); batch.set(db.collection("users").document(p.uid), updates, SetOptions.merge()); batch.set(db.collection("usernames").document(p.username), updates, SetOptions.merge())
            batch.commit().addOnSuccessListener { profile = p.copy(nick = newNick, status = newStatus.take(60), avatarUrl = newAvatar); toast("Сохранено"); showSettings() }.addOnFailureListener { toast("Ошибка: ${it.localizedMessage}") }
        }
        box.addView(card)
        if (p.isAdmin) box.addView(blueButton("🛡 Админ-панель").apply { layoutParams = LinearLayout.LayoutParams(match(), dp(54)).apply { topMargin = dp(12) }; setOnClickListener { showAdminPanel() } })
        box.addView(redButton("Выйти").apply { layoutParams = LinearLayout.LayoutParams(match(), dp(54)).apply { topMargin = dp(12) }; setOnClickListener { setOnline(false); auth.signOut(); profile = null; showLogin() } })
        scroll.addView(box); root.addView(scroll); setAnimatedContent(root)
    }

    private fun showAdminPanel() {
        val p = profile ?: return route()
        if (!p.isAdmin) { toast("Нет прав админа"); return }
        screen = Screen.ADMIN
        stopUiListeners()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(245,247,250)); layoutParams = LinearLayout.LayoutParams(match(), match()) }
        root.addView(simpleTop("Админ-панель", "бан, админы, объявления", "‹") { showHome() })
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(match(), 0, 1f) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(24)); layoutParams = FrameLayout.LayoutParams(match(), wrap()) }
        val ann = input("Текст объявления", false)
        box.addView(cardBox("Глобальное объявление", listOf(ann, blueButton("Опубликовать").apply { setOnClickListener { publishAnnouncement(ann.text.toString()) } }, blueOutlineButton("Очистить").apply { setOnClickListener { publishAnnouncement("") } })))
        val target = input("Юзернейм пользователя", false)
        box.addView(cardBox("Управление пользователем", listOf(target,
            blueButton("Найти/обновить").apply { setOnClickListener { adminFindUser(target.text.toString()) } },
            redButton("Забанить").apply { setOnClickListener { adminSetBan(target.text.toString(), true) } },
            blueOutlineButton("Разбанить").apply { setOnClickListener { adminSetBan(target.text.toString(), false) } },
            blueButton("Выдать админа").apply { setOnClickListener { adminSetAdmin(target.text.toString(), true) } },
            blueOutlineButton("Снять админа").apply { setOnClickListener { adminSetAdmin(target.text.toString(), false) } }
        )))
        scroll.addView(box); root.addView(scroll); setAnimatedContent(root)
    }

    private fun publishAnnouncement(value: String) {
        db.collection("announcements").document("current").set(mapOf("text" to value, "updatedBy" to (profile?.uid ?: ""), "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).addOnSuccessListener { toast("Готово") }.addOnFailureListener { toast("Ошибка: ${it.localizedMessage}") }
    }

    private fun adminFindUser(usernameRaw: String) {
        val username = usernameRaw.trim().lowercase(Locale.ROOT)
        if (!validUsername(username)) { toast("Введите юзернейм"); return }
        db.collection("usernames").document(username).get().addOnSuccessListener { d ->
            if (!d.exists()) toast("Пользователь не найден") else toast("${d.getString("nick")} @${d.getString("username")} role=${d.getString("role") ?: "user"} banned=${d.getBoolean("banned") ?: false}")
        }
    }

    private fun adminSetBan(usernameRaw: String, banned: Boolean) {
        adminGetUser(usernameRaw) { uid, username ->
            val data = mapOf("banned" to banned, "updatedAt" to FieldValue.serverTimestamp())
            db.collection("users").document(uid).set(data, SetOptions.merge())
            db.collection("usernames").document(username).set(data, SetOptions.merge()).addOnSuccessListener { toast(if (banned) "Пользователь забанен" else "Пользователь разбанен") }
        }
    }

    private fun adminSetAdmin(usernameRaw: String, makeAdmin: Boolean) {
        adminGetUser(usernameRaw) { uid, username ->
            val role = if (makeAdmin) "admin" else "user"
            val data = mapOf("role" to role, "updatedAt" to FieldValue.serverTimestamp())
            db.collection("users").document(uid).set(data, SetOptions.merge())
            db.collection("usernames").document(username).set(data, SetOptions.merge())
            if (makeAdmin) db.collection("admins").document(uid).set(mapOf("uid" to uid, "username" to username, "role" to "admin", "createdAt" to FieldValue.serverTimestamp()), SetOptions.merge()).addOnSuccessListener { toast("Админ выдан") }
            else db.collection("admins").document(uid).delete().addOnSuccessListener { toast("Админ снят") }
        }
    }

    private fun adminGetUser(usernameRaw: String, done: (String, String) -> Unit) {
        val username = usernameRaw.trim().lowercase(Locale.ROOT)
        if (!validUsername(username)) { toast("Введите юзернейм"); return }
        db.collection("usernames").document(username).get().addOnSuccessListener { d ->
            val uid = d.getString("uid")
            if (uid == null) toast("Пользователь не найден") else done(uid, username)
        }.addOnFailureListener { toast("Ошибка: ${it.localizedMessage}") }
    }

    private fun cardBox(title: String, views: List<View>): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); background = rounded(panel, dp(20), line, 1); layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply { bottomMargin = dp(12) } }
        c.addView(TextView(this).apply { text = title; textSize = 18f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(this@MainActivity.text); setPadding(0, 0, 0, dp(10)) })
        views.forEachIndexed { i, v -> c.addView(v); if (i != views.lastIndex) c.addView(space(9)) }
        return c
    }

    // UI helpers
    private fun authLayout(title: String, sub: String, views: List<View>): View {
        val root = FrameLayout(this).apply { background = gradient(Color.rgb(18, 157, 217), Color.rgb(42, 171, 238)); layoutParams = FrameLayout.LayoutParams(match(), match()) }
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(22), dp(24), dp(22), dp(24)); layoutParams = FrameLayout.LayoutParams(match(), match()) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(24), dp(22), dp(22)); background = rounded(panel, dp(28)); layoutParams = LinearLayout.LayoutParams(match(), wrap()) }
        box.addView(TextView(this).apply { text = "V"; textSize = 34f; gravity = Gravity.CENTER; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(Color.WHITE); background = rounded(telegramBlue, dp(22)); layoutParams = LinearLayout.LayoutParams(dp(70), dp(70)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) } })
        box.addView(TextView(this).apply { text = title; textSize = 29f; gravity = Gravity.CENTER; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(this@MainActivity.text) })
        box.addView(TextView(this).apply { text = sub; textSize = 14f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(8), dp(7), dp(8), dp(18)) })
        views.forEachIndexed { i, v -> box.addView(v); if (i != views.lastIndex) box.addView(space(11)) }
        holder.addView(box); root.addView(holder); return root
    }

    private fun topBar(title: String, sub: String, avatarUrl: String, actionText: String, action: () -> Unit): View {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(12), dp(12)); background = rounded(telegramBlue, 0); layoutParams = LinearLayout.LayoutParams(match(), dp(96)) }
        top.addView(avatar(avatarUrl, title, 58))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f).apply { leftMargin = dp(12) } }
        texts.addView(TextView(this).apply { text = title; textSize = 24f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(Color.WHITE) })
        texts.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(Color.argb(225,255,255,255)); maxLines = 1 })
        top.addView(texts)
        top.addView(TextView(this).apply { text = actionText; textSize = 24f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = rounded(Color.argb(45,255,255,255), dp(18)); layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)); setOnClickListener { action() } })
        return top
    }

    private fun simpleTop(title: String, sub: String, actionText: String, action: () -> Unit): View {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(10), dp(14), dp(10)); background = rounded(telegramBlue, 0); layoutParams = LinearLayout.LayoutParams(match(), dp(86)) }
        top.addView(TextView(this).apply { text = actionText; textSize = 38f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(dp(48), dp(56)); setOnClickListener { action() } })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f) }
        texts.addView(TextView(this).apply { text = title; textSize = 22f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(Color.WHITE) })
        texts.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(Color.argb(225,255,255,255)) })
        top.addView(texts); return top
    }

    private fun loadingView(value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = gradient(telegramBlue, Color.rgb(20, 120, 180)); layoutParams = LinearLayout.LayoutParams(match(), match())
        addView(TextView(this@MainActivity).apply { text = "Vectory"; textSize = 34f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        addView(TextView(this@MainActivity).apply { text = value; textSize = 15f; setTextColor(Color.argb(230,255,255,255)); setPadding(0, dp(8), 0, 0) })
    }

    private fun avatar(url: String, name: String, sizeDp: Int): FrameLayout {
        val frame = FrameLayout(this).apply { background = rounded(Color.rgb(208, 232, 245), dp(sizeDp / 2)); layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)) }
        val fallback = TextView(this).apply { text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "V"; textSize = sizeDp / 2.5f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(telegramBlue); layoutParams = FrameLayout.LayoutParams(match(), match()) }
        frame.addView(fallback)
        if (url.isNotBlank()) {
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = FrameLayout.LayoutParams(match(), match()) }
            frame.addView(img); loadImage(url, img)
        }
        return frame
    }

    private fun loadImage(url: String, img: ImageView) {
        imageCache[url]?.let { img.setImageBitmap(it); return }
        Thread {
            try {
                val bmp = URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                if (bmp != null) { imageCache[url] = bmp; runOnUiThread { img.setImageBitmap(bmp) } }
            } catch (_: Exception) {}
        }.start()
    }

    private fun input(hint: String, password: Boolean): EditText = EditText(this).apply { this.hint = hint; textSize = 16f; setSingleLine(!password); setTextColor(this@MainActivity.text); setHintTextColor(muted); background = rounded(Color.rgb(248,250,252), dp(16), line, 1); setPadding(dp(14), dp(11), dp(14), dp(11)); inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; layoutParams = LinearLayout.LayoutParams(match(), wrap()) }
    private fun blueButton(label: String): Button = Button(this).apply { text = label; textSize = 15f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(Color.WHITE); isAllCaps = false; background = rounded(telegramBlue, dp(16)); layoutParams = LinearLayout.LayoutParams(match(), dp(52)); pressEffect(this) }
    private fun blueOutlineButton(label: String): Button = Button(this).apply { text = label; textSize = 15f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(telegramBlue); isAllCaps = false; background = rounded(Color.WHITE, dp(16), telegramBlue, 1); layoutParams = LinearLayout.LayoutParams(match(), dp(52)); pressEffect(this) }
    private fun redButton(label: String): Button = Button(this).apply { text = label; textSize = 15f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(Color.WHITE); isAllCaps = false; background = rounded(danger, dp(16)); layoutParams = LinearLayout.LayoutParams(match(), dp(52)); pressEffect(this) }
    private fun linkText(label: String): TextView = TextView(this).apply { text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(telegramBlue); setPadding(0, dp(8), 0, dp(4)); layoutParams = LinearLayout.LayoutParams(match(), wrap()) }
    private fun label(v: String): TextView = TextView(this).apply { text = v; textSize = 13f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(muted); setPadding(dp(4), 0, 0, dp(5)); layoutParams = LinearLayout.LayoutParams(match(), wrap()) }
    private fun readOnly(v: String): TextView = TextView(this).apply { text = v; textSize = 16f; setTextColor(this@MainActivity.text); setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(Color.rgb(248,250,252), dp(16), line, 1); layoutParams = LinearLayout.LayoutParams(match(), wrap()) }
    private fun addInfo(parent: LinearLayout, value: String) { val v = TextView(this).apply { text = value; textSize = 14f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(panel, dp(16), line, 1); layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply { bottomMargin = dp(8) } }; parent.addView(v); animateIn(v, 0) }
    private fun addEmpty(parent: LinearLayout, title: String, sub: String) { val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(18), dp(28), dp(18), dp(28)); background = rounded(panel, dp(20), line, 1); layoutParams = LinearLayout.LayoutParams(match(), wrap()) }; c.addView(TextView(this).apply { text = title; textSize = 20f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(this@MainActivity.text); gravity = Gravity.CENTER }); c.addView(TextView(this).apply { text = sub; textSize = 14f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), 0) }); parent.addView(c); animateIn(c, 0) }
    private fun space(h: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun rounded(color: Int, radius: Int, stroke: Int? = null, strokeDp: Int = 0): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = radius.toFloat(); if (stroke != null && strokeDp > 0) setStroke(dp(strokeDp), stroke) }
    private fun gradient(vararg colors: Int): GradientDrawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
    private fun setAnimatedContent(v: View) { setContentView(v); animateIn(v, 0) }
    private fun animateIn(v: View, delay: Long) { v.alpha = 0f; v.translationY = dp(12).toFloat(); v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(260).setInterpolator(DecelerateInterpolator()).start() }
    private fun pressEffect(v: View) { v.setOnTouchListener { view, ev -> when (ev.action) { MotionEvent.ACTION_DOWN -> view.animate().scaleX(.97f).scaleY(.97f).setDuration(70).start(); MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(110).start() }; false } }
    private fun setBusy(b: Button, busy: Boolean, label: String) { b.isEnabled = !busy; b.alpha = if (busy) .65f else 1f; b.text = label }

    // Data/helpers
    private fun chatPreviewFromDoc(id: String, data: Map<String, Any>, myUid: String): ChatPreview? {
        val members = data["members"] as? List<*> ?: return null
        val otherUid = members.mapNotNull { it as? String }.firstOrNull { it != myUid } ?: return null
        val info = data["memberInfo"] as? Map<*, *>; val other = info?.get(otherUid) as? Map<*, *>
        val username = other?.get("username") as? String ?: "user"; val nick = other?.get("nick") as? String ?: username
        return ChatPreview(id, otherUid, username, nick, other?.get("avatarUrl") as? String ?: defaultAvatar(username), other?.get("status") as? String ?: "в сети", other?.get("role") as? String ?: "user", data["lastMessage"] as? String ?: "", data["lastSenderUid"] as? String ?: "", (data["updatedAt"] as? Timestamp)?.toDate()?.time ?: 0L)
    }

    private fun defaultAvatar(seed: String): String = "https://robohash.org/${url(seed)}.png?size=160x160&set=set4"
    private fun url(v: String): String = URLEncoder.encode(v, "UTF-8")
    private fun copyText(v: String) { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Vectory message", v)); toast("Скопировано") }
    private fun chatId(a: String, b: String): String = listOf(a, b).sorted().joinToString("_")
    private fun validUsername(name: String): Boolean = Regex("^[a-z0-9_.]{3,24}$").matches(name)
    private fun usernameToEmail(username: String): String = "${username.trim().lowercase(Locale.ROOT)}@mass.local"
    private fun friendlyAuthError(e: Exception): String { val m = e.localizedMessage ?: return "проверьте данные"; return when { m.contains("already", true) -> "юзернейм уже занят"; m.contains("password", true) -> "неверный пароль"; m.contains("no user", true) -> "пользователь не найден"; else -> m } }
    private fun requestNotificationPermissionIfNeeded() { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7002) }
    private fun updateFcmToken() { val p = profile ?: return; FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> db.collection("users").document(p.uid).set(mapOf("fcmToken" to token, "tokenUpdatedAt" to FieldValue.serverTimestamp(), "platform" to "android"), SetOptions.merge()) } }
    private fun setOnline(online: Boolean) { val p = profile ?: return; db.collection("users").document(p.uid).set(mapOf("online" to online, "lastSeen" to FieldValue.serverTimestamp()), SetOptions.merge()) }
    private fun stopUiListeners() { chatListListener?.remove(); messagesListener?.remove(); chatListListener = null; messagesListener = null }
    private fun toast(v: String) = Toast.makeText(this, v, Toast.LENGTH_LONG).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + .5f).toInt()
    private fun match(): Int = ViewGroup.LayoutParams.MATCH_PARENT
    private fun wrap(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    private data class UserProfile(val uid: String, val username: String, val nick: String, val avatarUrl: String, val status: String, val isAdmin: Boolean, val banned: Boolean)
    private data class ChatPreview(val id: String, val otherUid: String, val otherUsername: String, val otherNick: String, val otherAvatarUrl: String, val otherStatus: String, val otherRole: String, val lastMessage: String, val lastSenderUid: String, val updatedAtMillis: Long)
    private enum class Screen { AUTH, LOADING, HOME, SEARCH, CHAT, SETTINGS, ADMIN }
}
