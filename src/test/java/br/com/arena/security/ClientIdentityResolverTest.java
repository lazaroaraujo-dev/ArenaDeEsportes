package br.com.arena.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** V5.7/A4: identidade do cliente sem confiança cega em X-Forwarded-For. */
class ClientIdentityResolverTest {

    private static SecurityHardeningProperties props(boolean trust, String trustedProxies) {
        return new SecurityHardeningProperties(10, 5, 60, trust, trustedProxies, 5, 300, 20, 60);
    }

    private static MockHttpServletRequest request(String remoteAddr, String xff) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (xff != null) {
            request.addHeader("X-Forwarded-For", xff);
        }
        return request;
    }

    @Test
    void cabecalhoAusenteUsaRemoteAddress() {
        ClientIdentityResolver resolver = new ClientIdentityResolver(props(false, ""));

        assertEquals("203.0.113.10", resolver.resolve(request("203.0.113.10", null)));
    }

    @Test
    void cabecalhoPresenteComOpcaoDesabilitadaEhIgnorado() {
        ClientIdentityResolver resolver = new ClientIdentityResolver(props(false, ""));

        assertEquals("203.0.113.10",
                resolver.resolve(request("203.0.113.10", "198.51.100.99")));
    }

    @Test
    void cabecalhoFalsificadoPeloClienteNaoEhAceito() {
        ClientIdentityResolver resolver = new ClientIdentityResolver(props(false, ""));

        assertEquals("203.0.113.10",
                resolver.resolve(request("203.0.113.10", "1.2.3.4, 5.6.7.8")));
    }

    @Test
    void multiplosEnderegosComOpcaoDesabilitadaSaoIgnorados() {
        ClientIdentityResolver resolver = new ClientIdentityResolver(props(false, ""));

        assertEquals("203.0.113.10",
                resolver.resolve(request("203.0.113.10", "1.1.1.1, 2.2.2.2, 3.3.3.3")));
    }

    @Test
    void enderecoInvalidoComOpcaoDesabilitadaEhIgnorado() {
        ClientIdentityResolver resolver = new ClientIdentityResolver(props(false, ""));

        assertEquals("203.0.113.10",
                resolver.resolve(request("203.0.113.10", "not-an-ip")));
    }

    @Test
    void remoteAddressAusenteUsaMarcadorConhecido() {
        ClientIdentityResolver resolver = new ClientIdentityResolver(props(false, ""));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("");
        assertEquals("unknown", resolver.resolve(request));
    }

    @Test
    void semProxiesConfiaveisConfiguradosXffPermaneceDesabilitado() {
        ClientIdentityResolver resolver = new ClientIdentityResolver(props(true, ""));

        assertEquals("203.0.113.10",
                resolver.resolve(request("203.0.113.10", "198.51.100.99")));
    }

    @Test
    void proxyNaoConfiavelFazOCabecalhoSerIgnorado() {
        ClientIdentityResolver resolver =
                new ClientIdentityResolver(props(true, "10.0.0.1"));

        assertEquals("203.0.113.10",
                resolver.resolve(request("203.0.113.10", "198.51.100.99, 10.0.0.1")));
    }

    @Test
    void proxyConfiavelComMultiplosEnderecosRetornaPrimeiroClienteNaoConfiavel() {
        ClientIdentityResolver resolver =
                new ClientIdentityResolver(props(true, "10.0.0.1, 10.0.0.2"));

        MockHttpServletRequest request = request("10.0.0.2",
                "198.51.100.20, 10.0.0.1, 10.0.0.2");
        assertEquals("198.51.100.20", resolver.resolve(request));
    }

    @Test
    void cadeiaComCidrConfiavelEBuscaDaDireitaParaEsquerda() {
        ClientIdentityResolver resolver =
                new ClientIdentityResolver(props(true, "10.0.0.0/24"));

        MockHttpServletRequest request = request("10.0.0.5",
                "198.51.100.20, 10.0.0.4, 10.0.0.5");
        assertEquals("198.51.100.20", resolver.resolve(request));
    }

    @Test
    void todosOsHopsConfiaveisRetornaOMaisAEsquerda() {
        ClientIdentityResolver resolver =
                new ClientIdentityResolver(props(true, "10.0.0.1, 10.0.0.2"));

        MockHttpServletRequest request = request("10.0.0.2", "10.0.0.1, 10.0.0.2");
        assertEquals("10.0.0.1", resolver.resolve(request));
    }

    @Test
    void cabecalhoMalformadoComProxyConfiavelVoltaAoRemoteAddress() {
        ClientIdentityResolver resolver =
                new ClientIdentityResolver(props(true, "10.0.0.1"));

        assertEquals("10.0.0.1",
                resolver.resolve(request("10.0.0.1", "198.51.100.20, not-an-ip")));
    }

    @Test
    void hostnameNaoEhAceitoComoEndereco() {
        ClientIdentityResolver resolver =
                new ClientIdentityResolver(props(true, "10.0.0.1"));

        assertEquals("10.0.0.1",
                resolver.resolve(request("10.0.0.1", "proxy.exemplo.invalid")));
    }
}
