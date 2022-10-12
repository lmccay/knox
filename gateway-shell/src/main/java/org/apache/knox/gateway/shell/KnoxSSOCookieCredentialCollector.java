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
package org.apache.knox.gateway.shell;

//import org.apache.knox.gateway.util.JsonUtils;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.lang3.StringEscapeUtils;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
//import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
//import java.util.Map;

public class KnoxSSOCookieCredentialCollector extends AbstractCredentialCollector {

  public static final String COLLECTOR_TYPE = "KnoxSSO";

  private static final String KNOXTOKENCACHE = ".knoxtokencache";

  private String targetUrl;

  private String tokenType;

  private String endpointPublicCertPem;

  private long expiresIn;

  /* (non-Javadoc)
   * @see CredentialCollector#collect()
   */
  @Override
  public void collect() throws CredentialCollectionException {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 9999), 0);
      ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
      server.createContext("/sso", new  MyHttpHandler());
      server.setExecutor(threadPoolExecutor);
      server.start();
      System.out.println("Server started on port 9999");

      Browser browser = new Browser();
      browser.launch("https://localhost:8443/gateway/knoxsso/api/v1/websso?originalUrl=http://localhost:9999/sso");

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected String getCachedKnoxToken() throws IOException {
    String line = null;
    String userDir = System.getProperty("user.home");
    File knoxtoken = new File(userDir, KNOXTOKENCACHE);
    if (knoxtoken.exists()) {
      Path path = Paths.get(knoxtoken.toURI());
      List<String> lines;
      lines = Files.readAllLines(path, StandardCharsets.UTF_8);
      if (!lines.isEmpty()) {
        line = lines.get(0);
      }
    }

    return line;
  }

  public String getTargetUrl() {
    return targetUrl;
  }

  public String getTokenType() {
    return tokenType;
  }

  public String getEndpointClientCertPEM() {
    return endpointPublicCertPem;
  }

  public long getExpiresIn() {
    return expiresIn;
  }

  /* (non-Javadoc)
   * @see CredentialCollector#name()
   */
  @Override
  public String type() {
    return COLLECTOR_TYPE;
  }


  private class MyHttpHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
      System.out.println("handle request");
      Headers reqHeaders = httpExchange.getRequestHeaders();

      // some small sanity check
      List<String> cookies = reqHeaders.get("Cookie");
      for (String cookie : cookies) {
        System.out.println("cookie: " + cookie);
        if (cookie.contains("hadoop-jwt")) {
          value = cookie;
          System.out.println("value: " + value);
        }
      }
      String requestParamValue=null;
      if("GET".equals(httpExchange.getRequestMethod())) {
        System.out.println("GET request");
        requestParamValue = handleGetRequest(httpExchange);
      }else if("POST".equals(httpExchange.getRequestMethod())) {
        System.out.println("POST request");
        requestParamValue = handlePostRequest(httpExchange);
      }
      handleResponse(httpExchange,requestParamValue);
    }

    private String handlePostRequest(HttpExchange httpExchange) {
      return handleGetRequest(httpExchange);
    }

    private String handleGetRequest(HttpExchange httpExchange) {
      return httpExchange.
      getRequestURI()
              .toString()
              .split("\\?")[1]
              .split("=")[1];
    }
    private void handleResponse(HttpExchange httpExchange, String requestParamValue)  throws  IOException {
      System.out.println("handle response start");
      OutputStream outputStream = httpExchange.getResponseBody();
      StringBuilder htmlBuilder = new StringBuilder();
      htmlBuilder.append("<html>").
      append("<body>").
      append("<h1>").
      append("Hello ")
              .append(requestParamValue)
              .append("</h1>")
              .append("</body>")
              .append("</html>");
      // encode HTML content
      String htmlResponse = StringEscapeUtils.escapeHtml4(htmlBuilder.toString());

      // this line is a must
      httpExchange.sendResponseHeaders(200, htmlResponse.length());
      outputStream.write(htmlResponse.getBytes());
      outputStream.flush();
      outputStream.close();
      System.out.println("handle response end");
    }
  }
  public class Browser {
    public void Browser() {
    }
    public void launch(String url) {
      if(Desktop.isDesktopSupported()){
        Desktop desktop = Desktop.getDesktop();
        try {
          desktop.browse(new URI(url));
        } catch (IOException | URISyntaxException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }else{
        Runtime runtime = Runtime.getRuntime();
        try {
          runtime.exec("xdg-open " + url);
        } catch (IOException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }
    }
  }
}
