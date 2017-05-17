package server.chord;

import java.io.IOException;
import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static server.Utils.addToNodeId;
import static server.Utils.between;
import static server.chord.Node.MAX_NODES;

public class FingerTable {
    public static final int FINGER_TABLE_SIZE = (int) (Math.log(MAX_NODES) / Math.log(2));
    public static final int NUM_SUCCESSORS = 5;

    private NodeInfo predecessor;
    private final NodeInfo[] fingers;
    private final NodeInfo[] successors;
    private final NodeInfo self;

    public FingerTable(NodeInfo self) {
        this.self = self;
        predecessor = self;
        fingers = new NodeInfo[FINGER_TABLE_SIZE];
        successors = new NodeInfo[NUM_SUCCESSORS];

        for (int i = 0; i < fingers.length; i++)
            fingers[i] = self;
    }

    /**
     * Check if a given key belongs to this node's successor, that is,
     * if the key is between this node and the successor
     *
     * @param key key being checked
     * @return true if the key belongs to the node's successor
     */
    public boolean keyBelongsToSuccessor(BigInteger key) {
        return between(self, fingers[0], key);
    }


    /**
     * @param index
     * @param successor
     */
    public void setFinger(int index, NodeInfo successor) {
        System.out.println("Updated fingers[" + index + "] with " + successor);
        fingers[index] = successor;
    }

    /**
     * Gets the best next node that precedes the key.
     *
     * @param key the key being searched
     * @return {NodeInfo} of the best next node.
     */
    public NodeInfo getNextBestNode(BigInteger key) {

        int keyOwner = Integer.remainderUnsigned(key.intValueExact(), MAX_NODES);
        for (int i = fingers.length - 1; i >= 0; i--) {
            if (between(self.getId(), keyOwner, fingers[i].getId()) && !fingers[i].equals(self))
                return fingers[i];
        }

        return self;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("Predecessor ID: ");
        sb.append(predecessor == null
                ? "null"
                : predecessor.getId());
        sb.append("\n\n");
        sb.append("Finger Table:\n");
        sb.append("Index\t\t\tID\n");

        for (int i = 0; i < fingers.length; i++) {
            sb.append(i);
            sb.append("\t\t\t");
            sb.append(fingers[i] == null
                    ? "null"
                    : fingers[i].getId());
            sb.append("\n");
        }

        sb.append("Successors:\n");
        sb.append("Index\t\t\tID\n");

        for (int i = 0; i < successors.length; i++) {
            sb.append(i);
            sb.append("\t\t\t");
            sb.append(successors[i] == null
                    ? "null"
                    : successors[i].getId());
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Fills the node's finger table
     *
     * @param currentNode node the finger table belongs to
     * @throws Exception
     */
    public void fill(Node currentNode) throws Exception {
        for (int i = 1; i < FINGER_TABLE_SIZE; i++) {

            /* (NodeId + 2^i) mod MAX_NODES */
            BigInteger keyToLookup = BigInteger.valueOf(addToNodeId(self.getId(), (int) Math.pow(2, i)));

            try {
                /*
                 * If the key corresponding to the ith row of the finger table stands between me and my successor,
                 * it means that fingers[i] is still my successor. If it is not, look for the corresponding node.
                 */
                if (between(self, fingers[0], keyToLookup))
                    fingers[i] = fingers[0];
                else {
                    int index = i;
                    CompletableFuture<Void> fingerLookup = currentNode.lookup(keyToLookup).thenAcceptAsync(
                            finger -> setFinger(index, finger),
                            currentNode.getThreadPool());

                    fingerLookup.get();

                    if (fingerLookup.isCancelled() || fingerLookup.isCompletedExceptionally())
                        throw new Exception("Could not find finger" + i);
                }
            } catch (IOException | InterruptedException | ExecutionException e) {
                throw new Exception("Could not find finger " + i);
            }
        }
    }

    /**
     * Check if a given node should replace any of the finger table's nodes
     *
     * @param node node being compared
     */
    public void updateFingerTable(NodeInfo node) {
        BigInteger keyEquivalent = BigInteger.valueOf(node.getId());

        for (int i = 0; i < fingers.length; i++)
            if (between(addToNodeId(self.getId(), (int) Math.pow(2, i)), fingers[i].getId(), keyEquivalent)) {

                if (i == 0)
                    setSuccessor(node, 0); //already sets finger[0] to the node
                else
                    fingers[i] = node;
            }
    }

    /**
     * Get this node's successor
     *
     * @return NodeInfo for the successor
     */
    public NodeInfo getSuccessor() {
        return (successors[0] != null ? successors[0] : fingers[0]);
    }

    public NodeInfo getNthSuccessor(int index) {
        return successors[index];
    }

    public void setSuccessor(NodeInfo successor, int index) {
        successors[index] = successor;
        fingers[index] = successor;
    }

    /**
     * Get this node's predecessor
     *
     * @return NodeInfo for the predecessor
     */
    public NodeInfo getPredecessor() {
        return predecessor;
    }

    /**
     * Set the node's predecessor without checking
     * Use only if needed (e.g. setting the predecessor to null)
     * See updatePredecessor()
     *
     * @param predecessor new predecessor
     */
    public void setPredecessor(NodeInfo predecessor) {
        this.predecessor = predecessor;
    }

    /**
     * Check if a given node should be this node's predecessor
     *
     * @param node node being compared
     */
    public boolean updatePredecessor(NodeInfo node) {
        if (node.equals(self) || node == null)
            return false;

        if (predecessor == null) {
            predecessor = node;
            return true;
        }

        BigInteger keyEquivalent = BigInteger.valueOf(node.getId());
        if (between(predecessor, self, keyEquivalent)) {
            predecessor = node;
            return true;
        }
        return false;
    }

    public void updateSuccessors(NodeInfo node) {
        if(node.equals(self))
            return;

        int lowerNodeKey = self.getId();

        /*
         * Find, if any, the first successor that should be after the node being checked
         * Shift all the nodes in the array a position forwards and
         * Insert the node in the correct position
         */
        for (int upperNodeIndex = 0; upperNodeIndex < successors.length; upperNodeIndex++) {
            NodeInfo upperNode = successors[upperNodeIndex];
            if (upperNode == null) {
                successors[upperNodeIndex] = node;
                break;
            }

            if (node.equals(upperNode))
                break;

            int nodeKey = node.getId();
            int upperNodeKey = upperNode.getId();
            if (between(lowerNodeKey, upperNodeKey, nodeKey)) {
                successors[successors.length - 1] = null;

                for (int i = successors.length - 1; i > upperNodeIndex; i--)
                    successors[i] = successors[i - 1];

                successors[upperNodeIndex] = node;
                break;
            }

            lowerNodeKey = upperNodeKey;
        }
    }
}
