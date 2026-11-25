package app.compendium;

import java.nio.charset.StandardCharsets;

/**
 * The C++ engine (libcompendium.so). Every call takes and returns JSON text in the form
 * {"ok":true,"data":...} or {"ok":false,"error":"..."}; see engine.hpp. Strings cross the
 * boundary as UTF-8 bytes, because JNI's own string functions mangle characters outside the
 * basic multilingual plane (emoji in mod names).
 *
 * Calls block. Run them off the main thread; long ones accept a ProgressListener.
 */
public final class Native {
    private Native() {}

    static {
        System.loadLibrary("compendium");
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static native byte[] nInit(byte[] dataDir, byte[] outputRoot, byte[] gameVersion, byte[] userAgent);
    private static native void nShutdown();
    private static native void nSetGameVersion(byte[] version);
    private static native byte[] nBrowse(byte[] paramsJson);
    private static native byte[] nDetails(long modId);
    private static native byte[] nResolveLink(byte[] text);
    private static native byte[] nPrepare(byte[] detailsJson, int fileIndex, ProgressListener l);
    private static native byte[] nConflicts(int handle, int[] variants, byte[] replaceIdsJson);
    private static native byte[] nFinish(int handle, int[] variants, byte[] replaceIdsJson, ProgressListener l);
    private static native void nDiscard(int handle);
    private static native byte[] nLibrary();
    private static native byte[] nSetEnabled(byte[] id, boolean enabled, ProgressListener l);
    private static native byte[] nRemove(byte[] id, ProgressListener l);
    private static native byte[] nMove(byte[] id, int delta, ProgressListener l);
    private static native byte[] nRebuild(ProgressListener l);
    private static native byte[] nLog(int maxBytes);
    private static native byte[] nStorage();
    private static native byte[] nClearDownloads();

    public static String init(String dataDir, String outputRoot, String gameVersion, String userAgent) {
        return s(nInit(b(dataDir), b(outputRoot), b(gameVersion), b(userAgent)));
    }
    public static void shutdown() { nShutdown(); }
    public static void setGameVersion(String v) { nSetGameVersion(b(v)); }
    public static String browse(String paramsJson) { return s(nBrowse(b(paramsJson))); }
    public static String details(long modId) { return s(nDetails(modId)); }
    public static String resolveLink(String text) { return s(nResolveLink(b(text))); }
    public static String prepare(String detailsJson, int fileIndex, ProgressListener l) {
        return s(nPrepare(b(detailsJson), fileIndex, l));
    }
    public static String conflicts(int handle, int[] variants, String replaceIdsJson) {
        return s(nConflicts(handle, variants, b(replaceIdsJson)));
    }
    public static String finish(int handle, int[] variants, String replaceIdsJson, ProgressListener l) {
        return s(nFinish(handle, variants, b(replaceIdsJson), l));
    }
    public static void discard(int handle) { nDiscard(handle); }
    public static String library() { return s(nLibrary()); }
    public static String setEnabled(String id, boolean enabled, ProgressListener l) { return s(nSetEnabled(b(id), enabled, l)); }
    public static String remove(String id, ProgressListener l) { return s(nRemove(b(id), l)); }
    public static String move(String id, int delta, ProgressListener l) { return s(nMove(b(id), delta, l)); }
    public static String rebuild(ProgressListener l) { return s(nRebuild(l)); }
    public static String log(int maxBytes) { return s(nLog(maxBytes)); }
    public static String storage() { return s(nStorage()); }
    public static String clearDownloads() { return s(nClearDownloads()); }
}
