package com.lmax.angler.monitoring.network.monitor.socket.udp;

import com.lmax.angler.monitoring.network.monitor.socket.CandidateSockets;
import com.lmax.angler.monitoring.network.monitor.socket.MonitoredSockets;
import com.lmax.angler.monitoring.network.monitor.socket.SocketIdentifier;
import com.lmax.angler.monitoring.network.monitor.socket.SocketMonitoringLifecycleListener;
import com.lmax.angler.monitoring.network.monitor.util.FileLoader;
import com.lmax.angler.monitoring.network.monitor.util.Parsers;
import com.lmax.angler.monitoring.network.monitor.util.TokenHandler;
import org.agrona.collections.Long2ObjectHashMap;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.lmax.angler.monitoring.network.monitor.socket.SocketIdentifier.asMatchAllSocketsSocketIdentifier;
import static com.lmax.angler.monitoring.network.monitor.socket.SocketIdentifier.fromInet4Address;
import static com.lmax.angler.monitoring.network.monitor.socket.SocketIdentifier.fromInet4AddressAndInode;
import static com.lmax.angler.monitoring.network.monitor.socket.SocketIdentifier.fromInet4SocketAddress;
import static com.lmax.angler.monitoring.network.monitor.socket.SocketIdentifier.fromInet4SocketAddressAndInode;

/**
 * Monitor for reporting changes in /proc/net/udp.
 */
public final class UdpSocketMonitor
{
    private final CandidateSockets candidateSockets = new CandidateSockets();
    private final MonitoredSockets<UdpBufferStats> monitoredSockets;
    private final UdpColumnHandler tokenHandler = new UdpColumnHandler(this::handleEntry);
    private final TokenHandler lineParser = Parsers.rowColumnParser(tokenHandler);

    private final FileLoader fileLoader;

    private UdpSocketStatisticsHandler statisticsHandler;
    private long updateCount = 0;

    public UdpSocketMonitor(final SocketMonitoringLifecycleListener lifecycleListener)
    {
        this(lifecycleListener, Paths.get("/proc/net/udp"));
    }

    UdpSocketMonitor(final SocketMonitoringLifecycleListener lifecycleListener, final Path pathToProcNetUdp)
    {
        fileLoader = new FileLoader(pathToProcNetUdp, 65536);
        monitoredSockets = new MonitoredSockets<>(lifecycleListener);
    }

    /**
     * Read from monitored file, report any changed values for monitored socket statistics.
     *
     * Not thread-safe, only call from a single thread.
     *
     * @param handler the callback for socket statistics
     */
    public void poll(final UdpSocketStatisticsHandler handler)
    {
        this.statisticsHandler = handler;
        try
        {
            fileLoader.load();
            final ByteBuffer buffer = fileLoader.getBuffer();

            lineParser.reset();
            lineParser.handleToken(buffer, buffer.position(), buffer.limit());
        }
        finally
        {
            this.statisticsHandler = null;
        }

        monitoredSockets.purgeEntriesOlderThan(updateCount);

        updateCount++;
    }

    /**
     * Register interest in a socket.
     *
     * Thread-safe, can be called from multiple threads concurrently.
     *
     * @param socketAddress the socket address
     */
    public void beginMonitoringOf(final InetSocketAddress socketAddress)
    {
        candidateSockets.beginMonitoringSocketIdentifier(socketAddress, fromInet4SocketAddress(socketAddress));
    }

    /**
     * De-register interest in a socket.
     *
     * Thread-safe, can be called from multiple threads concurrently.
     *
     * @param socketAddress the socket address
     */
    public void endMonitoringOf(final InetSocketAddress socketAddress)
    {
        candidateSockets.endMonitoringOfSocketIdentifier(fromInet4SocketAddress(socketAddress));
    }

    /**
     * Register interest in sockets listening to the specified address on any port.
     *
     * Thread-safe, can be called from multiple threads concurrently.
     *
     * @param inetAddress the IP address
     */
    public void beginMonitoringOf(final InetAddress inetAddress)
    {
        final long socketIdentifier = fromInet4Address(inetAddress);
        candidateSockets.beginMonitoringSocketIdentifier(new InetSocketAddress(inetAddress, 0), socketIdentifier);
    }

    /**
     * De-register interest in an IP address.
     *
     * Thread-safe, can be called from multiple threads concurrently.
     *
     * @param inetAddress the IP address
     */
    public void endMonitoringOf(final InetAddress inetAddress)
    {
        candidateSockets.endMonitoringOfSocketIdentifier(fromInet4Address(inetAddress));
    }

    /**
     * Register interest in a socket.
     *
     * Thread-safe, can be called from multiple threads concurrently.
     *
     * @param socketAddress the socket address
     * @param inode the socket inode
     */
    public void beginMonitoringOf(final InetSocketAddress socketAddress, final int inode)
    {
        candidateSockets.beginMonitoringSocketIdentifier(socketAddress, fromInet4SocketAddressAndInode(socketAddress, inode));
    }

    /**
     * De-register interest in a socket.
     *
     * Thread-safe, can be called from multiple threads concurrently.
     *
     * @param socketAddress the socket address
     * @param inode the socket inode
     */
    public void endMonitoringOf(final InetSocketAddress socketAddress, final int inode)
    {
        candidateSockets.endMonitoringOfSocketIdentifier(fromInet4SocketAddressAndInode(socketAddress, inode));
    }

    /**
     * Register interest in sockets listening to the specified address on any port.
     *
     * Thread-safe, can be called from multiple threads concurrently.
     *
     * @param inetAddress the IP address
     * @param inode the socket inode
     */
    public void beginMonitoringOf(final InetAddress inetAddress, final int inode)
    {
        final long socketIdentifier = fromInet4AddressAndInode(inetAddress, inode);
        candidateSockets.beginMonitoringSocketIdentifier(new InetSocketAddress(inetAddress, 0), socketIdentifier);
    }

    /**
     * De-register interest in an IP address.
     *
     * Thread-safe, can be called from multiple threads concurrently.
     *
     * @param inetAddress the IP address
     * @param inode the socket inode
     */
    public void endMonitoringOf(final InetAddress inetAddress, final int inode)
    {
        candidateSockets.endMonitoringOfSocketIdentifier(fromInet4AddressAndInode(inetAddress, inode));
    }

    private void handleEntry(final UdpStatsEntry entry)
    {
        final long socketIdentifier = entry.getSocketIdentifier();
        final long matchAllPortsSocketIdentifier = asMatchAllSocketsSocketIdentifier(socketIdentifier);
        final long matchAllPortsSocketInstanceIdentifier = asMatchAllSocketsSocketIdentifier(entry.getSocketInstanceIndentifier());
        final Long2ObjectHashMap<InetSocketAddress> candidateSocketsSnapshot = candidateSockets.getSnapshot();

        if(candidateSocketsSnapshot.containsKey(socketIdentifier) ||
           candidateSocketsSnapshot.containsKey(matchAllPortsSocketIdentifier) ||
           candidateSocketsSnapshot.containsKey(entry.getSocketInstanceIndentifier()) ||
           candidateSocketsSnapshot.containsKey(matchAllPortsSocketInstanceIdentifier))
        {
            final long socketInstanceIdentifier = entry.getSocketInstanceIndentifier();
            final int port = SocketIdentifier.extractPortNumber(socketIdentifier);
            if(!monitoredSockets.contains(socketInstanceIdentifier))
            {
                InetSocketAddress socketAddress = candidateSocketsSnapshot.get(socketIdentifier);
                if(socketAddress == null)
                {
                    // this is a match-all request
                    // need to construct socket address based on entry
                    socketAddress = candidateSocketsSnapshot.get(matchAllPortsSocketIdentifier);
                }
                if(socketAddress == null)
                {
                    // this is an inode-specific request
                    socketAddress = candidateSocketsSnapshot.get(entry.getSocketInstanceIndentifier());
                }
                if(socketAddress == null)
                {
                    // this is an inode-specific, match-all ports request
                    socketAddress = candidateSocketsSnapshot.get(matchAllPortsSocketInstanceIdentifier);
                }
                monitoredSockets.put(socketInstanceIdentifier,
                        new UdpBufferStats(socketAddress.getAddress(),
                                port, entry.getInode()));
            }
            final UdpBufferStats lastUpdate = monitoredSockets.get(socketInstanceIdentifier);
            lastUpdate.updateFrom(entry);
            lastUpdate.updateCount(updateCount);
            if(lastUpdate.hasChanged())
            {
                statisticsHandler.onStatisticsUpdated(
                        lastUpdate.getInetAddress(),
                        port,
                        entry.getSocketIdentifier(),
                        entry.getInode(),
                        entry.getReceiveQueueDepth(),
                        entry.getTransmitQueueDepth(),
                        entry.getDrops());
            }
        }
    }

}