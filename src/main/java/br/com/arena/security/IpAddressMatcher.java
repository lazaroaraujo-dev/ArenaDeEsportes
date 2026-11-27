package br.com.arena.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Utilitário mínimo para validar IPs e compará-los com regras de proxy confiável
 * (IP exato ou CIDR). Não resolve hostnames: apenas literais IPv4/IPv6 numéricos
 * são aceitos, evitando consulta DNS a partir de cabeçalhos controlados pelo cliente.
 */
public final class
IpAddressMatcher {

    private static final Pattern IPV4 = Pattern.compile(
            "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6_LITERAL = Pattern.compile("^[0-9a-fA-F:.]+$");

    private IpAddressMatcher() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        int zone = normalized.indexOf('%');
        if (zone >= 0) {
            normalized = normalized.substring(0, zone);
        }
        return normalized;
    }

    public static boolean isValidAddress(String value) {
        String address = normalize(value);
        if (isIpv4(address)) {
            return true;
        }
        if (address.contains(":") && IPV6_LITERAL.matcher(address).matches()) {
            return toBytes(address) != null;
        }
        return false;
    }

    public static boolean matches(String address, String rule) {
        String normalizedAddress = normalize(address);
        String normalizedRule = normalize(rule);
        if (!isValidAddress(normalizedAddress) || normalizedRule.isEmpty()) {
            return false;
        }

        int slash = normalizedRule.indexOf('/');
        if (slash < 0) {
            if (!isValidAddress(normalizedRule)) {
                return false;
            }
            byte[] a = toBytes(normalizedAddress);
            byte[] b = toBytes(normalizedRule);
            return a != null && a.length == b.length && java.util.Arrays.equals(a, b);
        }

        String base = normalizedRule.substring(0, slash);
        String prefixText = normalizedRule.substring(slash + 1);
        if (!isValidAddress(base) || !prefixText.matches("\\d+")) {
            return false;
        }

        byte[] a = toBytes(normalizedAddress);
        byte[] b = toBytes(base);
        if (a == null || a.length != b.length) {
            return false;
        }

        int prefix = Integer.parseInt(prefixText);
        int maxBits = a.length * 8;
        if (prefix < 0 || prefix > maxBits) {
            return false;
        }

        for (int bit = 0; bit < prefix; bit++) {
            int byteIndex = bit / 8;
            int mask = 0x80 >>> (bit % 8);
            if ((a[byteIndex] & mask) != (b[byteIndex] & mask)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv4(String value) {
        var matcher = IPV4.matcher(value);
        if (!matcher.matches()) {
            return false;
        }
        for (int i = 1; i <= 4; i++) {
            int octet = Integer.parseInt(matcher.group(i));
            if (octet < 0 || octet > 255) {
                return false;
            }
        }
        return true;
    }

    private static byte[] toBytes(String value) {
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException ex) {
            return null;
        }
    }
}
