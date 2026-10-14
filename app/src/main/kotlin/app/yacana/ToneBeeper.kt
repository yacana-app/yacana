// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import app.yacana.ui.Beeper

/**
 * The flats' beeps on the alarm's volume, so that they sound with the phone on silent, face down
 * on the telescope: one beep as they start, a double beep as they end.
 */
class ToneBeeper : Beeper {
    override fun beep(end: Boolean) {
        // A phone without a tone generator free stays silent: the flats go on all the same.
        val tones = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull() ?: return
        tones.startTone(if (end) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, TONE_MILLIS)
        Handler(Looper.getMainLooper()).postDelayed(tones::release, RELEASE_MILLIS)
    }

    private companion object {
        const val TONE_MILLIS = 600
        const val RELEASE_MILLIS = 1_500L
    }
}
