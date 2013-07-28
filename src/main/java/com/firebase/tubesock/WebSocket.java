package com.firebase.tubesock;

import org.apache.http.conn.ssl.StrictHostnameVerifier;

import javax.net.SocketFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;


public class WebSocket extends Thread {
    private static final int VERSION = 13;
    private static final String THREAD_BASE_NAME = "TubeSock";

    static final byte OPCODE_NONE = 0x0;
    static final byte OPCODE_TEXT = 0x1;
    static final byte OPCODE_BINARY = 0x2;
    static final byte OPCODE_CLOSE = 0x8;
    static final byte OPCODE_PING = 0x9;
    static final byte OPCODE_PONG = 0xA;

    private URI url = null;
    private WebSocketEventHandler eventHandler = null;

    private volatile boolean connected = false;

    private WebSocketReceiver receiver = null;
    private WebSocketWriter writer = null;
    private WebSocketHandshake handshake = null;

    public WebSocket(URI url) {
        this(url, null);
    }

    public WebSocket(URI url, String protocol) {
        this(url, protocol, null);
    }

    public WebSocket(URI url, String protocol, Map<String, String> extraHeaders) {
        this.url = url;
        handshake = new WebSocketHandshake(url, protocol, extraHeaders);
    }

    /**
     * Must be called before connect(). Set the handler for all websocket-related events.
     * @param eventHandler The handler to be triggered with relevant events
     */
    public void setEventHandler(WebSocketEventHandler eventHandler) {
        this.eventHandler = eventHandler;
    }

    WebSocketEventHandler getEventHandler() {
        return this.eventHandler;
    }

    /**
     * Start up the socket. This is non-blocking, it will fire up the threads used by the library and then trigger the
     * onOpen handler once the connection is established.
     */
    public void connect() {
        if (connected) {
            throw new WebSocketException("already connected");
        }
        setName(THREAD_BASE_NAME + "Reader");
        start();
    }

    @Override
    public void run() {
        try {
            Socket socket = createSocket();
            DataInputStream input = new DataInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();

            output.write(handshake.getHandshake());

            boolean handshakeComplete = false;
            int len = 1000;
            byte[] buffer = new byte[len];
            int pos = 0;
            ArrayList<String> handshakeLines = new ArrayList<String>();

            while (!handshakeComplete) {
                int b = input.read();
                buffer[pos] = (byte) b;
                pos += 1;

                if (buffer[pos - 1] == 0x0A && buffer[pos - 2] == 0x0D) {
                    String line = new String(buffer, "UTF-8");
                    if (line.trim().equals("")) {
                        handshakeComplete = true;
                    } else {
                        handshakeLines.add(line.trim());
                    }

                    buffer = new byte[len];
                    pos = 0;
                }
            }

            handshake.verifyServerStatusLine(handshakeLines.get(0));
            handshakeLines.remove(0);

            HashMap<String, String> headers = new HashMap<String, String>();
            for (String line : handshakeLines) {
                String[] keyValue = line.split(": ", 2);
                headers.put(keyValue[0], keyValue[1]);
            }
            handshake.verifyServerHandshakeHeaders(headers);

            connected = true;
            writer = new WebSocketWriter(output, this, THREAD_BASE_NAME);
            writer.start();
            receiver = new WebSocketReceiver(input, this);
            eventHandler.onOpen();
            receiver.run();
        } catch (WebSocketException wse) {
            eventHandler.onError(wse);
        } catch (IOException ioe) {
            eventHandler.onError(new WebSocketException("error while connecting: " + ioe.getMessage(), ioe));
        } finally {
            close();
        }
    }

    /**
     * Send a TEXT message over the socket
     * @param data The text payload to be sent
     */
    public void send(String data) {
        send(OPCODE_TEXT, data.getBytes());
    }

    /**
     * Send a BINARY message over the socket
     * @param data The binary payload to be sent
     */
    public void send(byte[] data) {
        send(OPCODE_BINARY, data);
    }

    void pong(byte[] data) {
        send(OPCODE_PONG, data);
    }

    private void send(byte opcode, byte[] data) {
        if (!connected) {
            throw new WebSocketException("error while sending data: not connected");
        }

        try {
            writer.send(opcode, true, data);
        } catch (IOException e) {
            eventHandler.onError(new WebSocketException("Failed to send frame", e));
            close();
        }
    }

    void handleReceiverError(WebSocketException e) {
        eventHandler.onError(e);
        if (connected) {
            close();
        } else {
            System.err.println("close called on closed connection");
        }
    }

    /**
     * Close down the socket. Will trigger the onClose handler if the socket has not been previously closed.
     */
    public synchronized void close()
    {
        if (!connected) {
            return;
        }

        // This method also shuts down the writer
        sendCloseHandshake();

        if (receiver.isRunning()) {
            receiver.stopit();
        }

        eventHandler.onClose();
    }

    private void sendCloseHandshake() {
        try {
            // Set the stop flag then queue up a message. This ensures that the writer thread
            // will wake up, and since we set the stop flag, it will exit its run loop.
            writer.stopIt();
            writer.send(OPCODE_CLOSE, true, new byte[0]);
        } catch (IOException e) {
            eventHandler.onError(new WebSocketException("Failed to send close frame", e));
        }

        connected = false;
    }

    private Socket createSocket() {
        String scheme = url.getScheme();
        String host = url.getHost();
        int port = url.getPort();

        Socket socket;

        if (scheme != null && scheme.equals("ws")) {
            if (port == -1) {
                port = 80;
            }
            try {
                socket = new Socket(host, port);
            } catch (UnknownHostException uhe) {
                throw new WebSocketException("unknown host: " + host, uhe);
            } catch (IOException ioe) {
                throw new WebSocketException("error while creating socket to " + url, ioe);
            }
        } else if (scheme != null && scheme.equals("wss")) {
            if (port == -1) {
                port = 443;
            }
            try {
                SocketFactory factory = SSLSocketFactory.getDefault();
                socket = factory.createSocket(host, port);
                // Make sure the cert we got is for the hostname we're expecting
                verifyHost((SSLSocket)socket, host);
            } catch (UnknownHostException uhe) {
                throw new WebSocketException("unknown host: " + host, uhe);
            } catch (IOException ioe) {
                throw new WebSocketException("error while creating secure socket to " + url, ioe);
            }
        } else {
            throw new WebSocketException("unsupported protocol: " + scheme);
        }

        return socket;
    }

    private void verifyHost(SSLSocket socket, String host) throws SSLException {
        Certificate[] certs = socket.getSession().getPeerCertificates();
        X509Certificate peerCert = (X509Certificate)certs[0];
        StrictHostnameVerifier verifier = new StrictHostnameVerifier();
        verifier.verify(host, peerCert);
    }

    public static int getVersion() {
        return VERSION;
    }

    /**
     * Blocks until both threads exit. The actual close must be triggered separately. This is just a convenience
     * method to make sure everything shuts down, if desired.
     * @throws InterruptedException
     */
    public void blockClose() throws InterruptedException {
        writer.join();
        this.join();
    }
}
