// User service bound through Shizuku.bindUserService (API 10+).
// This is NOT an Android component: there is no <service> tag for it in the manifest.
// Transaction 16777114 ("destroy") is reserved by the Shizuku server and must keep this id.
package me.rerere.rikkahub.data.workspace;

import android.os.ParcelFileDescriptor;

interface IWorkspaceShellService {

    void destroy() = 16777114;

    /**
     * Starts a command and returns immediately with the read side of a pipe.
     * The service writes one WorkspaceCommandWire payload and closes the write side.
     */
    ParcelFileDescriptor startCommand(
            String taskId,
            String ownerKey,
            String command,
            String cwd,
            long timeoutMillis,
            in byte[] stdin,
            int expectedUid) = 1;

    /** Cancels a command by id; also honoured when the command has not spawned yet. */
    void cancel(String taskId) = 2;

    /** Returns uid/realUid/selinux context of the user service process ("key=value" lines). */
    String probe() = 3;
}
