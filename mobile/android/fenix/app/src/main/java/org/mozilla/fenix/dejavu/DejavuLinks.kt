/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu

/** Dejavu's own pages, which the About screen links to instead of Mozilla's pages for Firefox. */
object DejavuLinks {
    const val SOURCE_CODE = "https://github.com/by-architect/Dejavu"
    const val RELEASES = "$SOURCE_CODE/releases"
    const val ISSUES = "$SOURCE_CODE/issues"
    const val PRIVACY_POLICY = "$SOURCE_CODE/blob/main/PRIVACY.md"

    /** The Google Play release's package. Every build rates that one, as no other build has a Play listing. */
    const val PLAY_STORE_ID = "com.byarchitect.dejavu"
    const val PLAY_STORE_APP_LISTING = "market://details?id=$PLAY_STORE_ID"
    const val PLAY_STORE_WEB_LISTING = "https://play.google.com/store/apps/details?id=$PLAY_STORE_ID"
}
