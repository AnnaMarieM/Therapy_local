package com.therapylocal.app.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.therapylocal.app.models.SpeechLanguage
import com.therapylocal.app.ui.design.Palette
import com.therapylocal.app.ui.design.Type

/** One language to pick from the supported ones, as a list of radio rows. */
@Composable
fun LanguagePicker(selected: SpeechLanguage, onSelect: (SpeechLanguage) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SpeechLanguage.entries.forEach { l ->
            val label = stringResource(l.labelRes)
            Row(
                Modifier.fillMaxWidth().pressable({ if (l != selected) onSelect(l) }, label = label, depth = 0.015f).padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(22.dp)) {
                    // Drawn, not typed: the geometric shapes are not in the bundled fonts, so a typed dot would come from the system font.
                    Box(
                        Modifier.size(12.dp).then(
                            if (l == selected) Modifier.background(Palette.Ink, CircleShape)
                            else Modifier.border(1.5.dp, Palette.InkFaint, CircleShape),
                        ),
                    )
                }
                Text(label, style = Type.Body, color = Palette.Ink)
            }
            Hairline()
        }
    }
}
