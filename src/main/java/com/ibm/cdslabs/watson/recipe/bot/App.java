package com.ibm.cdslabs.watson.recipe.bot;

import java.util.Map;

/**
 * Hello world!
 *
 */
public class App {
    public static void main( String[] args ) throws Exception {
        Map env = System.getenv();
        SousChef sousChef = new SousChef(
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
