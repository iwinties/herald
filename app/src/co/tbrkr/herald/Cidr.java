package co.tbrkr.herald;

import java.util.ArrayList;
import java.util.List;

/** Numeric IP literals only. Parsing never performs a name lookup. */
final class Cidr {
    private final byte[] network;
    private final int bits;

    private Cidr(byte[] network, int bits) {
        this.network = network;
        this.bits = bits;
    }

    static Cidr parse(String literal) {
        if (literal == null) throw invalid();
        int slash = literal.indexOf('/');
        byte[] ip = address(slash < 0 ? literal : literal.substring(0, slash));
        int bits = ip.length * 8;
        if (slash >= 0) {
            String prefix = literal.substring(slash + 1);
            if (!prefix.matches("[0-9]{1,3}")) throw invalid();
            bits = Integer.parseInt(prefix);
            if (bits > ip.length * 8) throw invalid();
        }
        return new Cidr(ip, bits);
    }

    boolean matches(String literal) {
        try {
            byte[] ip = address(literal);
            if (ip.length != network.length) return false;
            for (int i = 0; i < bits; i += 8) {
                int mask = 0xff << Math.max(0, i + 8 - bits);
                if (((ip[i / 8] ^ network[i / 8]) & mask) != 0) return false;
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] address(String s) {
        if (s == null) throw invalid();
        if (!s.contains(":")) return ipv4(s);
        if (s.contains(".")) {
            int colon = s.lastIndexOf(':');
            byte[] tail = ipv4(s.substring(colon + 1));
            int high = (tail[0] & 255) * 256 + (tail[1] & 255);
            int low = (tail[2] & 255) * 256 + (tail[3] & 255);
            s = s.substring(0, colon + 1) + Integer.toHexString(high) + ":" + Integer.toHexString(low);
        }
        int gap = s.indexOf("::");
        List<Integer> left = words(gap < 0 ? s : s.substring(0, gap));
        List<Integer> right = words(gap < 0 ? "" : s.substring(gap + 2));
        int missing = 8 - left.size() - right.size();
        if (gap < 0 ? missing != 0 : missing < 1) throw invalid();
        while (missing-- > 0) left.add(0);
        left.addAll(right);
        byte[] result = new byte[16];
        for (int i = 0; i < 8; i++) {
            int word = left.get(i);
            result[2 * i] = (byte) (word >> 8);
            result[2 * i + 1] = (byte) word;
        }
        return result;
    }

    private static List<Integer> words(String side) {
        List<Integer> words = new ArrayList<>();
        if (!side.isEmpty()) {
            for (String word : side.split(":", -1)) {
                if (!word.matches("[0-9a-fA-F]{1,4}")) throw invalid();
                words.add(Integer.parseInt(word, 16));
            }
        }
        return words;
    }

    private static byte[] ipv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) throw invalid();
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw invalid();
            int value = Integer.parseInt(parts[i]);
            if (value > 255) throw invalid();
            result[i] = (byte) value;
        }
        return result;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid IP address or prefix");
    }
}
