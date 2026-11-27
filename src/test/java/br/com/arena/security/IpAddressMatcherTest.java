package br.com.arena.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpAddressMatcherTest {

    @Test
    void validaIpv4Ipv6ERejeitaHostnames() {
        assertTrue(IpAddressMatcher.isValidAddress("203.0.113.10"));
        assertTrue(IpAddressMatcher.isValidAddress("::1"));
        assertTrue(IpAddressMatcher.isValidAddress("[2001:db8::1]:8080".replace(":8080", "")));
        assertFalse(IpAddressMatcher.isValidAddress("proxy.exemplo.invalid"));
        assertFalse(IpAddressMatcher.isValidAddress("999.1.1.1"));
        assertFalse(IpAddressMatcher.isValidAddress("not-an-ip"));
    }

    @Test
    void comparaIpExatoECidr() {
        assertTrue(IpAddressMatcher.matches("10.0.0.5", "10.0.0.5"));
        assertTrue(IpAddressMatcher.matches("10.0.0.5", "10.0.0.0/24"));
        assertFalse(IpAddressMatcher.matches("10.1.0.5", "10.0.0.0/24"));
        assertFalse(IpAddressMatcher.matches("10.0.0.5", "10.0.0.0/33"));
        assertFalse(IpAddressMatcher.matches("10.0.0.5", "host.invalid"));
    }

    @Test
    void normalizaZoneIdEBracket() {
        assertTrue(IpAddressMatcher.matches("fe80::1%eth0", "fe80::1"));
        assertTrue(IpAddressMatcher.matches("[::1]", "::1"));
    }
}
