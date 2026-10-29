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
import androidx.compose.runtime.produceState
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
import com.therapylocal.app.data.Job
import com.therapylocal.app.data.JobStatus
import com.therapylocal.app.data.MindStore
import com.therapylocal.app.jobs.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Clients, their sessions' jobs in queue order with live progress, and the facts the profile holds. Developer view of the job queue. */
@Composable
fun MemoryTab() {
    val context = LocalContext.current
    val queue = remember { Jobs.queue(context) }
    val s by Jobs.snapshot.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val done = s.jobs.count { it.status == JobStatus.Done }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.running != null) {
                Button(onClick = { queue.requestStop() }, enabled = !s.stopping) { Text(if (s.stopping) "Stopping" else "Stop", fontSize = 12.sp) }
            } else {
                Button(onClick = { MemoryLab.start(context) }) { Text(if (s.jobs.isEmpty()) "Start" else "Resume", fontSize = 12.sp) }
                OutlinedButton(onClick = { queue.retryFailed(); MemoryProcessor.resumePending(context) }, enabled = s.failed > 0) { Text("Retry failed", fontSize = 12.sp) }
            }
            s.progress?.let { Text("${it.phase} ${it.step}/${it.steps}", fontSize = 12.sp) }
        }
        if (s.jobs.isNotEmpty()) {
            LinearProgressIndicator(progress = { done.toFloat() / s.jobs.size }, modifier = Modifier.fillMaxWidth())
            Text("$done / ${s.jobs.size} jobs done" + (if (s.failed > 0) ", ${s.failed} failed" else "") + if (s.blocked > 0) ", ${s.blocked} blocked" else "", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        } else {
            Text("Queue empty. Put files/memory/job.json and the transcripts on the phone, then press Start.", fontSize = 12.sp)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(s.sessions.map { it.clientId }.distinct(), key = { it }) { client ->
                val jobs = s.jobs.filter { it.clientId == client }
                val facts by produceState(emptyList<Fact>(), client, done) {
                    value = withContext(Dispatchers.IO) { MindStore.get(context).facts(client).map { it.toFact() } }
                }
                Divider()
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(client.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("${jobs.count { it.status == JobStatus.Done }}/${jobs.size} jobs · ${facts.size} facts", fontSize = 12.sp)
                        OutlinedButton(onClick = { open = if (open == client) null else client }) { Text(if (open == client) "Hide facts" else "Facts", fontSize = 11.sp) }
                    }
                    jobs.forEach { JobRow(it) }
                    if (open == client) {
                        facts.forEach { f ->
                            Column(Modifier.padding(top = 4.dp)) {
                                Text("${f.id} · ${f.subject} · ${f.session}", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
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
private fun JobRow(j: Job) {
    val color = when (j.status) {
        JobStatus.Done -> Color(0xFF2E7D32)
        JobStatus.Running -> Color(0xFF1565C0)
        JobStatus.Failed -> Color(0xFFC62828)
        JobStatus.Cancelled -> Color(0xFFEF6C00)
        JobStatus.Pending -> Color.Gray
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(j.status.name, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 2.dp))
        Text("${j.kind} ${j.sessionId.substringAfter('_')}", fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("attempt ${j.attempts}/${j.maxAttempts}" + (j.errorCode?.let { " · $it" } ?: ""), fontSize = 11.sp)
    }
}
