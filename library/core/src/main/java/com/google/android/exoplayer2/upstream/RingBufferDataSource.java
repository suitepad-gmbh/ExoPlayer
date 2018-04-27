/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.upstream;

import android.net.Uri;
import android.util.Log;

import com.google.android.exoplayer2.C;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A UDP {@link DataSource}.
 */
public final class RingBufferDataSource implements DataSource {

    private static final String TAG = "RingBufferDataSource";

    /**
     * The default maximum datagram packet size, in bytes.
     */
    public static final int DEFAULT_MAX_PACKET_SIZE = 2000;

    /**
     * The default socket timeout, in milliseconds.
     */
    public static final int DEAFULT_SOCKET_TIMEOUT_MILLIS = 8 * 1000;

    private final TransferListener<? super RingBufferDataSource> listener;
    private final int socketTimeoutMillis;
    private final byte[] packetBuffer;
    private final DatagramPacket packet;

    private Uri uri;
    private DatagramSocket socket;
    private MulticastSocket multicastSocket;
    private InetAddress address;
    private InetSocketAddress socketAddress;
    private android.os.Handler streamReaderHandler;
    private LinkedBlockingQueue<BufferedPacket> linkedBuffer;
    //    private CircularFifoQueue<BufferedPacket> buf;
    private BufferedPacket currentPacket;
    private boolean opened;
    private int packetCount = 0, packetIndex = 0;

    private int packetRemaining;
    private boolean logStackIsEmpty;

    /**
     * @param listener An optional listener.
     */
    public RingBufferDataSource(TransferListener<? super RingBufferDataSource> listener) {
        this(listener, DEFAULT_MAX_PACKET_SIZE);
    }

    /**
     * @param listener      An optional listener.
     * @param maxPacketSize The maximum datagram packet size, in bytes.
     */
    public RingBufferDataSource(TransferListener<? super RingBufferDataSource> listener, int maxPacketSize) {
        this(listener, maxPacketSize, DEAFULT_SOCKET_TIMEOUT_MILLIS);
    }

    /**
     * @param listener            An optional listener.
     * @param maxPacketSize       The maximum datagram packet size, in bytes.
     * @param socketTimeoutMillis The socket timeout in milliseconds. A timeout of zero is interpreted
     *                            as an infinite timeout.
     */
    public RingBufferDataSource(TransferListener<? super RingBufferDataSource> listener, int maxPacketSize,
                                int socketTimeoutMillis) {
        this.listener = listener;
        this.socketTimeoutMillis = socketTimeoutMillis;
        packetBuffer = new byte[maxPacketSize];
        packet = new DatagramPacket(packetBuffer, 0, maxPacketSize);
//        buf = new CircularFifoQueue<>(100);
        linkedBuffer = new LinkedBlockingQueue<>();
        streamReaderHandler = new android.os.Handler();
    }

    private Runnable runnable = new Runnable() {
        @Override
        public void run() {
            // We've read all of the data from the current packet. Get another.
            try {
                while (true) {
                    try {
                        socket.receive(packet);
                        //                        buf.add(new BufferedPacket(
                        //                                packetBuffer, packet.getLength()
                        //                        ));
                        linkedBuffer.put(new BufferedPacket(
                                packetBuffer, packet.getLength()
                        ));
                        packetCount++;
                        Log.d(TAG, "receiving packet N°" + packetCount + " size: " + linkedBuffer.size());
                    } catch (IOException e) {
                        throw new UdpDataSource.UdpDataSourceException(e);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (!opened) {
                        break;
                    }
                }
            } catch (UdpDataSource.UdpDataSourceException e) {
                e.printStackTrace();
            }
        }
    };

    @Override
    public long open(DataSpec dataSpec) throws UdpDataSource.UdpDataSourceException {
        Log.d(TAG, "open: ");
        packetCount = 0;
        uri = dataSpec.uri;
        String host = uri.getHost();
        int port = uri.getPort();

        try {
            address = InetAddress.getByName(host);
            socketAddress = new InetSocketAddress(address, port);
            if (address.isMulticastAddress()) {
                multicastSocket = new MulticastSocket(socketAddress);
                multicastSocket.joinGroup(address);
                socket = multicastSocket;
            } else {
                socket = new DatagramSocket(socketAddress);
            }
        } catch (IOException e) {
            throw new UdpDataSource.UdpDataSourceException(e);
        }

        try {
            socket.setSoTimeout(socketTimeoutMillis);
        } catch (SocketException e) {
            throw new UdpDataSource.UdpDataSourceException(e);
        }

        opened = true;
        if (listener != null) {
            listener.onTransferStart(this, dataSpec);
        }
        new Thread(runnable).start();
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws UdpDataSource.UdpDataSourceException {
        boolean currentPacketAvailable = currentPacket != null && currentPacket.remaining > 0;
        if (readLength == 0) {
            return 0;
        }

        if (currentPacket == null || currentPacket.remaining == 0) {
            if (linkedBuffer.isEmpty()) {
                if (logStackIsEmpty) {
                    logStackIsEmpty = false;
                    Log.w(TAG, "read: stack is empty");
                }
                return 0;
            }
            try {
                currentPacket = linkedBuffer.take();
                logStackIsEmpty = true;
                packetIndex++;
                Log.i(TAG, "reading -> stack size = " + linkedBuffer.size());
            } catch (InterruptedException e) {
                e.printStackTrace();
                return 0;
            }
        }

        int packetOffset = currentPacket.length - currentPacket.remaining;
        int bytesToRead = Math.min(currentPacket.remaining, readLength);
        System.arraycopy(currentPacket.buffer, packetOffset, buffer, offset, bytesToRead);
//        Log.v(TAG, "read(" + packetIndex + "/" + packetCount + ":" + linkedBuffer.size() + "): "
//                + bytesToRead + "/" + readLength + ":" + currentPacket.remaining);
//        Log.i(TAG, new String(currentPacket.buffer, packetOffset, bytesToRead));
        if (listener != null) {
            listener.onBytesTransferred(RingBufferDataSource.this, bytesToRead);
        }
        currentPacket.remaining -= bytesToRead;
        return bytesToRead;
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        Log.d(TAG, "close: ");
        uri = null;
        if (multicastSocket != null) {
            try {
                multicastSocket.leaveGroup(address);
            } catch (IOException e) {
                // Do nothing.
            }
            multicastSocket = null;
        }
        if (socket != null) {
            socket.close();
            socket = null;
        }
        address = null;
        socketAddress = null;
        packetRemaining = 0;
        if (opened) {
            opened = false;
            if (listener != null) {
                listener.onTransferEnd(this);
            }
        }
        linkedBuffer.clear();
    }

    private class BufferedPacket {
        byte[] buffer;
        int length;
        int remaining;

        public BufferedPacket(byte[] buffer, int length) {
            this.buffer = buffer;
            this.length = length;
            this.remaining = length;
        }

    }

}
