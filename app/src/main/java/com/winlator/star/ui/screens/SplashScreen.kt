package com.winlator.star.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R

@Composable
fun SplashScreen(
    progress: Int,
    showProceed: Boolean = false,
    onProceed: () -> Unit = {},
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val dotPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 3.99f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dots",
    )
    val dots = ".".repeat(dotPhase.toInt() + 1)

    // Auto-proceed as soon as setup completes
    LaunchedEffect(showProceed, progress) {
        if (showProceed || progress >= 100) {
            onProceed()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Image(
            painter = painterResource(id = R.drawable.splash_logo),
            contentDescription = "Higurashi Splash",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        Text(
            text = "Loading$dots",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = 20.dp),
        )
    }
}
