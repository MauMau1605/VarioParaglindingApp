package com.vario.app

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity statistics and multi-sport history window.
 *
 * Provides category filtering across sport themes ([ActivityTheme]), customizable sorting
 * ([ActivitySortOrder]), aggregated metric highlights (with special ski-flight telemetry emphasis),
 * and individual track inspection/sharing/deletion.
 */
@Composable
fun ActivityStatsScreen(
    onBackToVario: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var tracks by remember { mutableStateOf<List<TrackSummary>>(emptyList()) }
    var selectedTheme by remember { mutableStateOf(ActivityTheme.ALL) }
    var selectedSortOrder by remember { mutableStateOf(ActivitySortOrder.DATE_DESC) }
    var isSortDropdownExpanded by remember { mutableStateOf(false) }
    var trackToDelete by remember { mutableStateOf<TrackSummary?>(null) }

    fun refreshTracks() {
        tracks = GpxTrackManager.getSavedTracks(context)
    }

    LaunchedEffect(Unit) {
        refreshTracks()
    }

    val stats = remember(selectedTheme, tracks) {
        ActivityStatsCalculator.calculateStats(selectedTheme, tracks)
    }

    val filteredTracks = remember(selectedTheme, selectedSortOrder, tracks) {
        ActivityStatsCalculator.filterAndSort(tracks, selectedTheme, selectedSortOrder)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFF090D14)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // ── Top Navigation Bar ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    modifier = Modifier.clickable { onBackToVario() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(text = "←", fontSize = 16.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                        Text(text = "Retour", fontSize = 14.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold)
                    }
                }

                Text(
                    text = "Statistiques d'activités",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Box(modifier = Modifier.size(40.dp))
            }

            // ── Filter Themes Row (Tous, Vol, Randonnée, Course, Ski) ─────────
            val themes = listOf(
                ActivityTheme.ALL,
                ActivityTheme.FLIGHT,
                ActivityTheme.HIKING,
                ActivityTheme.RUNNING,
                ActivityTheme.SKI
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                themes.forEach { theme ->
                    val isSelected = selectedTheme == theme
                    val bgColor = if (isSelected) Color(0xFF0284C7) else Color(0xFF131926)
                    val borderColor = if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E293B)
                    val textColor = if (isSelected) Color.White else Color(0xFF94A3B8)

                    Surface(
                        color = bgColor,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.clickable { selectedTheme = theme }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(text = theme.emoji, fontSize = 14.sp)
                            Text(
                                text = theme.label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = textColor
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Sorting Selector Row ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${filteredTracks.size} trace(s)",
                    fontSize = 13.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Medium
                )

                Box {
                    Surface(
                        color = Color(0xFF131926),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.clickable { isSortDropdownExpanded = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Trier : ${selectedSortOrder.label}",
                                fontSize = 12.sp,
                                color = Color(0xFFCBD5E1),
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Trier",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = isSortDropdownExpanded,
                        onDismissRequest = { isSortDropdownExpanded = false },
                        modifier = Modifier.background(Color(0xFF1E293B))
                    ) {
                        ActivitySortOrder.values().forEach { sort ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = sort.label,
                                        fontSize = 13.sp,
                                        color = if (selectedSortOrder == sort) Color(0xFF38BDF8) else Color.White,
                                        fontWeight = if (selectedSortOrder == sort) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    selectedSortOrder = sort
                                    isSortDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Summary Cards Area ───────────────────────────────────────────
            if (selectedTheme == ActivityTheme.SKI) {
                // Prominent Ski & Flight Summary Highlighting Air Metrics
                SkiSummaryCard(stats = stats)
            } else {
                // General Activity Theme Summary Card
                GeneralSummaryCard(stats = stats)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Track List Area ──────────────────────────────────────────────
            if (filteredTracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF131926), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "📁", fontSize = 36.sp)
                        Text(
                            text = "Aucune trace enregistrée",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            text = "Enregistrez une activité depuis le cockpit pour la retrouver ici avec ses statistiques complètes.",
                            fontSize = 12.sp,
                            color = Color(0xFF64748B),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filteredTracks, key = { it.id }) { track ->
                        TrackItemCard(
                            track = track,
                            onShare = { GpxTrackManager.shareTrack(context, track.file) },
                            onDelete = { trackToDelete = track }
                        )
                    }
                }
            }
        }
    }

    // ── Delete Confirmation Dialog ───────────────────────────────────────────
    if (trackToDelete != null) {
        val target = trackToDelete!!
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            containerColor = Color(0xFF1E293B),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCBD5E1),
            title = {
                Text(text = "Supprimer la trace ?", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "Voulez-vous supprimer définitivement le fichier GPX \"${target.fileName}\" ? Cette action est irréversible.",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        GpxTrackManager.deleteTrack(target.file)
                        trackToDelete = null
                        refreshTracks()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Supprimer", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                Button(
                    onClick = { trackToDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                ) {
                    Text("Annuler", color = Color(0xFFE2E8F0))
                }
            }
        )
    }
}

/**
 * High-emphasis summary card tailored specifically for Ski & Flight activities.
 */
@Composable
private fun SkiSummaryCard(stats: ActivityStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131926)),
        border = BorderStroke(1.dp, Color(0xFF0284C7))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "⛷️🪂", fontSize = 18.sp)
                    Text(
                        text = "Statistiques Ski & Vol",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }
                Text(
                    text = "${stats.totalActivities} sorties",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF94A3B8)
                )
            }

            // Highlighted Three Key Metrics: Air Time, Air Distance, Average Distance
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Temps de vol total
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatDurationBrief(stats.totalFlightTimeSec),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF4ADE80)
                    )
                    Text(
                        text = "Temps de vol",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF94A3B8)
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(Color(0xFF1E293B))
                )

                // 2. Distance totale en l'air
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatDistanceShort(stats.totalAirDistanceM),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF38BDF8)
                    )
                    Text(
                        text = "Dist. en l'air",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF94A3B8)
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(Color(0xFF1E293B))
                )

                // 3. Distance moyenne
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatDistanceShort(stats.averageDistanceM),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFFACC15)
                    )
                    Text(
                        text = "Dist. moyenne",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // Secondary Metrics Row: Total Distance & Total Duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Distance totale : ${formatDistanceLong(stats.totalDistanceM)}",
                    fontSize = 12.sp,
                    color = Color(0xFFCBD5E1)
                )
                Text(
                    text = "Durée totale : ${formatDurationFull(stats.totalDurationSec)}",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
            }
        }
    }
}

/**
 * Standard summary card for General / Flight / Hiking / Running categories.
 */
@Composable
private fun GeneralSummaryCard(stats: ActivityStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131926)),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = stats.theme.emoji, fontSize = 16.sp)
                    Text(
                        text = "Synthèse ${stats.theme.label}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Text(
                    text = "${stats.totalActivities} activités",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF38BDF8)
                )
            }

            // Four-column compact grid
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                    .padding(vertical = 10.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricColumn(
                    value = formatDurationBrief(stats.totalDurationSec),
                    label = "Durée totale",
                    color = Color(0xFFE2E8F0)
                )
                MetricColumn(
                    value = formatDistanceShort(stats.totalDistanceM),
                    label = "Dist. totale",
                    color = Color(0xFF38BDF8)
                )
                MetricColumn(
                    value = formatDistanceShort(stats.averageDistanceM),
                    label = "Dist. moy.",
                    color = Color(0xFFFACC15)
                )
                MetricColumn(
                    value = "${stats.maxAltitudeM.toInt()} m",
                    label = "Plafond max",
                    color = Color(0xFF4ADE80)
                )
            }
        }
    }
}

@Composable
private fun MetricColumn(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF94A3B8)
        )
    }
}

/**
 * Individual track card with detailed metrics and share/delete buttons.
 */
@Composable
private fun TrackItemCard(
    track: TrackSummary,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.FRENCH) }
    val formattedDate = remember(track.startTimeMs) {
        dateFormat.format(Date(track.startTimeMs))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131926)),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header: Activity Emoji + Label, Date, and Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = track.activityType.emoji, fontSize = 16.sp)
                    Column {
                        Text(
                            text = track.activityType.label,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = formattedDate,
                            fontSize = 11.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Partager",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Supprimer",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Core Telemetry Metrics: Duration, Distance, Max Altitude
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricSnippet(label = "Durée", value = formatDurationFull(track.durationSec))
                MetricSnippet(label = "Distance", value = formatDistanceLong(track.totalDistanceM))
                MetricSnippet(label = "Plafond", value = "${track.maxAltitudeM.toInt()} m")
                MetricSnippet(label = "Points", value = "${track.pointCount}")
            }

            // Special Ski / Flight Airborne Badge
            if (track.airDurationSec > 0L || track.airDistanceM > 0f) {
                Surface(
                    color = Color(0xFF0284C7).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "🪂 En l'air :", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                        Text(
                            text = "${formatDurationBrief(track.airDurationSec)} • ${formatDistanceLong(track.airDistanceM)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF4ADE80)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricSnippet(label: String, value: String) {
    Column {
        Text(text = label, fontSize = 10.sp, color = Color(0xFF94A3B8))
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE2E8F0))
    }
}

// ── Formatting Utilities ─────────────────────────────────────────────────────

private fun formatDurationFull(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) {
        String.format(Locale.US, "%dh %02dm %02ds", h, m, s)
    } else {
        String.format(Locale.US, "%02dm %02ds", m, s)
    }
}

private fun formatDurationBrief(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return when {
        h > 0 -> String.format(Locale.US, "%dh%02d", h, m)
        m > 0 -> String.format(Locale.US, "%dmin", m)
        else -> String.format(Locale.US, "%ds", s)
    }
}

private fun formatDistanceShort(meters: Float): String {
    return if (meters >= 1000f) {
        String.format(Locale.US, "%.1f km", meters / 1000f)
    } else {
        "${meters.toInt()} m"
    }
}

private fun formatDistanceLong(meters: Float): String {
    return if (meters >= 1000f) {
        String.format(Locale.US, "%.2f km", meters / 1000f)
    } else {
        "${meters.toInt()} m"
    }
}
