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
package org.apache.knox.gateway.service.knoxai;

import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import org.apache.knox.gateway.i18n.messages.MessagesFactory;

import org.apache.knox.gateway.services.GatewayServices;
import org.apache.knox.gateway.services.ServiceType;
import org.apache.knox.gateway.services.security.AliasService;
import org.apache.knox.gateway.services.security.AliasServiceException;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.annotation.PostConstruct;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_XML;
import static javax.ws.rs.core.Response.ok;


import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.TimeUnit;

@Path( KnoxAIResource.RESOURCE_PATH )
public class KnoxAIResource {
  private static final KnoxAIMessages log = MessagesFactory.get( KnoxAIMessages.class );

  private static final String DEFAULT_KEY_ALIAS = "openai.api.key";

  static final String RESOURCE_PATH = "knoxai/api/v1/chat/completions";
  public static final String MODEL_ENDPOINT_URL = "model.endpoint.url";
  public static final String DEFAULT_MODEL_ENDPOINT_URL = "https://api.openai.com/v1/chat/completions";
  public static final String MODEL_NAME = "model.name";
  private static final String DEFAULT_MODEL_NAME = "gpt-4-turbo-preview";

  @Context
  HttpServletRequest request;

  @Context
  HttpServletResponse response;

  @Context
  ServletContext context;

  private char[] apiKey = null;
  private String modelEndpointUrl = null;

  private String modelName = null;

  @PostConstruct
  public void init() {
    GatewayServices services = (GatewayServices) context.getAttribute(GatewayServices.GATEWAY_SERVICES_ATTRIBUTE);
    AliasService as = services.getService(ServiceType.ALIAS_SERVICE);
    try {
        apiKey = as.getPasswordFromAliasForCluster(getTopologyName(), DEFAULT_KEY_ALIAS);
    } catch (AliasServiceException e) {
        throw new RuntimeException(e);
    }
    modelEndpointUrl = context.getInitParameter(MODEL_ENDPOINT_URL);
    if (modelEndpointUrl == null) {
      modelEndpointUrl = DEFAULT_MODEL_ENDPOINT_URL;
    }

    modelName = context.getInitParameter(MODEL_NAME);
    if (modelName == null) {
      modelName = DEFAULT_MODEL_NAME;
    }
  }

  @POST
  @Produces({APPLICATION_JSON, APPLICATION_XML})
  public Response interact(@QueryParam("pattern") String pattern, @QueryParam("input") String input) {
    String responseContent = do_openai(pattern, input);
    if (responseContent != null) {
      return ok().entity("{ \"content\" : " + responseContent + " }").build();
    } else {
      return ok().entity("{ \"content\" : none }").build();
    }
  }

  private String do_openai(String pattern, String input) {
    String responseContent = null;
    OkHttpClient client = new OkHttpClient();
    client.setConnectTimeout(60000, TimeUnit.MILLISECONDS);
    client.setReadTimeout(1000, TimeUnit.SECONDS);

    // Replace with the path to the file containing system instructions
    //File systemInstructionsFile = new File("path/to/system_instructions.txt");
    String patternURL = "https://raw.githubusercontent.com/danielmiessler/fabric/main/patterns/" + pattern + "/system.md";
    System.out.println(patternURL);
    System.out.println(input);

    // Replace with the path to the file containing user input or prompt
    String inputContent = null;
    try {
      inputContent = getInputContent(input);
      String prompt = "You are a generally useful agent hoping to be of help.";
      if (pattern != null) {
        prompt = getInputContent(patternURL);
      }

      String bodyString = "{\"model\": \"" + modelName + "\""
              + ", \"messages\": [{\"role\": \"system\", \"content\": \"" + prompt + "\"},"
              + "{\"role\": \"user\", \"content\": \"" + inputContent + "\"}]}";
      //System.out.println(bodyString);

      //JSONObject json = new JSONObject(bodyString);
      RequestBody body = RequestBody.create(MediaType.parse("application/json"), bodyString);

      Request request = new Request.Builder()
              .url(modelEndpointUrl)
              .addHeader("Authorization", "Bearer " + new String(apiKey))
              .addHeader("Content-Type", "application/json")
              .post(body)
              .build();

      com.squareup.okhttp.Response resp = client.newCall(request).execute();

      if (resp.isSuccessful()) {
          String responseBody = resp.body().string();
          JSONObject jsonResponse = new JSONObject(responseBody);
        JSONArray choices = jsonResponse.getJSONArray("choices");

        for (int i = 0; i < choices.length(); i++) {
          JSONObject choice = choices.getJSONObject(i);
          JSONObject message = choice.getJSONObject("message");
          System.out.println(message.getString("content"));
          responseContent = message.getString("content");
        }
      } else {
        System.out.println("Error: " + resp.code() + " - " + resp.body().string());
        System.out.println(request);
        System.out.println(body.contentType());
      }
    } catch (IOException e) {
      // TODO Auto-generated catch block
      throw new RuntimeException(e);
    }

    return responseContent;
  }

  private String getInputContent(String urlString) throws IOException {
    URL url = null;

    if (urlString.startsWith("/")) {
      urlString = "file:///" + urlString;
    }
    URLConnection connection = null;
    if (urlString.startsWith("http://") || urlString.startsWith("https://")) {
      url = new URL(urlString);
      connection = (HttpURLConnection) url.openConnection();
      return readToString(connection);
    } else if (urlString.startsWith("file:///")) {
      url = new URL(urlString);
      connection = url.openConnection();
      return readToString(connection);
    } else {
      return urlString.replaceAll("\r\n", " ");
    }
  }

  private static String readToString(URLConnection connection) throws IOException {

    // Initialize a StringBuilder to hold the response
    StringBuilder response = new StringBuilder();

    // Creating a BufferedReader to read the InputStream from URL
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        response.append(line.replaceAll("\r\n", " "));
        response.append(" ");
      }
    } finally {
      if (connection instanceof HttpURLConnection) {
        ((HttpURLConnection)connection).disconnect();
      }
    }
    return response.toString();
  }

  private String getTopologyName() {
    return (String) context.getAttribute("org.apache.knox.gateway.gateway.cluster");
  }

}
