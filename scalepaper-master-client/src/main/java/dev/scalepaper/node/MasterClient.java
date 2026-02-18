package dev.scalepaper.node;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class MasterClient {

    private static final Logger log = LoggerFactory.getLogger(MasterClient.class);
    private static final Gson GSON = new Gson();

    private final String masterHost;
    private final int masterPort;
    private final String nodeHost;
    private final int nodePort;

    private Channel channel;
    private UUID nodeId;
    private EventLoopGroup group;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Called when the master sends us our node ID after registration
    private Consumer<UUID> onRegistered;

    public MasterClient(String masterHost, int masterPort, String nodeHost, int nodePort) {
        this.masterHost = masterHost;
        this.masterPort = masterPort;
        this.nodeHost = nodeHost;
        this.nodePort = nodePort;
    }

    public void setOnRegistered(Consumer<UUID> onRegistered) {
        this.onRegistered = onRegistered;
    }

    public void connect() throws InterruptedException {
        group = new NioEventLoopGroup();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(
                        new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4),
                        new LengthFieldPrepender(4),
                        new StringDecoder(CharsetUtil.UTF_8),
                        new StringEncoder(CharsetUtil.UTF_8),
                        new MasterClientHandler()
                    );
                }
            });

        ChannelFuture future = bootstrap.connect(masterHost, masterPort).sync();
        channel = future.channel();
        log.info("Connected to Master at {}:{}", masterHost, masterPort);

        // Send registration packet
        sendRegister();
    }

    private void sendRegister() {
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "REGISTER");
        packet.addProperty("host", nodeHost);
        packet.addProperty("port", nodePort);
        send(packet);
        log.info("Sent REGISTER to Master");
    }

    public void startHeartbeat(int playerCount, double tps) {
        scheduler.scheduleAtFixedRate(() -> {
            if (channel != null && channel.isActive() && nodeId != null) {
                JsonObject packet = new JsonObject();
                packet.addProperty("type", "HEARTBEAT");
                packet.addProperty("playerCount", playerCount);
                packet.addProperty("tps", tps);
                send(packet);
                log.debug("Sent HEARTBEAT to Master");
            }
        }, 1, 5, TimeUnit.SECONDS);
    }

    public void requestChunk(String world, int x, int z, Consumer<Boolean> callback) {
        if (channel == null || !channel.isActive()) {
            callback.accept(false);
            return;
        }
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "CHUNK_REQUEST");
        packet.addProperty("chunk", world + ":" + x + "," + z);
        send(packet);
        // Response handled in MasterClientHandler
    }

    public void releaseChunk(String world, int x, int z) {
        if (channel == null || !channel.isActive()) return;
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "CHUNK_RELEASE");
        packet.addProperty("chunk", world + ":" + x + "," + z);
        send(packet);
    }

    private void send(JsonObject packet) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(GSON.toJson(packet));
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        if (channel != null) channel.close();
        if (group != null) group.shutdownGracefully();
        log.info("MasterClient shut down.");
    }

    public UUID getNodeId() {
        return nodeId;
    }

    public boolean isConnected() {
        return channel != null && channel.isActive();
    }

    // ── Inner handler ────────────────────────────────────────────────────────

    private class MasterClientHandler extends SimpleChannelInboundHandler<String> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String message) {
            JsonObject packet = GSON.fromJson(message, JsonObject.class);
            String type = packet.get("type").getAsString();

            switch (type) {
                case "REGISTER_ACK" -> {
                    nodeId = UUID.fromString(packet.get("nodeId").getAsString());
                    log.info("Registered with Master. Node ID: {}", nodeId);
                    if (onRegistered != null) onRegistered.accept(nodeId);
                }
                case "CHUNK_RESPONSE" -> {
                    String chunk = packet.get("chunk").getAsString();
                    boolean granted = packet.get("granted").getAsBoolean();
                    log.debug("Chunk {} ownership {}", chunk, granted ? "GRANTED" : "DENIED");
                }
                default -> log.warn("Unknown packet from Master: {}", type);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.warn("Lost connection to Master server. Will need to reconnect.");
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Error in Master connection", cause);
            ctx.close();
        }
    }
}
