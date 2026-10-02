package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.rules.IpNetwork;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

/** Immutable, merged CIDR intervals with logarithmic membership lookup, without address expansion.
 * A false membership result is absence of local evidence, never a negative VPN verdict. */
public final class NetworkIndex {
    public static final int MAX_RECORDS = 100000;
    private static final class Range {
        private final BigInteger first, last;
        private Range(BigInteger first, BigInteger last) { this.first = first; this.last = last; }
    }
    private final List<Range> ipv4, ipv6;
    private final int records;
    public NetworkIndex(Collection<String> networks) {
        if (networks == null || networks.size() > MAX_RECORDS) throw new IllegalArgumentException("Local list exceeds its record bound.");
        List<Range> v4 = new ArrayList<>(), v6 = new ArrayList<>();
        for (String text : networks) {
            if (text == null || text.length() > 72) throw new IllegalArgumentException("Invalid local network record.");
            IpNetwork network = IpNetwork.parse(text);
            byte[] bytes = network.getNetworkBytes();
            BigInteger first = new BigInteger(1, bytes);
            BigInteger last = first.add(BigInteger.ONE.shiftLeft(bytes.length * 8 - network.getPrefix()).subtract(BigInteger.ONE));
            (bytes.length == 4 ? v4 : v6).add(new Range(first, last));
        }
        this.ipv4 = merge(v4); this.ipv6 = merge(v6); this.records = networks.size();
    }
    private static List<Range> merge(List<Range> draft) {
        draft.sort(Comparator.comparing(range -> range.first));
        List<Range> result = new ArrayList<>();
        for (Range next : draft) {
            if (result.isEmpty()) { result.add(next); continue; }
            Range last = result.get(result.size()-1);
            if (next.first.compareTo(last.last.add(BigInteger.ONE)) <= 0) {
                result.set(result.size()-1, new Range(last.first, last.last.max(next.last)));
            } else result.add(next);
        }
        return Collections.unmodifiableList(result);
    }
    public boolean contains(String literalIp) {
        final byte[] bytes;
        try { bytes = InetAddress.getByName(Exemptions.normalize(literalIp)).getAddress(); }
        catch (UnknownHostException invalid) { throw new IllegalArgumentException("Invalid literal address."); }
        BigInteger address = new BigInteger(1, bytes);
        List<Range> ranges = bytes.length == 4 ? ipv4 : ipv6;
        int low = 0, high = ranges.size()-1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            Range range = ranges.get(middle);
            if (address.compareTo(range.first) < 0) high = middle-1;
            else if (address.compareTo(range.last) > 0) low = middle+1;
            else return true;
        }
        return false;
    }
    public int getRecords() { return records; }
    public int getIpv4Intervals() { return ipv4.size(); }
    public int getIpv6Intervals() { return ipv6.size(); }
}
