package com.therapylocal.app.ui.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.therapylocal.app.R
import com.therapylocal.app.config.SummaryConfig
import com.therapylocal.app.ui.design.Palette
import com.therapylocal.app.ui.design.Type
import kotlin.math.roundToInt

/** The display name and the invented example of a summary format (strings_summary.xml), by format id. A format without an entry here is not offered. */
object SummaryFormatTexts {
    class Texts(@StringRes val name: Int, @StringRes val example: Int)

    val byId: Map<String, Texts> = mapOf(
        "soap" to Texts(R.string.summary_format_soap, R.string.summary_example_soap),
        "dap" to Texts(R.string.summary_format_dap, R.string.summary_example_dap),
        "birp" to Texts(R.string.summary_format_birp, R.string.summary_example_birp),
        "narrative" to Texts(R.string.summary_format_narrative, R.string.summary_example_narrative),
    )

    /** The formats offered, in the order of [SummaryConfig.FORMATS]. */
    val ids: List<String> = SummaryConfig.FORMATS.keys.filter { it in byId }
}

/**
 * The summary format as an option-select slider: one stop per registered format, named under the track, and under it an example of the
 * selected format (an invented session, the same in every format). Stateless: [selected] is the format id shown, [onSelect] gets every stop the thumb
 * passes (the example follows it), [onSettle] gets the stop the thumb is left on or the label that was tapped, which is when a change is applied.
 */
@Composable
fun SummaryFormatPicker(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier, onSettle: (String) -> Unit = {}) {
    val ids = SummaryFormatTexts.ids
    val index = ids.indexOf(selected).coerceAtLeast(0)
    val title = stringResource(R.string.settings_summary_format_slider)
    val names = ids.map { stringResource(SummaryFormatTexts.byId.getValue(it).name) }
    Column(modifier.padding(vertical = 10.dp)) {
        Text(stringResource(R.string.settings_summary_format_intro), style = Type.BodySmall, color = Palette.InkSoft)
        Spacer(Modifier.height(6.dp))
        Slider(
            index.toFloat(), { v -> ids.getOrNull(v.roundToInt())?.let { if (it != selected) onSelect(it) } },
            onValueChangeFinished = { onSettle(selected) },
            valueRange = 0f..(ids.size - 1).toFloat(), steps = (ids.size - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(thumbColor = Palette.Ink, activeTrackColor = Palette.Ink, inactiveTrackColor = Palette.Hairline, activeTickColor = Palette.Paper, inactiveTickColor = Palette.InkFaint),
            modifier = Modifier.semantics { contentDescription = title; stateDescription = names[index] },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ids.forEachIndexed { i, id ->
                Label(names[i], modifier = Modifier.pressable({ onSelect(id); onSettle(id) }, label = names[i], role = androidx.compose.ui.semantics.Role.RadioButton, depth = 0.015f).padding(vertical = 8.dp), color = if (i == index) Palette.Ink else Palette.InkFaint)
            }
        }
        Spacer(Modifier.height(14.dp))
        Label(stringResource(R.string.settings_summary_format_example))
        Spacer(Modifier.height(8.dp))
        // Sections are separated by a blank line; where the format has headings, each paragraph starts with one that ends at the first colon.
        val hasHeadings = SummaryConfig.format(ids[index]).sections.isNotEmpty()
        stringResource(SummaryFormatTexts.byId.getValue(ids[index]).example).split("\n\n").forEach { part ->
            val colon = part.indexOf(':')
            Column(Modifier.padding(bottom = 10.dp)) {
                if (hasHeadings && colon > 0) {
                    Label(part.substring(0, colon), color = Palette.Ink)
                    Text(part.substring(colon + 1).trim(), style = Type.BodySmall, color = Palette.InkSoft)
                } else {
                    Text(part, style = Type.BodySmall, color = Palette.InkSoft)
                }
            }
        }
    }
    Hairline()
}
