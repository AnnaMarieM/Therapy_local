package com.therapylocal.app.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.therapylocal.app.models.SpeechLanguage
import com.therapylocal.app.ui.design.Palette
import com.therapylocal.app.ui.design.Type

/** One language to pick from the supported ones, as a list of radio rows. */
@Composable
fun LanguagePicker(selected: SpeechLanguage, onSelect: (SpeechLanguage) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SpeechLanguage.entries.forEach { l ->
            Row(
                Modifier.fillMaxWidth().pressable({ if (l != selected) onSelect(l) }, label = l.title, depth = 0.015f).padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(22.dp)) { if (l == selected) Label("●", color = Palette.Ink) else Label("○", color = Palette.InkFaint) }
                Text(l.title, style = Type.Body, color = Palette.Ink)
            }
            Hairline()
        }
    }
}
