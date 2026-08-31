package com.compressly.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.compressly.ui.components.ActionButton
import com.compressly.ui.theme.GradientAudio
import com.compressly.ui.theme.GradientHero
import com.compressly.ui.theme.GradientPhoto
import com.compressly.ui.theme.GradientVideo
import ir.siliksama.hajmino.R
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val icon: ImageVector,
    val gradient: List<Color>,
    val titleRes: Int,
    val bodyRes: Int
)

private val PAGES = listOf(
    OnboardingPage(Icons.Outlined.Lock,        GradientHero,  R.string.onboard_title_0, R.string.onboard_body_0),
    OnboardingPage(Icons.Outlined.PhotoCamera, GradientPhoto, R.string.onboard_title_1, R.string.onboard_body_1),
    OnboardingPage(Icons.Outlined.Videocam,    GradientVideo, R.string.onboard_title_2, R.string.onboard_body_2),
    OnboardingPage(Icons.Outlined.MusicNote,   GradientAudio, R.string.onboard_title_3, R.string.onboard_body_3),
    OnboardingPage(Icons.Outlined.Star,        GradientHero,  R.string.onboard_title_4, R.string.onboard_body_4),
)

/**
 * Full-screen onboarding carousel shown only on first launch.
 * 5 pages covering: privacy-first offline design, photo, video, audio, and
 * the smart mode. Accessible: each page has a semantic content description
 * that TalkBack announces without requiring the user to read the visual text.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pagerState = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val p = PAGES[page]
            val title = stringResource(p.titleRes)
            val body  = stringResource(p.bodyRes)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp)
                    .semantics { contentDescription = "$title. $body" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Icon card
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(36.dp))
                        .background(Brush.linearGradient(p.gradient)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = p.icon,
                        contentDescription = null, // described by parent semantics
                        tint = Color.White,
                        modifier = Modifier.size(58.dp)
                    )
                }
                Spacer(Modifier.height(36.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.4
                )
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 32.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Page dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(PAGES.size) { idx ->
                    val selected = pagerState.currentPage == idx
                    val dotWidth by animateDpAsState(
                        if (selected) 24.dp else 8.dp,
                        spring(stiffness = Spring.StiffnessMedium),
                        label = "dot"
                    )
                    Box(
                        modifier = Modifier
                            .height(8.dp)
                            .width(dotWidth)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            val isLast = pagerState.currentPage == PAGES.size - 1
            ActionButton(
                text = if (isLast) stringResource(R.string.onboard_start)
                       else stringResource(R.string.onboard_next),
                onClick = {
                    if (isLast) {
                        onDone()
                    } else {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                }
            )
        }
    }
}
