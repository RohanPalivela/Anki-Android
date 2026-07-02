/*
 *  Copyright (c) 2026 Rohan Palivela
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.pages

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.speedrun.Speedrun

/**
 * Memory dashboard on Android — renders the shared `speedrun-dashboard` Svelte
 * page (identical to desktop) in a WebView. It shows the three-score layout
 * (Memory live, Performance/Readiness as honest M3 placeholders), each with a
 * range, coverage, graded-card count, and the abstention/give-up rule, all fed
 * by the shared `getMemoryScore` RPC.
 *
 * The only native bridge command is the destructive `reset-profile`, which
 * mirrors desktop: after confirmation it re-suspends every activated memory
 * card, clears their review history (so Memory restarts), and wipes any
 * in-progress guided session — while keeping the imported questions and cards.
 */
class SpeedrunDashboardPage : PageFragment() {
    override val pagePath = "speedrun-dashboard"

    override val bridgeCommands: Map<String, () -> Unit> =
        mapOf(
            "reset-profile" to { onUi { confirmReset() } },
        )

    private fun onUi(block: () -> Unit) {
        if (!isAdded) return
        requireActivity().runOnUiThread { if (isAdded) block() }
    }

    private fun confirmReset() {
        AlertDialog
            .Builder(requireContext())
            .setTitle(R.string.speedrun_reset_profile_action)
            .setMessage(R.string.speedrun_reset_profile_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> resetProfile() }
            .show()
    }

    private fun resetProfile() {
        launchCatchingTask {
            withCol {
                // Mirror desktop `Speedrun.reset_profile`: forget EVERY
                // first-principles memory card (clears FSRS history so Memory's
                // graded count returns to 0 — including cards that were activated
                // then re-suspended), then re-suspend them all so they start
                // inert again, and wipe any in-progress guided session. Imported
                // content (questions + cards) is kept. Selected by the shared
                // `speedrun-first-principles` tag, exactly like desktop.
                val cards = findCards("tag:${Speedrun.FIRST_PRINCIPLES_TAG}")
                if (cards.isNotEmpty()) {
                    sched.forgetCards(cards)
                    sched.suspendCards(cards)
                }
                config.remove(Speedrun.SESSION_STATE_CONFIG_KEY)
            }
            // Refresh the page so the new (abstaining) score is shown.
            webViewLayout.post { webViewLayout.reload() }
        }
    }

    companion object {
        fun getIntent(context: Context): Intent = SingleFragmentActivity.getIntent(context, SpeedrunDashboardPage::class)
    }
}
