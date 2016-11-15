package com.ibm.cdslabs.watson.recipe.bot;

import com.ibm.graph.client.Element;
import com.ibm.graph.client.IBMGraphClient;
import com.ibm.graph.client.Vertex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hello world!
 *
 */
public class PurgeGraph {

    public static void main( String[] args ) throws Exception {
        IBMGraphClient graphClient = new IBMGraphClient();
        String[] userIds = new String[]{"U2JBLUPL2"};
        String[] ingredients = new String[]{"beef"};
        String[] recipes = new String[]{"163864"};
        //
        for(String userId : userIds) {
            Element[] elements = graphClient.runGremlinQuery("g.V().hasLabel(\"person\").has(\"name\", \"" + userId + "\")");
            if (elements.length > 0) {
                for (Element element : elements) {
                    boolean success = graphClient.deleteVertex(((Vertex) element).getId());
                    System.out.println("DELETED = " + success);
                }
            }
        }
        for (String ingredient : ingredients) {
            Element[] elements = graphClient.runGremlinQuery("g.V().hasLabel(\"ingredient\").has(\"name\", \"" + ingredient + "\")");
            if (elements.length > 0) {
                for (Element element : elements) {
                    boolean success = graphClient.deleteVertex(((Vertex) element).getId());
                    System.out.println("DELETED = " + success);
                }
            }
        }
        for (String recipe : recipes) {
            Element[] elements = graphClient.runGremlinQuery("g.V().hasLabel(\"recipe\").has(\"name\", \"" + recipe + "\")");
            if (elements.length > 0) {
                for (Element element : elements) {
                    boolean success = graphClient.deleteVertex(((Vertex) element).getId());
                    System.out.println("DELETED = " + success);
                }
            }
        }
    }

}
