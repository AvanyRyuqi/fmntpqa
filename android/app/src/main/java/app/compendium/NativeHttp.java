package app.compendium;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/**
 * The network, for the C++ engine. The engine does the transfers in its own loop (so it can
 * show progress and cancel); this class only opens a connection, follows redirects and hands
 * the body over in chunks. Plain JDK classes only, so it also runs on a desktop JVM for tests.
 */
public final class NativeHttp {
    private NativeHttp() {}

    private static volatile String userAgent = "Compendium-Android";

    public static void setUserAgent(String ua) {
        if (ua != null && !ua.isEmpty()) userAgent = ua;
    }

    /** An open response. Read it with {@link #read}, then {@link #close}. */
    public static final class Response {
        public final int status;
        public final long length; // Content-Length, or -1
        public final String finalUrl; // where redirects ended
        public final String contentRange; // header value, or ""
        private final HttpURLConnection conn;
        private InputStream in;

        Response(HttpURLConnection conn, int status, long length, String finalUrl, String contentRange) {
            this.conn = conn;
            this.status = status;
            this.length = length;
            this.finalUrl = finalUrl;
            this.contentRange = contentRange;
        }

        /** Fills up to buf.length bytes; returns how many, or -1 at the end. */
        public int read(byte[] buf) throws IOException {
            try {
                if (in == null) {
                    InputStream s = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    if (s == null) return -1;
                    in = s;
                }
                return in.read(buf);
            } catch (IOException e) {
                throw friendly(e, finalUrl);
            }
        }

        public void close() {
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
            }
            conn.disconnect();
        }
    }

    /**
     * Opens `url` with `method` ("GET" or "HEAD"), following up to 10 redirects (also from
     * http to https). rangeFrom < 0 means no Range header. `identity` turns off compression
     * so Content-Length matches the bytes of a download.
     */
    public static Response open(String url, String method, long rangeFrom, long rangeTo, boolean identity)
            throws IOException {
        String current = url;
        for (int hops = 0; hops < 10; hops++) {
            HttpURLConnection c;
            try {
                c = (HttpURLConnection) new URL(current).openConnection();
            } catch (ClassCastException | java.net.MalformedURLException e) {
                throw new IOException("That isn't a web link: " + url);
            }
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                c.setRequestMethod(method);
                c.setRequestProperty("User-Agent", userAgent);
                c.setRequestProperty("Accept", "*/*");
                if (identity) c.setRequestProperty("Accept-Encoding", "identity");
                if (rangeFrom >= 0) c.setRequestProperty("Range", "bytes=" + rangeFrom + "-" + rangeTo);
                int status = c.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null || loc.isEmpty()) throw new IOException("The server sent a redirect with nowhere to go.");
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                String range = c.getHeaderField("Content-Range");
                return new Response(c, status, c.getContentLengthLong(), current, range == null ? "" : range);
            } catch (IOException e) {
                c.disconnect();
                throw friendly(e, current);
            }
        }
        throw new IOException("Too many redirects.");
    }

    static IOException friendly(IOException e, String url) {
        String host = url;
        try {
            host = new URL(url).getHost();
        } catch (Exception ignored) {
        }
        if (e instanceof UnknownHostException)
            return new IOException("Couldn't reach " + host + ". Check that the phone is online.");
        if (e instanceof SocketTimeoutException) return new IOException("The connection to " + host + " timed out.");
        if (e instanceof SSLException) return new IOException("Couldn't make a secure connection to " + host + ": " + e.getMessage());
        String m = e.getMessage();
        return new IOException(m == null || m.isEmpty() ? e.getClass().getSimpleName() : m);
    }
}
