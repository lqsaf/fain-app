package cc.liqs.fain

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import cc.liqs.fain.ui.theme.FainTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var bgColor by remember { mutableStateOf(Color(0xFF000000)) }
            val isLight = bgColor.luminance() > 0.5f

            SideEffect {
                val style = if (isLight) {
                    SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                } else {
                    SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            FainTheme(customBackground = bgColor) {
                Scaffold(containerColor = bgColor) { padding ->
                    FainWeb(
                        modifier = Modifier.padding(padding),
                        onBgChanged = { bgColor = it }
                    )
                }
            }
        }
    }
}

private fun String.toColorOrNull(): Int? = runCatching { trim().toColorInt() }.getOrNull()

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun FainWeb(
    modifier: Modifier = Modifier,
    onBgChanged: (Color) -> Unit = {}
) {
    var isRefreshing by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var logoTint by remember { mutableStateOf(Color.White) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val state = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    var startY by remember { mutableFloatStateOf(0f) }
    var isPulling by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    val spinAngle by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "angle"
    )

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { isRefreshing = true; webView?.reload() },
        state = state,
        modifier = modifier.fillMaxSize(),
        indicator = {
            if (isRefreshing || state.distanceFraction > 0f) {
                Icon(
                    painter = painterResource(R.drawable.fain_logo),
                    contentDescription = null,
                    tint = logoTint,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .size(42.dp)
                        .graphicsLayer {
                            translationY = if (isRefreshing) 15.dp.toPx()
                            else (state.distanceFraction.coerceAtMost(1f) * 95.dp.toPx()) - 80.dp.toPx()
                            rotationZ = if (isRefreshing) spinAngle else state.distanceFraction * 120f
                        }
                )
            }
        }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    addJavascriptInterface(object {
                        @Suppress("unused")
                        @JavascriptInterface
                        fun onThemeChanged(rawBg: String, rawText: String) {
                            post {
                                rawBg.toColorOrNull()?.let {
                                    setBackgroundColor(it)
                                    onBgChanged(Color(it))
                                }
                                rawText.toColorOrNull()?.let { logoTint = Color(it) }
                            }
                        }
                    }, "AndroidTheme")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            isRefreshing = false
                            canGoBack = view?.canGoBack() == true
                            view?.evaluateJavascript(THEME_OBSERVER_JS, null)
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            canGoBack = view?.canGoBack() == true
                        }
                    }

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                startY = event.y
                                isPulling = false
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (v.scrollY > 0) {
                                    startY = event.y
                                    isPulling = false
                                } else if (!isRefreshing) {
                                    val dy = event.y - startY
                                    if (dy > 0) {
                                        if (!isPulling) {
                                            startY = event.y
                                            isPulling = true
                                        } else {
                                            scope.launch { state.snapTo(dy / 300f) }
                                        }
                                        return@setOnTouchListener true
                                    } else if (isPulling) {
                                        isPulling = false
                                        scope.launch { state.snapTo(0f) }
                                    }
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (isPulling) {
                                isPulling = false
                                if (state.distanceFraction >= 1f) {
                                    isRefreshing = true
                                    reload()
                                }
                                scope.launch { state.animateToHidden() }
                                return@setOnTouchListener true
                            }
                        }
                        false
                    }

                    loadUrl("https://lqsaf.github.io/fain/")
                }.also { webView = it }
            }
        )
    }
}

private const val THEME_OBSERVER_JS = """
(function() {
    function sendTheme() {
        var s = getComputedStyle(document.documentElement);
        window.AndroidTheme?.onThemeChanged(
            s.getPropertyValue('--bg').trim(),
            s.getPropertyValue('--text-main').trim()
        );
    }
    sendTheme();
    if (window.__bgObserver) return;
    window.__bgObserver = true;
    var obs = new MutationObserver(sendTheme);
    var cfg = { attributes: true, attributeFilter: ['class', 'style', 'data-theme'] };
    obs.observe(document.documentElement, cfg);
    if (document.body) obs.observe(document.body, cfg);
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', sendTheme);
})();
"""