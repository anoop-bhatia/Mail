package eu.faircode.email;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.net.URL;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;

class ZohoTestHttpsConnection extends HttpsURLConnection {
    final String response;
    final int responseCode;
    private String requestMethod = "GET";
    final Map<String, String> responseHeaders = new HashMap<>();
    final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
    long errorStreamBytesRead;

    ZohoTestHttpsConnection(URL url, int responseCode, String response) {
        super(url);
        this.responseCode = responseCode;
        this.response = (response == null ? "" : response);
    }

    @Override
    public void disconnect() {
    }

    @Override
    public boolean usingProxy() {
        return false;
    }

    @Override
    public void connect() {
    }

    @Override
    public int getResponseCode() throws IOException {
        return responseCode;
    }

    @Override
    public void setRequestMethod(String method) throws ProtocolException {
        requestMethod = method;
    }

    @Override
    public String getRequestMethod() {
        return requestMethod;
    }

    @Override
    public String getHeaderField(String name) {
        return responseHeaders.get(name);
    }

    @Override
    public int getContentLength() {
        String value = getHeaderField("Content-Length");
        if (value == null)
            return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if (responseCode < 200 || responseCode >= 300)
            throw new IOException("unexpected test error response");
        return new ByteArrayInputStream(response.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public InputStream getErrorStream() {
        if (responseCode >= 200 && responseCode < 300)
            return null;
        return new ByteArrayInputStream(response.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override
            public synchronized int read() {
                int value = super.read();
                if (value != -1)
                    errorStreamBytesRead++;
                return value;
            }

            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                int count = super.read(buffer, offset, length);
                if (count > 0)
                    errorStreamBytesRead += count;
                return count;
            }
        };
    }

    @Override
    public OutputStream getOutputStream() {
        return requestBody;
    }

    @Override
    public String getCipherSuite() {
        return "TLS_TEST";
    }

    @Override
    public Certificate[] getLocalCertificates() {
        return null;
    }

    @Override
    public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
        return null;
    }

    @Override
    public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
        return null;
    }

    @Override
    public Principal getLocalPrincipal() {
        return null;
    }
}
