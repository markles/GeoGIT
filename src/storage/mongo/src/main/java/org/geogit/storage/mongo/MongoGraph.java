/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */
package org.geogit.storage.mongo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.types.ObjectId;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.WriteResult;
import com.tinkerpop.blueprints.Contains;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Features;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.GraphQuery;
import com.tinkerpop.blueprints.KeyIndexableGraph;
import com.tinkerpop.blueprints.Parameter;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.VertexQuery;
import com.tinkerpop.blueprints.util.DefaultGraphQuery;
import com.tinkerpop.blueprints.util.DefaultVertexQuery;

/**
 * MongoGraph encodes a general property graph into a MongoDB collection.
 * The encoding works as follows:
 *   * User-provided keys are simply ignored.  Everything is based on mongo's
 *     autogenerated '_id' identity.
 *   * For an edge, the endpoints are stored by object id (mongo's "$id") in
 *     "_in" and "_out" fields.
 *   * The (optional) edge label is stored in "_label".
 *   * Any user-defined properties are stored in a sub-document named
 *     "_properties".  There are no restrictions on property names or values
 *     beyond those imposed by Mongo.
 *   * For indexing, all index names are prefixed with 'e' for edge indices or
 *     'v' or vertex indices.  Mongo's index system doesn't let us easily filter
 *     out edges or vertices with the document conventions used here, so indexed
 *     queries always are modified to require or disallow the presence of the '_in' and '_out'
 *     properties according to the index type - the name just helps the system
 *     to know which one we expect.
 */
class MongoGraph implements Graph, KeyIndexableGraph {
    private final DBCollection collection;

    public MongoGraph(DBCollection collection) {
        this.collection = collection;
    }

    public Edge addEdge(Object id, Vertex out, Vertex in, String label) {
        if (label == null) {
            throw new IllegalArgumentException("Edge label may not be null");
        }

        final BasicDBObjectBuilder builder;
        if (id == null) {
            builder = BasicDBObjectBuilder.start();
        } else {
            builder = BasicDBObjectBuilder.start("_id", id);
        }
        DBObject edge =
            builder
            .append("_out", out.getId())
            .append("_in", in.getId())
            .append("_label", label)
            .get();
        WriteResult result = collection.save(edge);
        if (result.getLastError().ok()) {
            return new MEdge(edge);
        } else {
            throw new RuntimeException("Storing edge to mongo failed: " + result.getError());
        }
    }

    public Edge getEdge(Object id) {
        DBObject query = idQuery(id);
        DBObject result = collection.findOne(query);
        if (result == null) {
            return null;
        } else {
            return new MEdge(result);
        }
    }

    public Iterable<Edge> getEdges() {
        DBObject query = BasicDBObjectBuilder.start()
                         .push("_in")
                         .append("$exists", 1)
                         .pop()
                         .push("_out")
                         .append("$exists", 1)
                         .pop()
                         .get();
        return getEdges(query);
    }

    public Iterable<Edge> getEdges(String key, Object value) {
        DBObject query = BasicDBObjectBuilder.start()
                         .push("_in")
                         .append("$exists", 1)
                         .pop()
                         .push("_out")
                         .append("$exists", 1)
                         .pop()
                         .append("_properties." + key, value)
                         .get();
        return getEdges(query);
    }

    private Iterable<Edge> getEdgesWithLimit(DBObject query, int limit) {
        Iterable<DBObject> results = collection.find(query).limit(limit);
        return edges(results);
    }

    private Iterable<Edge> getEdges(DBObject query) {
        Iterable<DBObject> results = collection.find(query);
        return edges(collection.find(query));
    }

    private Iterable<Edge> edges(Iterable<DBObject> objects) {
        return Iterables.transform(objects, new Function<DBObject, Edge>() {
            @Override
            public Edge apply(DBObject record) {
                return new MEdge(record);
            }
        });
    }

    private Iterable<Vertex> vertices(Iterable<DBObject> objects) {
        return Iterables.transform(objects, new Function<DBObject, Vertex>() {
            @Override
            public Vertex apply(DBObject record) {
                return new MVertex(record);
            }
        });
    }

    private Iterable<Vertex> edgeVertices(final Direction direction, Iterable<Edge> objects) {
        return Iterables.transform(objects, new Function<Edge, Vertex>() {
            @Override
            public Vertex apply(Edge edge) {
                return edge.getVertex(direction);
            }
        });
    }

    public Features getFeatures() {
        Features features = new Features();
        features.ignoresSuppliedIds = false;
        features.isPersistent = false; // TODO: Fix tests! they currently drop the database before each run.
        features.isWrapper = false;
        features.supportsBooleanProperty = true;
        features.supportsDoubleProperty = true;
        features.supportsDuplicateEdges = true;
        features.supportsEdgeIndex = false;
        features.supportsEdgeIteration = true;
        features.supportsEdgeKeyIndex = true;
        features.supportsEdgeProperties = true;
        features.supportsEdgeRetrieval = false;
        features.supportsFloatProperty = true;
        features.supportsIndices = false;
        features.supportsIntegerProperty = true;
        features.supportsKeyIndices = true;
        features.supportsLongProperty = true;
        features.supportsMapProperty = true;
        features.supportsMixedListProperty = true;
        features.supportsPrimitiveArrayProperty = true;
        features.supportsSelfLoops = true;
        features.supportsSerializableObjectProperty = false;
        features.supportsStringProperty = true;
        features.supportsThreadedTransactions = false;
        features.supportsTransactions = false;
        features.supportsUniformListProperty = true;
        features.supportsVertexIndex = false;
        features.supportsVertexIteration = true;
        features.supportsVertexKeyIndex = true;
        features.supportsVertexProperties = true;
        return features;
    }

    public Vertex addVertex(Object key) {
        DBObject record;
        WriteResult result;

        if (key instanceof String) {
            record = BasicDBObjectBuilder.start("_id", (String) key).get();
        } else {
            record = new BasicDBObject();
        }
        result = collection.save(record);
        if (result.getLastError().ok())
            return new MVertex(record);
        else
            throw new RuntimeException("Storing vertex to mongo failed: " + result.getError());
    }

    public Vertex getVertex(Object id) {
        DBObject query = idQuery(id);
        DBObject result = collection.findOne(query);
        if (result == null) {
            return null;
        } else {
            return new MVertex(result);
        }
    }

    public Iterable<Vertex> getVertices() {
        DBObject query = BasicDBObjectBuilder.start()
                         .push("_in")
                         .append("$exists", 0)
                         .pop()
                         .push("_out")
                         .append("$exists", 0)
                         .pop()
                         .get();
        return getVertices(query);
    }

    public Iterable<Vertex> getVertices(String key, Object value) {
        DBObject query = BasicDBObjectBuilder.start()
                         .push("_in")
                         .append("$exists", 0)
                         .pop()
                         .push("_out")
                         .append("$exists", 0)
                         .pop()
                         .append("_properties." + key, value)
                         .get();
        return getVertices(query);
    }

    public Iterable<Vertex> getVerticesWithLimit(DBObject query, int limit) {
        return vertices(collection.find(query).limit(limit));
    }

    public Iterable<Vertex> getVertices(DBObject query) {
        return vertices(collection.find(query));
    }

    public GraphQuery query() {
        return new MGraphQuery(new BasicDBObject());
    }

    public void removeEdge(Edge edge) {
        edge.remove();
    }

    public void removeVertex(Vertex vertex) {
        vertex.remove();
    }

    public void shutdown() {
        // TODO: manage resources
    }

    public <T extends Element> void createKeyIndex(String key, Class<T> elementClass) {
        createKeyIndex(key, elementClass, new Parameter[0]);
    }

    public <T extends Element> void createKeyIndex(String key, Class<T> elementClass, Parameter... parameters) {
        if (parameters.length > 0) {
            throw new UnsupportedOperationException("No parameters are recognized at this time.");
        }
        if (elementClass.equals(Vertex.class)) {
            DBObject keys = new BasicDBObject("properties." + key, 1);
            collection.ensureIndex(keys, "v" + key);
        } else if (elementClass.equals(Edge.class)) {
            DBObject keys = new BasicDBObject("properties." + key, 1);
            collection.ensureIndex(keys, "e" + key);
        } else {
            throw new UnsupportedOperationException("Can only index properties of edges or vertices, not " + elementClass);
        }
    }

    public <T extends Element> void dropKeyIndex(String key, Class<T> elementClass) {
        final String prefix;
        if (elementClass.equals(Vertex.class)) {
            prefix = "v";
        } else if (elementClass.equals(Edge.class)) {
            prefix = "e";
        } else {
            throw new UnsupportedOperationException("Can only drop indices for edges or vertices, not "  + elementClass);
        }
        collection.dropIndex(prefix + key);
    }

    public <T extends Element> Set<String> getIndexedKeys(Class<T> elementClass) {
        List<DBObject> indices = collection.getIndexInfo();
        Set<String> results = new HashSet<String>();
        final String prefix;
        if (elementClass.equals(Vertex.class)) {
            prefix = "v";
        } else if (elementClass.equals(Edge.class)) {
            prefix = "e";
        } else {
            throw new UnsupportedOperationException("Can only retrieve indices for edges or vertices, not "  + elementClass);
        }
        for (DBObject index : indices) {
            Object untypedName = index.get("name");
            if (untypedName instanceof String) {
                String name = (String) untypedName;
                if (name.startsWith(prefix)) {
                    Object untypedKeys = index.get("key");
                    if (untypedKeys instanceof DBObject) {
                        DBObject keys = (DBObject) untypedKeys;
                        for (String k : keys.keySet()) {
                            results.add(k.substring("properties.".length()));
                        }
                    }
                }
            }
        }
        return Collections.unmodifiableSet(results);
    }


    private DBObject idQuery(Object id) {
        if (id instanceof String) {
            try {
                return new BasicDBObject("$or",
                                         new DBObject[] {
                                             new BasicDBObject("_id", (String)id),
                                             new BasicDBObject("_id", new ObjectId((String) id))
                                         });
            } catch (IllegalArgumentException e) {
                return new BasicDBObject("_id", id);
            }
        } else if (id == null) {
            throw new IllegalArgumentException("Cannot lookup element with null id");
        } else {
            return new BasicDBObject("_id", id);
        }
    }

    private abstract class MElement implements Element {
        protected DBObject record;

        public MElement(DBObject record) {
            this.record = record;
        }

        public Object getId() {
            return record.get("_id");
        }

        public <T> T getProperty(String key) {
            Object properties = record.get("_properties");
            if (properties instanceof DBObject) {
                return (T)((DBObject) properties).get(key);
            } else {
                return null;
            }
        }

        public Set<String> getPropertyKeys() {
            Object properties = record.get("_properties");
            if (properties instanceof DBObject) {
                return new HashSet(((DBObject) properties).keySet());
            } else {
                return Collections.emptySet();
            }
        }

        public abstract void remove();

        public <T> T removeProperty(String key) {
            Object properties = record.get("_properties");
            if (properties instanceof DBObject) {
                T result = (T)((DBObject) properties).removeField(key);
                collection.save(record);
                return result;
            } else {
                return null;
            }
        }

        public void setProperty(String key, Object value) {
            if (MongoGraph.RESERVED_IDENTIFIERS.contains(key)) {
                throw new IllegalArgumentException("'id' is a reserved property name");
            }
            if (value instanceof Date) {
                throw new IllegalArgumentException("blocking date properties for blueprints test suite");
            }
            Object propertiesUntyped = record.get("_properties");
            DBObject properties;
            if (propertiesUntyped instanceof DBObject) {
                properties = (DBObject) propertiesUntyped;
            } else {
                properties =  new BasicDBObject();
            }
            properties.put(key, value);
            record.put("_properties", properties);
            collection.save(record);
        }

        @Override
        public boolean equals(Object that) {
            if (that instanceof MElement) {
                return getId().equals(((MElement)that).getId());
            } else {
                return false;
            }
        }

        public abstract int hashCode();
    }

    private class MVertex extends MElement implements Vertex {
        public MVertex(DBObject record) {
            super(record);
        }

        public Edge addEdge(String label, Vertex inVertex) {
            return MongoGraph.this.addEdge(null, this, inVertex, label);
        }

        public Iterable<Edge> getEdges(Direction direction, String... labels) {
            DBObject path, query;
            switch(direction) {
            case BOTH:
                return Iterables.concat(
                           getEdges(Direction.OUT, labels),
                           getEdges(Direction.IN, labels));
            case IN:
                path = new BasicDBObject("_in", getId());
                query =
                    labels.length == 0
                    ? path
                    : BasicDBObjectBuilder.start(path.toMap())
                    .push("_label")
                    .append("$in", labels)
                    .pop()
                    .get();
                return edges(collection.find(query));
            case OUT:
                path = new BasicDBObject("_out", getId());
                query =
                    labels.length == 0
                    ? path
                    : BasicDBObjectBuilder.start(path.toMap())
                    .push("_label")
                    .append("$in", labels)
                    .pop()
                    .get();
                return edges(collection.find(query));
            default:
                throw new IllegalArgumentException("Null direction");
            }
        }

        public Iterable<Vertex> getVertices(Direction direction, String... labels) {
            switch(direction) {
            case OUT:
                return edgeVertices(Direction.IN, getEdges(direction, labels));
            case IN:
                return edgeVertices(Direction.OUT, getEdges(direction, labels));
            case BOTH:
                return Iterables.concat(
                           getVertices(Direction.OUT, labels),
                           getVertices(Direction.IN, labels));
            default:
                throw new RuntimeException("null direction");
            }
        }

        public VertexQuery query() {
            return new MVertexQuery(new BasicDBObject("_id", getId()), this);
        }

        @Override
        public void remove() {
            DBObject query =
                new BasicDBObject("$or",
                                  new DBObject[] {
                                      new BasicDBObject("_id", record.get("_id")),
                                      new BasicDBObject("_in", record.get("_id")),
                                      new BasicDBObject("_out", record.get("_id"))
                                  });
            collection.remove(query);
        }

        @Override
        public int hashCode() {
            int hash = 17;
            hash *= "Vertex".hashCode();
            hash += 31;
            hash *= getId().hashCode();
            return hash;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder("Vertex(");
            builder.append(getId());
            builder.append("){");
            Object properties = record.get("_properties");
            if (properties instanceof DBObject) {
                for (Map.Entry<String, Object> entry
                        : ((Map<String, Object>) ((DBObject) properties).toMap()).entrySet()) {
                    builder.append(entry.getKey());
                    builder.append(";");
                    builder.append(entry.getValue());
                }
            }
            builder.append("}");
            return builder.toString();
        }
    }

    private class MEdge extends MElement implements Edge {
        public MEdge(DBObject record) {
            super(record);
        }

        public String getLabel() {
            Object label = record.get("_label");
            if (label instanceof String) {
                return (String) label;
            } else {
                return null;
            }
        }

        public Vertex getVertex(Direction direction) {
            switch(direction) {
            case BOTH:
                throw new IllegalArgumentException("You can't get BOTH vertices");
            case OUT:
                return MongoGraph.this.getVertex(record.get("_out"));
            case IN:
                return MongoGraph.this.getVertex(record.get("_in"));
            default:
                throw new IllegalArgumentException("Null direction");
            }
        }

        @Override
        public void remove() {
            DBObject query = new BasicDBObject("_id", record.get("_id"));
            collection.remove(query);
        }

        @Override
        public int hashCode() {
            int hash = 17;
            hash *= "Edge".hashCode();
            hash += 31;
            hash *= getId().hashCode();
            hash += 43;
            return hash;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();

            builder.append("Edge(");
            builder.append(getId());
            builder.append(" : ");
            builder.append(record.get("_out"));
            builder.append(" -> ");
            builder.append(record.get("_in"));
            builder.append("){");

            Object properties = record.get("_properties");
            if (properties instanceof DBObject) {
                for (Map.Entry<String, Object> entry : (Set<Map.Entry<String, Object>>) ((DBObject) properties).toMap().entrySet()) {
                    builder.append(entry.getKey());
                    builder.append("=");
                    builder.append(entry.getValue());
                }
            }
            builder.append("}");

            return builder.toString();
        }
    }

    private class MGraphQuery extends DefaultGraphQuery {
        private DBObject filter;
        public MGraphQuery(DBObject filter) {
            super(MongoGraph.this);
            this.filter = filter;
        }

        //
        // Couldn't be factored out because of HasContainer being a nested class. Ouch.
        //
        protected BasicDBObjectBuilder mkMongoQuery(BasicDBObjectBuilder builder) {
            List<DBObject> accum = new ArrayList<DBObject>();
            for (HasContainer container : hasContainers) {
                String key = "_properties." + container.key;
                if (container.predicate instanceof com.tinkerpop.blueprints.Compare) {
                    switch((com.tinkerpop.blueprints.Compare) container.predicate) {
                    case EQUAL:
                        accum.add(
                            new BasicDBObject(key, container.value));
                        break;
                    case GREATER_THAN:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$gt", container.value)));
                        break;
                    case GREATER_THAN_EQUAL:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$gte", container.value)));
                        break;
                    case LESS_THAN:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$lt", container.value)));
                        break;
                    case LESS_THAN_EQUAL:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$lte", container.value)));
                        break;
                    case NOT_EQUAL:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$ne", container.value)));
                        break;
                    }
                } else if (container.predicate instanceof Compare) {
                    switch((Compare) container.predicate) {
                    case EQUAL:
                        accum.add(
                            new BasicDBObject(key, container.value));
                        break;
                    case GREATER_THAN:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$gt", container.value)));
                        break;
                    case GREATER_THAN_EQUAL:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$gte", container.value)));
                        break;
                    case LESS_THAN:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$lt", container.value)));
                        break;
                    case LESS_THAN_EQUAL:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$lte", container.value)));
                        break;
                    case NOT_EQUAL:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$ne", container.value)));
                        break;
                    }
                } else if (container.predicate instanceof Contains) {
                    switch((Contains) container.predicate) {
                    case IN:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$in", container.value)));
                        break;
                    case NOT_IN:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$nin", container.value)));
                        break;
                    }
                }
            }
            if (accum.size() == 0) {
                return builder;
            } else if (accum.size() == 1) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>)accum.get(0).toMap()).entrySet()) {
                    builder.append(e.getKey(), e.getValue());
                }
            } else {
                builder.append("$and", accum);
            }
            return builder;
        }

        @Override
        public Iterable<Edge> edges() {
            if (limit == 0) {
                return Collections.emptyList();
            } else {
                BasicDBObjectBuilder builder = BasicDBObjectBuilder.start()
                                               .push("_in")
                                               .append("$exists", 1)
                                               .pop()
                                               .push("_out")
                                               .append("$exists", 1)
                                               .pop();

                DBObject query = mkMongoQuery(builder).get();
                return MongoGraph.this.getEdgesWithLimit(query, limit);
            }
        }

        @Override
        public Iterable<Vertex> vertices() {
            if (limit == 0) {
                return Collections.emptyList();
            } else {
                BasicDBObjectBuilder builder = BasicDBObjectBuilder.start()
                                               .push("_in")
                                               .append("$exists", 0)
                                               .pop()
                                               .push("_out")
                                               .append("$exists", 0)
                                               .pop();

                DBObject query = mkMongoQuery(builder).get();
                return MongoGraph.this.getVerticesWithLimit(query, limit);
            }
        }
    }

    private class MVertexQuery extends DefaultVertexQuery {
        private DBObject filter;
        public MVertexQuery(DBObject filter, Vertex vertex) {
            super(vertex);
            this.filter = filter;
        }

        //
        // Couldn't be factored out because of HasContainer being a nested class. Ouch.
        //
        protected BasicDBObjectBuilder mkMongoQuery(BasicDBObjectBuilder builder) {
            List<DBObject> accum = new ArrayList<DBObject>();
            for (HasContainer container : hasContainers) {
                String key = "_properties." + container.key;
                if (container.predicate instanceof com.tinkerpop.blueprints.Compare) {
                    switch((com.tinkerpop.blueprints.Compare) container.predicate) {
                    case EQUAL:
                        accum.add(
                            new BasicDBObject(key, container.value));
                        break;
                    case GREATER_THAN:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$gt", container.value)));
                        break;
                    case GREATER_THAN_EQUAL:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$gte", container.value)));
                        break;
                    case LESS_THAN:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$lt", container.value)));
                        break;
                    case LESS_THAN_EQUAL:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$lte", container.value)));
                        break;
                    case NOT_EQUAL:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$ne", container.value)));
                        break;
                    }
                } else if (container.predicate instanceof Compare) {
                    switch((Compare) container.predicate) {
                    case EQUAL:
                        accum.add(
                            new BasicDBObject(key, container.value));
                        break;
                    case GREATER_THAN:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$gt", container.value)));
                        break;
                    case GREATER_THAN_EQUAL:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$gte", container.value)));
                        break;
                    case LESS_THAN:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$lt", container.value)));
                        break;
                    case LESS_THAN_EQUAL:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$lte", container.value)));
                        break;
                    case NOT_EQUAL:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$ne", container.value)));
                        break;
                    }
                } else if (container.predicate instanceof Contains) {
                    switch((Contains) container.predicate) {
                    case IN:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$in", container.value)));
                        break;
                    case NOT_IN:
                        accum.add(
                            new BasicDBObject(key,
                                              new BasicDBObject("$nin", container.value)));
                        break;
                    }
                }
            }
            if (labels != null && labels.length > 0) {
                accum.add(BasicDBObjectBuilder.start()
                  .push("_label")
                  .add("$in", labels)
                  .pop()
                  .get());
            }
            if (accum.size() == 0) {
                return builder;
            } else if (accum.size() == 1) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>)accum.get(0).toMap()).entrySet()) {
                    builder.append(e.getKey(), e.getValue());
                }
            } else {
                builder.append("$and", accum);
            }
            return builder;
        }

        @Override
        public Iterable<Edge> edges() {
            if (limit == 0) {
                return Collections.emptyList();
            } else {
                BasicDBObjectBuilder builder = BasicDBObjectBuilder.start();
                DBObject propertyQuery = mkMongoQuery(builder).get();

                DBObject edgeQuery;
                switch(this.direction) {
                    case IN: 
                        edgeQuery = new BasicDBObject("_in", filter.get("_id"));
                        break;
                    case OUT:
                        edgeQuery = new BasicDBObject("_out", filter.get("_id"));
                        break;
                    case BOTH:
                        edgeQuery = 
                          new BasicDBObject("$or", new DBObject[] {
                              new BasicDBObject("_in", filter.get("_id")),
                              new BasicDBObject("_out", filter.get("_id"))
                          });
                        break;
                    default:
                        throw new IllegalArgumentException("Don't pick NULL as a direction");
                };

                DBObject query = 
                  new BasicDBObject("$and", new DBObject[] { propertyQuery, edgeQuery });
                return MongoGraph.this.getEdgesWithLimit(query, limit);
            }
        }

        @Override
        public Iterable<Vertex> vertices() {
            return Iterables.transform(edges(), new Function<Edge, Vertex>() {
                @Override
                public Vertex apply(Edge e) {
                    Vertex v = e.getVertex(Direction.OUT);
                    if (!v.equals(vertex)) {
                        return v;
                    } else {
                        return e.getVertex(Direction.IN);
                    }
                }
            });
        }
    }


    @Override
    public String toString() {
        return "mongograph";
    }

    private final static Set<String> RESERVED_IDENTIFIERS;
    static {
        Set<String> temp = new HashSet<String>();
        temp.add("");
        temp.add("id");
        temp.add("label");
        RESERVED_IDENTIFIERS = Collections.unmodifiableSet(temp);
    }
}