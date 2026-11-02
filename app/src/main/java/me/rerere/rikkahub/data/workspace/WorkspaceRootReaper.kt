package me.rerere.rikkahub.data.workspace

/** Best-effort cleanup through the already authorized su channel, scoped to a fresh command marker. */
object WorkspaceRootReaper {
    fun script(marker: String): String {
        require(marker.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        val wanted = WorkspaceShellCommand.singleQuote("${WorkspaceProcessReaper.MARKER_ENV}=$marker")
        return """
            [ "$(id -u)" = 0 ] || exit 126
            for signal in TERM KILL; do
                for entry in /proc/[0-9]*/environ; do
                    [ -r "${'$'}entry" ] || continue
                    if tr '\000' '\n' < "${'$'}entry" 2>/dev/null | grep -Fqx -- $wanted; then
                        pid=${'$'}{entry#/proc/}
                        pid=${'$'}{pid%/environ}
                        case "${'$'}pid" in ''|*[!0-9]*) continue ;; esac
                        [ "${'$'}pid" = "${'$'}${'$'}" ] && continue
                        kill -"${'$'}signal" "${'$'}pid" 2>/dev/null || true
                    fi
                done
            done
        """.trimIndent()
    }

    fun reap(su: String, marker: String) {
        // No recursive marker/callback on the cleanup helper. A revoked su grant may prevent this;
        // arbitrary root descendants are never advertised as being perfectly sandboxed or reclaimable.
        runCatching {
            WorkspaceNativeCommandRunner.run(NativeCommandRequest(
                argv = listOf(su, "-c", script(marker)), timeoutMillis = 3_000, outputLimitBytes = 4_096,
            ))
        }
    }
}
