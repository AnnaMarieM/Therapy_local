package com.therapylocal.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therapylocal.app.ui.models.FactItem
import com.therapylocal.app.ui.models.FactOccurrence
import com.therapylocal.app.ui.models.TranscriptLine
import com.therapylocal.app.ui.theme.CharcoalText
import com.therapylocal.app.ui.theme.MutedText
import com.therapylocal.app.ui.theme.SageDark
import com.therapylocal.app.ui.theme.SageGreen
import com.therapylocal.app.ui.theme.SageLight
import com.therapylocal.app.ui.theme.SurfaceCard

/** A fact and every place it was said: the first session, then each later one, with the transcript around it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactDetailSheet(fact: FactItem, load: suspend () -> List<FactOccurrence>, onDismiss: () -> Unit) {
    var occurrences by remember(fact.id) { mutableStateOf<List<FactOccurrence>?>(null) }
    LaunchedEffect(fact.id) { occurrences = load() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceCard) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(text = fact.heading, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, color = CharcoalText)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = fact.content, fontSize = 13.sp, lineHeight = 18.sp, color = CharcoalText)
            }
            val list = occurrences
            if (list == null) item { CircularProgressIndicator(modifier = Modifier.padding(8.dp), color = SageGreen) }
            else items(list, key = { "${it.sessionId}@${it.segIdx}" }) { o ->
                Column {
                    Text(
                        text = if (o.first) "${o.date} · First heard" else "${o.date} · Came up again",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SageDark
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TranscriptLines(lines = o.lines, highlight = o.segIdx)
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/** A stretch of one session's transcript, opened from a search result; [highlight] marks a fact's evidence segment. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptSheet(title: String, load: suspend () -> List<TranscriptLine>, highlight: Int?, onDismiss: () -> Unit) {
    var lines by remember(title) { mutableStateOf<List<TranscriptLine>?>(null) }
    LaunchedEffect(title) { lines = load() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceCard) {
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            item {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, color = CharcoalText)
                Spacer(modifier = Modifier.height(10.dp))
                val l = lines
                if (l == null) CircularProgressIndicator(modifier = Modifier.padding(8.dp), color = SageGreen)
                else if (l.isEmpty()) Text(text = "No transcript stored for this session.", fontSize = 12.sp, color = MutedText)
                else TranscriptLines(lines = l, highlight = highlight)
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/** Transcript segments with their time into the session; the [highlight] segment is tinted. */
@Composable
fun TranscriptLines(lines: List<TranscriptLine>, highlight: Int? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { line ->
            val marked = line.idx == highlight
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (marked) SageLight else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(text = line.time, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MutedText)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = line.text,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = if (marked) FontWeight.SemiBold else FontWeight.Normal,
                    color = CharcoalText
                )
            }
        }
    }
}
