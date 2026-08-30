package com.wifi.toolbox.ui.items

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.wifi.toolbox.R
import java.util.Scanner

@Composable
fun HyperOSBackground(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current

    val isDark = MaterialTheme.colorScheme.background.run {
        val luminance = (red * 0.2126f + green * 0.7152f + blue * 0.0722f)
        luminance < 0.5f
    }

    val shaderSrc = remember {
        context.resources.openRawResource(R.raw.bg_frag).use { inputStream ->
            Scanner(inputStream).useDelimiter("\\A").next()
        }
    }

    val shader = remember(shaderSrc) { RuntimeShader(shaderSrc) }
    val transition = rememberInfiniteTransition(label = "miui_flow")

    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(100000, easing = LinearEasing)
        ),
        label = "time"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        shader.setFloatUniform("uResolution", w, h)
        shader.setFloatUniform("uAnimTime", time)
        shader.setFloatUniform("uBound", 0.0f, 0.4489f, 1.0f, 0.5511f)
        shader.setFloatUniform("uNoiseScale", 1.5f)
        shader.setFloatUniform("uPointOffset", 0.1f)
        shader.setFloatUniform("uPointRadiusMulti", 1.0f)
        shader.setFloatUniform("uAlphaMulti", 1.0f)
        shader.setFloatUniform("uAlphaOffset", 0.5f)
        shader.setFloatUniform("uTranslateY", 0.0f)

        shader.setFloatUniform("uShadowColorMulti", 0.3f)
        shader.setFloatUniform("uShadowColorOffset", 0.3f)
        shader.setFloatUniform("uShadowNoiseScale", 5.0f)
        shader.setFloatUniform("uShadowOffset", 0.01f)

        if (isDark) {
            shader.setFloatUniform("uLightOffset", -0.1f)
            shader.setFloatUniform("uSaturateOffset", 0.2f)
            shader.setFloatUniform(
                "uPoints", floatArrayOf(
                    0.63f, 0.5f, 0.88f,
                    0.69f, 0.75f, 0.8f,
                    0.17f, 0.66f, 0.81f,
                    0.14f, 0.24f, 0.72f
                )
            )
            shader.setFloatUniform(
                "uColors", floatArrayOf(
                    0.0f, 0.31f, 0.58f, 1.0f,
                    0.53f, 0.29f, 0.15f, 1.0f,
                    0.46f, 0.06f, 0.27f, 1.0f,
                    0.16f, 0.12f, 0.45f, 1.0f
                )
            )
        } else {
            shader.setFloatUniform("uLightOffset", 0.1f)
            shader.setFloatUniform("uSaturateOffset", 0.2f)
            shader.setFloatUniform(
                "uPoints", floatArrayOf(
                    0.67f, 0.42f, 1.0f,
                    0.69f, 0.75f, 1.0f,
                    0.14f, 0.71f, 0.95f,
                    0.14f, 0.27f, 0.8f
                )
            )
            shader.setFloatUniform(
                "uColors", floatArrayOf(
                    0.57f, 0.76f, 0.98f, 1.0f,
                    0.98f, 0.85f, 0.68f, 1.0f,
                    0.98f, 0.75f, 0.93f, 1.0f,
                    0.73f, 0.7f, 0.98f, 1.0f
                )
            )
        }

        drawContext.canvas.nativeCanvas.drawPaint(Paint().apply {
            setShader(shader)
        })
    }
}