// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.yacana.YacanaApplication
import app.yacana.domain.session.log

/**
 * After the phone restarts, once it is first unlocked: if a sequence was going on, the
 * session starts again and resumes it where it was, as after the system closed the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as YacanaApplication
        // Not after the user disconnected the equipment by hand.
        if (SessionService.wasActive(context) && app.sequence.restore() && !SessionService.restore(context)) {
            app.journal.log("session_not_started", "reason" to "refused after a restart")
        }
    }
}
