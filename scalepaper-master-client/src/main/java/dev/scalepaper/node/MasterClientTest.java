package dev.scalepaper.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MasterClientTest {

    private static final Logger log = LoggerFactory.getLogger(MasterClientTest.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting test node...");

        MasterClient client = new MasterClient(
            "127.0.0.1", 25575,  // Master address
            "127.0.0.1", 25576   // This node's address
        );

        client.setOnRegistered(nodeId -> {
            log.info("Successfully registered! Node ID: {}", nodeId);

            // Start heartbeat
            client.startHeartbeat(5, 19.8);

            // Request a few chunks
            client.requestChunk("world", 0, 0, granted ->
                log.info("Chunk 0,0 granted: {}", granted));
            client.requestChunk("world", 1, 0, granted ->
                log.info("Chunk 1,0 granted: {}", granted));
            client.requestChunk("world", 0, 1, granted ->
                log.info("Chunk 0,1 granted: {}", granted));
        });

        client.connect();

        // Run for 15 seconds then shut down
        Thread.sleep(15000);
        client.releaseChunk("world", 0, 0);
        client.shutdown();
        log.info("Test complete.");
    }
}
