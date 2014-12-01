package util;

import com.fasterxml.jackson.databind.JsonNode;
import model.HeartbeatRequest;
import model.RegisterRequest;
import model.RegisterResponse;
import play.*;
import play.libs.F;
import play.libs.Json;
import play.libs.ws.WS;
import play.libs.ws.WSResponse;

import java.util.Timer;
import java.util.TimerTask;

public class Global extends GlobalSettings {

    private final static long REQUEST_WAITING_TIME = 10000;
    private static String ADDRESS_DIRECTORY_NODE;
    private static Integer PORT_DIRECTORY_NODE;
    private final static int HEARTBEAT_PERIOD = 5000;

    private static KeyManager keyManager;
    private static Config config;

    @Override
    public void onStart(Application app) {
        Logger.info("Application is starting...");

        // init configuration
        config = new Config();
        Logger.info("Configuration loaded!");

        ADDRESS_DIRECTORY_NODE = config.getDirectoryConfig().getIp();
        PORT_DIRECTORY_NODE = config.getDirectoryConfig().getPort();

        // init key manager
        keyManager = new KeyManager();
        Logger.info("Keys generated!");

        Logger.info("Register node...");
        // register node
        RegisterResponse registerResponse = registerNode();
        String secret = registerResponse.getSecret();

        Logger.info("Node registered!");
        Logger.info("Received secret: " + secret);

        // start heartbeat
        HeartbeatRequest heartbeatRequest = new HeartbeatRequest();
        heartbeatRequest.setSecret(secret);
        startHeartbeat(heartbeatRequest);

        Logger.info("Application has started");
    }

    private RegisterResponse registerNode() {
        String pubKey = keyManager.getPublicKey();
        RegisterRequest registerRequest = buildRegisterRequest(pubKey);

        JsonNode json = Json.toJson(registerRequest);
        F.Promise<String> promise = WS.url(UrlUtil.createHttpUrl(ADDRESS_DIRECTORY_NODE, PORT_DIRECTORY_NODE, "register"))
                .setContentType("application/json")
                .post(json)
                .map(new F.Function<WSResponse, String>() {
                    @Override
                    public String apply(WSResponse wsResponse) throws Throwable {
                        return wsResponse.getBody();
                    }
                });

        String result = promise.get(REQUEST_WAITING_TIME);
        RegisterResponse registerResponse = Json.fromJson(Json.parse(result), RegisterResponse.class);
        Logger.debug("Registration result: " + registerResponse);

        return registerResponse;
    }

    private void startHeartbeat(final HeartbeatRequest heartbeatRequest) {
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    F.Promise<String> promise = WS.url(UrlUtil.createHttpUrl(ADDRESS_DIRECTORY_NODE, PORT_DIRECTORY_NODE, "heartbeat"))
                            .setContentType("application/json")
                            .put(Json.toJson(heartbeatRequest))
                            .map(new F.Function<WSResponse, String>() {
                                @Override
                                public String apply(WSResponse wsResponse) throws Throwable {
                                    return wsResponse.getBody();
                                }
                            });

                    String response = promise.get(REQUEST_WAITING_TIME);

                    // TODO ghetto - proper error handling needed - reregister?
                    if ("{\"error\":\"invalid request\"}".equals(response)) {
                        Logger.debug("Got a response that the heartbeat is invalid");
                    }

                } catch (Exception e) {
                    Logger.debug("Something failed with the heartbeat, retrying...");
                }
            }
        }, HEARTBEAT_PERIOD, HEARTBEAT_PERIOD);
    }

    @Override
    public void onStop(Application app) {
        Logger.info("Application shutdown");
    }

    private RegisterRequest buildRegisterRequest(String pubKey) {
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setPublicKey(pubKey);
        registerRequest.setIp(config.getIp());
        registerRequest.setPort(config.getPort());
        return registerRequest;
    }

    public static KeyManager getKeyManager() {
        return keyManager;
    }

    public static Config getConfig() {
        return config;
    }

}
