package com.ibm.cdslabs.watson.recipe.bot;

import com.ibm.graph.client.Edge;
import com.ibm.graph.client.Element;
import com.ibm.graph.client.IBMGraphClient;
import com.ibm.graph.client.Vertex;
import com.ibm.graph.client.schema.*;
import com.ibm.watson.developer_cloud.conversation.v1.ConversationService;
import com.ibm.watson.developer_cloud.conversation.v1.model.MessageRequest;
import com.ibm.watson.developer_cloud.conversation.v1.model.MessageResponse;
import com.ullink.slack.simpleslackapi.SlackChannel;
import com.ullink.slack.simpleslackapi.SlackSession;
import com.ullink.slack.simpleslackapi.SlackUser;
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted;
import com.ullink.slack.simpleslackapi.impl.SlackSessionFactory;
import com.ullink.slack.simpleslackapi.listeners.SlackMessagePostedListener;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Created by markwatson on 11/11/16.
 */
public class SousChef {

    private IBMGraphClient graphClient;
    private String slackBotId;
    private String conversationWorkspaceId;
    private SlackSession slackSession;
    private RecipeClient recipeClient;
    private ConversationService conversationService;
    private HashMap<String,UserState> userStateMap = new HashMap<String, UserState>();

    private static Logger logger =  LoggerFactory.getLogger(SousChef.class);

    public SousChef(IBMGraphClient graphClient, String slackToken, String slackBotId, String recipeClientApiKey, String conversationUsername, String conversationPassword, String conversationWorkspaceId) {
        this.graphClient = graphClient;
        this.slackBotId = slackBotId;
        this.slackSession = SlackSessionFactory.createWebSocketSlackSession(slackToken);
        this.recipeClient = new RecipeClient(recipeClientApiKey);
        this.conversationService = new ConversationService(ConversationService.VERSION_DATE_2016_07_11);
        this.conversationService.setUsernameAndPassword(conversationUsername, conversationPassword);
        this.conversationWorkspaceId = conversationWorkspaceId;
    }

    public void run() throws Exception {
        this.initGraph();
        this.slackSession.connect();
        this.slackSession.addMessagePostedListener(new SlackMessagePostedListener() {
            public void onEvent(SlackMessagePosted event, SlackSession session) {
                SlackChannel channel = event.getChannel();
                String messageContent = event.getMessageContent();
                SlackUser messageSender = event.getSender();
                if (channel.getType() == SlackChannel.SlackChannelType.INSTANT_MESSAGING) {
                    if (!messageSender.getId().equals(slackBotId)) {
                        try {
                            processSlackMessage(messageSender.getId(), messageContent, channel);
                        }
                        catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                    else {
                        logger.debug("Received my message.");
                    }
                }
            }
        });
    }

    public void stop() throws Exception {
        this.slackSession.disconnect();
    }

    private void initGraph() throws Exception {
        logger.debug("Getting Graph Schema...");
        Schema schema = this.graphClient.getSchema();
        boolean schemaExists = (schema != null && schema.getPropertyKeys() != null && schema.getPropertyKeys().length > 0);
        if (! schemaExists) {
            schema = new Schema(
                    new PropertyKey[] {
                            new PropertyKey("name", "String", "SINGLE"),
                            new PropertyKey("title", "String", "SINGLE"),
                            new PropertyKey("detail", "String", "SINGLE")
                    },
                    new VertexLabel[] {
                            new VertexLabel("person"),
                            new VertexLabel("ingredient"),
                            new VertexLabel("cuisine"),
                            new VertexLabel("recipe")
                    },
                    new EdgeLabel[] {
                            new EdgeLabel("selects")
                    },
                    new VertexIndex[] {
                            new VertexIndex("vertexByName", new String[]{"name"}, true, true)
                    },
                    new EdgeIndex[]{}
            );
            logger.debug("Creating Graph Schema...");
            this.graphClient.saveSchema(schema);
            logger.debug("Graph Schema created.");
        }
    }

    private void processSlackMessage(String messageSender, String message, SlackChannel channel) throws Exception {
        UserState state = userStateMap.get(messageSender);
        if (state == null) {
            state = new UserState(messageSender);
            userStateMap.put(messageSender, state);
        }
        MessageRequest request = new MessageRequest.Builder().inputText(message).context(state.getConversationContext()).build();
        MessageResponse response = this.conversationService.message(this.conversationWorkspaceId, request).execute();
        state.setConversationContext(response.getContext());
        String reply;
        if (state.getConversationContext().containsKey("is_ingredients") && Boolean.TRUE.equals(state.getConversationContext().get("is_ingredients"))) {
            reply = this.handleIngredientsMessage(state, message);
        }
        else if (state.getConversationContext().containsKey("is_selection") && Boolean.TRUE.equals(state.getConversationContext().get("is_selection"))) {
            state.getConversationContext().put("selection_valid",Boolean.FALSE);
            reply = "Invalid selection! Say anything to see your choices again...";
            int selection = -1;
            if (state.getConversationContext().containsKey("selection")) {
                try {
                    selection = Integer.parseInt(state.getConversationContext().get("selection").toString());
                }
                catch(Exception ex) {}
            }
            if (selection >= 1 && selection <= 5 ) {
                state.getConversationContext().put("selection_valid",Boolean.FALSE);
                reply = this.handleSelectionMessage(state, selection);
            }
        }
        else if (response.getEntities() != null && response.getEntities().size() > 0 && response.getEntities().get(0).getEntity() == "cuisine") {
            String cuisine = response.getEntities().get(0).getValue();
            reply = this.handleCuisineMessage(state, cuisine);
        }
        else {
            reply = this.handleStartMessage(state, response);
        }
        this.slackSession.sendMessage(channel, reply);
    }

    // Messages from Bot

    private String handleStartMessage(UserState state, MessageResponse response) throws Exception {
        String reply = "";
        for (String text : ((ArrayList<String>)response.getOutput().get("text"))) {
            reply += text + "\n";
        }
        this.addUserVertex(state);
        return reply;
    }

    private String handleIngredientsMessage(UserState state, String message) throws Exception {
        // we want to get a list of recipes based on the ingredients (message)
        // first we see if we already have the ingredients in our graph
        String ingredientsStr = message;
        JSONArray matchingRecipes = null;
        Vertex ingredientVertex = this.findIngredientsVertex(ingredientsStr);
        if (ingredientVertex != null) {
            logger.debug(String.format("Ingredients vertex exists for %s. Returning recipes from vertex.",ingredientsStr));
            matchingRecipes = new JSONArray(ingredientVertex.getPropertyValue("detail").toString());
        }
        else {
            // we don't have the ingredients in our graph yet, so get list of recipes from Spoonacular
            logger.debug(String.format("Ingredients vertex does not exist for %s. Querying Spoonacular for recipes.",ingredientsStr));
            matchingRecipes = this.recipeClient.findByIngredients(ingredientsStr);
            // add vertex for the ingredients to our graph
            ingredientVertex = this.addIngredientsVertex(state, ingredientsStr, matchingRecipes);
        }
        // update state
        state.getConversationContext().put("recipes", matchingRecipes);
        state.setLastGraphVertex(ingredientVertex);
        // return the response
        String response = "Let's see here...\nI've found these recipes: \n";
        for (int i=0; i<matchingRecipes.length(); i++) {
            response += (i+1) + ". " + matchingRecipes.getJSONObject(i).getString("title") + "\n";
        }
        response += "\nPlease enter the corresponding number of your choice.";
        return response;
    }

    private String handleSelectionMessage(UserState state, int selection) throws Exception {
        // we want to get a the recipe based on the selection
        // first we see if we already have the recipe in our graph
        ArrayList recipes = (ArrayList)state.getConversationContext().get("recipes");
        String recipeId = ((Number)((AbstractMap)recipes.get(selection-1)).get("id")).intValue() + "";
        String recipeDetail = null;
        Vertex recipeVertex = this.findRecipeVertex(recipeId);
        if (recipeVertex != null) {
            logger.debug(String.format("Recipe vertex exists for %s. Returning recipe steps from vertex.",recipeId));
            recipeDetail = recipeVertex.getPropertyValue("detail").toString();
        }
        else {
            logger.debug(String.format("Recipe vertex does not exist for %s. Querying Spoonacular for details.",recipeId));
            JSONObject recipeInfo = this.recipeClient.getInfoById(recipeId);
            JSONArray recipeSteps = this.recipeClient.getStepsById(recipeId);
            recipeDetail = this.makeFormattedSteps(recipeInfo, recipeSteps);
            // add vertex for recipe
            this.addRecipeVertex(state, recipeId, recipeInfo.getString("title"), recipeDetail);
        }
        // clear out state
        state.setLastGraphVertex(null);
        state.setConversationContext(null);
        // return response
        return recipeDetail;
    }

    private String handleCuisineMessage(UserState state, String cuisine) throws Exception {
        JSONArray recipes = null;
        if (state.getConversationContext().containsKey("get_recipes")) {
            recipes = this.recipeClient.findByCuisine(cuisine);
            state.getConversationContext().put("recipes",recipes);
        }
        String response = "Let's see here...\nI've found these recipes: \n";
        for (int i=0; i<recipes.length(); i++) {
            response += (i+1) + ". " + recipes.getJSONObject(i).getString("title") + "\n";
        }
        response += "\nPlease enter the corresponding number of your choice.";
        // add vertex for recipe
        this.addCuisineVertex(state, cuisine);
        // return response
        return response;
    }

    private String makeFormattedSteps(JSONObject recipeInfo, JSONArray recipeSteps) throws Exception {
        String response = "Ok, it takes *";
        response += recipeInfo.get("readyInMinutes").toString() + "* minutes to make *";
        response += recipeInfo.get("servings").toString() + "* servings of *";
        response += recipeInfo.getString("title") + "*. Here are the steps:\n\n";
        if (recipeSteps != null && recipeSteps.length() > 0) {
            // mw:TODO - add steps
        }
        else {
            response += "_No instructions available for this recipe._\n\n";
        }
        return response;
    }

    //

    private void addUserVertex(final UserState state) throws Exception {
        Vertex userVertex = new Vertex("person", new HashMap() {{
            put("name", state.getUserId());
        }});
        userVertex = this.addVertexIfNotExists(userVertex, "name");
        state.setLastGraphVertex(userVertex);
    }

    // Ingredients

    private String getUniqueIngredientsName(final String ingredientsStr) {
        String[] ingredients = ingredientsStr.trim().toLowerCase().split(",");
        for (int i=0; i<ingredients.length; i++) {
            ingredients[i] = ingredients[i].trim();
        }
        Arrays.sort(ingredients);
        return String.join(",", ingredients);
    }

    private Vertex findIngredientsVertex(final String ingredientsStr) throws Exception {
        return findVertex("ingredient", "name", this.getUniqueIngredientsName(ingredientsStr));
    }

    private Vertex addIngredientsVertex(UserState state, final String ingredientsStr, final JSONArray matchingRecipes) throws Exception {
        Vertex ingredientVertex = new Vertex("ingredient", new HashMap() {{
            put("name", getUniqueIngredientsName(ingredientsStr));
            put("detail", matchingRecipes.toString());
        }});
        ingredientVertex = this.addVertexIfNotExists(ingredientVertex, "name");
        this.addEdgeIfNotExists(new Edge("selects", state.getLastGraphVertex().getId(), ingredientVertex.getId()));
        return ingredientVertex;
    }

    // Cuisine

    private void addCuisineVertex(UserState state, final String cuisine) throws Exception {
        Vertex ingredientVertex = new Vertex("recipe", new HashMap() {{
            put("name", cuisine.trim().toLowerCase());
        }});
        ingredientVertex = this.addVertexIfNotExists(ingredientVertex, "name");
        this.addEdgeIfNotExists(new Edge("selects", state.getLastGraphVertex().getId(), ingredientVertex.getId()));
        state.setLastGraphVertex(ingredientVertex);
    }

    // Recipe

    private String getUniqueRecipeName(final String recipeId) {
       return recipeId.trim().toLowerCase();
    }

    private Vertex findRecipeVertex(final String recipeId) throws Exception {
        return findVertex("recipe", "name", getUniqueRecipeName(recipeId));
    }

    private void addRecipeVertex(UserState state, final String recipeId, final String recipeTitle, final String recipeDetail) throws Exception {
        Vertex recipeVertex = new Vertex("recipe", new HashMap() {{
            put("name", getUniqueRecipeName(recipeId));
            put("title", recipeTitle.trim());
            put("detail", recipeDetail);
        }});
        recipeVertex = this.addVertexIfNotExists(recipeVertex, "name");
        this.addEdgeIfNotExists(new Edge("selects", state.getLastGraphVertex().getId(), recipeVertex.getId()));
        state.setLastGraphVertex(recipeVertex);
    }

    // Graph Helper Methods

    private Vertex findVertex(String label, String propertyName, String propertyValue) throws Exception {
        String query = "g.V().hasLabel(\"" + label + "\").has(\"" + propertyName +"\", \"" + propertyValue + "\")";
        Element[] elements = this.graphClient.runGremlinQuery(query);
        if (elements.length > 0) {
            return (Vertex)elements[0];
        }
        else {
            return null;
        }
    }

    private Vertex addVertexIfNotExists(Vertex vertex, String uniquePropertyName) throws Exception {
        String propertyValue = vertex.getProperties().get(uniquePropertyName).toString();
        String query = "g.V().hasLabel(\"" + vertex.getLabel() + "\").has(\"" + uniquePropertyName +"\", \"" + propertyValue + "\")";
        Element[] elements = this.graphClient.runGremlinQuery(query);
        if (elements.length == 0 || !(elements[0] instanceof Vertex)) {
            logger.debug(String.format("Adding %s vertex where %s=%s",vertex.getLabel(),uniquePropertyName,propertyValue));
            return this.graphClient.addVertex(vertex);
        }
        else {
            logger.debug(String.format("Return %s vertex where %s=%s",vertex.getLabel(),uniquePropertyName,propertyValue));
            return (Vertex)elements[0];
        }
    }

    private void addEdgeIfNotExists(Edge edge) throws Exception {
        String query = "g.V(" + edge.getOutV() + ").outE().inV().hasId(" + edge.getInV() + ")";
        Element[] elements = this.graphClient.runGremlinQuery(query);
        if (elements.length == 0 || !(elements[0] instanceof Vertex)) {
            this.graphClient.addEdge(edge);
        }
    }

}
