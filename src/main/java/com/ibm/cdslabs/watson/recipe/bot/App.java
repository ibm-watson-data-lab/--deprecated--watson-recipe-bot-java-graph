package com.ibm.cdslabs.watson.recipe.bot;

import com.ibm.graph.client.IBMGraphClient;

import java.util.Map;

public class App {

    public static void main( String[] args ) throws Exception {
        Map env = System.getenv();
        IBMGraphClient graphClient = new IBMGraphClient(
                env.get("GRAPH_API_URL").toString(),
                env.get("GRAPH_USERNAME").toString(),
                env.get("GRAPH_PASSWORD").toString()
        );
        SousChef sousChef = new SousChef(
                new GraphRecipeStore(graphClient),
                env.get("SLACK_BOT_TOKEN").toString(),
                env.get("SLACK_BOT_ID").toString(),
                env.get("SPOONACULAR_KEY").toString(),
                env.get("CONVERSATION_USERNAME").toString(),
                env.get("CONVERSATION_PASSWORD").toString(),
                env.get("CONVERSATION_WORKSPACE_ID").toString()
        );
        sousChef.run();
        System.in.read();
        sousChef.stop();
    }

}
