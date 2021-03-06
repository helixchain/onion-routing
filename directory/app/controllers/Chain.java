package controllers;

import model.ChainNode;
import model.ChainNodesResponse;
import model.ErrorResponse;
import play.Logger;
import play.libs.Json;
import play.mvc.Result;
import util.NodeStorage;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import static play.mvc.Results.badRequest;
import static play.mvc.Results.ok;

public class Chain {

    private static int chainLength;
    private static int timeout;

    static {
        // set chain length
        String chainLength = System.getenv("CHAIN_LENGTH");
        if (chainLength == null) {
            Chain.chainLength = 3;
            Logger.info("CHAIN_NODE_TIMEOUT environment variable is not set");
        } else {
            try {
                Chain.chainLength = Integer.valueOf(chainLength);
            } catch (NumberFormatException e) {
                Chain.chainLength = 3;
                Logger.info("invalid format of CHAIN_LENGTH environment variable");
            }
        }

        // set node timeout
        String timeout = System.getenv("CHAIN_NODE_TIMEOUT");
        if (timeout == null) {
            Chain.timeout = 15;
            Logger.info("CHAIN_NODE_TIMEOUT environment variable is not set");
        } else {
            try {
                Chain.timeout = Integer.valueOf(timeout);
            } catch (NumberFormatException e) {
                Chain.timeout = 15;
                Logger.info("invalid format of CHAIN_NODE_TIMEOUT environment variable");
            }
        }
    }

    public static synchronized Result getChain() {
        ChainNodesResponse chainNodesResponse = new ChainNodesResponse();

        List<ChainNode> originalChainNodeList = NodeStorage.getChainNodeList();
        List<ChainNode> nodeList = new ArrayList<>();
        nodeList.addAll(originalChainNodeList);

        int nodeCount = 0;
        while (nodeList.size() > 0 && chainLength > nodeCount) {
            ChainNode node = nodeList.remove(0);
            if (node != null) {
                if (isNodeAlive(node)) {
                    chainNodesResponse.getChainNodes().add(node);
                    originalChainNodeList.remove(node);
                    originalChainNodeList.add(node);
                    nodeCount++;
                }
            }
        }
        if (chainLength == nodeCount)
            return ok(Json.toJson(chainNodesResponse));

        return badRequest(Json.toJson(new ErrorResponse("not enough nodes")));
    }

    public static boolean isNodeAlive(ChainNode node) {
        if (NodeStorage.getLastHeartbeatForNode(node) != null) {
            long diff = Calendar.getInstance().getTimeInMillis() - NodeStorage.getLastHeartbeatForNode(node);
            return diff / 1000 < timeout;
        } else {
            return false;
        }
    }
}
