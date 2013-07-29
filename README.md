TubeSock - A Java WebSocket Client Library
===========================================

TubeSock is a Java implementation of the client side of the WebSocket Protocol for use in Java applications.

Building / Installing
---------------------

If compiling from source, you must include
[Apache Httpcomponents Client 4.2.5](https://hc.apache.org/httpcomponents-client-ga/download.html) as well.

Usage
-----
This code snippet demonstrates using TubeSock in your code:

```java

    URI url = new URI("ws://127.0.0.1:8080/test");
    WebSocket websocket = new WebSocket(url);

    // Register Event Handlers
    websocket.setEventHandler(new WebSocketEventHandler() {
            public void onOpen() {
                System.out.println("--open");
            }

            public void onMessage(WebSocketMessage message) {
                System.out.println("--received message: " + message.getText());
            }

            public void onClose() {
                System.out.println("--close");
            }

            public void onError(WebSocketException e) {
                System.err.println(e.getMessage());
            }

            public void onLogMessage(String msg) {
                System.err.println(msg);
            }
        });

    // Establish a WebSocket Connection
    websocket.connect();

    // Send UTF-8 Text
    websocket.send("hello world");

    // Close WebSocket Connection
    websocket.close();
```

Architecture
------------

TubeSock uses two threads, one for reading and one for writing. The underlying socket is a blocking socket, or, if
using wss, a blocking SSLSocket. Using an SSLSocket is preferred over a non-blocking socket with SSLEngine due to bugs
 in some Android implementations of SSLEngine. TubeSock can make SSL-enabled connections from both a standard
 JVM as well as Android, tested on version 2.2 and forward.

When events occur, the specified handler will be called with details of the event. The onMessage method will always be
called from the reader thread. The onError and onClose methods can be called from either the writer or the reader. As
both threads perform blocking I/O, it is recommended that you handle events on your own thread.

Acknowledgements
----------------

TubeSock is based on work originally done by Roderick Baier on the weberknecht library. Renamed and relicensed with
permission.

License
-------
[MIT](http://firebase.mit-license.org).