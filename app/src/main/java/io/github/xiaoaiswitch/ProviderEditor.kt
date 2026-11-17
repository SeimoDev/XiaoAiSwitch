package io.github.xiaoaiswitch

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ProviderEditor(value: JSONObject, onChange: (String, String) -> Unit, onKind: () -> Unit) {
    val context = LocalContext.current
    val subscription = value.optString("provider") == "codex_subscription"
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Section("Provider 设置") {
            Field("名称", value.optString("name"), "provider_name") { onChange("name", it) }
            ChoiceRow("类型", if (subscription) "Codex 订阅" else "API Key / OpenAI 兼容源", "provider_kind", onClick = onKind)
            if (subscription) {
                val account by accountStatus()
                ChoiceRow("手机订阅账号", if (account?.loggedIn() == true) "已登录 · ${account?.plan()}" else "点击登录 ChatGPT",
                    "provider_account") { context.startActivity(Intent(context, CodexLoginActivity::class.java)) }
                Text("使用手机当前登录的 Codex 账号，无需填写 API Key。", fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            } else {
                Field("API Endpoint", value.optString("endpoint"), "provider_endpoint") { onChange("endpoint", it) }
                Field("API Token", value.optString("token"), "provider_token", secret = true) { onChange("token", it) }
            }
        }
    }
}
