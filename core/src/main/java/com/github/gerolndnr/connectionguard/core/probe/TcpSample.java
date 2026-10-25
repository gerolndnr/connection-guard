package com.github.gerolndnr.connectionguard.core.probe;

import java.lang.reflect.Method;
import java.util.Optional;

/** The kernel's view of one TCP connection (Linux TCP_INFO through Netty's epoll transport). */
public final class TcpSample {
    /** Linux tcpi_options bit for TCP timestamps, which take 12 bytes of every segment. */
    private static final int TIMESTAMPS = 1;
    public final long rttMicros, rttVarMicros, sndMss, advMss;
    public final boolean timestamps;

    public TcpSample(long rttMicros, long rttVarMicros, long sndMss, long advMss, boolean timestamps) {
        this.rttMicros = rttMicros; this.rttVarMicros = rttVarMicros; this.sndMss = sndMss; this.advMss = advMss; this.timestamps = timestamps;
    }
    /** The MSS the peer announced in its SYN, as far as our own MTU lets us see it. */
    public long peerMss() { return sndMss + (timestamps ? 12 : 0); }
    public double rttMillis() { return rttMicros / 1000.0; }

    private static volatile Method tcpInfo, rtt, rttVar, sndMssMethod, advMssMethod, options;

    /** Reads an io.netty.channel.epoll.EpollSocketChannel by reflection; any other transport (NIO, Windows, macOS) has no sample. */
    public static Optional<TcpSample> read(Object channel) {
        if (channel == null || !channel.getClass().getName().equals("io.netty.channel.epoll.EpollSocketChannel")) return Optional.empty();
        try {
            if (tcpInfo == null) {
                Method info = channel.getClass().getMethod("tcpInfo");
                Class<?> type = info.getReturnType();
                rtt = type.getMethod("rtt"); rttVar = type.getMethod("rttvar"); sndMssMethod = type.getMethod("sndMss");
                advMssMethod = type.getMethod("advmss"); options = type.getMethod("options");
                tcpInfo = info;
            }
            Object info = tcpInfo.invoke(channel);
            return Optional.of(new TcpSample((Long) rtt.invoke(info), (Long) rttVar.invoke(info), (Long) sndMssMethod.invoke(info),
                    (Long) advMssMethod.invoke(info), (((Integer) options.invoke(info)) & TIMESTAMPS) != 0));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }
}
