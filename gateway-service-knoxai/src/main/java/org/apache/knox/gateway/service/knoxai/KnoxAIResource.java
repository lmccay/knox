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
import org.apache.knox.gateway.config.GatewayConfig;
import org.apache.knox.gateway.i18n.messages.MessagesFactory;

import org.apache.knox.gateway.services.GatewayServices;
import org.apache.knox.gateway.services.ServiceType;
import org.apache.knox.gateway.services.security.AliasService;
import org.apache.knox.gateway.services.security.AliasServiceException;
import org.apache.knox.gateway.util.Urls;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.annotation.PostConstruct;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.Consumes;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.Response.ok;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Path( KnoxAIResource.RESOURCE_PATH )
public class KnoxAIResource {
  private static final KnoxAIMessages log = MessagesFactory.get( KnoxAIMessages.class );

  private static final String API_KEY_ALIAS = "api.key.alias";
  private static final String DEFAULT_KEY_ALIAS = "openai.api.key.alias";
  static final String RESOURCE_PATH = "/{serviceName:.*}/api/v1/chat/completions";
  public static final String MODEL_ENDPOINT_URL = "model.endpoint.url";
  public static final String MODEL_NAME = "model.name";
  private static final String DEFAULT_MODEL_NAME = "gpt-4-turbo-preview";
  private static final String PROMPT_DIR = "prompt.dir";
  private static final String ALLOW_UNCURATED_PROMPTS = "allow.uncurated.prompts";

  @Context
  HttpServletRequest request;
  @Context
  HttpServletResponse response;
  @Context
  ServletContext context;

  private Map<String, String> params = new HashMap<>();
  private AliasService as = null;

  @PostConstruct
  public void init() {
    GatewayServices services = (GatewayServices) context.getAttribute(GatewayServices.GATEWAY_SERVICES_ATTRIBUTE);
    as = services.getService(ServiceType.ALIAS_SERVICE);

    Enumeration<String> enumeration = context.getInitParameterNames();
    String paramName = null;
    while (enumeration.hasMoreElements()) {
      paramName = enumeration.nextElement();
      params.put(paramName, context.getInitParameter(paramName));
    }
  }

  @POST
  @Produces({APPLICATION_JSON})
  @Consumes({APPLICATION_JSON})
  public Response interact(String requestBody) {
    String responseContent = do_openai(requestBody);
      return ok().entity(responseContent).build();
  }

  private String do_openai(String requestBody) {
    String responseContent = null;
    OkHttpClient client = new OkHttpClient();
    client.setConnectTimeout(60000, TimeUnit.MILLISECONDS);
    client.setReadTimeout(1000, TimeUnit.SECONDS);

    ParsedRequest pr = new ParsedRequest(requestBody);

    String patternURL = getPromptDir() + pr.getPrompt() + "/system.md";
    System.out.println(patternURL);
    System.out.println(pr.getInput());

    String inputContent = null;
    String responseBody = null;
    String modelName = getModelName();
    char[] apiKey = null;
    try {
        apiKey = as.getPasswordFromAliasForCluster(getTopologyName(), params.get(modelName + "." + API_KEY_ALIAS));
    } catch (AliasServiceException e) {
        throw new RuntimeException(e);
    }
    try {
      int words = pr.getPrompt().split("\\s").length;
      if (words > 1 && !"true".equalsIgnoreCase(params.get(modelName + "." + ALLOW_UNCURATED_PROMPTS))) {
        response.sendError(400, "Uncurated Prompts are not Allowed");
      }

      inputContent = getInputContent(pr.getInput());
      String prompt = "You are a generally useful agent hoping to be of help.";
      if (pr.getPrompt() != null) {
        prompt = getInputContent(patternURL);
      }

      String model = params.get(modelName + "." + MODEL_NAME);
      String bodyString = "{\"model\": \"" + model + "\""
              + ", \"messages\": [{\"role\": \"system\", \"content\": \"" + prompt + "\"},"
              + "{\"role\": \"user\", \"content\": \"" + inputContent + "\"}]}";

      RequestBody body = RequestBody.create(MediaType.parse("application/json"), bodyString);

      Request request = new Request.Builder()
              .url(params.get(modelName + "." + MODEL_ENDPOINT_URL))
              .addHeader("Authorization", "Bearer " + new String(apiKey))
              .addHeader("Content-Type", "application/json")
              .post(body)
              .build();

      com.squareup.okhttp.Response resp = client.newCall(request).execute();

      if (resp.isSuccessful()) {
          responseBody = resp.body().string();
      } else {
        System.out.println("Error: " + resp.code() + " - " + resp.body().string());
        System.out.println(request);
        System.out.println(body.contentType());
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return responseBody;
  }

  private String getPromptDir() {
    String modelName = getModelName();
    String promptDir = params.get(modelName + "." + PROMPT_DIR);
    return (promptDir.endsWith("/")) ? promptDir : promptDir + "/";
  }

  private String getModelName() {
      try {
          return Urls.getServiceNameFromKnoxURLWithGatewayPath(
                request.getRequestURL().toString(), ((GatewayConfig)context.getAttribute(
                        GatewayConfig.GATEWAY_CONFIG_ATTRIBUTE)).getGatewayPath());
      } catch (MalformedURLException e) {
        // this shouldn't happen given that we are getting the url from the request
          throw new RuntimeException(e);
      }
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
      return escapePrompt(readToString(connection));
    } else if (urlString.startsWith("file:///")) {
      url = new URL(urlString);
      connection = url.openConnection();
      return escapePrompt(readToString(connection));
    } else {
      return urlString.replaceAll("\r\n", " ");
    }
  }

  public static String escapePrompt(String prompt) {
    StringBuilder escaped = new StringBuilder();
    boolean inCodeBlock = false;
    String[] lines = prompt.split("\n", -1);  // -1 to keep empty lines

    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.trim().startsWith("```")) {
              inCodeBlock = !inCodeBlock;
      escaped.append(escapeJsonString(line)).append("\\n");
    } else if (inCodeBlock) {
      // For code blocks, escape only what's necessary for JSON
      escaped.append(escapeCodeBlock(line))
              .append(i < lines.length - 1 ? "\\n" : "");
    } else {
      escaped.append(escapeJsonString(line))
              .append(i < lines.length - 1 ? "\\n" : "");
    }
  }

    return escaped.toString();
}

private static String escapeCodeBlock(String input) {
  return input.replace("\\", "\\\\")  // Escape backslashes first
          .replace("\"", "\\\""); // Then escape double quotes
}

private static String escapeJsonString(String input) {
  StringBuilder result = new StringBuilder();
  for (char c : input.toCharArray()) {
    switch (c) {
      case '"': result.append("\\\""); break;
      case '\\': result.append("\\\\"); break;
      case '\b': result.append("\\b"); break;
      case '\f': result.append("\\f"); break;
      case '\n': result.append("\\n"); break;
      case '\r': result.append("\\r"); break;
      case '\t': result.append("\\t"); break;
      default:
        if (c < ' ') {
          String t = "000" + Integer.toHexString(c);
          result.append("\\u").append(t.substring(t.length() - 4));
        } else {
          result.append(c);
        }
    }
  }
  return result.toString();
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

  private class ParsedRequest {
    private String prompt;
    private String input;

    public ParsedRequest(String requestBody) {
      // Parse the request body using org.json
      JSONObject jsonRequest = new JSONObject(requestBody);

      // Extract system prompt and user message
      String systemPrompt = null;
      String userMessage = null;
      JSONArray messages = jsonRequest.getJSONArray("messages");
      for (int i = 0; i < messages.length(); i++) {
        JSONObject message = messages.getJSONObject(i);
        String role = message.getString("role");
        if ("system".equals(role)) {
          prompt = message.getString("content");
        } else if ("user".equals(role)) {
          input = message.getString("content");
        }
      }
    }
    public String getPrompt() {
      return prompt;
    }

    public String getInput() {
      return input;
    }
  }
}
