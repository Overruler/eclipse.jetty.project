//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Future;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.IO;
import org.junit.Test;


/**
 * IdleSessionTest
 *
 * Checks that a session can be idled and de-idled on the next request if it hasn't expired.
 *
 */
public class IdleSessionTest
{
    public class IdleHashTestServer extends HashTestServer
    {
        private int _idlePeriod;
        private File _storeDir;

        /**
         * @param port
         * @param maxInactivePeriod
         * @param scavengePeriod
         * @param idlePeriod
         */
        public IdleHashTestServer(int port, int maxInactivePeriod, int scavengePeriod, int idlePeriod, File storeDir)
        {
            super(port, maxInactivePeriod, scavengePeriod);
            _idlePeriod = idlePeriod;
            _storeDir = storeDir;
        }

        @Override
        public SessionManager newSessionManager()
        {
            HashSessionManager manager = (HashSessionManager)super.newSessionManager();
            manager.setStoreDirectory(_storeDir);
            manager.setIdleSavePeriod(_idlePeriod);
            return manager;
        }



    }

    public  HashTestServer createServer(int port, int max, int scavenge, int idle, File storeDir)
    {
        HashTestServer server = new IdleHashTestServer(port, max, scavenge, idle, storeDir);       
        return server;
    }
    
    

    public void pause (int sec)
    {
        try
        {
            Thread.sleep(sec * 1000L);
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    @Test
    public void testSessionIdle() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 200;
        int scavengePeriod = 3;
        int idlePeriod = 5;

        File storeDir = new File (System.getProperty("java.io.tmpdir"), "idle-test");
        storeDir.deleteOnExit();

        HashTestServer server1 = createServer(0, inactivePeriod, scavengePeriod, idlePeriod, storeDir);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server
            Future<ContentResponse> future = client.GET(url + "?action=init");
            ContentResponse response = future.get();
            assertEquals(HttpServletResponse.SC_OK,response.getStatus());
            String sessionCookie = response.getHeaders().getStringField("Set-Cookie");
            assertTrue(sessionCookie != null);
            // Mangle the cookie, replacing Path with $Path, etc.
            sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

            //and wait until the session should be idled out
            pause(scavengePeriod * 2);

            //check that the file exists
            checkSessionIdled(storeDir);

            //make another request to de-idle the session
            Request request = client.newRequest(url + "?action=test");
            request.getHeaders().add("Cookie", sessionCookie);
            future = request.send();
            ContentResponse response2 = future.get();
            assertEquals(HttpServletResponse.SC_OK,response2.getStatus());

            //check session de-idled
            checkSessionDeIdled(storeDir);

        }
        finally
        {
            server1.stop();
            IO.delete(storeDir);
        }
    }


    public void checkSessionIdled (File sessionDir)
    {
        assertNotNull(sessionDir);
        assertTrue(sessionDir.exists());
        String[] files = sessionDir.list();
        assertNotNull(files);
        assertEquals(1, files.length);
    }


    public void checkSessionDeIdled (File sessionDir)
    {
        assertNotNull(sessionDir);
        assertTrue(sessionDir.exists());
        String[] files = sessionDir.list();
        assertNotNull(files);
        assertEquals(0, files.length);
    }


    public static class TestServlet extends HttpServlet
    {
        public String originalId = null;
        public String testId = null;
        public String checkId = null;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("test", "test");
                originalId = session.getId();
                assertTrue(!((HashedSession)session).isIdled());
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertTrue(session != null);
                assertTrue(originalId.equals(session.getId()));
                assertEquals("test", session.getAttribute("test"));
                assertTrue(!((HashedSession)session).isIdled());
            }
        }
    }
}