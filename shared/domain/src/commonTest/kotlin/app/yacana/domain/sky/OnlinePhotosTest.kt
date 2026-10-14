// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

import app.yacana.ports.SkyPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OnlinePhotosTest {
    private val m51 = SkyPosition(13.4979733, 47.1952)

    @Test
    fun noPhotoWhileTheCacheHasTheDetail() {
        // A phone upright 3° across, 4.4° down: a photo of it, 6.6° in 3,000 pixels, would have less detail than the full cache.
        assertNull(OnlinePhotos.wanted(m51, 3.0, 4.4, emptyList()))
    }

    @Test
    fun aPhotoAsSoonAsTheCacheLooksBlurred() {
        // 2.4° across, 3.5° down: three steps in from the map's first view.
        val photo = assertNotNull(OnlinePhotos.wanted(m51, 2.4, 3.5, emptyList()))

        assertEquals(315.0, photo.fovArcmin)
    }

    @Test
    fun aPhotoAHalfWiderThanTheViewWhenZoomedIn() {
        val photo = assertNotNull(OnlinePhotos.wanted(m51, 0.6, 1.0, emptyList()))

        assertEquals(m51, photo.center)
        assertEquals(90.0, photo.fovArcmin)
    }

    @Test
    fun aPhotoThereDoesUntilTheViewLeavesItOrNeedsMoreDetail() {
        val photo = assertNotNull(OnlinePhotos.wanted(m51, 0.6, 1.0, emptyList()))
        val have = listOf(photo.tile("m51.jpg"))

        assertNull(OnlinePhotos.wanted(m51, 0.6, 1.0, have))
        // Moved a fifth of a degree north: still inside it.
        assertNull(OnlinePhotos.wanted(SkyPosition(m51.raHours, m51.decDeg + 0.2), 0.6, 1.0, have))
        // Half a degree: out of it.
        assertNotNull(OnlinePhotos.wanted(SkyPosition(m51.raHours, m51.decDeg + 0.5), 0.6, 1.0, have))
        // Zoomed in a little: it still has the detail.
        assertNull(OnlinePhotos.wanted(m51, 0.5, 0.8, have))
        // Zoomed in more than a half: it would look blurred.
        assertNotNull(OnlinePhotos.wanted(m51, 0.36, 0.6, have))
    }

    @Test
    fun shownOnlyWhereTheyAreAskedFor() {
        assertEquals(false, OnlinePhotos.shown(3.0, 4.4))
        assertEquals(true, OnlinePhotos.shown(2.4, 3.5))
    }

    @Test
    fun askedAsNinaAsksHips2Fits() {
        val photo = OnlinePhoto(SkyPosition(13.4979733, 47.1952), 30.0)

        assertEquals(
            "https://alaskybis.u-strasbg.fr/hips-image-services/hips2fits?projection=STG&hips=CDS%2FP%2FDSS2%2Fcolor" +
                "&width=3000&height=3000&fov=0.5&ra=202.4695995&dec=47.1952&format=jpg",
            Hips2Fits.url(Hips2Fits.SERVERS.first(), photo),
        )
    }

    @Test
    fun namedAsNinaNamesThePhotosOfItsCache() {
        assertEquals("13:29:53;47° 11' 43\"", OnlinePhoto(m51, 30.0).name)
        assertEquals("05:35:17;-05° 23' 28\"", OnlinePhoto(SkyPosition(5.588, -5.391), 30.0).name)
    }
}
