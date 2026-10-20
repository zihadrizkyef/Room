package com.zihad.room.ui

import android.graphics.Typeface
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zihad.room.MicRoute
import com.zihad.room.PeerUi
import com.zihad.room.Requirements
import com.zihad.room.RoomState
import com.zihad.room.net.WifiStatus

// Token dari design/Room Prototype.html
private object C {
    val bg = Color(0xFF12100E)
    val surface = Color(0xFF1C1A17)
    val line = Color(0xFF34312D)
    val ink = Color(0xFFF6F4EE)
    val muted = Color(0xFFABA69C)
    val signal = Color(0xFFF5CB2E)
    val signalInk = Color(0xFF241E12)
    val live = Color(0xFF5BD982)
    val warn = Color(0xFFF7A25A)
    val warnSurface = Color(0xFF3F3021)
    val danger = Color(0xFFE5453A)
}

private val Condensed = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))
private val Eyebrow = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = C.muted)

@Composable
fun RoomScreen(
    state: RoomState,
    micDenied: Boolean,
    onToggleMute: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(C.bg)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Header(state, onStop)
        Spacer(Modifier.height(16.dp))

        if (!state.running) {
            Idle(micDenied, state.error, onStart)
            return@Column
        }

        if (state.wifi != WifiStatus.OK) {
            WifiCard(state.wifi, state.wifiName)
            Spacer(Modifier.height(12.dp))
        }
        RouteCard(state.micRoute, state.micLabel)
        Spacer(Modifier.height(20.dp))

        LazyColumn(Modifier.weight(1f)) {
            item { HorizontalDivider(color = C.line) }
            item { PeerRow(state.myName, state.myLevel, me = true, muted = state.muted, talking = state.talking) }
            items(state.peers, key = { it.id }) { PeerRow(it) }
            if (state.peers.isEmpty()) item {
                Text("Menunggu HP lain… Pastikan semua HP tersambung ke WiFi MiFi yang sama dan Room sudah dibuka.",
                    color = C.muted, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 20.dp, horizontal = 4.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        MuteButton(state.muted, onToggleMute)
    }
}

@Composable
private fun Header(state: RoomState, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Room", fontFamily = Condensed, fontSize = 36.sp, color = C.ink)
            if (state.running) {
                val connected = state.peers.count { !it.lost } + 1
                Text(if (state.peers.isEmpty()) "WiFi · mencari HP lain" else "WiFi · $connected HP tersambung",
                    color = C.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (state.running) {
            Box(
                Modifier
                    .height(48.dp)
                    .clip(CircleShape)
                    .border(1.dp, C.line, CircleShape)
                    .clickable(role = Role.Button, onClick = onStop)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Stop", color = C.muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun WifiCard(status: WifiStatus, ssid: String) {
    val text = when (status) {
        WifiStatus.NO_WIFI -> "WiFi belum tersambung. Sambungkan HP ke ${Requirements.WIFI_SSID}."
        WifiStatus.WRONG_NETWORK -> "HP tersambung ke \"$ssid\". Room hanya jalan di WiFi ${Requirements.WIFI_SSID}."
        WifiStatus.OK -> return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.warnSurface)
            .padding(16.dp)
    ) {
        Text("WIFI", style = Eyebrow)
        Text(text, color = C.ink, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp))
        Text("Suara belum dikirim maupun diputar.", color = C.muted, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RouteCard(route: MicRoute, label: String) {
    val warn = route == MicRoute.PHONE
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (warn) C.warnSurface else C.surface)
            .padding(16.dp)
    ) {
        Box(Modifier.padding(top = 22.dp).size(12.dp).clip(CircleShape)
            .background(when (route) { MicRoute.BLUETOOTH -> C.live; MicRoute.PHONE -> C.warn; MicRoute.UNKNOWN -> C.muted }))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("MIC & SUARA", style = Eyebrow)
            Text(label, color = C.ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            if (warn) {
                Text("Room hanya jalan dengan intercom ${Requirements.INTERCOM_NAME}, jadi suara belum dikirim maupun diputar. Nyalakan intercom, sambungannya otomatis.",
                    color = C.ink, fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun PeerRow(peer: PeerUi) = PeerRow(
    name = peer.name, level = peer.level, me = false, muted = false, delayMs = peer.delayMs, lost = peer.lost,
)

@Composable
private fun PeerRow(
    name: String, level: Float, me: Boolean, muted: Boolean,
    talking: Boolean = false, delayMs: Int? = null, lost: Boolean = false,
) {
    Column(Modifier.padding(vertical = 14.dp, horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text(name, fontFamily = Condensed, fontSize = 26.sp, color = if (lost) C.muted else C.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (me) Text("HP INI", style = Eyebrow, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                val (k, v) = when {
                    me -> "kamu" to when { muted -> "bisu"; talking -> "kirim"; else -> "siaga" }
                    lost -> "terputus" to "—"
                    delayMs != null -> "jaringan" to "$delayMs ms"
                    else -> "jaringan" to "…"
                }
                Text(k, color = C.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(v, color = if (lost) C.danger else C.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(10.dp))
        Meter(level, if (me) C.signal else C.live)
    }
    HorizontalDivider(color = C.line)
}

@Composable
private fun Meter(level: Float, on: Color) {
    val lit = (level * SEGMENTS).toInt()
    Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(SEGMENTS) { i ->
            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(2.dp)).background(if (i < lit) on else C.line))
        }
    }
}

@Composable
private fun MuteButton(muted: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (muted) C.danger else C.live, label = "mute")
    Column(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (muted) "Mic mati. Ketuk untuk bicara" else "Mic nyala. Ketuk untuk bisukan" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val ink = if (muted) C.ink else C.signalInk
        Text(if (muted) "MIC MATI" else "MIC NYALA", fontFamily = Condensed, fontSize = 40.sp, letterSpacing = 1.5.sp, color = ink)
        Text(if (muted) "Ketuk untuk bicara lagi" else "Ketuk untuk bisukan", fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = 0.8f))
    }
}

@Composable
private fun Idle(micDenied: Boolean, error: String?, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(
            when {
                micDenied -> "Room butuh izin mic untuk bisa ngobrol."
                error != null -> error
                else -> "Menyambungkan…"
            },
            color = C.muted, fontSize = 18.sp, lineHeight = 24.sp,
        )
        Spacer(Modifier.weight(1f))
        if (micDenied || error != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(C.signal)
                    .clickable(role = Role.Button, onClick = onStart),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (micDenied) "IZINKAN MIC" else "COBA LAGI", fontFamily = Condensed, fontSize = 26.sp,
                    color = C.signalInk, textAlign = TextAlign.Center)
            }
        }
    }
}

private const val SEGMENTS = 24

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun RoomScreenPreview() {
    RoomScreen(
        state = RoomState(
            running = true, myName = "Redmi Note 12", myLevel = 0.1f,
            micRoute = MicRoute.BLUETOOTH, micLabel = "Intercom Bluetooth",
            peers = listOf(PeerUi(1, "Samsung A15", 0.3f, 42, false), PeerUi(2, "Oppo A58", 0.6f, 48, false)),
        ),
        micDenied = false, onToggleMute = {}, onStart = {}, onStop = {},
    )
}
