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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
 * Top header dropdown menu panel providing quick actions and device toggles.
 *
 * When collapsed:
 * - Shows nothing (height 0.dp) — the toggle button resides directly in the HeaderBar.
 *
 * When expanded:
 * - Slides down smoothly (~180.dp max height) with a scrollable grid/list of action tiles:
 *   📊 Statistiques, 📋 Logs & Diagnostics, 🔌 Reconnexion USB, 🔇/🔊 Couper/Activer le son.
 *
 * Zero heap allocation on fast-path: pure Compose UI outside the audio & telemetry loop.
 */
@Composable
fun HeaderMenuPanel(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenDebug: () -> Unit,
    onReconnectUsb: () -> Unit,
    onToggleMute: () -> Unit,
    isMuted: Boolean,
    isUsbConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedHeight by animateDpAsState(
        targetValue = if (isExpanded) 180.dp else 0.dp,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "headerMenuPanelHeight"
    )

    if (animatedHeight > 0.dp) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .height(animatedHeight)
                .clip(RoundedCornerShape(10.dp)),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF0F172A),
            tonalElevation = 4.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Row 1: Statistiques & Logs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MenuTileItem(
                            icon = "📊",
                            label = "Statistiques",
                            onClick = {
                                onToggleExpand()
                                onOpenStats()
                            },
                            tint = Color(0xFFFACC15),
                            modifier = Modifier.weight(1f)
                        )

                        MenuTileItem(
                            icon = "📋",
                            label = "Logs & Diag",
                            onClick = {
                                onToggleExpand()
                                onOpenDebug()
                            },
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Row 2: USB Reconnect & Audio Mute
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MenuTileItem(
                            icon = "🔌",
                            label = if (isUsbConnected) "USB connecté" else "Reconnecter USB",
                            onClick = {
                                onToggleExpand()
                                onReconnectUsb()
                            },
                            tint = if (isUsbConnected) Color(0xFF4ADE80) else Color(0xFF38BDF8),
                            modifier = Modifier.weight(1f)
                        )

                        MenuTileItem(
                            icon = if (isMuted) "🔇" else "🔊",
                            label = if (isMuted) "Son coupé" else "Son actif",
                            onClick = onToggleMute,
                            tint = if (isMuted) Color(0xFFF87171) else Color(0xFF4ADE80),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Bottom info label
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
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
            }
        }
    }
}

/**
 * Clickable tile entry inside [HeaderMenuPanel].
 */
@Composable
fun MenuTileItem(
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF131926))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier.size(26.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = icon,
                fontSize = 15.sp
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Backward compatibility wrapper for [HeaderMenuPanel].
 */
@Deprecated("Use HeaderMenuPanel integrated directly into the top header bar area")
@Suppress("UNUSED_PARAMETER")
@Composable
fun CollapsiblePushMenu(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenMap: () -> Unit = {},
    onOpenDebug: () -> Unit,
    onToggleMute: () -> Unit = {},
    isMuted: Boolean = false,
    isUsbConnected: Boolean = false,
    onReconnectUsb: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    HeaderMenuPanel(
        isExpanded = isExpanded,
        onToggleExpand = onToggleExpand,
        onOpenStats = onOpenStats,
        onOpenDebug = onOpenDebug,
        onReconnectUsb = onReconnectUsb,
        onToggleMute = onToggleMute,
        isMuted = isMuted,
        isUsbConnected = isUsbConnected,
        modifier = modifier
    )
}
