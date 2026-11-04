package app.floatphone.shell

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 推送诊断的「兜底窗口」：一个只监听 127.0.0.1 的极小 HTTP 服务。
 *
 * 为什么需要它：推送排查最缺的是「壳里到底发生了什么」。三条常规通道各有短板——
 *   - 通知栏只能塞一句话，失败原因经常被截成「未登录或站点不可达」；
 *   - chrome://inspect 需要 USB + 页面里自己发起请求，App 被杀后连不上；
 *   - adb logcat 在开了 VPN 的手机上常常握手失败。
 * 而手机自己的浏览器永远连得上自己。于是壳在启动推送服务时顺手开这个端口，
 * 用手机浏览器（或 Termux 里 curl）打开 http://127.0.0.1:8787/ 就能看到完整轨迹。
 *
 * 边界（刻意做窄）：
 *   - 只 bind 回环地址，局域网里其它设备访问不到；
 *   - 只支持 GET，只有 / 与 /json 两个只读端点，不接受任何参数写入；
 *   - 输出的内容与 PushService.diagnosticsJson() 完全一致，不含 Cookie / API key
 *     / 聊天内容，只有状态、时间与错误摘要。
 */
class PushDebugServer : Service() {

    companion object {
        const val PORT = 8787

        @Volatile private var started = false

        /** 在 App 启动时顺手拉起（失败静默：端口被占用不影响任何主功能）。 */
        fun start(context: Context) {
            if (started) return
            runCatching { context.startService(Intent(context, PushDebugServer::class.java)) }
        }
    }

    private var serverSocket: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        started = true
        thread(name = "push-debug-server", isDaemon = true) { serve() }
    }

    override fun onDestroy() {
        started = false
        runCatching { serverSocket?.close() }
        super.onDestroy()
    }

    private fun serve() {
        try {
            // bind 到回环地址（而不是 0.0.0.0）：局域网内其它设备连不上，
            // 也不会触发 Android 的明文流量网络提示。
            val socket = ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            PushService.log("诊断端口已开 http://127.0.0.1:$PORT/")
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "push-debug-conn", isDaemon = true) { handle(client) }
            }
        } catch (err: Throwable) {
            // 端口被占用等：只在轨迹里留一行，不打扰用户（主功能与它无关）
            PushService.log("诊断端口开启失败：${err.javaClass.simpleName}")
        }
    }

    private fun handle(client: Socket) {
        runCatching {
            client.use { conn ->
                val reader = BufferedReader(InputStreamReader(conn.getInputStream()))
                val requestLine = reader.readLine().orEmpty()
                // 把请求头读空，避免客户端因未读数据而报错
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(' ').getOrNull(1).orEmpty()
                val body = if (path.startsWith("/json")) {
                    PushService.diagnosticsJson()
                } else {
                    htmlPage()
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                val head = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append(if (path.startsWith("/json")) "Content-Type: application/json; charset=utf-8\r\n" else "Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ${bytes.size}\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n\r\n")
                }
                val out: OutputStream = conn.getOutputStream()
                out.write(head.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
            }
        }
    }

    /**
     * 极简页面：不引任何外部资源（离线、VPN 都不影响），
     * 每秒自动拉一次 /json 重绘，用户开着页面就能盯着看推送事件有没有进来。
     */
    private fun htmlPage(): String = """
<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>小手机 · 推送诊断</title>
<style>
  body{margin:0;padding:16px;background:#0f1218;color:#e6ebf5;font:14px/1.6 -apple-system,"PingFang SC",sans-serif}
  h1{font-size:16px;margin:0 0 4px}
  .sub{color:#8b98ad;font-size:12px;margin-bottom:14px}
  .card{background:#171d27;border:1px solid #232c3a;border-radius:12px;padding:12px;margin-bottom:12px}
  .k{color:#8b98ad}
  .v{color:#e6ebf5;word-break:break-all}
  .ok{color:#4ade80}.warn{color:#fbbf24}.bad{color:#f87171}
  ul{margin:0;padding-left:18px;font-family:ui-monospace,Menlo,monospace;font-size:12px}
  li{margin:3px 0;word-break:break-all}
  button{background:#3557e0;border:0;border-radius:8px;padding:8px 12px;color:#fff;font-size:13px}
</style></head><body>
<h1>推送诊断</h1>
<div class="sub">本页面由壳内服务在 127.0.0.1 提供，每 2 秒自动刷新。仅本机可访问。</div>
<div class="card" id="summary">读取中…</div>
<div class="card"><div class="k">最近轨迹（新→旧）</div><ul id="trace"></ul></div>
<script>
function esc(s){return String(s==null?'':s).replace(/[&<>]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c];});}
function row(k,v,cls){return '<div><span class="k">'+k+'：</span><span class="v '+(cls||'')+'">'+esc(v)+'</span></div>';}
async function tick(){
  try{
    var r = await fetch('/json',{cache:'no-store'});
    var d = await r.json();
    var verdictCls = d.broadcasts>0?'ok':(d.running?'warn':'bad');
    document.getElementById('summary').innerHTML =
      row('结论', d.verdict, verdictCls)+
      row('服务运行', d.running?'是':'否')+
      row('连接状态', d.state)+
      row('站点', d.siteUrl)+
      row('频道', d.channel||'（未取得）')+
      row('配置', d.config||'正常')+
      row('壳订阅', d.shellSub||'（未注册）')+
      row('收到广播', d.broadcasts+(d.lastBroadcastAt?'（最近 '+d.lastBroadcastAt+'）':''), d.broadcasts>0?'ok':'')+
      row('通知权限', d.notificationGranted?'可用':'被关闭', d.notificationGranted?'':'bad')+
      row('手动重连', d.reconnects+' 次');
    var t = (d.trace||[]).slice().reverse();
    document.getElementById('trace').innerHTML = t.map(function(x){return '<li>'+esc(x)+'</li>';}).join('') || '<li>暂无</li>';
  }catch(e){document.getElementById('summary').textContent='读取失败：'+e;}
}
tick(); setInterval(tick, 2000);
</script>
</body></html>
    """.trimIndent()
}
