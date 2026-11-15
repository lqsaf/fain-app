package cc.liqs.fain

import android.annotation.SuppressLint
import androidx.compose.ui.graphics.Color
import android.graphics.Color as GraphicsColor
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cc.liqs.fain.ui.theme.FainTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(GraphicsColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(GraphicsColor.TRANSPARENT)
        )
        setContent {
            FainTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    FainWeb(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun FainWeb(modifier: Modifier = Modifier) {

    var isRefreshing by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val state = rememberPullToRefreshState()

    var canGoBack by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var startY by remember { mutableFloatStateOf(0f) }
    var isPulling by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing)
        ),
        label = "angle"
    )

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            webView?.reload()
        },
        state = state,
        modifier = modifier.fillMaxSize(),
        indicator = {
            if (isRefreshing || state.distanceFraction > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .graphicsLayer {
                            val targetY = if (isRefreshing) {
                                15.dp.toPx()
                            } else {
                                (state.distanceFraction.coerceAtMost(1f) * 95.dp.toPx()) - 80.dp.toPx()
                            }
                            translationY = targetY
                        }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.fain_logo),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(42.dp)
                            .graphicsLayer {
                                rotationZ = if (isRefreshing) {
                                    spinAngle
                                } else {
                                    state.distanceFraction * 120f
                                }
                            }
                    )
                }
            }
        }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isRefreshing = false
                            canGoBack = view?.canGoBack() == true
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            canGoBack = view?.canGoBack() == true
                        }
                    }

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                    }

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
                                    return@setOnTouchListener false
                                }

                                val dy = event.y - startY
                                if (dy > 0 && !isRefreshing) {
                                    if (!isPulling) {
                                        startY = event.y
                                        isPulling = true
                                        return@setOnTouchListener true
                                    }

                                    val fraction = (dy / 300f).coerceAtLeast(0f)
                                    scope.launch { state.snapTo(fraction) }
                                    return@setOnTouchListener true
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                if (isPulling) {
                                    isPulling = false
                                    if (state.distanceFraction >= 1f) {
                                        isRefreshing = true
                                        reload()
                                    }
                                    scope.launch { state.animateToHidden() }
                                    return@setOnTouchListener true
                                }
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
