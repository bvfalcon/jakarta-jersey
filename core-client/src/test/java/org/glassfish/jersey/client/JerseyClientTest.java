/*
 * Copyright (c) 2011, 2022 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.client;

import java.io.IOException;
import java.util.concurrent.Future;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.ext.ReaderInterceptor;
import javax.ws.rs.ext.ReaderInterceptorContext;

import javax.inject.Inject;

import org.glassfish.jersey.client.spi.AsyncConnectorCallback;
import org.glassfish.jersey.client.spi.Connector;
import org.glassfish.jersey.client.spi.ConnectorProvider;
import org.glassfish.jersey.internal.Version;
import org.glassfish.jersey.internal.inject.AbstractBinder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link JerseyClient} unit test.
 *
 * @author Marek Potociar
 */
public class JerseyClientTest {

    private JerseyClient client;

    public JerseyClientTest() {
    }

    @BeforeEach
    public void setUp() {
        this.client = (JerseyClient) ClientBuilder.newClient();
    }

    @AfterEach
    public void tearDown() {
    }

    @Test
    public void testCreateClient() {
        assertNotNull(client);
    }

    @Test
    public void testClose() {
        client.close();
        assertTrue(client.isClosed());
        client.close(); // closing multiple times is ok

        try {
            client.getConfiguration();
            fail("IllegalStateException expected if a method is called on a closed client instance.");
        } catch (IllegalStateException ex) {
            // ignored
        }
        try {
            client.target("http://jersey.java.net/examples");
            fail("IllegalStateException expected if a method is called on a closed client instance.");
        } catch (IllegalStateException ex) {
            // ignored
        }
    }

    @Test
    public void testConfiguration() {
        final ClientConfig configuration = client.getConfiguration();
        assertNotNull(configuration);

        configuration.property("hello", "world");

        assertEquals("world", client.getConfiguration().getProperty("hello"));
    }

    @Test
    public void testTarget() {
        final JerseyWebTarget target = client.target("http://jersey.java.net/examples");
        assertNotNull(target);
        assertEquals(client.getConfiguration(), target.getConfiguration());
    }

    @Test
    public void testTargetIAE() {
        assertThrows(IllegalArgumentException.class, () -> UriBuilder.fromUri(":xxx:8080//yyy:90090//jaxrs "));
    }

    public static class TestProvider implements ReaderInterceptor {

        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
            return context.proceed();
        }
    }

    // Reproducer JERSEY-1637
    @Test
    public void testRegisterNullOrEmptyContracts() {
        final TestProvider provider = new TestProvider();

        client.register(TestProvider.class, (Class<?>[]) null);
        assertFalse(client.getConfiguration().isRegistered(TestProvider.class));

        client.register(provider, (Class<?>[]) null);
        assertFalse(client.getConfiguration().isRegistered(TestProvider.class));
        assertFalse(client.getConfiguration().isRegistered(provider));

        client.register(TestProvider.class, new Class[0]);
        assertFalse(client.getConfiguration().isRegistered(TestProvider.class));

        client.register(provider, new Class[0]);
        assertFalse(client.getConfiguration().isRegistered(TestProvider.class));
        assertFalse(client.getConfiguration().isRegistered(provider));
    }

    @Test
    public void testTargetConfigUpdate() {
        final JerseyWebTarget target = client.target("http://jersey.java.net/examples");

        target.getConfiguration().register(new ClientRequestFilter() {
            @Override
            public void filter(ClientRequestContext clientRequestContext) throws IOException {
                throw new UnsupportedOperationException("Not supported yet");
            }
        });

        assertEquals(1, target.getConfiguration().getInstances().size());
    }

    /**
     * Regression test for JERSEY-1192.
     */
    @Test
    public void testCreateLinkBasedInvocation() {
        final JerseyClient jerseyClient = new JerseyClient();

        try {
            jerseyClient.invocation(null);
            fail("NullPointerException expected.");
        } catch (NullPointerException ex) {
            // success.
        }

        try {
            jerseyClient.invocation(null);
            fail("NullPointerException expected.");
        } catch (NullPointerException ex) {
            // success.
        }

        Link link1 =
                Link.fromUri(UriBuilder.fromPath("http://localhost:8080/").build())
                        .build();
        Link link2 =
                Link.fromUri(UriBuilder.fromPath("http://localhost:8080/").build())
                        .type("text/plain")
                        .build();


        assertNotNull(jerseyClient.invocation(link1).buildPost(null));
        assertNotNull(jerseyClient.invocation(link2).buildPost(null));

        assertNotNull(jerseyClient.invocation(link1).buildPost(Entity.text("Test.")));
        assertNotNull(jerseyClient.invocation(link2).buildPost(Entity.text("Test.")));

        assertNotNull(jerseyClient.invocation(link1).buildPost(Entity.xml("Test.")));
        assertNotNull(jerseyClient.invocation(link2).buildPost(Entity.xml("Test.")));
    }

    @Test
    public void userAgentTest() {
        final Client customClient = ClientBuilder.newClient(new ClientConfig().connectorProvider(new TestConnector()));

        try {
            customClient.target("test").request().get();
        } catch (ProcessingException e) {
            assertEquals("Jersey/" + Version.getVersion(), e.getMessage());
        }

        try {
            customClient.target("test").request().async().get().get();
        } catch (Exception e) {
            assertEquals("Jersey/" + Version.getVersion(), e.getCause().getMessage());
        }
    }

    /**
     * JERSEY-2189 reproducer.
     */
    @Test
    public void customUserAgentTest() {
        final Client customClient = ClientBuilder.newClient(new ClientConfig().connectorProvider(new TestConnector()));

        try {
            customClient.target("test").request().header(HttpHeaders.USER_AGENT, null).get();
        } catch (Exception e) {
            assertEquals("[null]", e.getMessage());
        }

        try {
            customClient.target("test").request().header(HttpHeaders.USER_AGENT, null).async().get();
        } catch (Exception e) {
            assertEquals("[null]", e.getCause().getMessage());
        }

        try {
            customClient.target("test").request().header(HttpHeaders.USER_AGENT, "custom").get();
        } catch (Exception e) {
            assertEquals("custom", e.getMessage());
        }

        try {
            customClient.target("test").request().header(HttpHeaders.USER_AGENT, "custom").async().get();
        } catch (Exception e) {
            assertEquals("custom", e.getCause().getMessage());
        }
    }

    public static interface CustomContract {
        public String getFoo();
    }

    public static class CustomService implements CustomContract {

        @Override
        public String getFoo() {
            return "Foo";
        }
    }

    public static class CustomBinder extends AbstractBinder {

        @Override
        protected void configure() {
            bind(CustomService.class).to(CustomContract.class);
        }
    }

    public static class CustomProvider implements ClientRequestFilter {
        @Inject
        private CustomContract customContract;

        @Override
        public void filter(ClientRequestContext requestContext) throws IOException {
            requestContext.abortWith(Response.ok(customContract.getFoo()).build());
        }
    }

    @Test
    public void testCustomBinders() {
        final CustomBinder binder = new CustomBinder();
        Client client = ClientBuilder.newClient().register(binder).register(CustomProvider.class);

        Response resp = client.target("test").request().get();
        assertEquals("Foo", resp.readEntity(String.class));
    }

    private static class TestConnector implements Connector, ConnectorProvider {
        @Override
        public ClientResponse apply(ClientRequest request) throws ProcessingException {
            final Object agent = request.getHeaders().getFirst(HttpHeaders.USER_AGENT);
            throw new ProcessingException((agent == null) ? "[null]" : agent.toString());
        }

        @Override
        public Future<?> apply(ClientRequest request, AsyncConnectorCallback callback) {
            final Object agent = request.getHeaders().getFirst(HttpHeaders.USER_AGENT);
            callback.failure(new ProcessingException((agent == null) ? "[null]" : agent.toString()));
            return null;
        }

        @Override
        public void close() {
            // nothing
        }

        @Override
        public String getName() {
            return null;
        }

        @Override
        public Connector getConnector(Client client, Configuration runtimeConfig) {
            return this;
        }
    }
}
