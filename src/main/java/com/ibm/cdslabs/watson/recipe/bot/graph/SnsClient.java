package com.ibm.cdslabs.watson.recipe.bot.graph;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.wink.json4j.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Created by markwatson on 1/6/17.
 */
public class SnsClient {

    private String apiUrl;
    private String apiKey;
    private boolean enabled;

    private static Logger logger = LoggerFactory.getLogger(SnsClient.class);


    public SnsClient(String apiUrl, String apiKey) {
        if (apiUrl == null || apiUrl.trim().length() == 0) {
            this.enabled = false;
        }
        else {
            this.enabled = true;
            this.apiUrl = apiUrl;
            this.apiKey = apiKey;
        }
    }

    public void postStartMessage(UserState state) {
        this.postMessage("start", state, String.format("%s started a new conversation.", state.getUserId()), null);
    }

    public void postFavoritesMessage(UserState state) {
        this.postMessage("favorites", state, String.format("%s requested their favorite recipes.",state.getUserId()), null);
    }

    public void postIngredientMessage(UserState state, String ingredientStr) {
        this.postMessage("ingredient", state, String.format("%s requested recipes for ingredient \"%s\".",state.getUserId(),ingredientStr), null);
    }

    public void postCuisineMessage(UserState state, String cuisineStr) {
        this.postMessage("ingredient", state, String.format("%s requested recipes for cuisine \"%s\".",state.getUserId(),cuisineStr), null);
    }

    public void postRecipeMessage(UserState state, String recipeId, String recipeTitle) {
        this.postMessage("ingredient", state, String.format("%s selected recipe \"%s\".",state.getUserId(),recipeTitle), recipeId);
    }

    public void postMessage(final String action, final UserState state, final String message, final String recipeId) {
        if (!this.enabled) {
            return;
        }
        String ingredient = "";
        String cuisine = "";
        if (state.getIngredientCuisine() != null) {
            if (state.getIngredientCuisine().getLabel().equalsIgnoreCase("ingredient")) {
                ingredient = state.getIngredientCuisine().getPropertyValue("name").toString();
            }
            else {
                cuisine = state.getIngredientCuisine().getPropertyValue("name").toString();
            }
        }
        try {
            final String ingredientFinal = ingredient;
            final String cuisineFinal = cuisine;
            JSONObject body = new JSONObject() {{
                put("userQuery", new JSONObject() {{
                    put("type", "action");
                }});
                put("notification", new JSONObject() {{
                    put("action", action);
                    put("message", message);
                    put("state", new JSONObject() {{
                        put("user", state.getUserId());
                        put("ingredient", ingredientFinal);
                        put("cuisine", cuisineFinal);
                        put("recipe", recipeId);
                    }});
                }});
            }};
            String url = String.format("%s/%s/notification",this.apiUrl,this.apiKey);
            this.doHttpPost(body, url);
        }
        catch(Exception ex) {
            logger.error("Error posting message", ex);
        }
    }

    private void doHttpPost(JSONObject json, String url) throws Exception {
        String payload = (json == null ? "" : json.toString());
        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));
        logger.debug(String.format("Making HTTP POST request to %s; payload=%s",url,payload));
        this.doHttpRequest(httpPost);
    }

    private void doHttpRequest(HttpUriRequest request) throws Exception {
        CloseableHttpClient httpclient = HttpClients.createDefault();
        CloseableHttpResponse httpResponse = null;
        try {
            logger.debug(String.format("Sending HTTP request %s", request.toString()));
            httpResponse = httpclient.execute(request);
        }
        finally {
            if (httpResponse != null) {
                try {
                    httpResponse.close();
                }
                catch (IOException ioex) {
                    // ignore
                }
            }

        }
    }
}
