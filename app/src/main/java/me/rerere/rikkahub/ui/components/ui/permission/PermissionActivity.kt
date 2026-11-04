package me.rerere.rikkahub.ui.components.ui.permission

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import java.util.Collections
import java.util.IdentityHashMap

/** Dialogs and sheets expose a themed ContextWrapper, not necessarily the Activity itself. */
internal fun Context.findPermissionActivity(): ComponentActivity? {
    var current: Context? = this
    val visited = Collections.newSetFromMap(IdentityHashMap<Context, Boolean>())
    while (current != null && visited.add(current)) {
        if (current is ComponentActivity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    // A service/application context has no permission host. Never fabricate a granted state.
    return null
}
