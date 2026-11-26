package dev.encore.manager.data

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thin wrapper over libsu so the rest of the app never touches Shell directly. */
object RootShell {

    data class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
    }

    /**
     * Requests a root shell. If a non-root fallback shell was cached by an earlier denied
     * attempt, it is closed first so a retry after the user grants root really re-asks.
     */
    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Shell.getCachedShell()?.let { if (!it.isRoot) it.close() } }
        Shell.getShell().isRoot
    }

    suspend fun run(command: String): Result = withContext(Dispatchers.IO) {
        val r = Shell.cmd(command).exec()
        Result(r.code, r.out.joinToString("\n"), r.err.joinToString("\n"))
    }

    /** POSIX single-quote escaping, safe for any string. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
