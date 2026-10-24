package com.therapylocal.app.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Clients, their sessions in queue order with live stage and progress, and the facts the profile holds. Developer view of [MemoryQueue]. */
@Composable
fun MemoryTab() {
    val context = LocalContext.current
    val s by MemoryQueue.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val done = s.jobs.count { it.status == JobStatus.Done }
    val failed = s.jobs.count { it.status == JobStatus.Failed }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.running) {
                Button(onClick = { MemoryQueue.cancelRequested = true }, enabled = !MemoryQueue.cancelRequested) { Text(if (MemoryQueue.cancelRequested) "Stopping" else "Stop", fontSize = 12.sp) }
            } else {
                Button(onClick = { MemoryLab.start(context) }) { Text(if (s.jobs.isEmpty()) "Start" else "Resume", fontSize = 12.sp) }
                OutlinedButton(onClick = { MemoryQueue.retryFailed() }, enabled = s.jobs.any { it.status == JobStatus.Failed || it.status == JobStatus.Cancelled }) { Text("Retry failed", fontSize = 12.sp) }
            }
            Text("${s.message}  ·  ${s.backend}", fontSize = 12.sp)
        }
        if (s.jobs.isNotEmpty()) {
            LinearProgressIndicator(progress = { done.toFloat() / s.jobs.size }, modifier = Modifier.fillMaxWidth())
            Text("$done / ${s.jobs.size} sessions done" + if (failed > 0) ", $failed failed" else "", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        } else {
            Text("Queue empty. Put files/memory/job.json and the transcripts on the phone, then press Start.", fontSize = 12.sp)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(s.clients, key = { it }) { client ->
                val jobs = s.jobs.filter { it.client == client }
                val facts = s.profiles[client].orEmpty()
                Divider()
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(client.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("${jobs.count { it.status == JobStatus.Done }}/${jobs.size} sessions · ${facts.size} facts", fontSize = 12.sp)
                        OutlinedButton(onClick = { open = if (open == client) null else client }) { Text(if (open == client) "Hide facts" else "Facts", fontSize = 11.sp) }
                    }
                    jobs.forEach { JobRow(it) }
                    if (open == client) {
                        facts.forEach { f ->
                            Column(Modifier.padding(top = 4.dp)) {
                                Text("${f.id} · ${f.category} · ${f.session}${f.time?.let { " · $it" } ?: ""}", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                Text(f.fact, fontSize = 13.sp)
                                if (f.evidence.isNotBlank()) Text("“${f.evidence}”", fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobRow(j: SessionJob) {
    val color = when (j.status) {
        JobStatus.Done -> Color(0xFF2E7D32)
        JobStatus.Running -> Color(0xFF1565C0)
        JobStatus.Failed -> Color(0xFFC62828)
        JobStatus.Cancelled -> Color(0xFFEF6C00)
        JobStatus.Queued -> Color.Gray
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(j.status.name, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 2.dp))
            Text("${j.id.substringAfter('_')}  ${j.date}", fontSize = 12.sp, modifier = Modifier.weight(1f))
            val detail = when (j.status) {
                JobStatus.Running -> j.stage
                JobStatus.Done -> "${j.extracted} found, +${j.added} new"
                JobStatus.Failed -> j.error ?: "failed"
                else -> j.stage
            }
            Text(detail, fontSize = 11.sp)
        }
        if (j.status == JobStatus.Running && j.windows > 0) {
            LinearProgressIndicator(progress = { j.windowsDone.toFloat() / j.windows }, modifier = Modifier.fillMaxWidth())
            Text("window ${j.windowsDone}/${j.windows} · ${j.extracted} facts · prefill %.0f tok/s · decode %.1f tok/s".format(j.prefillTokPerSec, j.decodeTokPerSec), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
