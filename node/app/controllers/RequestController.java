package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import handlers.TargetServiceHandler;
import model.EncryptedNodeRequest;
import model.NodeRequest;
import model.TargetServiceRequest;
import play.Logger;
import play.libs.F;
import play.libs.Json;
import play.libs.ws.WS;
import play.libs.ws.WSResponse;
import play.mvc.Controller;
import play.mvc.Result;
import util.EncodingUtil;
import util.EncryptionUtil;
import util.Global;
import util.UrlUtil;

import javax.crypto.IllegalBlockSizeException;
import java.security.InvalidKeyException;
import java.security.Key;

public class RequestController extends Controller {

    private static long REQUEST_WAITING_TIME = 1000;

    // curl --header "Content-type: application/json" --request POST --data-binary @data.json http://localhost:9000/request
    public static Result requestMessage() {
        Logger.info("Process a new request");
        JsonNode json = request().body().asJson();
        if (json == null) {
            return badRequest("Expecting Json data");
        } else {
            /*
             * Decrypt request
             */
            EncryptedNodeRequest encryptedNodeRequest = Json.fromJson(json, EncryptedNodeRequest.class);
            String encryptedPayload = EncodingUtil.decodeMessage(encryptedNodeRequest.getPayload());
            String decryptedPayload;
            try {
                decryptedPayload = decryptPayload(encryptedPayload);
            } catch (IllegalBlockSizeException | InvalidKeyException e) {
                Logger.debug("Could not decrypt message");
                return badRequest("Could not decrypt message");
            }

            /*
             * Process request
             */
            NodeRequest nodeRequest = Json.fromJson(Json.parse(decryptedPayload), NodeRequest.class);
            Logger.info(nodeRequest.toString());

            if (!isExitNode(nodeRequest)) {
                return processNextNode(nodeRequest);
            } else {
                try {
                    return processServiceRequest(nodeRequest);
                } catch (IllegalBlockSizeException | InvalidKeyException e) {
                    Logger.debug("Could not encrypt message with originator's key");
                    return badRequest("Could not encrypt message with originator's key");
                }
            }
        }
    }

    private static String decryptPayload(String payload) throws IllegalBlockSizeException, InvalidKeyException {
        Key key = EncryptionUtil.stringToKey(Global.getKeyManager().getPrivateKey());
        payload = EncryptionUtil.decryptMessage(payload, key);
        return payload;
    }

    private static Result processNextNode(NodeRequest nodeRequest) {
        Logger.info("Request next node");
        EncryptedNodeRequest encryptedNodeRequest = new EncryptedNodeRequest();
        encryptedNodeRequest.setPayload(nodeRequest.getPayload());

        String nextNodeUrl = UrlUtil.createHttpUrl(
                nodeRequest.getTarget().getIp(),
                nodeRequest.getTarget().getPort(),
                "request");

        F.Promise<String> promise = WS.url(nextNodeUrl)
                .setContentType("application/json")
                .post(Json.toJson(encryptedNodeRequest))
                .map(new F.Function<WSResponse, String>() {
                    @Override
                    public String apply(WSResponse wsResponse) throws Throwable {
                        return wsResponse.getBody();
                    }
                });

        String result = promise.get(REQUEST_WAITING_TIME);
        Logger.info("Got message: " + result);
        return ok(result);
    }

    private static Result processServiceRequest(NodeRequest nodeRequest) throws IllegalBlockSizeException, InvalidKeyException {
        Logger.info("Request the target service");
        TargetServiceRequest targetServiceRequest = nodeRequest.getTargetServiceRequest();
        TargetServiceHandler targetServiceHandler = new TargetServiceHandler(targetServiceRequest);
        String response = targetServiceHandler.callService();

        String originatorPubKey = targetServiceRequest.getOriginatorPubKey();
        Key key = EncryptionUtil.stringToKey(originatorPubKey);
        String encryptedResponse = EncryptionUtil.encryptMessage(response, key);

        return ok(encryptedResponse);
    }

    private static boolean isExitNode(NodeRequest nodeRequest) {
        return nodeRequest.getTargetServiceRequest() != null;
    }

}
