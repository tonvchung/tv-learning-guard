package com.icybard.learningguard.parent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.icybard.learningguard.data.GuardStore
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections

class ParentServer(
    context: Context,
    private val store: GuardStore,
    private val onConfigChanged: () -> Unit,
    private val onUnlock: () -> Unit,
    private val diagnostics: () -> JSONObject,
) : NanoHTTPD(PORT) {
    private val pinManager = PinManager(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private val sessions = mutableMapOf<String, Long>()
    private var loginFailures = 0
    private var lockedUntil: Long = 0L

    fun entryUrl(): String = "http://${localIpv4()}:$PORT/setup"

    override fun serve(session: IHTTPSession): Response = try {
        when {
            session.uri == "/setup" && session.method == Method.GET -> htmlResponse()
            session.uri == "/api/v1/auth/login" && session.method == Method.POST -> login(session)
            session.uri == "/api/v1/auth/logout" && session.method == Method.POST -> logout(session)
            session.uri == "/api/v1/config" && session.method == Method.GET -> withSession(session) {
                json(store.config.toJson())
            }
            session.uri == "/api/v1/config" && session.method == Method.PUT -> withSession(session) {
                val body = bodyJson(session)
                val updated = store.config.updated(body)
                store.config = updated
                mainHandler.post(onConfigChanged)
                json(updated.toJson())
            }
            session.uri == "/api/v1/apps" && session.method == Method.GET -> withSession(session) {
                json(JSONObject()
                    .put("com.ktcp.video", JSONObject()
                        .put("name", "云视听极光")
                        .put("enabled", store.config.guardCloudEnabled))
                    .put("com.tonvchung.webtv", JSONObject()
                        .put("name", "WebTV")
                        .put("enabled", store.config.guardWebTvEnabled)))
            }
            session.uri == "/api/v1/diagnostics" && session.method == Method.GET -> withSession(session) {
                json(diagnostics())
            }
            session.uri == "/api/v1/parent/unlock" && session.method == Method.POST -> withSession(session) {
                val body = bodyJson(session)
                require(body.optBoolean("confirm", false)) { "unlock requires explicit confirmation" }
                mainHandler.post(onUnlock)
                json(JSONObject().put("ok", true))
            }
            else -> json(JSONObject().put("error", "not_found"), Response.Status.NOT_FOUND)
        }
    } catch (error: IllegalArgumentException) {
        json(JSONObject().put("error", error.message ?: "bad_request"), Response.Status.BAD_REQUEST)
    } catch (error: Exception) {
        json(JSONObject().put("error", "request_failed"), Response.Status.INTERNAL_ERROR)
    }

    @Synchronized
    private fun login(session: IHTTPSession): Response {
        val now = SystemClock.elapsedRealtime()
        if (now < lockedUntil) {
            return json(JSONObject().put("error", "登录失败次数过多，请稍后再试"), Response.Status.UNAUTHORIZED)
        }
        val body = bodyJson(session)
        // The fixed LAN bookmark is the intended entry. A PIN is always required;
        // legacy entryToken fields are ignored so old bookmarks do not expire either.
        if (!pinManager.verify(body.optString("pin"))) {
            loginFailures += 1
            if (loginFailures >= 5) {
                lockedUntil = now + 60_000L
                loginFailures = 0
            }
            return json(JSONObject().put("error", "家长 PIN 错误"), Response.Status.UNAUTHORIZED)
        }
        loginFailures = 0
        val sessionToken = randomToken()
        sessions[sessionToken] = now + SESSION_TTL_MS
        sessions.entries.removeAll { it.value < now }
        return json(JSONObject().put("session", sessionToken).put("expiresInSec", SESSION_TTL_MS / 1000))
    }

    @Synchronized
    private fun logout(session: IHTTPSession): Response {
        sessions.remove(session.headers["x-session"])
        return json(JSONObject().put("ok", true))
    }

    @Synchronized
    private fun authorized(session: IHTTPSession): Boolean {
        val token = session.headers["x-session"] ?: return false
        val expiry = sessions[token] ?: return false
        val now = SystemClock.elapsedRealtime()
        if (expiry < now) {
            sessions.remove(token)
            return false
        }
        sessions[token] = now + SESSION_TTL_MS
        return true
    }

    private fun withSession(session: IHTTPSession, action: () -> Response): Response =
        if (authorized(session)) action() else {
            json(JSONObject().put("error", "unauthorized"), Response.Status.UNAUTHORIZED)
        }

    private fun bodyJson(session: IHTTPSession): JSONObject {
        val body = if (session.method == Method.PUT) {
            // NanoHTTPD 2.3.1 does not expose this TV client's JSON PUT body through postData.
            val length = session.headers["content-length"]?.toIntOrNull() ?: 0
            require(length in 1..65_536) { "missing or oversized JSON body" }
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = session.inputStream.read(bytes, offset, length - offset)
                if (read < 0) break
                offset += read
            }
            String(bytes, 0, offset, Charsets.UTF_8)
        } else {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"].orEmpty()
        }
        require(body.isNotBlank()) { "missing JSON body" }
        return JSONObject(body)
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun json(value: JSONObject, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", value.toString()).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
        }

    private fun htmlResponse(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Content-Security-Policy", "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'")
        }

    private fun localIpv4(): String {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (network in interfaces) {
            if (!network.isUp || network.isLoopback) continue
            for (address in Collections.list(network.inetAddresses)) {
                val host = address.hostAddress ?: continue
                if (!address.isLoopbackAddress && host.indexOf(':') < 0 &&
                    (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172."))
                ) return host
            }
        }
        return "127.0.0.1"
    }

    companion object {
        private const val PORT = 18080
        private const val SESSION_TTL_MS = 15 * 60 * 1000L

        private val PAGE = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>学习守护 · 家长设置</title>
<style>
:root{color-scheme:dark;font-family:system-ui,-apple-system,"Microsoft YaHei",sans-serif;background:#07111f;color:#e5edf7}
*{box-sizing:border-box}html,body{max-width:100%;overflow-x:hidden}body{margin:0}.wrap{max-width:680px;min-width:0;margin:auto;padding:24px}h1{font-size:28px;margin:8px 0}h2{font-size:20px;margin-top:28px}
.muted{color:#94a3b8;line-height:1.6;overflow-wrap:anywhere;word-break:break-word}.card{background:#0f1e31;border:1px solid #20364d;border-radius:18px;padding:20px;margin-top:18px;min-width:0;max-width:100%}
label{display:block;margin:17px 0 7px;color:#cbd5e1}input[type=number],input[type=password]{box-sizing:border-box;width:100%;font-size:18px;padding:13px;border:1px solid #34506d;border-radius:10px;background:#081522;color:#fff}
.check{display:flex;align-items:center;gap:12px;font-size:17px;margin:18px 0}.check input{width:24px;height:24px;flex:0 0 auto}
button{width:100%;border:0;border-radius:12px;padding:15px;font-size:18px;font-weight:700;background:#67e8f9;color:#07111f;margin-top:18px}
.secondary{background:#263c52;color:#fff}.danger{background:#fecaca;color:#7f1d1d}
#statusToast{position:fixed;top:16px;left:50%;transform:translateX(-50%);z-index:1000;display:flex;align-items:center;gap:16px;width:max-content;max-width:min(560px,calc(100% - 32px));padding:14px 16px 14px 20px;border:1px solid #34506d;border-radius:14px;background:#122a40;box-shadow:0 10px 36px #0009;color:#67e8f9}
#status{min-width:0;line-height:1.5;overflow-wrap:anywhere}#statusClose{flex:0 0 auto;width:32px;height:32px;margin:0;padding:0;border-radius:50%;background:#263c52;color:#fff;font-size:24px;font-weight:400;line-height:30px}
[hidden]{display:none!important}.grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:14px}.grid>div,.grid label{min-width:0}.grid label{margin-top:4px}
.pin-row{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:10px;max-width:460px;margin-top:12px}
.pin-digit{min-width:0;height:58px;padding:0!important;text-align:center;font-size:28px!important;font-weight:700;border-radius:12px!important;caret-color:#67e8f9}
.pin-digit:focus{outline:3px solid #67e8f9;outline-offset:2px;border-color:#67e8f9}
#diagnostics{max-width:100%;min-width:0;margin-top:16px}.diag-list{display:grid;gap:8px;max-width:100%;min-width:0}.diag-row{display:grid;grid-template-columns:minmax(92px,.75fr) minmax(0,1.65fr);gap:12px;align-items:start;padding:11px 12px;border-radius:10px;background:#0a1828;min-width:0;max-width:100%}
.diag-name{color:#cbd5e1;min-width:0}.diag-value{color:#e5edf7;text-align:right;min-width:0;max-width:100%;overflow-wrap:anywhere;word-break:break-word;white-space:normal}.diag-good{color:#86efac}.diag-warn{color:#fde68a}.diag-bad{color:#fca5a5}
@media(max-width:480px){.grid{grid-template-columns:minmax(0,1fr)}.wrap{padding:20px}.card{padding:16px}.pin-row{gap:7px}.pin-digit{height:52px;font-size:24px!important}.diag-row{grid-template-columns:minmax(82px,.7fr) minmax(0,1.5fr);gap:8px;padding:10px;font-size:14px}#statusToast{gap:10px;padding:12px 12px 12px 16px}}
</style>
</head>
<body><main class="wrap">
<h1>学习守护</h1>
<div class="muted">仅家庭局域网使用。关闭全局或当前应用守护并保存会立即结束本轮任务；保留守护时也可单独解除本轮。</div>
<div class="muted">固定地址：<span id="fixedAddress"></span><br>可收藏到手机，电视 IP 不变时长期有效；以后直接打开并验证 PIN，无需扫码。</div>
<section id="login" class="card">
<h2>家长验证</h2>
<label>6 位家长 PIN</label>
<div class="pin-row" role="group" aria-label="6 位家长 PIN">
<input class="pin-digit" type="password" inputmode="numeric" pattern="[0-9]*" maxlength="1" autocomplete="one-time-code" aria-label="第 1 位">
<input class="pin-digit" type="password" inputmode="numeric" pattern="[0-9]*" maxlength="1" autocomplete="off" aria-label="第 2 位">
<input class="pin-digit" type="password" inputmode="numeric" pattern="[0-9]*" maxlength="1" autocomplete="off" aria-label="第 3 位">
<input class="pin-digit" type="password" inputmode="numeric" pattern="[0-9]*" maxlength="1" autocomplete="off" aria-label="第 4 位">
<input class="pin-digit" type="password" inputmode="numeric" pattern="[0-9]*" maxlength="1" autocomplete="off" aria-label="第 5 位">
<input class="pin-digit" type="password" inputmode="numeric" pattern="[0-9]*" maxlength="1" autocomplete="off" enterkeyhint="done" aria-label="第 6 位">
</div>
<button id="loginBtn">验证并进入设置</button>
</section>
<section id="settings" class="card" hidden>
<h2>基本规则</h2>
<div class="check"><input id="enabled" type="checkbox"><label for="enabled" style="margin:0">启用做题守护</label></div>
<div class="grid">
<div><label for="trigger">播放多少分钟后触发</label><input id="trigger" type="number" min="1" max="120"></div>
<div><label for="streak">需要连续答对题数</label><input id="streak" type="number" min="1" max="10"></div>
<div><label for="cooldown">连错 2 题冷却秒数</label><input id="cooldown" type="number" min="30" max="600"></div>
<div><label for="timeout">每题限时秒数（0 为关闭）</label><input id="timeout" type="number" min="0" max="180"></div>
<div><label for="feedback">答错反馈锁定秒数</label><input id="feedback" type="number" min="3" max="30"></div>
</div>
<div class="check"><input id="resume" type="checkbox"><label for="resume" style="margin:0">完成后自动继续原视频</label></div>
<div class="check"><input id="estimate" type="checkbox"><label for="estimate" style="margin:0">无媒体会话时按前台播放估算</label></div>
<h2>受控应用</h2>
<div class="check"><input id="guardCloud" type="checkbox"><label for="guardCloud" style="margin:0">云视听极光</label></div>
<div class="check"><input id="guardWebTv" type="checkbox"><label for="guardWebTv" style="margin:0">WebTV</label></div>
<div class="muted">可单独关闭某个应用的做题守护，不影响另一个应用。</div>
<button id="saveBtn">保存设置</button>
<button id="diagBtn" class="secondary">查看运行诊断</button>
<div id="diagnostics" class="muted" aria-live="polite"></div>
<h2>本次家长解锁</h2>
<div class="muted">仅解除本轮任务并清零本轮计时，不会关闭后续守护。</div>
<button id="unlockBtn" class="danger">确认解除本轮任务</button>
</section>
</main>
<div id="statusToast" role="status" aria-live="polite" hidden><span id="status"></span><button id="statusClose" type="button" aria-label="关闭提示">×</button></div>
<script>
if(location.search)history.replaceState(null,'',location.pathname);
let session=sessionStorage.getItem('guardSession')||'';
const byId=id=>document.getElementById(id);
byId('fixedAddress').textContent=location.origin+'/setup';
let statusTimer;
const hideStatus=()=>{clearTimeout(statusTimer);byId('statusToast').hidden=true;};
const status=text=>{clearTimeout(statusTimer);byId('status').textContent=text;byId('statusToast').hidden=false;statusTimer=setTimeout(hideStatus,5000);};
byId('statusClose').onclick=hideStatus;
const pinInputs=Array.from(document.querySelectorAll('.pin-digit'));
const pinValue=()=>pinInputs.map(input=>input.value).join('');
function fillPin(start,digits){
  Array.from(digits).slice(0,6-start).forEach((digit,offset)=>{pinInputs[start+offset].value=digit;});
  pinInputs[Math.min(start+digits.length,5)].focus();
}
pinInputs.forEach((input,index)=>{
  input.addEventListener('input',()=>{
    const digits=input.value.replace(/\D/g,'');
    input.value='';
    if(digits)fillPin(index,digits);
  });
  input.addEventListener('paste',event=>{
    event.preventDefault();
    const digits=(event.clipboardData.getData('text')||'').replace(/\D/g,'');
    if(digits)fillPin(index,digits);
  });
  input.addEventListener('keydown',event=>{
    if(event.key==='Backspace'&&!input.value&&index>0){pinInputs[index-1].value='';pinInputs[index-1].focus();event.preventDefault();}
    if(event.key==='ArrowLeft'&&index>0){pinInputs[index-1].focus();event.preventDefault();}
    if(event.key==='ArrowRight'&&index<5){pinInputs[index+1].focus();event.preventDefault();}
    if(event.key==='Enter'&&pinValue().length===6){byId('loginBtn').click();event.preventDefault();}
  });
});
async function api(path,options={}){
  options.headers=Object.assign({'Content-Type':'application/json','X-Session':session},options.headers||{});
  const response=await fetch(path,options);
  const data=await response.json();
  if(!response.ok)throw new Error(data.error||'请求失败');
  return data;
}
async function load(){
  try{
    const config=await api('/api/v1/config');
    byId('enabled').checked=config.enabled;
    byId('trigger').value=Math.round(config.triggerPlayMs/60000);
    byId('streak').value=config.requiredStreak;
    byId('cooldown').value=config.wrongCooldownSec;
    byId('timeout').value=config.answerTimeoutSec;
    byId('feedback').value=config.answerFeedbackSec;
    byId('resume').checked=config.autoResume;
    byId('estimate').checked=config.allowForegroundEstimate;
    byId('guardCloud').checked=config.guardCloudEnabled!==false;
    byId('guardWebTv').checked=config.guardWebTvEnabled!==false;
    byId('login').hidden=true;
    byId('settings').hidden=false;
  }catch(error){session='';sessionStorage.removeItem('guardSession');}
}
byId('loginBtn').onclick=async()=>{
  try{
    if(pinValue().length!==6)throw new Error('请输入完整的 6 位家长 PIN');
    const data=await api('/api/v1/auth/login',{method:'POST',body:JSON.stringify({pin:pinValue()})});
    session=data.session;
    sessionStorage.setItem('guardSession',session);
    pinInputs.forEach(input=>{input.value='';});
    status('验证成功');
    await load();
  }catch(error){status(error.message);}
};
byId('saveBtn').onclick=async()=>{
  try{
    const timeout=Number(byId('timeout').value);
    if(timeout!==0&&(timeout<30||timeout>180))throw new Error('每题限时应为 0 或 30～180 秒');
    const feedback=Number(byId('feedback').value);
    if(feedback<3||feedback>30)throw new Error('答错反馈锁定应为 3～30 秒');
    const body={
      enabled:byId('enabled').checked,
      triggerPlayMs:Number(byId('trigger').value)*60000,
      requiredStreak:Number(byId('streak').value),
      wrongCooldownSec:Number(byId('cooldown').value),
      answerTimeoutSec:timeout,
      answerFeedbackSec:feedback,
      autoResume:byId('resume').checked,
      allowForegroundEstimate:byId('estimate').checked,
      guardCloudEnabled:byId('guardCloud').checked,
      guardWebTvEnabled:byId('guardWebTv').checked
    };
    await api('/api/v1/config',{method:'PUT',body:JSON.stringify(body)});
    status(body.enabled?'设置已保存；关闭守护的应用不会再触发答题。':'设置已保存，做题守护已关闭并退出当前答题。');
  }catch(error){status(error.message);}
};
function renderDiagnostics(data){
  const container=byId('diagnostics');
  container.replaceChildren();
  const list=document.createElement('div');
  list.className='diag-list';
  const addRow=(name,value,tone='')=>{
    const row=document.createElement('div');row.className='diag-row';
    const label=document.createElement('div');label.className='diag-name';label.textContent=name;
    const result=document.createElement('div');result.className='diag-value'+(tone?' '+tone:'');result.textContent=String(value);
    row.append(label,result);list.appendChild(row);
  };
  const health=(value,yes='正常',no='异常')=>[value?yes:no,value?'diag-good':'diag-bad'];
  const appName=value=>({'com.ktcp.video':'云视听极光','com.tonvchung.webtv':'WebTV','app.orchard.tv.icybard':'Aerial 桌面'})[value]||value||'尚未识别';
  const seconds=value=>Math.max(0,Math.ceil(Number(value||0)/1000))+' 秒';
  const minutes=value=>(Number(value||0)/60000).toFixed(1)+' 分钟';
  const phaseName=value=>({IDLE:'播放监测中',QUIZ_PENDING:'准备答题',QUIZ_ACTIVE:'等待作答',ANSWER_FEEDBACK:'答错反馈锁定中',COOLDOWN:'连错冷却中',PARENT_GRACE:'家长宽限期'})[value]||value;
  let result=health(data.serviceConnected);addRow('守护服务',...result);
  result=health(data.notificationListenerConnected,'已连接','未连接');addRow('媒体通知监听',...result);
  addRow('当前前台应用',appName(data.foregroundPackage));
  addRow('当前守护阶段',phaseName(data.phase),data.phase==='IDLE'?'diag-good':'diag-warn');
  addRow('做题守护总开关',data.config.enabled?'已开启':'已关闭',data.config.enabled?'diag-good':'diag-warn');
  addRow('云视听极光守护',data.config.guardCloudEnabled?'已开启':'已关闭',data.config.guardCloudEnabled?'diag-good':'diag-warn');
  addRow('WebTV 守护',data.config.guardWebTvEnabled?'已开启':'已关闭',data.config.guardWebTvEnabled?'diag-good':'diag-warn');
  addRow('本轮播放累计',minutes(data.accumulatedPlayMs));
  addRow('触发答题阈值',minutes(data.config.triggerPlayMs));
  addRow('当前连续答对',data.currentStreak+' / '+data.config.requiredStreak);
  addRow('当前连续答错',data.consecutiveWrong+' / '+data.config.wrongStreakThreshold);
  addRow('本轮出题序号',data.questionSerial);
  addRow('当前题目编号',data.currentQuestionId||'无');
  if(data.phase==='QUIZ_ACTIVE')addRow('本题剩余时间',seconds(data.answerRemainingMs));
  if(data.phase==='ANSWER_FEEDBACK')addRow('答案反馈剩余',seconds(data.feedbackRemainingMs));
  if(data.phase==='COOLDOWN')addRow('冷却剩余时间',seconds(data.cooldownRemainingMs));
  addRow('暂停页面',data.playbackPauseActivityVisible?'已显示':'未显示',data.playbackPauseActivityVisible?'diag-warn':'');
  addRow('后台音频焦点',data.fallbackFocusHeld?'已持有':'未持有');
  const sessions=(data.mediaSessionPackages||[]).map(appName);
  addRow('可用媒体会话',sessions.length?sessions.join('、'):'无（使用音频活跃检测）',sessions.length?'diag-good':'diag-warn');
  addRow('错题集题目数',data.wrongQuestionCount||0);
  const subjectNames={chinese:'语文',math:'数学',english:'英语'};
  const coverage=Object.entries(data.subjectSeenCounts||{}).map(([key,value])=>(subjectNames[key]||key)+' '+value+' 题').join('；');
  addRow('本轮分类覆盖',coverage||'尚无记录');
  addRow('最近媒体检测',data.lastMediaDiagnostic||'尚未检测');
  addRow('前台恢复检查',data.foregroundRecoveryCount||0,'');
  container.appendChild(list);
}
byId('diagBtn').onclick=async()=>{
  try{renderDiagnostics(await api('/api/v1/diagnostics'));status('诊断已刷新。');}
  catch(error){status(error.message);}
};
byId('unlockBtn').onclick=async()=>{
  if(!confirm('确定只解除本轮任务吗？此操作会记录在本机。'))return;
  try{await api('/api/v1/parent/unlock',{method:'POST',body:JSON.stringify({confirm:true})});status('本轮任务已解除。');}
  catch(error){status(error.message);}
};
if(session)load();
</script>
</body></html>
        """.trimIndent()
    }
}
