/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.security.ldap;

import com.google.common.collect.ImmutableSet;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.apache.directory.api.ldap.model.exception.LdapOperationException;
import org.apache.directory.api.ldap.model.exception.LdapProtocolErrorException;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.assertj.core.api.Assertions;
import org.graylog2.rest.models.system.ldap.requests.LdapTestConfigRequest;
import org.graylog2.security.DefaultX509TrustManager;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Testcontainers
public class LdapConnectorSSLTLSIT {
    private static final int DEFAULT_TIMEOUT = 60 * 1000;
    private static final Set<String> ENABLED_TLS_PROTOCOLS = ImmutableSet.of("TLSv1.2");
    private static final String NETWORK_ALIAS = "ldapserver";
    private static final Integer PORT = 389;
    private static final Integer SSL_PORT = 636;

    private final GenericContainer<?> container = new GenericContainer<>("osixia/openldap:1.4.0")
            .waitingFor(Wait.forLogMessage(".*slapd starting.*", 1))
            .withEnv("LDAP_TLS_VERIFY_CLIENT", "allow")
            .withEnv("LDAP_TLS_CRT_FILENAME", "server-cert.pem")
            .withEnv("LDAP_TLS_KEY_FILENAME", "server-key.pem")
            .withEnv("LDAP_TLS_CA_CRT_FILENAME", "CA-cert.pem")
            .withFileSystemBind(resourceDir("certs"), "/container/service/slapd/assets/certs")
            .withNetworkAliases(NETWORK_ALIAS)
            .withExposedPorts(PORT, SSL_PORT)
            .withStartupTimeout(Duration.ofMinutes(1));

    private String resourceDir(String certs) {
        return this.getClass().getResource(certs).getPath();
    }

    private URI internalUri() {
        return URI.create(String.format(Locale.US, "ldap://%s:%d",
                container.getContainerIpAddress(),
                container.getMappedPort(PORT)));
    }

    private URI internalSSLUri() {
        return URI.create(String.format(Locale.US, "ldaps://%s:%d",
                container.getContainerIpAddress(),
                container.getMappedPort(SSL_PORT)));
    }

    private LdapConnector ldapConnector;

    @BeforeEach
    void setUp() {
        container.start();
        final LdapSettingsService ldapSettingsService = mock(LdapSettingsService.class);
        final LdapConnector.TrustManagerProvider trustManagerProvider = (host) -> {
            try {
                return new DefaultX509TrustManager(host);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        this.ldapConnector = new LdapConnector(DEFAULT_TIMEOUT, ENABLED_TLS_PROTOCOLS, ldapSettingsService, trustManagerProvider);
    }

    @Test
    void shouldNotConnectViaTLSToSelfSignedCertIfValidationIsRequested() throws Exception {
        final LdapTestConfigRequest request = createTLSTestRequest(false);

        Assertions.assertThatThrownBy(() -> ldapConnector.connect(request))
                .isInstanceOf(LdapException.class)
                .hasRootCauseInstanceOf(LdapOperationException.class)
                .hasMessage("Failed to initialize the SSL context");
    }

    @Test
    void shouldConnectViaTLSToSelfSignedCertIfValidationIsNotRequested() throws Exception {
        final LdapTestConfigRequest request = createTLSTestRequest(true);

        final LdapNetworkConnection connection = ldapConnector.connect(request);
        assertThat(connection.isAuthenticated()).isTrue();
        assertThat(connection.isConnected()).isTrue();
        assertThat(connection.isSecured()).isTrue();
    }

    @NotNull
    private LdapTestConfigRequest createTLSTestRequest(boolean trustAllCertificates) {
        return LdapTestConfigRequest.create(
                "cn=admin,dc=example,dc=org",
                "admin",
                internalUri(),
                true,
                trustAllCertificates,
                false,
                null,
                null,
                null,
                null,
                true,
                null,
                null,
                null
        );
    }

    @Test
    void shouldNotConnectViaSSLToSelfSignedCertIfValidationIsRequested() throws Exception {
        final LdapTestConfigRequest request = createSSLTestRequest(false);

        Assertions.assertThatThrownBy(() -> ldapConnector.connect(request))
                .isInstanceOf(LdapProtocolErrorException.class)
                .hasMessage("PROTOCOL_ERROR: The server will disconnect!");
    }

    @Test
    void shouldConnectViaSSLToSelfSignedCertIfValidationIsNotRequested() throws Exception {
        final LdapTestConfigRequest request = createSSLTestRequest(true);

        final LdapNetworkConnection connection = ldapConnector.connect(request);

        assertThat(connection.isAuthenticated()).isTrue();
        assertThat(connection.isConnected()).isTrue();
        assertThat(connection.isSecured()).isTrue();
    }

    @NotNull
    private LdapTestConfigRequest createSSLTestRequest(boolean trustAllCertificates) {
        return LdapTestConfigRequest.create(
                "cn=admin,dc=example,dc=org",
                "admin",
                internalSSLUri(),
                false,
                trustAllCertificates,
                false,
                null,
                null,
                null,
                null,
                true,
                null,
                null,
                null
        );
    }
}
