package com.therapylocal.app.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.therapylocal.app.R
import com.therapylocal.app.ui.design.Palette
import com.therapylocal.app.ui.design.Type

/**
 * The model download would go over mobile data (issue #55); [size] is what is still to download. Three answers, each a verb: download now,
 * wait for Wi-Fi, or not now. Back, or a tap beside it, is "Not now": nothing is downloaded over mobile data without a yes.
 */
@Composable
fun MobileDataDialog(size: String, onDownload: () -> Unit, onWait: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        containerColor = Palette.Paper,
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.setup_mobile_title), style = Type.Title, color = Palette.Ink) },
        text = { Text(stringResource(R.string.setup_mobile_body, size), style = Type.BodySmall, color = Palette.InkSoft) },
        confirmButton = { TextAction(stringResource(R.string.setup_mobile_download_now), onDownload, color = Palette.Ink) },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction(stringResource(R.string.setup_mobile_not_now), onNotNow, color = Palette.InkSoft)
                TextAction(stringResource(R.string.setup_mobile_wait_wifi), onWait, color = Palette.Ink)
            }
        },
    )
}
