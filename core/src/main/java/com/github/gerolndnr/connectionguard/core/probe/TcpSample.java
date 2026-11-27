package com.github.gerolndnr.connectionguard.core.probe;

import java.lang.reflect.Method;
import java.util.Optional;

/** The kernel's view of one TCP connection (Linux TCP_INFO through Netty's epoll transport). */
public final class TcpSample {
    /** Linux tcpi_options bits: timestamps (12 bytes of every segment), SACK, window scaling. */
    private static final int TIMESTAMPS = 1, SACK = 2, WSCALE = 4;
    public final long rttMicros, rttVarMicros, sndMss, advMss;
    public final boolean timestamps, sack;
    /** Window scale the peer announced (-1 without the option), our own; part of the peer's TCP stack fingerprint. */
    public final int peerWscale, ownWscale;

    public TcpSample(long rttMicros, long rttVarMicros, long sndMss, long advMss, boolean timestamps) {
        this(rttMicros, rttVarMicros, sndMss, advMss, timestamps, false, -1, -1);
    }
    public TcpSample(long rttMicros, long rttVarMicros, long sndMss, long advMss, boolean timestamps, boolean sack, int peerWscale, int ownWscale) {
        this.rttMicros = rttMicros; this.rttVarMicros = rttVarMicros; this.sndMss = sndMss; this.advMss = advMss; this.timestamps = timestamps;
        this.sack = sack; this.peerWscale = peerWscale; this.ownWscale = ownWscale;
    }
    /** The MSS the peer announced in its SYN, as far as our own MTU lets us see it. */
    public long peerMss() { return sndMss + (timestamps ? 12 : 0); }
    public double rttMillis() { return rttMicros / 1000.0; }

    private static volatile Method tcpInfo, rtt, rttVar, sndMssMethod, advMssMethod, options, sndWscale, rcvWscale;

    /** Reads an io.netty.channel.epoll.EpollSocketChannel by reflection; any other transport (NIO, Windows, macOS) has no sample. */
    public static Optional<TcpSample> read(Object channel) {
        if (channel == null || !channel.getClass().getName().equals("io.netty.channel.epoll.EpollSocketChannel")) return Optional.empty();
        try {
            if (tcpInfo == null) {
                Method info = channel.getClass().getMethod("tcpInfo");
                Class<?> type = info.getReturnType();
                rtt = type.getMethod("rtt"); rttVar = type.getMethod("rttvar"); sndMssMethod = type.getMethod("sndMss");
                advMssMethod = type.getMethod("advmss"); options = type.getMethod("options");
                sndWscale = type.getMethod("sndWscale"); rcvWscale = type.getMethod("rcvWscale");
                tcpInfo = info;
            }
            Object info = tcpInfo.invoke(channel);
            int opts = number(options.invoke(info)).intValue();
            boolean scaled = (opts & WSCALE) != 0;
            return Optional.of(new TcpSample(number(rtt.invoke(info)).longValue(), number(rttVar.invoke(info)).longValue(),
                    number(sndMssMethod.invoke(info)).longValue(), number(advMssMethod.invoke(info)).longValue(), (opts & TIMESTAMPS) != 0,
                    (opts & SACK) != 0, scaled ? number(sndWscale.invoke(info)).intValue() : -1, scaled ? number(rcvWscale.invoke(info)).intValue() : -1));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }
    private static Number number(Object value) { return (Number) value; }
}
