/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */
package org.asynchttpclient.async;

import static org.testng.Assert.*;

import static org.asynchttpclient.async.util.TestUtils.findFreePort;
import static org.asynchttpclient.async.util.TestUtils.newJettyHttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

abstract public class MaxConnectionsInThreads extends AbstractBasicTest {

    // FIXME weird
    private static URI servletEndpointUri;

    @Test(groups = { "online", "default_provider" })
    public void testMaxConnectionsWithinThreads() throws InterruptedException {

        String[] urls = new String[] { servletEndpointUri.toString(), servletEndpointUri.toString() };

        final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setConnectTimeout(1000).setRequestTimeout(5000).setAllowPoolingConnections(true)//
                .setMaxConnections(1).setMaxConnectionsPerHost(1).build());

        final CountDownLatch inThreadsLatch = new CountDownLatch(2);
        final AtomicReference<Integer> failedRank = new AtomicReference(-1);
        
        try {
            for (int i = 0; i < urls.length; i++) {
                final String url = urls[i];
                final int rank = i;
                Thread t = new Thread() {
                    public void run() {
                        client.prepareGet(url).execute(new AsyncCompletionHandlerBase() {
                            @Override
                            public Response onCompleted(Response response) throws Exception {
                                Response r = super.onCompleted(response);
                                inThreadsLatch.countDown();
                                return r;
                            }
                            
                            @Override
                            public void onThrowable(Throwable t) {
                                super.onThrowable(t);
                                failedRank.set(rank);
                                inThreadsLatch.countDown();
                            }
                        });
                    }
                };
                t.start();
            }

            inThreadsLatch.await();

            assertEquals(failedRank.get().intValue(), 1, "Max Connections should have been reached");

            final CountDownLatch notInThreadsLatch = new CountDownLatch(2);
            failedRank.set(-1);
            for (int i = 0; i < urls.length; i++) {
                final String url = urls[i];
                final int rank = i;
                client.prepareGet(url).execute(new AsyncCompletionHandlerBase() {
                    @Override
                    public Response onCompleted(Response response) throws Exception {
                        Response r = super.onCompleted(response);
                        notInThreadsLatch.countDown();
                        return r;
                    }
                    
                    @Override
                    public void onThrowable(Throwable t) {
                        super.onThrowable(t);
                        failedRank.set(rank);
                        notInThreadsLatch.countDown();
                    }
                });
            }
            
            notInThreadsLatch.await();
            
            assertEquals(failedRank.get().intValue(), 1, "Max Connections should have been reached");
        } finally {
            client.close();
        }
    }

    @Override
    @BeforeClass
    public void setUpGlobal() throws Exception {

        port1 = findFreePort();
        server = newJettyHttpServer(port1);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        context.addServlet(new ServletHolder(new MockTimeoutHttpServlet()), "/timeout/*");

        server.start();

        String endpoint = "http://127.0.0.1:" + port1 + "/timeout/";
        servletEndpointUri = new URI(endpoint);
    }

    public String getTargetUrl() {
        String s = "http://127.0.0.1:" + port1 + "/timeout/";
        try {
            servletEndpointUri = new URI(s);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return s;
    }

    @SuppressWarnings("serial")
    public static class MockTimeoutHttpServlet extends HttpServlet {
        private static final Logger LOGGER = LoggerFactory.getLogger(MockTimeoutHttpServlet.class);
        private static final String contentType = "text/plain";
        public static long DEFAULT_TIMEOUT = 2000;

        public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
            res.setStatus(200);
            res.addHeader("Content-Type", contentType);
            long sleepTime = DEFAULT_TIMEOUT;
            try {
                sleepTime = Integer.parseInt(req.getParameter("timeout"));

            } catch (NumberFormatException e) {
                sleepTime = DEFAULT_TIMEOUT;
            }

            try {
                LOGGER.debug("=======================================");
                LOGGER.debug("Servlet is sleeping for: " + sleepTime);
                LOGGER.debug("=======================================");
                Thread.sleep(sleepTime);
                LOGGER.debug("=======================================");
                LOGGER.debug("Servlet is awake for");
                LOGGER.debug("=======================================");
            } catch (Exception e) {

            }

            res.setHeader("XXX", "TripleX");

            byte[] retVal = "1".getBytes();
            OutputStream os = res.getOutputStream();

            res.setContentLength(retVal.length);
            os.write(retVal);
            os.close();
        }
    }
}
