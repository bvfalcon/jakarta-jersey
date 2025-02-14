/*
 * Copyright (c) 2014, 2022 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.jersey.tests.e2e.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.QueryParam;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.RequestEntityProcessing;
import org.glassfish.jersey.message.internal.ReaderWriter;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.util.concurrent.SettableFuture;

/**
 * Reproducer for JERSEY-2705. Client side entity InputStream exception
 * in chunked mode should not lead to the same behavior on the server side,
 * as if no exception occurred at all.
 *
 * @author Jakub Podlesak
 * @author Marek Potociar
 */
public class ChunkedInputStreamClosedPrematurelyTest extends JerseyTest {

    private static final Logger LOGGER = Logger.getLogger(ChunkedInputStreamClosedPrematurelyTest.class.getName());
    private static final Exception NO_EXCEPTION = new Exception("No exception.");

    private static final AtomicInteger NEXT_REQ_ID = new AtomicInteger(0);
    private static final String REQ_ID_PARAM_NAME = "test-req-id";
    private static final int BYTES_TO_SEND = 1024 * 1024 + 13;

    @Path("/test")
    @SuppressWarnings({"ThrowableResultOfMethodCallIgnored", "JavaDoc"})
    public static class TestResource {

        private static final ConcurrentMap<String, SettableFuture<Exception>> REQUEST_MAP = new ConcurrentHashMap<>();

        @QueryParam(REQ_ID_PARAM_NAME)
        private String reqId;

        @POST
        public String post(InputStream is) {
            final byte[] buffer = new byte[4096];
            int readTotal = 0;

            Exception thrown = NO_EXCEPTION;
            try {
                int read;
                while ((read = is.read(buffer)) > -1) {
                    readTotal += read;
                }
            } catch (Exception ex) {
                thrown = ex;
            }

            if (!getFutureFor(reqId).set(thrown)) {
                LOGGER.log(Level.WARNING,
                        "Unable to set stream processing exception into the settable future instance for request id " + reqId,
                        thrown);
            }

            return Integer.toString(readTotal);
        }

        @Path("/requestWasMade")
        @GET
        public Boolean getRequestWasMade() {
            // add a new future for the request if not there yet to avoid race conditions with POST processing
            final SettableFuture<Exception> esf = getFutureFor(reqId);
            try {
                // wait for up to three second for a request to be made;
                // there is always a value, if set...
                return esf.get(3, TimeUnit.SECONDS) != null;
            } catch (InterruptedException | TimeoutException | ExecutionException e) {
                throw new InternalServerErrorException("Post request processing has timed out for request id " + reqId, e);
            }
        }

        @Path("/requestCausedException")
        @GET
        public Boolean getRequestCausedException() {
            final SettableFuture<Exception> esf = getFutureFor(reqId);
            try {
                return esf.get(3, TimeUnit.SECONDS) != NO_EXCEPTION;
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new InternalServerErrorException("Post request processing has timed out for request id " + reqId, e);
            }
        }

        private SettableFuture<Exception> getFutureFor(String key) {
            final SettableFuture<Exception> esf = SettableFuture.create();
            final SettableFuture<Exception> oldEsf = REQUEST_MAP.putIfAbsent(key, esf);
            return (oldEsf != null) ? oldEsf : esf;
        }
    }

    @Override
    protected Application configure() {
        return new ResourceConfig(TestResource.class);
    }

    @Override
    protected void configureClient(ClientConfig config) {
        config.property(ClientProperties.REQUEST_ENTITY_PROCESSING, RequestEntityProcessing.CHUNKED);
        config.property(ClientProperties.CHUNKED_ENCODING_SIZE, 7);
    }

    /**
     * A sanity test to check the normal use case is working as expected.
     */
    @Test
    public void testUninterrupted() {
        final String testReqId = nextRequestId("testUninterrupted");

        Response testResponse = target("test").queryParam(REQ_ID_PARAM_NAME, testReqId)
                .request().post(Entity.entity("0123456789ABCDEF", MediaType.APPLICATION_OCTET_STREAM));
        assertEquals(200, testResponse.getStatus(), "Unexpected response status code.");
        assertEquals("16", testResponse.readEntity(String.class), "Unexpected response entity.");

        assertTrue(target("test").path("requestWasMade").queryParam(REQ_ID_PARAM_NAME, testReqId).request().get(Boolean.class),
                "POST request " + testReqId + " has not reached the server.");
        assertFalse(target("test").path("requestCausedException").queryParam(REQ_ID_PARAM_NAME, testReqId)
                .request().get(Boolean.class), "POST request " + testReqId
                + " has caused an unexpected exception on the server.");
    }

    /**
     * This test simulates how Jersey Client should behave after JERSEY-2705 gets fixed.
     *
     * @throws Exception in case the test fails to execute.
     */
    @Test
    public void testInterruptedJerseyHttpUrlConnection() throws Exception {

        final String testReqId = nextRequestId("testInterruptedJerseyHttpUrlConnection");

        URL postUrl = UriBuilder.fromUri(getBaseUri()).path("test").queryParam(REQ_ID_PARAM_NAME, testReqId).build().toURL();
        final HttpURLConnection connection = (HttpURLConnection) postUrl.openConnection();

        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", MediaType.APPLICATION_OCTET_STREAM);
            connection.setDoOutput(true);
            connection.setChunkedStreamingMode(1024);
            OutputStream entityStream = connection.getOutputStream();
            ReaderWriter.writeTo(new ExceptionThrowingInputStream(BYTES_TO_SEND), entityStream);
            Assertions.fail("Expected ProcessingException has not been thrown.");
        } catch (IOException expected) {
            // so far so good
        } finally {
            connection.disconnect();
        }
        // we should make it to the server, but there the exceptional behaviour should get noticed
        assertTrue(target("test").path("requestWasMade").queryParam(REQ_ID_PARAM_NAME, testReqId).request().get(Boolean.class),
                "POST request " + testReqId + " has not reached the server.");
        assertTrue(target("test").path("requestCausedException").queryParam(REQ_ID_PARAM_NAME, testReqId).request()
                .get(Boolean.class), "POST request " + testReqId + " did not cause an expected exception on the server.");
    }

    /**
     * This test reproduces the Jersey Client behavior reported in JERSEY-2705.
     */
    @Disabled
    @Test
    public void testInterruptedJerseyClient() {
        final String testReqId = nextRequestId("testInterruptedJerseyClient");

        try {
            target("test").queryParam(REQ_ID_PARAM_NAME, testReqId).request()
                    .post(Entity.entity(new ExceptionThrowingInputStream(BYTES_TO_SEND), MediaType.APPLICATION_OCTET_STREAM));
            Assertions.fail("Expected ProcessingException has not been thrown.");
        } catch (ProcessingException expected) {
            // so far so good
        }
        // we should make it to the server, but there the exceptional behaviour should get noticed
        assertTrue(target("test").path("requestWasMade").queryParam(REQ_ID_PARAM_NAME, testReqId).request().get(Boolean.class),
                "POST request " + testReqId + " has not reached the server.");
        assertTrue(target("test").path("requestCausedException").queryParam(REQ_ID_PARAM_NAME, testReqId)
            .request().get(Boolean.class), "POST request " + testReqId + " did not cause an expected exception on the server.");
    }

    private static String nextRequestId(String testMethodName) {
        return String.format(testMethodName + "-%03d", NEXT_REQ_ID.getAndIncrement());
    }

    /**
     * InputStream implementation that allows "reading" as many bytes as specified by threshold constructor parameter.
     * Throws an IOException if read operation is attempted after the threshold is exceeded.
     */
    private class ExceptionThrowingInputStream extends InputStream {

        private final int threshold;
        private int offset = 0;

        /**
         * Get me a new stream that throws exception.
         *
         * @param threshold this number of bytes will be read all right
         */
        public ExceptionThrowingInputStream(int threshold) {
            this.threshold = threshold;
        }

        @Override
        public int read() throws IOException {
            if (offset++ < threshold) {
                return 'A';
            } else {
                throw new IOException("stream closed");
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            offset += len;
            if (offset < threshold) {
                Arrays.fill(b, off, off + len, (byte) 'A');
                return len;
            } else {
                throw new IOException("Stream closed");
            }
        }
    }
}
