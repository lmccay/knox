/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.knox.gateway.filter;

import org.apache.knox.gateway.security.GroupPrincipal;
import org.apache.knox.gateway.security.PrimaryPrincipal;
import org.apache.knox.gateway.services.GatewayServices;
import org.apache.knox.gateway.services.ServiceType;
import org.apache.knox.gateway.services.security.KeystoreService;
import org.apache.knox.gateway.services.security.KeystoreServiceException;
import org.apache.knox.test.mock.MockServletContext;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.apache.logging.log4j.ThreadContext;

import javax.security.auth.Subject;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AccessController;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.security.KeyStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("PMD.JUnit4TestShouldUseBeforeAnnotation")
public class RemoteAuthFilterTest {

    public static final String BEARER_INVALID_TOKEN = "Bearer invalid-token";
    public static final String BEARER_VALID_TOKEN = "Bearer valid-token";
    public static final String URL_SUCCESS = "https://example.com/auth";
    private static final String URL_FAIL = "https://example.com/authfail";
    public static final String X_AUTHENTICATED_USER = "X-Authenticated-User";
    public static final String X_AUTHENTICATED_GROUP = "X-Authenticated-Group";
    public static final String X_AUTHENTICATED_GROUP_2 = "X-Authenticated-Group-2";
    public static final String X_CUSTOM_GROUP_1 = "X-Custom-Group-1";
    public static final String X_CUSTOM_GROUP_2 = "X-Custom-Group-2";
    private RemoteAuthFilter filter;
    private HttpServletRequest requestMock;
    private HttpServletResponse responseMock;
    private TestFilterChain chainMock;
    private GatewayServices gatewayServicesMock;
    private KeystoreService keystoreServiceMock;
    private ServletContext servletContextMock;

    @Before
    public void createMocks() {
        requestMock = EasyMock.createMock(HttpServletRequest.class);
        responseMock = EasyMock.createMock(HttpServletResponse.class);
    }

    private void setUp(String trustStorePath, String trustStorePass, String trustStoreType) {
        // Reset existing mocks
        EasyMock.reset(requestMock, responseMock);

        FilterConfig filterConfigMock = EasyMock.createNiceMock(FilterConfig.class);
        chainMock = new TestFilterChain();

        // Create and configure Gateway Services mocks
        gatewayServicesMock = EasyMock.createNiceMock(GatewayServices.class);
        keystoreServiceMock = EasyMock.createNiceMock(KeystoreService.class);
        servletContextMock = EasyMock.createNiceMock(ServletContext.class);

        // Set up Gateway Services expectations
        EasyMock.expect(gatewayServicesMock.getService(ServiceType.KEYSTORE_SERVICE))
               .andReturn(keystoreServiceMock)
               .anyTimes();
        EasyMock.expect(servletContextMock.getAttribute(GatewayServices.GATEWAY_SERVICES_ATTRIBUTE))
               .andReturn(gatewayServicesMock)
               .anyTimes();

        // Basic config
        EasyMock.expect(filterConfigMock.getInitParameter("remote.auth.url")).andReturn("https://example.com/auth").anyTimes();
        EasyMock.expect(filterConfigMock.getInitParameter("remote.auth.include.headers")).andReturn("Authorization").anyTimes();
        EasyMock.expect(filterConfigMock.getInitParameter("remote.auth.cache.key")).andReturn("Authorization").anyTimes();
        EasyMock.expect(filterConfigMock.getInitParameter("remote.auth.expire.after")).andReturn("5").anyTimes();
        EasyMock.expect(filterConfigMock.getInitParameter("remote.auth.user.header")).andReturn(X_AUTHENTICATED_USER).anyTimes();
        EasyMock.expect(filterConfigMock.getInitParameter("remote.auth.group.header"))
               .andReturn(X_AUTHENTICATED_GROUP + "," + X_AUTHENTICATED_GROUP_2 + ",X-Custom-Group-*").anyTimes();

        // Trust store config
        EasyMock.expect(filterConfigMock.getInitParameter("remote.auth.truststore.path")).andReturn(trustStorePath).anyTimes();
        EasyMock.expect(filterConfigMock.getInitParameter("remote.auth.truststore.password")).andReturn(trustStorePass).anyTimes();
        EasyMock.expect(filterConfigMock.getInitParameter("remote.auth.truststore.type")).andReturn(trustStoreType).anyTimes();

        // Only replay the mocks that won't need additional expectations
        EasyMock.replay(filterConfigMock, gatewayServicesMock, servletContextMock);

        filter = new RemoteAuthFilter();
        try {
            filter.init(filterConfigMock);
        } catch (ServletException e) {
            throw new RuntimeException(e);
        }
    }

    // Default setup method for backward compatibility
    private void setUp() {
        setUp(null, null, null);
    }

    private void setupURLConnection(String url) {
        try {
            filter.httpURLConnection = new MockHttpURLConnection(new URL(url));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void successfulAuthentication() throws Exception {
        setUp();

        EasyMock.expect(requestMock.getServletContext()).andReturn(new MockServletContext()).anyTimes();
        EasyMock.expect(requestMock.getHeader("Authorization")).andReturn(BEARER_VALID_TOKEN).anyTimes();
        EasyMock.expect(responseMock.getStatus()).andReturn(200).anyTimes();
        responseMock.sendError(EasyMock.eq(HttpServletResponse.SC_UNAUTHORIZED), EasyMock.anyString());
        EasyMock.expectLastCall().andThrow(new AssertionError("Authentication should be successful, but was not.")).anyTimes();

        EasyMock.replay(requestMock, responseMock);

        try {
            setupURLConnection(URL_SUCCESS);

            filter.doFilter(requestMock, responseMock, chainMock);
            assertEquals(responseMock.getStatus(), HttpServletResponse.SC_OK);

            assertTrue("Filter chain should have been called but wasn't", chainMock.doFilterCalled);
            Set<PrimaryPrincipal> primaryPrincipals = chainMock.subject.getPrincipals(PrimaryPrincipal.class);
            if (!primaryPrincipals.isEmpty()) {
                assertEquals("lmccay", ((Principal)primaryPrincipals.toArray()[0]).getName());
            }
            Set<GroupPrincipal> groupPrincipals = chainMock.subject.getPrincipals(GroupPrincipal.class);
            if (!groupPrincipals.isEmpty()) {
                assertEquals(2, groupPrincipals.toArray().length);
                assertTrue("Groups should include admin but don't", groupPrincipals.stream()
                        .anyMatch(p -> p.getName().equals("admin")));
                assertTrue("Groups should include engineers but don't", groupPrincipals.stream()
                        .anyMatch(p -> p.getName().equals("engineers")));
            }
        } catch (AssertionError e) {
            assert false : "Authentication failed unexpectedly";
        }
    }

    @Test
    public void authenticationFailsWithInvalidToken() throws Exception {
        setUp();

        EasyMock.expect(requestMock.getServletContext()).andReturn(new MockServletContext()).anyTimes();
        EasyMock.expect(responseMock.getStatus()).andReturn(401).anyTimes();
        EasyMock.expect(requestMock.getHeader("Authorization")).andReturn(BEARER_INVALID_TOKEN).anyTimes();
        responseMock.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication failed");
        EasyMock.expectLastCall();

        EasyMock.replay(requestMock, responseMock);

        try {
            setupURLConnection(URL_FAIL);

            filter.doFilter(requestMock, responseMock, chainMock);
            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, responseMock.getStatus());

        } catch (Exception e) {
            assertEquals("Expected an IOException for invalid authentication", IOException.class, e.getClass());
            assertFalse("Filter chain should NOT have been called but was", chainMock.doFilterCalled);
            assertTrue(chainMock.subject.getPrincipals(PrimaryPrincipal.class).isEmpty());
            assertTrue(chainMock.subject.getPrincipals(GroupPrincipal.class).isEmpty());
        }

        EasyMock.verify(responseMock); // Verification ensures the error response was indeed sent.
    }

    @Test
    public void testCacheBehavior() throws Exception {
        setUp();

        String principalName = "lmccayiv";
        String groupNames = "admin2,scientists";
        Subject subject = new Subject();
        subject.getPrincipals().add(new PrimaryPrincipal(principalName));
        Arrays.stream(groupNames.split(",")).forEach(groupName -> subject.getPrincipals()
                .add(new GroupPrincipal(groupName)));
        filter.setCachedSubject(BEARER_VALID_TOKEN, subject);

        EasyMock.expect(requestMock.getHeader("Authorization")).andReturn(BEARER_VALID_TOKEN).anyTimes();
        EasyMock.expect(responseMock.getStatus()).andReturn(200).anyTimes();
        responseMock.sendError(EasyMock.eq(HttpServletResponse.SC_UNAUTHORIZED), EasyMock.anyString());
        EasyMock.expectLastCall().andThrow(new AssertionError("Authentication should be successful, but was not.")).anyTimes();

        EasyMock.replay(requestMock, responseMock);

        try {
            setupURLConnection(URL_SUCCESS);

            filter.doFilter(requestMock, responseMock, chainMock);
            assertEquals(responseMock.getStatus(), HttpServletResponse.SC_OK);

            assertTrue("Filter chain should have been called but wasn't", chainMock.doFilterCalled);
            Set<PrimaryPrincipal> primaryPrincipals = chainMock.subject.getPrincipals(PrimaryPrincipal.class);
            if (!primaryPrincipals.isEmpty()) {
                assertEquals("lmccayiv", ((Principal)primaryPrincipals.toArray()[0]).getName());
            }
            Set<GroupPrincipal> groupPrincipals = chainMock.subject.getPrincipals(GroupPrincipal.class);
            if (!groupPrincipals.isEmpty()) {
                assertEquals(2, groupPrincipals.toArray().length);
                assertTrue("Groups should include admin2 but don't", groupPrincipals.stream()
                        .anyMatch(p -> p.getName().equals("admin2")));
                assertTrue("Groups should include scientists but don't", groupPrincipals.stream()
                        .anyMatch(p -> p.getName().equals("scientists")));
            }
        } catch (AssertionError e) {
            assert false : "Authentication failed unexpectedly";
        }
    }

    @Test
    public void testTraceIdPropagation() throws Exception {
        setUp();

        String expectedTraceId = "test-trace-123";

        EasyMock.expect(requestMock.getServletContext())
            .andReturn(new MockServletContext())
            .anyTimes();
        EasyMock.expect(requestMock.getHeader("Authorization"))
            .andReturn(BEARER_VALID_TOKEN)
            .anyTimes();
        EasyMock.expect(responseMock.getStatus())
            .andReturn(200)
            .anyTimes();

        EasyMock.replay(requestMock, responseMock);

        try {
            // Set up the trace ID in ThreadContext
            ThreadContext.put(RemoteAuthFilter.TRACE_ID, expectedTraceId);

            MockHttpURLConnection mockConn = new MockHttpURLConnection(new URL(URL_SUCCESS)) {
                private final Map<String, String> requestProperties = new HashMap<>();

                @Override
                public void addRequestProperty(String key, String value) {
                    requestProperties.put(key, value);
                }

                @Override
                public String getRequestProperty(String key) {
                    return requestProperties.get(key);
                }
            };
            filter.httpURLConnection = mockConn;

            filter.doFilter(requestMock, responseMock, chainMock);

            // Verify the trace ID was propagated with the correct header name
            assertEquals("Trace ID should be propagated to outgoing request",
                expectedTraceId,
                mockConn.getRequestProperty(RemoteAuthFilter.REQUEST_ID_HEADER_NAME));
        } finally {
            // Clean up ThreadContext
            ThreadContext.remove(RemoteAuthFilter.TRACE_ID);
        }
    }

    @Test
    public void successfulAuthenticationWithMultipleGroups() throws Exception {
        setUp();

        EasyMock.expect(requestMock.getServletContext()).andReturn(new MockServletContext()).anyTimes();
        EasyMock.expect(requestMock.getHeader("Authorization")).andReturn(BEARER_VALID_TOKEN).anyTimes();
        EasyMock.expect(responseMock.getStatus()).andReturn(200).anyTimes();
        responseMock.sendError(EasyMock.eq(HttpServletResponse.SC_UNAUTHORIZED), EasyMock.anyString());
        EasyMock.expectLastCall().andThrow(new AssertionError("Authentication should be successful, but was not.")).anyTimes();

        EasyMock.replay(requestMock, responseMock);

        try {
            MockHttpURLConnection mockConn = new MockHttpURLConnection(new URL(URL_SUCCESS));
            // Add groups from multiple headers
            mockConn.addHeader(X_AUTHENTICATED_GROUP, "admin,engineers");
            mockConn.addHeader(X_AUTHENTICATED_GROUP_2, "developers");
            mockConn.addHeader(X_CUSTOM_GROUP_1, "team-a");
            mockConn.addHeader(X_CUSTOM_GROUP_2, "team-b,team-c");
            filter.httpURLConnection = mockConn;

            filter.doFilter(requestMock, responseMock, chainMock);
            assertEquals(responseMock.getStatus(), HttpServletResponse.SC_OK);

            assertTrue("Filter chain should have been called but wasn't", chainMock.doFilterCalled);

            Set<GroupPrincipal> groupPrincipals = chainMock.subject.getPrincipals(GroupPrincipal.class);
            assertEquals("Should have all groups from all headers", 6, groupPrincipals.size());

            // Verify groups from different headers
            assertTrue(groupPrincipals.stream().anyMatch(p -> p.getName().equals("admin")));
            assertTrue(groupPrincipals.stream().anyMatch(p -> p.getName().equals("engineers")));
            assertTrue(groupPrincipals.stream().anyMatch(p -> p.getName().equals("developers")));
            assertTrue(groupPrincipals.stream().anyMatch(p -> p.getName().equals("team-a")));
            assertTrue(groupPrincipals.stream().anyMatch(p -> p.getName().equals("team-b")));
            assertTrue(groupPrincipals.stream().anyMatch(p -> p.getName().equals("team-c")));
        } catch (AssertionError e) {
            assert false : "Authentication failed unexpectedly";
        }
    }

    @Test
    public void testSuccessfulHttpsRequestWithTrustStore() throws Exception {
        // Setup with valid trust store configuration
        setUp("/path/to/truststore.jks", "trustpass", "JKS");

        KeyStore testTruststore = KeyStore.getInstance("JKS");
        EasyMock.expect(keystoreServiceMock.loadTruststore("/path/to/truststore.jks", "JKS", "trustpass"))
               .andReturn(testTruststore)
               .anyTimes();

        EasyMock.expect(requestMock.getServletContext())
               .andReturn(servletContextMock)
               .anyTimes();
        EasyMock.expect(requestMock.getHeader("Authorization"))
               .andReturn(BEARER_VALID_TOKEN)
               .anyTimes();
        EasyMock.expect(responseMock.getStatus())
               .andReturn(200)
               .anyTimes();
        responseMock.sendError(EasyMock.eq(HttpServletResponse.SC_UNAUTHORIZED), EasyMock.anyString());
        EasyMock.expectLastCall().andThrow(new AssertionError("Authentication should be successful, but was not.")).anyTimes();

        EasyMock.replay(requestMock, responseMock, keystoreServiceMock);

        setupURLConnection("https://example.com/auth");
        filter.doFilter(requestMock, responseMock, chainMock);

        assertTrue("Filter chain should have been called", chainMock.doFilterCalled);
    }

    @Test
    public void testHttpsRequestWithoutTrustStore() throws Exception {
        // Setup without trust store configuration
        setUp(null, null, null);

        KeyStore defaultTruststore = KeyStore.getInstance("JKS");
        EasyMock.expect(keystoreServiceMock.getTruststoreForHttpClient())
               .andReturn(defaultTruststore)
               .anyTimes();

        EasyMock.expect(requestMock.getServletContext())
               .andReturn(servletContextMock)
               .anyTimes();
        EasyMock.expect(requestMock.getHeader("Authorization"))
               .andReturn(BEARER_VALID_TOKEN)
               .anyTimes();
        EasyMock.expect(responseMock.getStatus())
               .andReturn(200)
               .anyTimes();
        responseMock.sendError(EasyMock.eq(HttpServletResponse.SC_UNAUTHORIZED), EasyMock.anyString());
        EasyMock.expectLastCall().andThrow(new AssertionError("Authentication should be successful, but was not.")).anyTimes();

        EasyMock.replay(requestMock, responseMock, keystoreServiceMock);

        setupURLConnection("https://example.com/auth");
        filter.doFilter(requestMock, responseMock, chainMock);

        assertTrue("Filter chain should have been called with default trust store", chainMock.doFilterCalled);
    }

    @Test
    public void testHttpsRequestWithInvalidTrustStoreConfig() throws Exception {
        // Setup with invalid trust store configuration
        setUp("/nonexistent/path/truststore.jks", "password", "JKS");

        EasyMock.expect(keystoreServiceMock.loadTruststore("/nonexistent/path/truststore.jks", "JKS", "password"))
               .andThrow(new KeystoreServiceException("Failed to load truststore"))
               .anyTimes();

        EasyMock.expect(requestMock.getServletContext())
               .andReturn(servletContextMock)
               .anyTimes();
        EasyMock.expect(requestMock.getHeader("Authorization"))
               .andReturn(BEARER_VALID_TOKEN)
               .anyTimes();
        EasyMock.expect(responseMock.getStatus())
               .andReturn(500)
               .anyTimes();
        responseMock.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error processing authentication request");
        EasyMock.expectLastCall().once();

        EasyMock.replay(requestMock, responseMock, keystoreServiceMock);

        filter.doFilter(requestMock, responseMock, chainMock);

        assertFalse("Filter chain should not have been called", chainMock.doFilterCalled);
        EasyMock.verify(responseMock);
    }

    public static class MockHttpURLConnection extends HttpURLConnection {
        private final URL url;
        private int responseCode;
        private final Map<String, List<String>> headers;

        public MockHttpURLConnection(URL url) {
            super(url);
            this.url = url;
            this.responseCode = getCode();
            this.headers = new HashMap<>();

            if (url.toString().equals(URL_SUCCESS)) {
                addHeader(X_AUTHENTICATED_USER, "lmccay");
                addHeader(X_AUTHENTICATED_GROUP, "admin,engineers");
            }
        }

        private int getCode() {
            return this.url.toString().equals(URL_SUCCESS) ? 200 : 401;
        }

        @Override
        public void connect() throws IOException {
            // No need to connect in this mock
        }

        @Override
        public void disconnect() {
            // No need to disconnect in this mock
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public int getResponseCode() throws IOException {
            return responseCode;
        }

        @Override
        public String getHeaderField(String name) {
            List<String> values = headers.get(name);
            return values != null && !values.isEmpty() ? values.get(0) : null;
        }

        @Override
        public Map<String, List<String>> getHeaderFields() {
            return headers;
        }

        public void addHeader(String name, String value) {
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
    }

    protected static class TestFilterChain implements FilterChain {
        boolean doFilterCalled;
        Subject subject;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            doFilterCalled = true;

            subject = Subject.getSubject( AccessController.getContext() );
        }

        public Subject getSubject() {
            return subject;
        }
    }
}
