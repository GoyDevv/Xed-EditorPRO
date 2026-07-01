package com.rk.ai

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Full-screen Google sign-in for the "Gemini (Google login)" provider. Loads Google login in a
 * WebView; once signed in to gemini.google.com it captures the `__Secure-1PSID` / `__Secure-1PSIDTS`
 * cookies (via [GeminiWebAuth]) that let the app use consumer Gemini without an API key.
 */
@Composable
fun GoogleLoginDialog(onDismiss: () -> Unit, onCaptured: () -> Unit) {
    var status by remember { mutableStateOf("Sign in with your Google account, then it captures automatically.") }
    var done by remember { mutableStateOf(false) }

    fun tryCapture(auto: Boolean): Boolean {
        if (done) return true
        val cm = CookieManager.getInstance()
        runCatching { cm.flush() }
        val cookies =
            cm.getCookie("https://gemini.google.com") ?: cm.getCookie("https://google.com") ?: ""
        var psid = ""
        var psidts = ""
        cookies.split(";").forEach {
            val t = it.trim()
            when {
                t.startsWith("__Secure-1PSID=") -> psid = t.removePrefix("__Secure-1PSID=")
                t.startsWith("__Secure-1PSIDTS=") -> psidts = t.removePrefix("__Secure-1PSIDTS=")
            }
        }
        return if (psid.isNotBlank()) {
            GeminiWebAuth.setCookies(psid, psidts)
            done = true
            status = "Login captured. You can close this."
            true
        } else {
            if (!auto) status = "Not signed in yet — finish Google sign-in, then tap Capture."
            false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sign in to Gemini", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { if (tryCapture(false)) { onCaptured(); onDismiss() } }) { Text("Capture") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text(
                    status,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                            webViewClient =
                                object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        if (url != null && url.contains("gemini.google.com")) {
                                            if (tryCapture(true)) {
                                                onCaptured()
                                                onDismiss()
                                            }
                                        }
                                    }
                                }
                            loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fgemini.google.com%2Fapp")
                        }
                    },
                )
            }
        }
    }
}
