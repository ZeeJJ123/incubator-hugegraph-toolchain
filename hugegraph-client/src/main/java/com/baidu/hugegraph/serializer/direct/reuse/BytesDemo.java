package com.baidu.hugegraph.serializer.direct.reuse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.baidu.hugegraph.driver.GraphManager;
import com.baidu.hugegraph.driver.HugeClient;
import com.baidu.hugegraph.driver.SchemaManager;
import com.baidu.hugegraph.serializer.direct.HBaseSerializer;
import com.baidu.hugegraph.serializer.direct.RocksDBSerializer;
import com.baidu.hugegraph.structure.graph.Edge;
import com.baidu.hugegraph.structure.graph.Vertex;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;

/**
 * @author jin
 * This class is a demo for rocksdb put(rowkey, values) which use Client-Side's graph struct
 * And we don't need to construct the graph element, just use it and transfer them to bytes array
 * instead of json format
 */
public class BytesDemo {

    static HugeClient client;
    boolean bypassServer = true;
    RocksDBSerializer ser;
    HBaseSerializer HBaseSer;

    public static void main(String[] args) {
        BytesDemo ins = new BytesDemo();
        ins.initGraph();
    }

    void initGraph() {
        int edgeLogicPartitions = 16;
        int vertexLogicPartitions = 8;
        // If connect failed will throw an exception.
        client = HugeClient.builder("http://localhost:8081", "hugegraph").build();

        SchemaManager schema = client.schema();


        schema.propertyKey("name").asText().ifNotExist().create();
        schema.propertyKey("age").asInt().ifNotExist().create();
        schema.propertyKey("lang").asText().ifNotExist().create();
        schema.propertyKey("date").asText().ifNotExist().create();
        schema.propertyKey("price").asText().ifNotExist().create();

        schema.vertexLabel("person")
                .properties("name", "age")
                .useCustomizeStringId()
                .enableLabelIndex(false)
                .ifNotExist()
                .create();

        schema.vertexLabel("personB")
                .properties("price")
                .nullableKeys("price")
                .useCustomizeNumberId()
                .enableLabelIndex(false)
                .ifNotExist()
                .create();

        schema.vertexLabel("software")
                .properties("name", "lang", "price")
                .useCustomizeStringId()
                .enableLabelIndex(false)
                .ifNotExist()
                .create();

        schema.edgeLabel("knows")
                .link("person", "person")
                .properties("date")
                .enableLabelIndex(false)
                .ifNotExist()
                .create();

        schema.edgeLabel("created")
                .link("person", "software")
                .properties("date")
                .enableLabelIndex(false)
                .ifNotExist()
                .create();

        HBaseSer = new HBaseSerializer(client, vertexLogicPartitions, edgeLogicPartitions);
        writeGraphElements();

        client.close();
    }

    private void writeGraphElements() {
        GraphManager graph = client.graph();
        // construct some vertexes & edges
        Vertex peter = new Vertex("person");
        peter.property("name", "peter");
        peter.property("age", 35);
        peter.id("peter");

        Vertex lop = new Vertex("software");
        lop.property("name", "lop");
        lop.property("lang", "java");
        lop.property("price", "328");
        lop.id("lop");

        Vertex vadasB = new Vertex("personB");
        vadasB.property("price", "120");
        vadasB.id(12345);

        Edge peterCreateLop = new Edge("created").source(peter).target(lop).property("date", "2017-03-24");

        List<Vertex> vertices = new ArrayList<Vertex>(){{
            add(peter);add(lop);add(vadasB);
        }};


        List<Edge> edges = new ArrayList<Edge>(){{
            add(peterCreateLop);
        }};

        // Old way: encode to json then send to server
        if (bypassServer) {
            writeDirectly(vertices, edges);
        } else {
            writeByServer(graph, vertices, edges);
        }
    }

    /* we transfer the vertex & edge into bytes array
     * TODO: use a batch and send them together
     * */
    void writeDirectly(List<Vertex> vertices, List<Edge> edges) {
        for (Vertex vertex : vertices) {
            byte[] rowkey = HBaseSer.getKeyBytes(vertex);
            byte[] values = HBaseSer.getValueBytes(vertex);
            sendRpcToHBase("vertex", rowkey, values);
        }

        for (Edge edge: edges) {
            byte[] rowkey = HBaseSer.getKeyBytes(edge);
            byte[] values = HBaseSer.getValueBytes(edge);
            sendRpcToHBase("edge", rowkey, values);
        }
    }

    boolean sendRpcToRocksDB(byte[] rowkey, byte[] values) {
        // here we call the rpc
        boolean flag = false;
        //flag = put(rowkey, values);
        return flag;
    }

    void writeByServer(GraphManager graph, List<Vertex> vertices, List<Edge> edges) {
        vertices = graph.addVertices(vertices);
        vertices.forEach(System.out::println);

        edges = graph.addEdges(edges, false);
        edges.forEach(System.out::println);
    }

    boolean sendRpcToHBase(String type, byte[] rowkey, byte[] values) {
        boolean flag = false;
        try {
            flag = put(type, rowkey, values);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return flag;
    }


    boolean put (String type, byte[] rowkey, byte[] values) throws IOException {
        Configuration config = HBaseConfiguration.create();
        // Our json records sometimes are very big, we have
        // disable the maxsize check on the keyvalue.
        config.set("hbase.zookeeper.quorum", "localhost");
        config.set("hbase.zookeeper.property.clientPort", "2181");


        Connection conn = ConnectionFactory.createConnection(config);
        Table htable = null ;
        if (type.equals("vertex")) {
            htable = conn.getTable(TableName.valueOf("hugegraph12p:g_v"));
        } else if (type.equals("edge")) {
            htable = conn.getTable(TableName.valueOf("hugegraph12p:g_oe"));
        }

        Put put = new Put(rowkey);
        put.addColumn(Bytes.toBytes("f"),
                Bytes.toBytes(""),
                values);
        htable.put(put);
        htable.close();


        return true;
    }

}
