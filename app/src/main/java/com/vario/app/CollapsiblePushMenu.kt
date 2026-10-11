package com.vario.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
 * Cockpit vertical bar component integrating quick actions (stats, logs)
 * with the flight instrument ladder gauge / status indicator in the central cockpit area.
 *
 * When collapsed (~44.dp):
 * - In flight Vz mode: Displays a compact expand toggle at top and the real-time VarioLadderGauge below.
 * - In stats/hike modes: Displays direct quick-action buttons (Stats 📊, Logs 📋) and mode badge.
 *
 * When expanded (~210.dp):
 * Smoothly pushes cockpit readouts to expose complete textual labels and actions.
 *
 * Zero heap allocation on fast-path: pure Compose UI outside the audio & telemetry loop.
 */
@Composable
fun CockpitVerticalBar(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenDebug: () -> Unit,
    isFlightVzView: Boolean,
    gaugeContent: @Composable () -> Unit = {},
    badgeText: String? = null,
    modifier: Modifier = Modifier
) {
    val animatedWidth by animateDpAsState(
        targetValue = if (isExpanded) 210.dp else 44.dp,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "cockpitVerticalBarWidth"
    )

    Surface(
        modifier = modifier
            .width(animatedWidth)
            .height(170.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0F172A),
        tonalElevation = 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
        ) {
            if (isExpanded) {
                // Expanded Menu: Full labels and action entries
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.Start
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Collapse Header Row
                        VerticalBarItem(
                            icon = "«",
                            label = "Réduire",
                            isExpanded = true,
                            onClick = onToggleExpand,
                            tint = Color(0xFF38BDF8)
                        )

                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color(0xFF1E293B))
                        )

                        // 1. Activity Statistics Window
                        VerticalBarItem(
                            icon = "📊",
                            label = "Statistiques",
                            isExpanded = true,
                            onClick = {
                                onToggleExpand()
                                onOpenStats()
                            },
                            tint = Color(0xFFFACC15)
                        )

                        // 2. Logs & Diagnostics Modal
                        VerticalBarItem(
                            icon = "📋",
                            label = "Logs & Diagnostics",
                            isExpanded = true,
                            onClick = {
                                onToggleExpand()
                                onOpenDebug()
                            },
                            tint = Color(0xFF94A3B8)
                        )
                    }

                    // Branding indicator
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "VarioAppli 🪂",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF64748B),
                            maxLines = 1
                        )
                    }
                }
            } else {
                // Collapsed Compact Vertical Band
                if (isFlightVzView) {
                    // Flight Vz Mode: Top expand toggle + Real-time Ladder Gauge
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 4.dp, horizontal = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF131926))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(6.dp))
                                .clickable(onClick = onToggleExpand),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "»",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            gaugeContent()
                        }
                    }
                } else {
                    // Stats / Hike / Non-Flight Mode: Action buttons + Mode indicator
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 6.dp, horizontal = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF131926))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(6.dp))
                                .clickable(onClick = onToggleExpand),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "»",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                        }

                        // 1. Stats action button
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF131926))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                                .clickable(onClick = onOpenStats),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "📊", fontSize = 17.sp)
                        }

                        // 2. Debug & logs action button
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF131926))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                                .clickable(onClick = onOpenDebug),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "📋", fontSize = 17.sp)
                        }

                        // Mode indicator badge (e.g. ▲D±▼ or emoji)
                        if (badgeText != null) {
                            Text(
                                text = badgeText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64748B)
                            )
                        } else {
                            Text(
                                text = "VA",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF475569)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single actionable item entry inside [CockpitVerticalBar].
 */
@Composable
private fun VerticalBarItem(
    icon: String,
    label: String,
    isExpanded: Boolean,
    onClick: () -> Unit,
    tint: Color = Color.White
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(Color(0xFF131926))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = icon,
                fontSize = 16.sp
            )
        }

        if (isExpanded && label.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Backward compatibility wrapper for [CockpitVerticalBar].
 */
@Deprecated("Use CockpitVerticalBar integrated directly into the central cockpit vertical band")
@Suppress("UNUSED_PARAMETER")
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
    CockpitVerticalBar(
        isExpanded = isExpanded,
        onToggleExpand = onToggleExpand,
        onOpenStats = onOpenStats,
        onOpenDebug = onOpenDebug,
        isFlightVzView = false,
        modifier = modifier
    )
}
