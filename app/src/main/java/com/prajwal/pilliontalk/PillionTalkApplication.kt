package com.prajwal.pilliontalk

import android.app.Application
import com.prajwal.pilliontalk.ride.RideController

/**
 * The Application object is created once, before any screen or service, and lives as long as
 * the app's process. A good home for things the whole app shares, like the ride.
 * (Registered in AndroidManifest.xml with android:name.)
 */
class PillionTalkApplication : Application() {
    val rideController: RideController by lazy { RideController(this) }
}
