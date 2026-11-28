package app.floatphone.shell

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
// RemoteInput 必须用 androidx 版：NotificationCompat.Action.addRemoteInput()
// 收的是 androidx.core.app.RemoteInput，与框架的 android.app.RemoteInput
// 是两个类型（同名不同源），混用会直接编译失败。
import androidx.core.app.RemoteInput
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 推送前台服务：不依赖 Google 服务的自建长连接。
 *
 * 原理：用 WebView 里已登录的站点 Cookie 调用站点接口拿到
 * Supabase 地址 / anon key / 当前用户 id，然后用 OkHttp WebSocket
 * 直连 Supabase Realtime，订阅个人频道 shellpush:<userId>。
 * 服务端（push-generate / 测试按钮）发离线消息时会向该频道广播一份，
 * 本服务收到即弹系统通知——App 被杀也能收（前台服务存活期间）。
 */
class PushService : Service() {

    companion object {
        private const val CH_KEEPALIVE = "shell_keepalive"
        private const val CH_MESSAGES = "shell_messages"
        private const val CH_CALLS = "shell_calls"
        private const val NOTIF_FG_ID = 1
        private const val PREFS = "shell_push_cache"
        private const val KEY_URL = "supabase_url"
        private const val KEY_ANON = "anon_key"
        private const val KEY_USER = "user_id"
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L
        const val WATCHDOG_ACTION = "app.floatphone.shell.PUSH_WATCHDOG"
        private var running = false

        /**
         * 壳内推送诊断轨迹（环形，最多 40 条）。
         *
         * 为什么要有：推送失败时用户能看到的只有通知栏那句「未登录或站点不可达」，
         * 而真正的原因（/api/auth/me 被拦截、online config 未配置、join 被 RLS 拒、
         * 广播送达但解析失败……）全被 mem 吞掉了。抓不到日志就没法排查——过去只能
         * 靠反复重装 APK 猜。轨迹只写本进程内存，不含 Cookie / key / 用户内容。
         * 网页侧经 AndroidShell.getPushDiagnostics() 读出并展示在「推送诊断」面板。
         */
        private val trace = ArrayDeque<String>()
        private const val TRACE_LIMIT = 40

        fun log(line: String) {
            synchronized(trace) {
                trace.addLast("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $line")
                while (trace.size > TRACE_LIMIT) trace.removeFirst()
            }
        }

        /** 诊断快照：连接状态 + 站点/频道/订阅注册情况 + 最近轨迹。 */
        fun diagnosticsJson(): String = runCatching {
            synchronized(trace) {
                val obj = JSONObject()
                obj.put("running", running)
                obj.put("state", lastState)
                obj.put("siteUrl", MainActivity.SITE_URL)
                obj.put("channel", lastChannel.orEmpty())
                obj.put("config", lastConfigNote.orEmpty())
                obj.put("shellSub", lastShellSubNote.orEmpty())
                obj.put("broadcasts", broadcastCount)
                obj.put("reconnects", reconnectNonce)
                obj.put("notificationGranted", notificationGranted)
                obj.put("lastBroadcastAt", lastBroadcastAt)
                // 关键：一次广播都没收到 ≠ 频道没连上。两者混在一起时，用户看到
                // 「已连接，等待角色消息」会以为通了，实际是发送端根本没往这个频道投。
                obj.put("verdict", when {
                    !running -> "推送服务未运行（可能被系统杀掉，或被电池策略拦住）"
                    broadcastCount > 0 -> "已收到过广播，链路通；若仍无通知请看通知权限"
                    lastState.startsWith("频道已订阅") -> "频道已订阅，但没有收到任何广播——问题在发送端（服务器/订阅注册/频道名）"
                    else -> lastState
                })
                val arr = JSONArray()
                for (line in trace) arr.put(line)
                obj.put("trace", arr)
                obj.toString()
            }
        }.getOrDefault("{}")

        private var lastState = "未启动"
        private var lastChannel: String? = null
        private var lastConfigNote = ""
        private var lastShellSubNote = ""
        @Volatile private var broadcastCount = 0
        @Volatile private var lastBroadcastAt = ""

        /**
         * 通知总开关状态。Android 13+ 用户可以在系统里单独关掉本 App 的通知，
         * 那时上面所有环节都成功、广播也收到了，通知栏却什么都不会出现——
         * 这条状态必须进诊断，否则又会绕回"看不出为什么"。
         */
        private fun isNotificationAllowed(context: Context): Boolean = runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < 26 || nm.getNotificationChannel(CH_MESSAGES)?.importance != NotificationManager.IMPORTANCE_NONE)
        }.getOrDefault(false)

        private var notificationGranted = false

        fun start(context: Context) {
            if (running) return
            val intent = Intent(context, PushService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        /**
         * 让现存的长连接立刻重来一次（用户点了「重连」、或刚改完账号/网络时）。
         *
         * 只取消当前 socket、不清退避计数：外层 connectionLoop 会把「断开」
         * 当成一次普通异常关闭，先睡几秒再取配置重连——比等 60 秒重试窗口快得多。
         */
    fun requestReconnect() {
        reconnectNonce += 1
        log("用户请求重连")
        // 没有存活连接时会话可能正卡在 60 秒重试窗口里（sleep 中），
        // 光 cancel 一个 null socket 什么也不会发生：打断退避睡眠等待，
        // 让外层循环立即回到「取配置 → 连接」。
        activeSocket?.cancel()
        activeLoop?.interrupt()
    }

    /** 重连计数：仅用于日志与诊断面板展示（每次 requestReconnect 自增）。 */
    @Volatile private var reconnectNonce = 0

    /** 当前长连接引用，供 requestReconnect() 主动打断。 */
    @Volatile private var activeSocket: WebSocket? = null

    /** 连接循环线程引用，供 requestReconnect() 打断退避睡眠。 */
    @Volatile private var activeLoop: Thread? = null

    /**
     * 看门狗：厂商清理后台后，START_STICKY 不一定会把服务拉回来。
     * 每 15 分钟给自己发一次精确闹钟，收到后若服务已死就重新 startForegroundService，
     * 然后继续排下一次。精确闹钟会触发系统的短时允许名单，后台启动前台服务才不会被拦。
     */
    fun scheduleWatchdog(context: Context) {
        runCatching {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, PushWatchdogReceiver::class.java).setAction(WATCHDOG_ACTION)
            val pending = PendingIntent.getBroadcast(
                context, 0x5055, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
            if (exactAllowed) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    }

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** 拉取远程头像用；超时压短，拿不到就退回默认图标，绝不能拖住通知。 */
    private val avatarClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var stopped = false
    private var msgSeq = 2
    private var notifId = 100
    private var shellSubRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannels()
        notificationGranted = isNotificationAllowed(this)
        log("服务启动 site=${MainActivity.SITE_URL} 通知=${if (notificationGranted) "可用" else "被关闭"}")
        if (!notificationGranted) log("⚠ 通知权限或「角色消息」渠道被系统关闭，广播到了也不会显示")
        startForeground(NOTIF_FG_ID, buildKeepAliveNotification("等待连接…"))
scheduleWatchdog(this)
        val loop = thread(name = "shell-push-loop") { connectionLoop() }
        activeLoop = loop
        // 顺手开诊断端口（仅 127.0.0.1）：手机浏览器打开 http://127.0.0.1:8787/
        // 就能看到本服务的完整轨迹，不必依赖 adb / chrome://inspect。
        PushDebugServer.start(this)

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopped = true
        running = false
        socket?.cancel()
        super.onDestroy()
    }

    // ── 连接循环：拿配置 → 连 WS → 断线退避重连 ──
    private fun connectionLoop() {
        var backoffSec = 5L
        while (!stopped) {
            val config = fetchConfig()
            if (config == null) {
                val reason = lastConfigNote.ifEmpty { "未知原因" }
                log("取配置失败：$reason")
                updateKeepAlive("未登录或站点不可达，稍后重试（$reason）")
                sleepSec(60); continue
            }
            updateKeepAlive("已连接，等待角色消息")
            val closedNormally = runSocket(config)
            if (stopped) break
            updateKeepAlive("连接断开，重连中…")
            sleepSec(if (closedNormally) 3 else backoffSec)
            backoffSec = (backoffSec * 2).coerceAtMost(120)
            if (closedNormally) backoffSec = 5
        }
    }

    private data class PushConfig(val supabaseUrl: String, val anonKey: String, val userId: String)

    /** 借 WebView 的登录 Cookie 调站点接口获取连接参数。 */
    private fun fetchConfig(): PushConfig? = runCatching {
        // 单机模式（NEXT_PUBLIC_SELF_HOSTED_MODE=true）不走登录流程，WebView 里
        // 可能完全没有 Cookie。此前这里 `?: return null` 把这种正常情况直接判成
        // 「未登录」，壳于是永远拿不到配置、常驻通知停在「未登录或站点不可达」。
        // 改为：Cookie 缺失也照常请求，放不放行交给服务端裁决。
        val cookie = CookieManager.getInstance().getCookie(MainActivity.SITE_URL).orEmpty()

        /** 每一步失败都写进 lastConfigNote（会出现在常驻通知与诊断面板里），不再静默返回 null。 */
        fun getJson(path: String, step: String): JSONObject? {
            val builder = Request.Builder()
                .url("${MainActivity.SITE_URL}$path")
                .header("Accept", "application/json")
            // 空 Cookie 头可能被服务端/WAF 拒绝，为空时干脆不带这个头。
            if (cookie.isNotEmpty()) builder.header("Cookie", cookie)
            val request = builder.build()
            return try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        lastConfigNote = "$step HTTP ${response.code}"
                        log("$step 失败 HTTP ${response.code} ${text.take(120)}")
                        return null
                    }
                    JSONObject(text)
                }
            } catch (err: Throwable) {
                // VPN / 规则模式把站点域名走了直连时，这里就是 connection refused / timeout
                lastConfigNote = "$step 连接异常 ${err.javaClass.simpleName}"
                log("$step 连接异常：${(err.message ?: err.javaClass.simpleName).take(160)}")
                null
            }
        }

log("取配置: cookie=${if (cookie.isEmpty()) "空" else "有"}")
        val me = getJson("/api/auth/me", "auth/me") ?: return@runCatching cachedConfig()
        val userId = me.optJSONObject("account")?.optString("id").orEmpty()
        if (userId.isEmpty()) {
            lastConfigNote = "auth/me 未返回账号（站点未登录）"
            log("auth/me 返回 account=null")
            return@runCatching null
        }
        log("账号 id=$userId")
        val online = getJson("/api/online/config", "online/config") ?: return@runCatching cachedConfig()
        if (!online.optBoolean("configured")) {
            lastConfigNote = "站点未配置 Supabase（online/config configured=false）"
            log("online/config configured=false")
            return@runCatching null
        }
        val url = online.optString("supabaseUrl")
        val key = online.optString("anonKey")
        if (url.isEmpty() || key.isEmpty()) {
            lastConfigNote = "online/config 缺 supabaseUrl/anonKey"
            log("online/config 字段为空")
            return@runCatching null
        }
        log("supabase=${url.substringAfter("//").substringBefore("/")}")
        lastConfigNote = ""
        val config = PushConfig(url.trimEnd('/'), key, userId)
        cacheConfig(config)

        registerShellSubscription(cookie, userId)
        config
    }.getOrNull()

    /** 站点短暂不可达时用上次成功的连接参数续命，避免长连接跟着站点一起断。 */
    private fun cachedConfig(): PushConfig? = runCatching {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val url = prefs.getString(KEY_URL, "").orEmpty()
        val key = prefs.getString(KEY_ANON, "").orEmpty()
        val userId = prefs.getString(KEY_USER, "").orEmpty()
        if (url.isEmpty() || key.isEmpty() || userId.isEmpty()) null
        else PushConfig(url, key, userId)
    }.getOrNull()

    private fun cacheConfig(config: PushConfig) {
        runCatching {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_URL, config.supabaseUrl)
                .putString(KEY_ANON, config.anonKey)
                .putString(KEY_USER, config.userId)
                .apply()
        }
    }

    /**
     * 在站点注册一条合成推送订阅（endpoint = shell:<userId>）。
     * 作用是让离线消息排期的"账号已订阅"门控放行，并让服务端知道
     * 要往 shellpush 频道广播；服务端不会对它做 Web Push 投递。
     */
    private fun registerShellSubscription(cookie: String, userId: String) {
        if (shellSubRegistered) return
        runCatching {
            val body = JSONObject()
                .put("endpoint", "shell:$userId")
                .put(
                    "keys",
                    JSONObject().put("p256dh", "shell").put("auth", "shell"),
                )
                .toString()
                .toRequestBody("application/json".toMediaType())
            val builder = Request.Builder()
                .url("${MainActivity.SITE_URL}/api/push/subscribe")
                .post(body)
            if (cookie.isNotEmpty()) builder.header("Cookie", cookie)
            val request = builder.build()
            client.newCall(request).execute().use { response ->
if (response.isSuccessful) {
            shellSubRegistered = true
            shellSubRegisteredUserId = userId
            lastShellSubNote = "已注册 shell:${userId}"
            log("订阅注册成功 shell:${userId}")
        } else {
            val text = response.body?.string().orEmpty()
            lastShellSubNote = "注册失败 HTTP ${response.code} ${text.take(80)}"
            log("订阅注册失败 HTTP ${response.code}: ${text.take(120)}")
        }

            }
        }.onFailure {
            lastShellSubNote = "注册异常 ${it.javaClass.simpleName}"
            log("订阅注册异常：${(it.message ?: "").take(120)}")
        }
    }

    /** 跑一条 WebSocket 直到断开；返回是否属于正常关闭。 */
    private fun runSocket(config: PushConfig): Boolean {
        val wsUrl = config.supabaseUrl.replaceFirst("http", "ws") +
            "/realtime/v1/websocket?apikey=${config.anonKey}&vsn=1.0.0"
        val topic = "realtime:shellpush:${config.userId}"
        lastChannel = "shellpush:${config.userId}"
        // 日志里抹掉 anon key（它虽然是公开密钥，但没必要出现在诊断面板里）
        log("连接 " + wsUrl.replace(config.anonKey, "anonKey"))
        val lock = Object()
        var normal = false
        var done = false

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val join = JSONObject()
                    .put("topic", topic)
                    .put("event", "phx_join")
                    .put("ref", "1")
                    .put(
                        "payload",
                        JSONObject().put(
                            "config",
                            JSONObject()
                                .put("broadcast", JSONObject().put("self", false))
                                .put("presence", JSONObject().put("key", "")),
                        ),
                    )
                webSocket.send(join.toString())
                // Phoenix 心跳（OkHttp pingInterval 是 TCP 层，这里是协议层）
                thread(name = "shell-push-heartbeat") {
                    while (!done && !stopped) {
                        sleepSec(25)
                        if (done || stopped) break
                        runCatching {
                            webSocket.send(
                                JSONObject()
                                    .put("topic", "phoenix")
                                    .put("event", "heartbeat")
                                    .put("payload", JSONObject())
                                    .put("ref", (msgSeq++).toString())
                                    .toString(),
                            )
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val msg = JSONObject(text)

                    // Supabase Realtime 的 phx_join 成功回执才代表频道真正订阅成功；
                    // WebSocket onOpen 仅表示 TCP/WebSocket 建立，不能提前报「已连接」。
                    if (msg.optString("event") == "phx_reply" && msg.optString("ref") == "1") {
                        val reply = msg.optJSONObject("payload")
                        if (reply?.optString("status") == "ok") {
                            lastState = "频道已订阅（等待广播）"
                            log("join ok topic=$topic")
                            updateKeepAlive("已连接，等待角色消息")
                        } else {
                            val reason = reply?.optJSONObject("response")?.optString("reason").orEmpty()
                            lastState = "频道订阅失败：$reason"
                            log("join 失败 reason=$reason raw=${text.take(200)}")
                            updateKeepAlive(if (reason.isNotEmpty()) "频道订阅失败：$reason" else "频道订阅失败，稍后重试")
                            // 加入频道失败就结束本次连接，让外层退避后重新取配置并重连。
                            synchronized(lock) { done = true; lock.notifyAll() }
                            webSocket.close(1000, "channel join failed")
                        }
                        return
                    }
                    // 非本频道的系统消息（presence_state/postgres_changes 等）在这里全部丢弃；
                    // 但 join 回执与 broadcast 之外的一切都在诊断里留一行，方便发现协议变动。
                    if (msg.optString("event") != "broadcast") {
                        val ev = msg.optString("event")
                        if (ev != "phx_reply" && ev != "presence_state" && ev != "presence_diff") {
                            log("收到事件 $ev topic=${msg.optString("topic")}")
                        }
                        return
                    }
                    broadcastCount += 1
                    lastBroadcastAt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                    log("收到广播 raw=${text.take(220)}")
                    val payload = msg.optJSONObject("payload") ?: run {
                        log("广播缺 payload 节点")
                        return
                    }
                    if (payload.optString("event") != "notify") {
                        log("广播 event=${payload.optString("event")}（不是 notify，已忽略）")
                        return
                    }
                    val body = payload.optJSONObject("payload") ?: run {
                        log("广播 payload 为空")
                        return
                    }

                    val title = body.optString("title").ifEmpty { "小手机" }
                    val text2 = body.optString("body").ifEmpty { "有新消息" }
                    val bridgeEventId = body.optString("bridgeEventId").trim()
                    // 来电：全屏来电通知（任何一步失败回落普通通知，主路不受影响）
                    if (body.optString("kind") == "call") {
                        val shown = runCatching {
                            showIncomingCallNotification(
                                body.optString("characterName").ifEmpty { title },
                                body.optString("sessionId"),
                                body.optLong("callTs", System.currentTimeMillis()),
                            )
                        }.isSuccess
                        if (shown) {
                            if (bridgeEventId.isNotEmpty()) sendBridgeReceipt(bridgeEventId)
                            return
                        }
                    }
val notificationPosted = runCatching {
            showMessageNotification(
                title, text2,
                body.optString("sessionId"),
                body.optString("avatar"),
                body.optString("kind"),
                body.optString("sceneId"),
            )
        }.getOrDefault(false)
        // 通知没发出去时只剩一条冷冰冰的轨迹——这恰恰是最需要人看见的一步。
        if (notificationPosted) {
            log("已发系统通知: title=$title / $text2")
            if (bridgeEventId.isNotEmpty()) sendBridgeReceipt(bridgeEventId)
        } else {
            log("系统通知发送失败（权限/渠道被关？）")
        }

                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log("WS 关闭 code=$code reason=$reason")
                lastState = "连接被关闭 code=$code"
                normal = true
                synchronized(lock) { done = true; lock.notifyAll() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = t.message?.take(160) ?: t.javaClass.simpleName
                lastState = "连接失败：$detail"
                log("WS 连接失败 ${t.javaClass.simpleName}: $detail http=${response?.code ?: 0}")
                synchronized(lock) { done = true; lock.notifyAll() }
            }
        }

        socket = client.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            listener,
        ).also { activeSocket = it }
        synchronized(lock) {
            while (!done && !stopped) runCatching { lock.wait(30_000) }
        }
        socket?.cancel()
        socket = null
        activeSocket = null
        return normal
    }

    /** 告知现实桥：这条事件通知已由 Android NotificationManager 接受提交。 */
    private fun sendBridgeReceipt(eventId: String) {
        thread(name = "bridge-notification-receipt") {
            runCatching {
                val cookie = CookieManager.getInstance().getCookie(MainActivity.SITE_URL).orEmpty()
                val body = JSONObject()
                    .put("eventId", eventId)
                    .put("status", "posted")
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val builder = Request.Builder()
                    .url("${MainActivity.SITE_URL.trimEnd('/')}/api/push/bridge-native-event/receipt")
                    .post(body)
                    .header("Accept", "application/json")
                if (cookie.isNotEmpty()) builder.header("Cookie", cookie)
                avatarClient.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) android.util.Log.w("FloatPush", "bridge receipt rejected: ${response.code}")
                }
            }.onFailure { android.util.Log.w("FloatPush", "bridge receipt failed", it) }
        }
    }

    // ── 通知 ──
    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CH_KEEPALIVE, "后台连接", NotificationManager.IMPORTANCE_MIN).apply {
                description = "维持角色消息接收通道（可在此关闭常驻通知的显示）"
                        setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "角色消息", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "角色发来的离线消息"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_CALLS, "角色来电", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "角色打来的语音电话（只振动，不响铃）"
                setSound(null, null)
                enableVibration(false) // 振动由 CallAlert 循环控制，渠道自带的一次性振动关掉
            },
        )
    }

    /**
     * 点通知的落点：带 sessionId 时经深链直达对应会话，否则回首页。
     * 深链与 MainActivity 共用 EXTRA_OPEN_URL 通道。
     */
    private fun contentIntent(sessionId: String? = null, lifeScene: Boolean = false, sceneId: String? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val id = sessionId?.trim().orEmpty()
        val targetUrl = if (lifeScene) {
            // Life-scene push taps must return to the living-world screen, not the
            // normal chat session used only to build the offline model snapshot.
            "${MainActivity.SITE_URL.trimEnd('/')}/life"
        } else if (id.isNotEmpty()) {
            "${MainActivity.SITE_URL.trimEnd('/')}/#open-chat=${android.net.Uri.encode(id)}"
        } else {
            ""
        }
        if (targetUrl.isNotEmpty()) intent.putExtra(MainActivity.EXTRA_OPEN_URL, targetUrl)
        val requestCode = if (lifeScene) ("life:" + sceneId.orEmpty()).hashCode() else id.hashCode()
        return PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildKeepAliveNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("小手机")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun updateKeepAlive(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_FG_ID, buildKeepAliveNotification(text))
    }

    /**
     * 全屏来电通知：锁屏/熄屏直接弹 IncomingCallActivity，亮屏时是带
     * 接听/拒接按钮的 heads-up。振动循环 + 55s 超时未接由 CallAlert 管。
     */
    private fun showIncomingCallNotification(characterName: String, sessionId: String, callTs: Long) {
        val fullScreen = Intent(this, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IncomingCallActivity.EXTRA_SESSION_ID, sessionId)
            putExtra(IncomingCallActivity.EXTRA_CHARACTER_NAME, characterName)
            putExtra(IncomingCallActivity.EXTRA_CALL_TS, callTs)
        }
        val fullScreenPending = PendingIntent.getActivity(
            this, 60, fullScreen,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun buildAction(actionName: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            this, code,
            Intent(this, CallActionReceiver::class.java).apply {
                action = actionName
                putExtra(CallActionReceiver.EXTRA_SESSION_ID, sessionId)
                putExtra(CallActionReceiver.EXTRA_CALL_TS, callTs)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(characterName)
            .setContentText("语音来电…")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .addAction(0, "拒接", buildAction(CallActionReceiver.ACTION_DECLINE, 61))
            .addAction(0, "接听", buildAction(CallActionReceiver.ACTION_ANSWER, 62))
            .build()
        getSystemService(NotificationManager::class.java).notify(CallAlert.NOTIF_CALL_ID, notification)
        CallAlert.start(this, sessionId, characterName) {
            // 超时未接：收场 + 换一条"未接来电"普通通知（正文消息本来就会进聊天）
            CallAlert.stop(this)
            runCatching { showMissedCallNotification(characterName) }
        }
    }

    private fun showMissedCallNotification(characterName: String) {
        val notification = NotificationCompat.Builder(this, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(characterName)
            .setContentText("未接来电")
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(CallAlert.NOTIF_MISSED_ID, notification)
    }

    private fun showMessageNotification(title: String, body: String, sessionId: String? = null, avatarUrl: String? = null, kind: String = "", sceneId: String = ""): Boolean {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && getSystemService(NotificationManager::class.java).getNotificationChannel(CH_MESSAGES)?.importance == NotificationManager.IMPORTANCE_NONE
        ) return false
        val builder = NotificationCompat.Builder(this, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(sessionId, kind == "life_scene", sceneId))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        // 角色头像当通知大图标（服务端随广播带的 avatar：data URL 或直链）。
        // 取不到就退回默认图标——头像只是锦上添花，绝不能拖住或搞丢通知。
        loadAvatarBitmap(avatarUrl)?.let { builder.setLargeIcon(it) }
        // 带「回复」按钮：用户可以直接在通知横幅上回一句，不用点开 App。
        // 没有 sessionId 的通知（系统提醒类）挂了也没处送，所以只在有时才加。
        val sid = sessionId?.trim().orEmpty()
        if (kind == "life_scene" && sid.isNotEmpty() && sceneId.isNotEmpty()) {
            builder.setContentTitle("生活现场 · 主动消息")
            builder.addAction(buildLifeReplyAction(sid, sceneId, "action", "回复行动"))
            builder.addAction(buildLifeReplyAction(sid, sceneId, "dialogue", "回复对话"))
        } else if (sid.isNotEmpty()) builder.addAction(buildReplyAction(sid))
        getSystemService(NotificationManager::class.java).notify(notifId++, builder.build())
        if (notifId > 400) notifId = 100
        return true
    }

    /**
     * 构造通知上的「回复」动作（系统级快捷回复）。
     *
     * PendingIntent 必须是 MUTABLE：用户输入要先被系统填进 RemoteInput，
     * 不可变的 PendingIntent 装不下这个结果，回复框会打不开。
     */
    private fun buildLifeReplyAction(sessionId: String, sceneId: String, mode: String, label: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(NotificationReplyReceiver.KEY_REPLY_TEXT).setLabel(label).build()
        val intent = Intent(this, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_REPLY
            putExtra(NotificationReplyReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(NotificationReplyReceiver.EXTRA_SCENE_ID, sceneId)
            putExtra(NotificationReplyReceiver.EXTRA_REPLY_MODE, mode)
        }
        val pending = PendingIntent.getBroadcast(this, (sessionId + sceneId + mode).hashCode(), intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(R.drawable.ic_stat, label, pending).addRemoteInput(remoteInput).setAllowGeneratedReplies(true).build()
    }

    private fun buildReplyAction(sessionId: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(NotificationReplyReceiver.KEY_REPLY_TEXT)
            .setLabel("回复")
            .build()
        val intent = Intent(this, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_REPLY
            putExtra(NotificationReplyReceiver.EXTRA_SESSION_ID, sessionId)
        }
        // requestCode 用 sessionId 的哈希：不同会话各自一个 PendingIntent，
        // 否则 FLAG_UPDATE_CURRENT 会让后一条通知顶掉前一条的回复目标。
        val pending = PendingIntent.getBroadcast(
            this,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_stat, "回复", pending)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
    }

    /**
     * 把广播里的头像串变成通知大图标。支持 data:image/... 内联与 http(s) 直链；
     * 解析失败或超时一律返回 null，调用方退回应用图标。
     */
    private fun loadAvatarBitmap(avatarUrl: String?): Bitmap? {
        val value = avatarUrl?.trim().orEmpty()
        if (value.isEmpty()) return null
        // 老壳不认识 avatar 字段时这里是空串，自然跳过；异常也绝不能影响通知。
        return runCatching {
            if (value.startsWith("data:image/")) decodeDataUrlBitmap(value) else fetchRemoteAvatar(value)
        }.getOrNull()
    }

    /** 解 data:image/png;base64,xxxx 形式的内联头像。 */
    private fun decodeDataUrlBitmap(dataUrl: String): Bitmap? = runCatching {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return@runCatching null
        val header = dataUrl.substring(0, comma)
        if (!header.contains("base64", ignoreCase = true)) return@runCatching null
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /** 拉取远程头像。可能是站点上的受保护资源，这里带上 WebView 的登录 Cookie；失败返回 null。 */
    private fun fetchRemoteAvatar(url: String): Bitmap? = runCatching {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@runCatching null
        val builder = Request.Builder().url(url)
        val cookie = CookieManager.getInstance().getCookie(url)
        if (!cookie.isNullOrEmpty()) builder.header("Cookie", cookie)
        avatarClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val bytes = response.body?.bytes() ?: return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()

    /**
     * 睡眠若干秒；被 requestReconnect() 打断时立刻返回，不抛异常。
     * 心跳线程也走它——打断不会误杀心跳，因为心跳循环自己会检查 done/stopped。
     */
    private fun sleepSec(sec: Long) {
        try {
            Thread.sleep(sec * 1000)
        } catch (_: InterruptedException) {
            // 用户主动重连：提前醒来，交给外层循环重新取配置
        }
    }
}

/** 推送服务看门狗：收到闹钟就把服务拉起并续排下一次。 */
class PushWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != PushService.WATCHDOG_ACTION) return
        runCatching { PushService.start(context) }
        runCatching { PushService.scheduleWatchdog(context) }
    }
}
