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
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.speedrun.SpeedrunSessionActivity

/**
 * MCAT home screen on Android — renders the shared `speedrun-home` Svelte page
 * (the same one desktop uses) in a WebView, so the landing screen mirrors
 * desktop exactly. Bridge commands route the page's buttons to native flows:
 * `start` opens the guided session, `dashboard` opens the Memory dashboard,
 * `decks` returns to the standard AnkiDroid deck picker.
 */
class SpeedrunHomePage : PageFragment() {
    override val pagePath = "speedrun-home"

    // The curriculum + Memory snapshot change after a session; reload the page
    // when the student returns so progress/mastery stay live, mirroring
    // desktop's `SpeedrunHome.refresh()`. Skip the first resume (the initial
    // load already fetched fresh data).
    private var freshlyCreated = true

    override val bridgeCommands: Map<String, () -> Unit> =
        mapOf(
            "start" to { onUi { startActivity(SpeedrunSessionActivity.getIntent(requireContext())) } },
            "dashboard" to { onUi { startActivity(SpeedrunDashboardPage.getIntent(requireContext())) } },
            "decks" to { onUi { requireActivity().finish() } },
        )

    override fun onResume() {
        super.onResume()
        if (freshlyCreated) {
            freshlyCreated = false
            return
        }
        // webViewLayout is assigned at the start of onViewCreated, which always
        // runs before onResume, so it is safe to touch here.
        webViewLayout.post { webViewLayout.reload() }
    }

    /** Bridge callbacks arrive on the JS bridge thread; UI work must hop to Main. */
    private fun onUi(block: () -> Unit) {
        if (!isAdded) return
        requireActivity().runOnUiThread { if (isAdded) block() }
    }

    companion object {
        fun getIntent(context: Context): Intent = SingleFragmentActivity.getIntent(context, SpeedrunHomePage::class)
    }
}
