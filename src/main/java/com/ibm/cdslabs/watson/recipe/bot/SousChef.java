package com.ibm.cdslabs.watson.recipe.bot;

import com.ibm.graph.client.Edge;
import com.ibm.graph.client.Element;
import com.ibm.graph.client.IBMGraphClient;
import com.ibm.graph.client.Vertex;
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
                        System.out.println("Received my message.");
                    }
                }
            }
        });
    }

    public void stop() throws Exception {
        this.slackSession.disconnect();
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

    //

    private String handleStartMessage(UserState state, MessageResponse response) throws Exception {
        String reply = "";
        for (String text : ((ArrayList<String>)response.getOutput().get("text"))) {
            reply += text + "\n";
        }
        this.addUserVertex(state);
        return reply;
    }

    private String handleIngredientsMessage(UserState state, String message) throws Exception {
        // get list of recipes from Spoonacular based on message from Slack (message = list of ingredients)
        String ingredientsStr = message;
        JSONArray recipes = null;
        if (state.getConversationContext().containsKey("get_recipes")) {
            recipes = this.recipeClient.findByIngredients(ingredientsStr);
            state.getConversationContext().put("recipes",recipes);
        }
        String response = "Let's see here...\nI've found these recipes: \n";
        for (int i=0; i<recipes.length(); i++) {
            response += (i+1) + ". " + recipes.getJSONObject(i).getString("title") + "\n";
        }
        response += "\nPlease enter the corresponding number of your choice.";
        // add vertex for ingredient
        this.addIngredientsVertex(state, ingredientsStr);
        // return response to send to user
        return response;
    }

    private String handleSelectionMessage(UserState state, int selection) throws Exception {
        ArrayList recipes = (ArrayList)state.getConversationContext().get("recipes");
        String recipeId = ((Number)((AbstractMap)recipes.get(selection-1)).get("id")).intValue() + "";
        JSONObject recipeInfo = this.recipeClient.getInfoById(recipeId);
        JSONArray recipeSteps = this.recipeClient.getStepsById(recipeId);
        String response = this.makeFormattedSteps(recipeInfo, recipeSteps);
        // add vertex for recipe
        this.addRecipeVertex(state, recipeId, recipeInfo.getString("title"));
        // clear out state
        state.setLastGraphVertex(null);
        state.setConversationContext(null);
        // return response
        return response;
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

    private void addIngredientsVertex(UserState state, final String ingredientsStr) throws Exception {
        // sort the ingredients so we can find exact matches and not duplicate vertices
        String[] ingredients = ingredientsStr.trim().split(",");
        for (int i=0; i<ingredients.length; i++) {
            ingredients[i] = ingredients[i].trim();
        }
        Arrays.sort(ingredients);
        final String ingredientsSorted = String.join(",", ingredients);
        Vertex ingredientVertex = new Vertex("ingredient", new HashMap() {{
            put("name", ingredientsSorted);
        }});
        ingredientVertex = this.addVertexIfNotExists(ingredientVertex, "name");
        this.addEdgeIfNotExists(new Edge("selects", state.getLastGraphVertex().getId(), ingredientVertex.getId()));
        state.setLastGraphVertex(ingredientVertex);
    }

    private void addCuisineVertex(UserState state, final String cuisine) throws Exception {
        Vertex ingredientVertex = new Vertex("recipe", new HashMap() {{
            put("name", cuisine.trim().toLowerCase());
        }});
        ingredientVertex = this.addVertexIfNotExists(ingredientVertex, "name");
        this.addEdgeIfNotExists(new Edge("selects", state.getLastGraphVertex().getId(), ingredientVertex.getId()));
        state.setLastGraphVertex(ingredientVertex);
    }

    private void addRecipeVertex(UserState state, final String recipeId, final String recipeTitle) throws Exception {
        Vertex recipeVertex = new Vertex("recipe", new HashMap() {{
            put("name", recipeId.trim().toLowerCase());
            put("title", recipeTitle.trim());
        }});
        recipeVertex = this.addVertexIfNotExists(recipeVertex, "name");
        this.addEdgeIfNotExists(new Edge("selects", state.getLastGraphVertex().getId(), recipeVertex.getId()));
        state.setLastGraphVertex(recipeVertex);
    }

    private Vertex addVertexIfNotExists(Vertex vertex, String uniquePropertyName) throws Exception {
        String query = "g.V().hasLabel(\"" + vertex.getLabel() + "\").has(\"" + uniquePropertyName +"\", \"" + vertex.getProperties().get(uniquePropertyName) + "\")";
        Element[] elements = this.graphClient.runGremlinQuery(query);
        if (elements.length == 0 || !(elements[0] instanceof Vertex)) {
            return this.graphClient.addVertex(vertex);
        }
        else {
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
