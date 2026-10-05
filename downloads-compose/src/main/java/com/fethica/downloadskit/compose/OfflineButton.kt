package com.fethica.downloadskit.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.fethica.downloadskit.DownloadState

/**
 * One icon slot that morphs with [state]: a down arrow to ask for a download, a clock
 * while it waits, a progress ring around a stop square while it runs, a filled check
 * once it is on disk, a warning when it failed.
 *
 * The whole thing is a 44 dp touch target with a per-state `contentDescription`, and
 * every colour comes from the host's `MaterialTheme`.
 *
 * @param onClick what a tap means depends on the state: start, stop, or retry. Taps are
 *   ignored while the item is being removed.
 */
@Composable
public fun OfflineButton(
    state: DownloadState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val scheme = MaterialTheme.colorScheme
    val description = buildContentDescription(state)
    val interactionSource = remember { MutableInteractionSource() }

    val target = ringProgress(state) ?: 0f
    val progress by animateFloatAsState(targetValue = target, label = "offlineProgress")
    val spin by rememberInfiniteTransition(label = "offlineSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing)),
        label = "offlineSpinAngle",
    )

    Box(
        modifier = modifier
            .size(TOUCH_TARGET)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(bounded = false, radius = TOUCH_TARGET / 2),
                enabled = state != DownloadState.Removing,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(GLYPH)) {
            when (state) {
                DownloadState.NotDownloaded -> drawDownloadArrow(tint)
                DownloadState.Queued -> drawClock(tint)
                is DownloadState.Downloading -> {
                    drawProgressRing(
                        trackColor = tint.copy(alpha = 0.24f),
                        progressColor = scheme.primary,
                        progress = ringProgress(state)?.let { progress },
                        spinDegrees = spin,
                    )
                    drawStopSquare(scheme.primary)
                }
                is DownloadState.Downloaded -> drawCheckInCircle(scheme.primary, scheme.onPrimary)
                is DownloadState.Failed -> drawWarning(scheme.error)
                DownloadState.Removing -> drawBin(tint.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun buildContentDescription(state: DownloadState): String {
    val percent = contentDescriptionPercent(state)
    val base = if (percent != null) {
        stringResource(contentDescriptionRes(state), percent)
    } else {
        stringResource(contentDescriptionRes(state))
    }
    val failure = (state as? DownloadState.Failed)?.reason ?: return base
    return "${stringResource(failureMessageRes(failure))}. $base"
}

private val TOUCH_TARGET = 44.dp
private val GLYPH = 24.dp
