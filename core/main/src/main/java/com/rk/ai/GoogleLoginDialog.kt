package com.rk.ai

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * Gemini (Google login) account screen. If not signed in, shows a WebView to sign in and captures the
 * Gemini cookies. If signed in, shows the account, whether Gemini is ready (some accounts must accept
 * terms first), and lets the user sign out / delete the login. No API key involved.
 */
@Composable
fun GoogleLoginDialog(onDismiss: () -> Unit, onCaptured: () -> Unit) {
    var mode by remember { mutableStateOf(if (GeminiWebAuth.hasCreds()) "status" else "web") }
    var status by remember { mutableStateOf("Sign in with your Google account. It captures automatically.") }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(false) }
    var recheck by remember { mutableStateOf(0) }

    // When in status mode, verify Gemini availability.
    LaunchedEffect(mode, recheck) {
        if (mode == "status" && GeminiWebAuth.hasCreds()) {
            checking = true
            available = GeminiWebClient.isReady()
            checking = false
        }
    }

    fun tryCapture(auto: Boolean): Boolean {
        val cm = CookieManager.getInstance()
        runCatching { cm.flush() }
        val cookies = cm.getCookie("https://gemini.google.com") ?: cm.getCookie("https://google.com") ?: ""
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
            onCaptured()
            mode = "status"
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
                    Text("Gemini · Google account", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (mode == "web") TextButton(onClick = { tryCapture(false) }) { Text("Capture") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                if (mode == "status") {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Signed in", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    GeminiWebAuth.account().ifBlank { "Google account" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.size(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    when {
                                        checking -> {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(8.dp)); Text("Checking Gemini…", style = MaterialTheme.typography.bodySmall)
                                        }
                                        available == true -> Text("✓ Gemini is available.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                        available == false ->
                                            Text(
                                                "Gemini isn't ready. Tap \"Open Gemini\" and accept the terms / finish setup, then \"Check again\".",
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        else -> Text("", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.size(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { mode = "web"; status = "Complete sign-in / accept terms, then Capture." }) { Text("Open Gemini") }
                            TextButton(onClick = { available = null; recheck++ }) { Text("Check again") }
                        }
                        TextButton(
                            onClick = {
                                GeminiWebAuth.clear()
                                available = null
                                status = "Signed out. Sign in with your Google account."
                                mode = "web"
                            }
                        ) {
                            Text("Sign out / delete login", color = MaterialTheme.colorScheme.error)
                        }
                        if (available == true) {
                            TextButton(onClick = onDismiss) { Text("Done") }
                        }
                    }
                } else {
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
                                            if (url != null && url.contains("gemini.google.com")) tryCapture(true)
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
}
