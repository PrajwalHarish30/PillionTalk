package com.prajwal.pilliontalk

import android.app.Application
import com.prajwal.pilliontalk.profile.ProfileStore
import com.prajwal.pilliontalk.ride.RideController
import com.prajwal.pilliontalk.update.Updater

/**
 * The Application object is created once, before any screen or service, and lives as long as
 * the app's process. A good home for things the whole app shares, like the ride and your profile.
 * (Registered in AndroidManifest.xml with android:name.)
 */
class PillionTalkApplication : Application() {
    val profile: ProfileStore by lazy { ProfileStore(this) }
    val rideController: RideController by lazy { RideController(this, profile) }
    val updater: Updater by lazy { Updater(this) }
}
