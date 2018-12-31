package com.github.leaderelection;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A simple TCP transport handler - note that this is designed to serve the needs of both a client
 * and a server.
 * 
 * @author gaurav
 */
public final class TCPTransport {
  private static final Logger logger = LogManager.getLogger(TCPTransport.class.getSimpleName());

  private static final int xsmallMessageSize = 512; // 0.5k
  private static final int smallMessageSize = 4 * 1024; // 4k
  private static final int mediumMessageSize = 128 * 1024; // 128k
  private static final int largeMessageSize = 1024 * 1024; // 1m

  private final ConcurrentMap<UUID, ServerMetadata> activeServers = new ConcurrentHashMap<>();

  // TODO: make part of the metadata query-able by server id
  private static final class ServerMetadata {
    private final UUID id = UUID.randomUUID();
    private final String host;
    private final int port;
    private final ServerSocketChannel serverChannel;
    private final ServerListener serverListener;
    private final SocketChannel clientChannel;

    private ServerMetadata(final String host, final int port,
        final ServerSocketChannel serverChannel, final ServerListener serverListener)
        throws IOException {
      this.host = host;
      this.port = port;
      this.serverChannel = serverChannel;
      this.serverListener = serverListener;
      clientChannel = SocketChannel.open(new InetSocketAddress(host, port));
    }
  }

  /**
   * Bind and create a server socket listening on the given host,port.
   */
  public UUID bindServer(final String host, final int port, final ResponseHandler responseHandler)
      throws IOException {
    logger.info("Preparing server to listen on {}:{}", host, port);
    for (final Map.Entry<UUID, ServerMetadata> serverEntry : activeServers.entrySet()) {
      final ServerMetadata activeServer = serverEntry.getValue();
      if (activeServer.host.equals(host) && activeServer.port == port) {
        if (activeServer.serverChannel != null && activeServer.serverChannel.isOpen()) {
          return activeServer.id;
        } else {
          // stop and clear
          stopServer(activeServer.id);
          break;
        }
      }
    }
    final ServerSocketChannel serverChannel = ServerSocketChannel.open();
    serverChannel.configureBlocking(false);
    serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
    serverChannel.bind(new InetSocketAddress(host, port));

    logger.info("Server ready to accept requests on {}", serverChannel.getLocalAddress());
    final Selector selector = Selector.open();
    serverChannel.register(selector, SelectionKey.OP_ACCEPT);

    final ServerListener serverListener =
        new ServerListener(serverChannel, selector, responseHandler);
    serverListener.start();

    final ServerMetadata server = new ServerMetadata(host, port, serverChannel, serverListener);
    final UUID serverId = server.id;
    activeServers.put(serverId, server);

    return serverId;
  }

  /**
   * Send a payload to the server and receive a response from it.
   */
  public byte[] send(final UUID serverId, final byte[] payload) throws IOException {
    final ServerMetadata server = activeServers.get(serverId);
    if (server == null) {
      throw new UnsupportedOperationException(
          String.format("No server found for serverId:%s", serverId.toString()));
    }
    logger.info("Client sending to server {}:{} {}bytes payload", server.host, server.port,
        payload.length);
    final SocketChannel clientChannel = server.clientChannel;
    clientChannel.configureBlocking(false);
    final Socket socket = clientChannel.socket();
    socket.setTcpNoDelay(true);

    final int bytesWritten = write(clientChannel, payload);
    if (bytesWritten != payload.length) {
      logger.error("Failed to completely write the payload, intended:{}, actual:{}", payload.length,
          bytesWritten);
    }

    // wait a tiny while for the server to respond; hmm but this is just dumb
    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));

    final byte[] serverResponse = read(clientChannel);

    // clientChannel.close();
    logger.info("Client received response from server {}:{} {}bytes", server.host, server.port,
        serverResponse.length);
    return serverResponse;
  }

  private static byte[] read(final SocketChannel clientChannel) throws IOException {
    final ByteBuffer buffer = ByteBuffer.allocate(xsmallMessageSize);
    int bytesRead = clientChannel.read(buffer);
    int totalBytesRead = bytesRead;
    while (bytesRead > 0) {
      bytesRead = clientChannel.read(buffer);
      totalBytesRead += bytesRead;
    }
    if (bytesRead == -1) {
      // end of stream
      clientChannel.close();
      logger.info("Server closed channel to client {}", clientChannel.getRemoteAddress());
      return new byte[0];
    }
    // trim to buffer's non-zero bytes
    final byte[] bytes = new byte[totalBytesRead];
    buffer.flip();
    buffer.get(bytes);
    // logger.info("Client received response from server {}, {} bytes",
    // clientChannel.getRemoteAddress(), totalBytesRead);
    // return buffer.array();
    return bytes;
  }

  private static int write(final SocketChannel clientChannel, final byte[] payload)
      throws IOException {
    final ByteBuffer buffer = ByteBuffer.wrap(payload);
    int bytesWritten = clientChannel.write(buffer);
    int totalBytesWritten = bytesWritten;
    while (bytesWritten > 0 && buffer.hasRemaining()) {
      bytesWritten = clientChannel.write(buffer);
      totalBytesWritten += bytesWritten;
    }
    // logger.info("Server sending to client {} response:{}, payload:{} bytes, written:{} bytes",
    // clientChannel.getRemoteAddress(), new String(payload), payload.length, totalBytesWritten);
    return totalBytesWritten;
  }

  /**
   * Close the server socket for the given serverId.
   */
  public void stopServer(final UUID serverId) throws IOException {
    final ServerMetadata server = activeServers.get(serverId);
    if (server == null) {
      throw new UnsupportedOperationException(
          String.format("No server found for serverId:%s", serverId.toString()));
    }
    close(server.serverChannel);
    close(server.clientChannel);
    final Thread listenerThread = server.serverListener;
    listenerThread.interrupt();
    activeServers.remove(serverId);
  }

  public void shutdown() throws IOException {
    for (final Map.Entry<UUID, ServerMetadata> serverEntry : activeServers.entrySet()) {
      final UUID serverId = serverEntry.getKey();
      stopServer(serverId);
    }
  }

  /**
   * Close the given client channel.
   */
  private void close(final SocketChannel clientChannel) throws IOException {
    if (clientChannel != null && clientChannel.isOpen()) {
      logger.info("Closing client socket connected to {}", clientChannel.getRemoteAddress());
      clientChannel.close();
    }
  }

  /**
   * Close the given server channel.
   */
  private void close(final ServerSocketChannel serverChannel) throws IOException {
    if (serverChannel != null && serverChannel.isOpen()) {
      logger.info("Closing server socket listening on {}", serverChannel.getLocalAddress());
      serverChannel.close();
    }
  }

  private final static class ServerListener extends Thread {
    private final ServerSocketChannel serverChannel;
    private final Selector selector;
    private final ResponseHandler responseHandler;

    private ServerListener(final ServerSocketChannel serverChannel, final Selector selector,
        final ResponseHandler responseHandler) {
      setDaemon(true);
      this.serverChannel = serverChannel;
      this.selector = selector;
      this.responseHandler = responseHandler;
      try {
        final InetSocketAddress address = (InetSocketAddress) serverChannel.getLocalAddress();
        setName("acceptor-" + address.getPort());
      } catch (IOException problem) {
      }
    }

    /**
     * TODO: register a listener
     */
    private void service(final Selector selector) throws IOException {
      final ByteBuffer buffer = ByteBuffer.allocate(256);
      while (true) {
        // logger.info("In service");
        final int channelsReady = selector.select();
        if (channelsReady == 0) {
          continue;
        }
        final Set<SelectionKey> selectedKeys = selector.selectedKeys();
        final Iterator<SelectionKey> iterator = selectedKeys.iterator();
        while (iterator.hasNext()) {
          final SelectionKey key = iterator.next();

          // accept
          if (key.isValid() && key.isAcceptable()) {
            // logger.info("Key is acceptable");
            final SocketChannel clientChannel = ((ServerSocketChannel) key.channel()).accept();
            clientChannel.configureBlocking(false);
            final Socket socket = clientChannel.socket();
            socket.setTcpNoDelay(true);
            clientChannel.register(selector, SelectionKey.OP_READ);
            // clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
          }

          // read
          else if (key.isValid() && key.isReadable()) {
            // logger.info("Key is readable");
            final SocketChannel clientChannel = (SocketChannel) key.channel();
            clientChannel.configureBlocking(false);
            final Socket socket = clientChannel.socket();
            socket.setTcpNoDelay(true);

            final byte[] payload = read(clientChannel);

            if (payload.length > 0) {
              logger.info("Server received from client {} payload:{}",
                  clientChannel.getRemoteAddress(), new String(payload).trim());

              // TODO
              responseHandler.handleResponse(payload);

              // tmp: echoing back to client with tstamp
              final byte[] responseBytes = Long.toString(System.currentTimeMillis()).getBytes();
              logger.info("Server sending to client {} response:{} {}bytes",
                  clientChannel.getRemoteAddress(), new String(responseBytes),
                  responseBytes.length);

              write(clientChannel, responseBytes);
              buffer.clear();
            }
          }

          // write
          else if (key.isValid() && key.isWritable()) {
            logger.info("Key is writable");
            final SocketChannel clientChannel = (SocketChannel) key.channel();
            clientChannel.configureBlocking(false);
            final Socket socket = clientChannel.socket();
            socket.setTcpNoDelay(true);

            // final byte[] serverResponse = read(clientChannel);

            final int bytesRead = clientChannel.read(buffer);
            if (bytesRead == -1) {
              clientChannel.close();
              key.cancel();
              logger.info("Server closed channel to client {}", clientChannel.getRemoteAddress());
            }

            buffer.flip();
            final byte[] payload = buffer.array();

            if (payload.length > 0) {
              logger.info("Server received from client {} payload:{}",
                  clientChannel.getRemoteAddress(), new String(payload).trim());
              // TODO
              responseHandler.handleResponse(payload);

              // tmp: echoing back to client with tstamp
              final byte[] responseBytes = Long.toString(System.currentTimeMillis()).getBytes();
              logger.info("Server sending to client {} response:{} {}bytes",
                  clientChannel.getRemoteAddress(), new String(responseBytes),
                  responseBytes.length);

              write(clientChannel, responseBytes);
              buffer.clear();
            }
          }

          iterator.remove();
        }
      }
    }

    @Override
    public void run() {
      try {
        logger.info("Started acceptor on {}", serverChannel.getLocalAddress());
        while (!interrupted()) {
          service(selector);
          // LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100L));
        }
        logger.info("Closing acceptor on {}", serverChannel.getLocalAddress());
      } catch (IOException problem) {
      }
    }
  }

}
