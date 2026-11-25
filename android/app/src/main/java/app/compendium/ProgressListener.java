package app.compendium;

/** Called by the engine during long jobs. Return false to cancel. */
public interface ProgressListener {
    boolean onProgress(long done, long total, String text);
}
