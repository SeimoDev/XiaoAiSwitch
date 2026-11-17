package io.github.xiaoaiswitch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.extra.WindowDialog

class CodexLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { AccountScreen { finish() } } }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AccountScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val status by accountStatus()
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var logout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, topBar = {
        SmallTopAppBar(title = "Codex 订阅", navigationIcon = { BackButton("account_back", onBack) })
    }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Section("订阅账号") {
                    Text(when {
                        status == null -> "正在读取登录状态…"
                        status?.loggedIn() == true -> "已连接 ChatGPT"
                        status?.pending() == true -> "等待官方页面确认"
                        else -> "尚未登录"
                    }, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("account_status"))
                    if (status?.loggedIn() == true) {
                        Text(status?.plan()?.uppercase() ?: "", color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        SelectionContainer { Text(status?.email() ?: "", color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    }
                    Text("登录凭据使用手机密钥库加密保存。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                if (status?.pending() == true) {
                    Section("设备代码") {
                        SelectionContainer { Text(status?.code() ?: "", fontSize = 32.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("device_code")) }
                        Text("打开官方页面，输入此代码并确认登录；返回后自动更新状态。", fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        PrimaryButton("复制代码并打开登录页面", "open_login") {
                            val code = status?.code().orEmpty()
                            val url = status?.url().orEmpty()
                            if (code.isNotEmpty() && url.isNotEmpty()) {
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Codex", code))
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                        TextButton("取消等待中的登录", { CodexAccounts.get(context).cancelLogin() }, modifier = Modifier.fillMaxWidth().testTag("cancel_login"))
                    }
                }
                if (problem != null || !status?.error().isNullOrEmpty()) {
                    Text(problem ?: status?.error().orEmpty(), color = MiuixTheme.colorScheme.error, modifier = Modifier.testTag("account_error"))
                }
                if (status?.pending() != true) {
                    PrimaryButton(if (busy) "正在申请设备代码…" else if (status?.loggedIn() == true) "登录其他账号" else "开始设备代码登录",
                        "start_login", enabled = !busy && status != null) {
                        busy = true
                        problem = null
                        scope.launch {
                            try { withContext(Dispatchers.IO) { CodexAccounts.get(context).start() } }
                            catch (error: Exception) { problem = "登录请求失败：${error.message}" }
                            finally { busy = false }
                        }
                    }
                }
                if (status?.loggedIn() == true) {
                    TextButton("退出此订阅账号", { logout = true }, modifier = Modifier.fillMaxWidth().testTag("logout_account"))
                }
                Text("文本、语音、绘图与控制均可使用订阅；绘图也可选择独立的图片 API。", fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
    if (logout) {
        WindowDialog(show = true, title = "退出订阅账号？", summary = "仅移除此手机保存的登录凭据，模型与指令配置保留。", onDismissRequest = { logout = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("退出账号", "confirm_logout") { CodexAccounts.get(context).logout(); logout = false }
                TextButton("取消", { logout = false }, modifier = Modifier.fillMaxWidth().testTag("cancel_logout"))
            }
        }
    }
}
