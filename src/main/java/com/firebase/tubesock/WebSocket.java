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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This is the main class used to create a websocket connection. Create a new instance, set an event handler, and then
 * call connect(). Once the event handler's onOpen method has been called, call send() on the websocket to transmit
 * data.
 */
public class WebSocket extends Thread {
    private static final String THREAD_BASE_NAME = "TubeSock";
    private static final AtomicInteger clientCount = new AtomicInteger(0);

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
    private int clientId = clientCount.incrementAndGet();

    /**
     * Create a websocket to connect to a given server
     * @param url The URL of a websocket server
     */
    public WebSocket(URI url) {
        this(url, null);
    }

    /**
     * Create a websocket to connect to a given server. Include protocol in websocket handshake
     * @param url The URL of a websocket server
     * @param protocol The protocol to include in the handshake. If null, it will be omitted
     */
    public WebSocket(URI url, String protocol) {
        this(url, protocol, null);
    }

    /**
     * Create a websocket to connect to a given server. Include the given protocol in the handshake, as well as any
     * extra HTTP headers specified. Useful if you would like to include a User-Agent or other header
     * @param url The URL of a websocket server
     * @param protocol The protocol to include in the handshake. If null, it will be omitted
     * @param extraHeaders Any extra HTTP headers to be included with the initial request. Pass null if not extra headers
     *                     are requested
     */
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
        setName(THREAD_BASE_NAME + "Reader-" + clientId);
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
                } else if (pos == 1000) {
                    // This really shouldn't happen, handshake lines are short, but just to be safe...
                    String line = new String(buffer, "UTF-8");
                    throw new WebSocketException("Unexpected long line in handshake: " + line);
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
            writer = new WebSocketWriter(output, this, THREAD_BASE_NAME, clientId);
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
            // We might have been disconnected on another thread, just report an error
            eventHandler.onError(new WebSocketException("error while sending data: not connected"));
        } else {
            try {
                writer.send(opcode, true, data);
            } catch (IOException e) {
                eventHandler.onError(new WebSocketException("Failed to send frame", e));
                close();
            }
        }
    }

    void handleReceiverError(WebSocketException e) {
        eventHandler.onError(e);
        if (connected) {
            close();
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
