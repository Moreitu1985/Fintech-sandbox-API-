package io.ledgerforge.fraud;

import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.pem.PemSslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreDetails;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Test certificates live in src/test/resources/mtls (see the README there). */
final class MtlsSupport {

    private MtlsSupport() {
    }

    static String pem(String file) {
        try {
            return new ClassPathResource("mtls/" + file).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Server side of the fake fraud service: its certificate, and the CA whose client certs it accepts. */
    static SSLContext serverContext(String serverCert, String trustedClientCa) {
        SslBundle bundle = SslBundle.of(new PemSslStoreBundle(
                PemSslStoreDetails.forCertificate(pem(serverCert + ".crt")).withPrivateKey(pem(serverCert + ".key")),
                PemSslStoreDetails.forCertificate(pem(trustedClientCa + ".crt"))));
        return bundle.createSslContext();
    }

    static FakeFraudService start(String serverCert, String trustedClientCa) {
        try {
            return new FakeFraudService(serverContext(serverCert, trustedClientCa));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The ledger's side: an SSL bundle named "fraud-client" with its client cert and the CA it trusts. */
    static void ledgerProperties(DynamicPropertyRegistry registry, FakeFraudService fraud, String clientCert,
                                 String trustedServerCa) {
        registry.add("ledgerforge.plugins.fraud.enabled", () -> "true");
        registry.add("ledgerforge.plugins.fraud.url", fraud::url);
        registry.add("ledgerforge.plugins.fraud.token", () -> FakeFraudService.TOKEN);
        registry.add("ledgerforge.plugins.fraud.fail-open", () -> "false"); // TLS failures must be visible
        registry.add("ledgerforge.plugins.fraud.ssl-bundle", () -> "fraud-client");
        registry.add("spring.ssl.bundle.pem.fraud-client.keystore.certificate", () -> "classpath:mtls/" + clientCert + ".crt");
        registry.add("spring.ssl.bundle.pem.fraud-client.keystore.private-key", () -> "classpath:mtls/" + clientCert + ".key");
        registry.add("spring.ssl.bundle.pem.fraud-client.truststore.certificate", () -> "classpath:mtls/" + trustedServerCa + ".crt");
    }
}
