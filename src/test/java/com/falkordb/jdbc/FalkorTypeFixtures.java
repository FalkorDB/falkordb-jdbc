package com.falkordb.jdbc;

import java.util.List;
import java.util.Map;

import com.falkordb.graph_entities.Edge;
import com.falkordb.graph_entities.Node;
import com.falkordb.graph_entities.Path;

/** Builders for the JFalkorDB graph entities the driver has to map, shared across tests. */
final class FalkorTypeFixtures {

    private FalkorTypeFixtures() {}

    static Node node(long id, String label, Map<String, Object> properties) {
        Node node = new Node();
        node.setId(id);
        node.addLabel(label);
        properties.forEach(node::addProperty);
        return node;
    }

    static Edge edge(long id, String type, long source, long target, Map<String, Object> properties) {
        Edge edge = new Edge();
        edge.setId(id);
        edge.setRelationshipType(type);
        edge.setSource(source);
        edge.setDestination(target);
        properties.forEach(edge::addProperty);
        return edge;
    }

    static Path path(List<Node> nodes, List<Edge> edges) {
        return new Path(nodes, edges);
    }
}
