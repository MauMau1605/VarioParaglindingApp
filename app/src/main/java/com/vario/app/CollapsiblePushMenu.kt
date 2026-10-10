package com.vario.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Collapsible push menu component for cockpit quick actions and navigation.
 *
 * When collapsed (~54.dp), displays high-contrast icons along the left screen border.
 * When expanded (~220.dp), smoothly pushes cockpit content to the right and exposes
 * complete textual labels and secondary status indicators.
 *
 * Zero heap allocation on fast-path: pure Compose UI outside the audio & telemetry loop.
 */
@Composable
fun CollapsiblePushMenu(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenDebug: () -> Unit,
    onToggleMute: () -> Unit,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedWidth by animateDpAsState(
        targetValue = if (isExpanded) 220.dp else 54.dp,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "pushMenuWidth"
    )

    Surface(
        modifier = modifier
            .width(animatedWidth)
            .fillMaxHeight(),
        color = Color(0xFF0F172A),
        tonalElevation = 6.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .border(
                    width = 1.dp,
                    color = Color(0xFF1E293B)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 12.dp, horizontal = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.Start
            ) {
                // Top section: Toggle button & Primary navigation actions
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Expand / Collapse Header Row
                    PushMenuItem(
                        icon = if (isExpanded) "«" else "»",
                        label = if (isExpanded) "Réduire «" else "",
                        isExpanded = isExpanded,
                        onClick = onToggleExpand,
                        tint = Color(0xFF38BDF8),
                        badgeText = null
                    )

                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0xFF1E293B))
                    )

                    // 1. Activity Statistics Window
                    PushMenuItem(
                        icon = "📊",
                        label = "Statistiques d'activités",
                        isExpanded = isExpanded,
                        onClick = onOpenStats,
                        tint = Color(0xFFFACC15)
                    )

                    // 2. Tactical Map Screen
                    PushMenuItem(
                        icon = "🗺️",
                        label = "Carte tactique",
                        isExpanded = isExpanded,
                        onClick = onOpenMap,
                        tint = Color(0xFF38BDF8)
                    )

                    // 3. Diagnostics & Sensors Modal
                    PushMenuItem(
                        icon = "⚙️",
                        label = "Diagnostics",
                        isExpanded = isExpanded,
                        onClick = onOpenDebug,
                        tint = Color(0xFF94A3B8)
                    )

                    // 4. Audio Vario Mute / Unmute
                    PushMenuItem(
                        icon = if (isMuted) "🔇" else "🔊",
                        label = if (isMuted) "Audio Vario (Muet)" else "Audio Vario",
                        isExpanded = isExpanded,
                        onClick = onToggleMute,
                        tint = if (isMuted) Color(0xFFEF4444) else Color(0xFF4ADE80),
                        badgeText = if (isMuted) "OFF" else "ON"
                    )
                }

                // Bottom branding indicator
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isExpanded) {
                        Text(
                            text = "VarioAppli 🪂",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF64748B),
                            maxLines = 1
                        )
                    } else {
                        Text(
                            text = "VA",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF475569)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single actionable item entry inside [CollapsiblePushMenu].
 */
@Composable
private fun PushMenuItem(
    icon: String,
    label: String,
    isExpanded: Boolean,
    onClick: () -> Unit,
    tint: Color = Color.White,
    badgeText: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(Color(0xFF131926))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        // Icon container (strictly 34.dp wide for stable collapsed centering)
        Box(
            modifier = Modifier.size(34.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = icon,
                fontSize = 17.sp
            )
        }

        // Expanded text label and optional status badge
        if (isExpanded && label.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (badgeText != null) {
                    Surface(
                        color = tint.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = tint,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
