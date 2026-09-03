package com.authlock.client.ui;

import javax.swing.SwingWorker;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Runs a blocking call (any {@code VaultService} RMI call, plus local
 * encrypt/decrypt/file-I/O around it) off the Swing Event Dispatch Thread,
 * then delivers the result — or the failure — back on the EDT, where it's
 * always safe to touch Swing components (UIUX.md §5 "Loading state": every
 * remote-call-triggering control must stay responsive and update safely
 * when the call finishes).
 *
 * <p>Every {@code VaultServiceImpl} RMI call blocks on network I/O; running
 * it directly on a button's {@code ActionListener} would freeze the whole
 * UI for the call's duration. This is the one shared plumbing piece behind
 * every async action in {@link LoginFrame} and {@link DashboardFrame},
 * instead of duplicating {@link SwingWorker} boilerplate at each call site.
 */
final class SwingAsync {

    private SwingAsync() {
    }

    /**
     * @param work      runs off the EDT; may throw (checked or unchecked)
     * @param onSuccess runs on the EDT with {@code work}'s result
     * @param onFailure runs on the EDT with whatever {@code work} threw
     *                  (unwrapped from {@link ExecutionException} where applicable)
     */
    static <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Exception> onFailure) {
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                try {
                    onSuccess.accept(get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    onFailure.accept(cause instanceof Exception causeEx ? causeEx : e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    onFailure.accept(e);
                }
            }
        }.execute();
    }
}
