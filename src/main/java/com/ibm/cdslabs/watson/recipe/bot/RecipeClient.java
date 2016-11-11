package com.ibm.cdslabs.watson.recipe.bot;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONObject;

import java.io.IOException;
import java.net.URI;

/**
 * Created by markwatson on 11/11/16.
 */
public class RecipeClient {

    private String apiKey;

    private final static String SCHEME = "https";
    private final static String HOST = "spoonacular-recipe-food-nutrition-v1.p.mashape.com";

    public RecipeClient(String apiKey) {
        this.apiKey = apiKey;
    }

    public JSONArray findByIngredients(String ingredients) throws Exception {
        URI uri = new URIBuilder()
                .setScheme(SCHEME)
                .setHost(HOST)
                .setPath("/recipes/findByIngredients")
                .addParameter("fillIngredients","false")
                .addParameter("ingredients",ingredients)
                .addParameter("limitLicense","false")
                .addParameter("number","5")
                .addParameter("ranking","1")
                .build();
        HttpGet httpGet = new HttpGet(uri);
        httpGet.setHeader("X-Mashape-Key", this.apiKey);
        httpGet.setHeader("Accept", "application/json");
        CloseableHttpClient httpclient = HttpClients.createDefault();
        CloseableHttpResponse httpResponse = null;
        try {
            httpResponse = httpclient.execute(httpGet);
            HttpEntity httpEntity = httpResponse.getEntity();
            String content = EntityUtils.toString(httpEntity);
            EntityUtils.consume(httpEntity);
            return new JSONArray(content);
        }
        finally {
            try {
                httpResponse.close();
            }
            catch (IOException ioe) {
            }
        }
    }

    public JSONArray findByCuisine(String cuisine) throws Exception {
        URI uri = new URIBuilder()
                .setScheme(SCHEME)
                .setHost(HOST)
                .setPath("/recipes/search")
                .addParameter("number","5")
                .addParameter("query"," ")
                .addParameter("cuisine",cuisine)
                .build();
        HttpGet httpGet = new HttpGet(uri);
        httpGet.setHeader("X-Mashape-Key", this.apiKey);
        httpGet.setHeader("Accept", "application/json");
        CloseableHttpClient httpclient = HttpClients.createDefault();
        CloseableHttpResponse httpResponse = null;
        try {
            httpResponse = httpclient.execute(httpGet);
            HttpEntity httpEntity = httpResponse.getEntity();
            String content = EntityUtils.toString(httpEntity);
            EntityUtils.consume(httpEntity);
            return new JSONObject(content).getJSONArray("results");
        }
        finally {
            try {
                httpResponse.close();
            }
            catch (IOException ioe) {
            }
        }
    }

    public JSONObject getInfoById(String id) throws Exception {
        URI uri = new URIBuilder()
                .setScheme(SCHEME)
                .setHost(HOST)
                .setPath("/recipes/" + id + "/information")
                .addParameter("includeNutrition","false")
                .build();
        HttpGet httpGet = new HttpGet(uri);
        httpGet.setHeader("X-Mashape-Key", this.apiKey);
        httpGet.setHeader("Accept", "application/json");
        CloseableHttpClient httpclient = HttpClients.createDefault();
        CloseableHttpResponse httpResponse = null;
        try {
            httpResponse = httpclient.execute(httpGet);
            HttpEntity httpEntity = httpResponse.getEntity();
            String content = EntityUtils.toString(httpEntity);
            EntityUtils.consume(httpEntity);
            return new JSONObject(content);
        }
        finally {
            try {
                httpResponse.close();
            }
            catch (IOException ioe) {
            }
        }
    }

    public JSONArray getStepsById(String id) throws Exception {
        URI uri = new URIBuilder()
                .setScheme(SCHEME)
                .setHost(HOST)
                .setPath("/recipes/" + id + "/analyzedInstructions")
                .addParameter("stepBreakdown","true")
                .build();
        HttpGet httpGet = new HttpGet(uri);
        httpGet.setHeader("X-Mashape-Key", this.apiKey);
        httpGet.setHeader("Accept", "application/json");
        CloseableHttpClient httpclient = HttpClients.createDefault();
        CloseableHttpResponse httpResponse = null;
        try {
            httpResponse = httpclient.execute(httpGet);
            HttpEntity httpEntity = httpResponse.getEntity();
            String content = EntityUtils.toString(httpEntity);
            EntityUtils.consume(httpEntity);
            return new JSONArray(content);
        }
        finally {
            try {
                httpResponse.close();
            }
            catch (IOException ioe) {
            }
        }
    }
}
