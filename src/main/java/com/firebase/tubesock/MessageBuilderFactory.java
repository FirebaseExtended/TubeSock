package com.firebase.tubesock;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.ArrayList;
import java.util.List;

/**
 * User: greg
 * Date: 7/25/13
 * Time: 12:19 PM
 */
public class MessageBuilderFactory {

    interface Builder {
        boolean appendBytes(byte[] bytes);

        WebSocketMessage toMessage();
    }

    static class BinaryBuilder implements Builder {
        private List<byte[]> pendingBytes;
        private int pendingByteCount = 0;

        BinaryBuilder() {
            pendingBytes = new ArrayList<byte[]>();
        }

        public boolean appendBytes(byte[] bytes) {
            pendingBytes.add(bytes);
            pendingByteCount += bytes.length;
            return true;
        }

        public WebSocketMessage toMessage() {
            byte[] payload = new byte[pendingByteCount];
            int offset = 0;
            for (int i = 0; i < pendingBytes.size(); ++i) {
                byte[] segment = pendingBytes.get(i);
                System.arraycopy(segment, 0, payload, offset, segment.length);
                offset += segment.length;
            }
            return new WebSocketMessage(payload);
        }
    }

    static class TextBuilder implements Builder {
        private StringBuilder builder;
        private ByteBuffer carryOver;

        TextBuilder() {
            builder = new StringBuilder();
        }

        public boolean appendBytes(byte[] bytes) {
            String nextFrame = decodeString(bytes);
            if (nextFrame != null) {
                builder.append(nextFrame);
                return true;
            }
            return false;
        }

        public WebSocketMessage toMessage() {
            if (carryOver != null) {
                return null;
            }
            return new WebSocketMessage(builder.toString());
        }

        private String decodeString(byte[] bytes) {
            try {
                ByteBuffer input = ByteBuffer.wrap(bytes);
                CharBuffer buf = decoder.decode(input);
                String text = buf.toString();
                return text;
            } catch (CharacterCodingException e) {
                return null;
            }
        }

        private String decodeStringStreaming(byte[] bytes) {
            try {
                ByteBuffer input = getBuffer(bytes);
                int bufSize = (int)(input.remaining() * decoder.averageCharsPerByte());
                CharBuffer output = CharBuffer.allocate(bufSize);
                for (;;) {
                    CoderResult result = decoder.decode(input, output, false);
                    if (result.isError()) {
                        return null;
                    }
                    if (result.isUnderflow()) {
                        break;
                    }
                    if (result.isOverflow()) {
                        // We need more room in our output buffer
                        bufSize = 2 * bufSize + 1;
                        CharBuffer o = CharBuffer.allocate(bufSize);
                        output.flip();
                        o.put(output);
                        output = o;
                    }
                }
                if (input.remaining() > 0) {
                    carryOver = input;
                }
                // Re-encode to work around bugs in UTF-8 decoder
                CharBuffer test = CharBuffer.wrap(output);
                encoder.encode(test);
                output.flip();
                String text = output.toString();
                return text;
            } catch (CharacterCodingException e) {
                return null;
            }
        }

        private ByteBuffer getBuffer(byte[] bytes) {
            if (carryOver != null) {
                ByteBuffer buffer = ByteBuffer.allocate(bytes.length + carryOver.remaining());
                buffer.put(carryOver);
                carryOver = null;
                buffer.put(bytes);
                buffer.flip();
                return buffer;
            } else {
                return ByteBuffer.wrap(bytes);
            }
        }
    }

    private static CharsetDecoder decoder;
    private static CharsetEncoder encoder;
    static {
        Charset utf8 = Charset.forName("UTF8");
        decoder = utf8.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        encoder = utf8.newEncoder();
        encoder.onMalformedInput(CodingErrorAction.REPORT);
        encoder.onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    static Builder builder(byte opcode) {
        if (opcode == WebSocket.OPCODE_BINARY) {
            return new BinaryBuilder();
        } else {
            // Text
            return new TextBuilder();
        }
    }
}
