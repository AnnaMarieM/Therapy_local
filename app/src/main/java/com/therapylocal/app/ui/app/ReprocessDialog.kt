package com.therapylocal.app.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.therapylocal.app.R
import com.therapylocal.app.jobs.Reprocess
import com.therapylocal.app.ui.logic.Progress
import com.therapylocal.app.ui.logic.UiText

/** Offers to read [plan]'s sessions again, with how long that should take (once the phone has measured a session) and that overnight suits it. */
@Composable
fun ReprocessDialog(t: UiText, plan: Reprocess.Plan, estimateMs: Long?, onStart: () -> Unit, onDismiss: () -> Unit) {
    val n = plan.sessions.size
    ConfirmDialog(
        stringResource(R.string.reprocess_title),
        if (estimateMs != null) t.plural(R.plurals.reprocess_body, n, n, Progress.span(t, estimateMs)) else t.plural(R.plurals.reprocess_body_unknown, n, n),
        stringResource(R.string.reprocess_confirm),
        onConfirm = onStart, onDismiss = onDismiss, destructive = false, dismiss = stringResource(R.string.reprocess_later),
    )
}
