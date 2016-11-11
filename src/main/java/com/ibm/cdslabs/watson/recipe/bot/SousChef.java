package com.ibm.cdslabs.watson.recipe.bot;

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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by markwatson on 11/11/16.
 */
public class SousChef {

    private String slackBotId;
    private String conversationWorkspaceId;
    private SlackSession slackSession;
    private RecipeClient recipeClient;
    private ConversationService conversationService;
    private HashMap<String,Map<String,Object>> userContextMap = new HashMap<String, Map<String, Object>>();
    
    public SousChef(String slackToken, String slackBotId, String recipeClientApiKey, String conversationUsername, String conversationPassword, String conversationWorkspaceId) {
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
                System.out.println(messageContent);
                if (! messageSender.getId().equals(slackBotId)) {
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
        });
    }

    public void stop() throws Exception {
        this.slackSession.disconnect();
    }

    private void processSlackMessage(String messageSender, String message, SlackChannel channel) throws Exception {
        if (channel.getType() == SlackChannel.SlackChannelType.INSTANT_MESSAGING) {
            Map<String, Object> context = userContextMap.get(messageSender);
            MessageRequest request = new MessageRequest.Builder().inputText(message).context(context).build();
            MessageResponse response = this.conversationService.message(this.conversationWorkspaceId, request).execute();
            context = response.getContext();
            userContextMap.put(messageSender, context);
            String reply;
            if (context.containsKey("is_ingredients") && Boolean.TRUE.equals(context.get("is_ingredients"))) {
                reply = this.handleIngredientsMessage(context, message);
            }
            else if (context.containsKey("is_selection") && Boolean.TRUE.equals(context.get("is_selection"))) {
                context.put("selection_valid",Boolean.FALSE);
                reply = "Invalid selection! Say anything to see your choices again...";
                int selection = -1;
                if (context.containsKey("selection")) {
                    try {
                        selection = Integer.parseInt(context.get("selection").toString());
                    }
                    catch(Exception ex) {}
                }
                if (selection >= 1 && selection <= 5 ) {
                    context.put("selection_valid",Boolean.FALSE);
                    reply = this.handleSelectionMessage(context, selection);
                }
            }
            else if (response.getEntities() != null && response.getEntities().size() > 0 && response.getEntities().get(0).getEntity() == "cuisine") {
                String cuisine = response.getEntities().get(0).getValue();
                reply = this.handleCuisineMessage(context, cuisine);
            }
            else {
                reply = "";
                for (String text : ((ArrayList<String>)response.getOutput().get("text"))) {
                    reply += text + "\n";
                }
            }
            this.slackSession.sendMessage(channel, reply);
        }
    }

    private String handleIngredientsMessage(Map<String, Object> context, String message) throws Exception {
        JSONArray recipes = null;
        if (context.containsKey("get_recipes")) {
            recipes = this.recipeClient.findByIngredients(message);
            context.put("recipes",recipes);
        }
        String response = "Let's see here...\nI've found these recipes: \n";
        for (int i=0; i<recipes.length(); i++) {
            response += (i+1) + ". " + recipes.getJSONObject(i).getString("title") + "\n";
        }
        response += "\nPlease enter the corresponding number of your choice.";
        return response;
    }

    private String handleSelectionMessage(Map<String, Object> context, int selection) throws Exception {
        ArrayList recipes = (ArrayList)context.get("recipes");
        String recipeId = ((Number)((AbstractMap)recipes.get(selection-1)).get("id")).intValue() + "";
        JSONObject recipeInfo = this.recipeClient.getInfoById(recipeId);
        JSONArray recipeSteps = this.recipeClient.getStepsById(recipeId);
        return this.makeFormattedSteps(recipeInfo, recipeSteps);
    }

    private String handleCuisineMessage(Map<String, Object> context, String cuisine) throws Exception {
        JSONArray recipes = null;
        if (context.containsKey("get_recipes")) {
            recipes = this.recipeClient.findByCuisine(cuisine);
            context.put("recipes",recipes);
        }
        String response = "Let's see here...\nI've found these recipes: \n";
        for (int i=0; i<recipes.length(); i++) {
            response += (i+1) + ". " + recipes.getJSONObject(i).getString("title") + "\n";
        }
        response += "\nPlease enter the corresponding number of your choice.";
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

}
