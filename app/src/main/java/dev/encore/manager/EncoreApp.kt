package dev.encore.manager

import android.app.Application
import com.topjohnwu.superuser.Shell

class EncoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must run before the first Shell.getShell() call anywhere in the process.
        Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(20))
    }
}
