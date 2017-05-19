package server.chord;

import server.communication.Mailman;
import server.communication.operations.LookupOperation;
import server.utils.SynchronizedFixedLinkedList;

import java.io.IOException;
import java.math.BigInteger;
import java.util.concurrent.*;

import static server.chord.Node.MAX_NODES;
import static server.utils.Utils.addToNodeId;
import static server.utils.Utils.between;

public class FingerTable {
    public static final int FINGER_TABLE_SIZE = (int) (Math.log(MAX_NODES) / Math.log(2));
    public static final int NUM_SUCCESSORS = 5;

    private NodeInfo predecessor;
    private final NodeInfo[] fingers;
    private final SynchronizedFixedLinkedList<NodeInfo> successors;
    private final NodeInfo self;
    private final ConcurrentHashMap<BigInteger, CompletableFuture<NodeInfo>> ongoingLookups = new ConcurrentHashMap<>();
    private final ExecutorService lookupThreadPool = Executors.newFixedThreadPool(10);

    public FingerTable(NodeInfo self) {
        this.self = self;
        predecessor = self;
        fingers = new NodeInfo[FINGER_TABLE_SIZE];
        successors = new SynchronizedFixedLinkedList<>(NUM_SUCCESSORS);

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
        return fingers[0] != null && between(self, fingers[0], key);
    }


    /**
     * @param index
     * @param successor
     */
    public void setFinger(int index, NodeInfo successor) {
        fingers[index] = successor;
    }

    /**
     * Gets the next best node that precedes the key.
     *
     * @param key the key being searched
     * @return {NodeInfo} of the best next node.
     */
    public NodeInfo getNextBestNode(BigInteger key) {

        int keyOwner = Integer.remainderUnsigned(key.intValueExact(), MAX_NODES);
        for (int i = fingers.length - 1; i >= 0; i--) {
            if (fingers[i].getId() != keyOwner && between(self.getId(), keyOwner, fingers[i].getId()) && !fingers[i].equals(self))
                return fingers[i];
        }

        return successors.get(0);
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

        for (int i = 0; i < successors.size(); i++) {
            sb.append(i);
            sb.append("\t\t\t");
            sb.append(successors.get(i) == null
                    ? "null"
                    : successors.get(i).getId());
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Fills the node's finger table
     */
    public void fill() {
        if (!hasSuccessors())
            return;

        for (int i = 1; i < FINGER_TABLE_SIZE; i++) {
            getFinger(i);
        }
    }

    private boolean getFinger(int index) {
        /* (NodeId + 2^i) mod MAX_NODES */
        BigInteger keyToLookup = BigInteger.valueOf(addToNodeId(self.getId(), (int) Math.pow(2, index)));

        /* Check if the finger belongs to the successors. If it does, then we don't need to look it up. */
        for (int i = 0; i < successors.size(); i++) {
            if (between(self, successors.get(i), keyToLookup)) {
                fingers[index] = successors.get(i);
                return true;
            }
        }

        try {
                /*
                 * If the key corresponding to the ith row of the finger table stands between me and my successor,
                 * it means that fingers[i] is still my successor. If it is not, look for the corresponding node.
                 */
            if (fingers[index] != null && between(self, fingers[0], keyToLookup))
                fingers[index] = fingers[0];
            else {
                CompletableFuture<Void> fingerLookup = lookup(keyToLookup).thenAcceptAsync(
                        finger -> setFinger(index, finger),
                        lookupThreadPool);

                fingerLookup.get(400, TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            onLookupFailed(keyToLookup);
            fingers[index] = self;
            System.err.println("Could not find finger " + index + ".");
            return false;
        }

        return true;
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

                if (i == 0) {
                    setSuccessor(node, 0);
                    setFinger(0, node);
                } else
                    fingers[i] = node;
            }
    }

    /**
     * Get this node's successor
     *
     * @return NodeInfo for the successor
     */
    public NodeInfo getSuccessor() {
        return (!successors.isEmpty() ? successors.get(0) : fingers[0]);
    }

    public NodeInfo getNthSuccessor(int index) throws IndexOutOfBoundsException {
        return successors.get(index);
    }

    public void setSuccessor(NodeInfo successor, int index) {
        successors.set(index, successor);
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
     * Check if a given node should be this node's predecessor and sets it if it should.
     *
     * @param node Node being checked
     */
    public boolean updatePredecessor(NodeInfo node) {
        if (node == null || node.equals(self))
            return false;

        if (predecessor == null) {
            predecessor = node;
            return true;
        }

        if (between(predecessor, self, node.getId())) {
            predecessor = node;
            return true;
        }

        return false;
    }

    public void updateSuccessors(NodeInfo node) {
        if (node.equals(self))
            return;

        NodeInfo lowerNode = self;

        /*
         * Find, if any, the first successor that should be after the node being checked
         * Shift all the nodes in the array a position forwards and
         * Insert the node in the correct position
         */
        synchronized (successors) {
            for (int i = 0; i < successors.size(); i++) {
                NodeInfo successor = successors.get(i);
                if (node.equals(successor))
                    return;

                int nodeKey = node.getId();

                if (between(lowerNode, successor, nodeKey)) {
                    successors.add(i, node);
                    return;
                }

                lowerNode = successor;
            }
        }

        if (successors.size() < NUM_SUCCESSORS)
            successors.add(node);
    }

    public void nformAboutExistence(NodeInfo node) {
        updateSuccessors(node);
        updateFingerTable(node);
        updatePredecessor(node);
    }

    public boolean hasSuccessors() {
        return !successors.isEmpty();
    }

    public void informAboutFailure(NodeInfo node) {
        informSuccessorsOfFailure(node);
        informFingersOfFailure(node);
        informPredecessorOfFailure(node);
    }

    private void informFingersOfFailure(NodeInfo node) {
        for (int i = fingers.length - 1; i >= 0; i--)
            if (fingers[i].equals(node))
                getFinger(i);
    }


    private void informPredecessorOfFailure(NodeInfo node) {
        if (predecessor.equals(node))
            predecessor = null;
    }

    private void informSuccessorsOfFailure(NodeInfo node) {
        if (successors.remove(node)) {
            if (successors.isEmpty())
                lookup(BigInteger.valueOf(addToNodeId(self.getId(), 1)));
            else
                lookup(BigInteger.valueOf(addToNodeId(successors.last().getId(), 1)));
        }
    }

    /**
     * Search for a key, starting from a specific node
     *
     * @param key
     * @param nodeToLookup
     * @return
     * @throws IOException
     */
    CompletableFuture<NodeInfo> lookupFrom(BigInteger key, NodeInfo nodeToLookup) {
        /* Check if requested lookup is already being done */
        CompletableFuture<NodeInfo> lookupResult = ongoingLookups.get(key);

        if (lookupResult != null)
            return lookupResult;

        lookupResult = new CompletableFuture<>();
        ongoingLookups.put(key, lookupResult);

        try {
            Mailman.sendOperation(nodeToLookup, new LookupOperation(self, key));
        } catch (IOException e) {
            lookupResult.completeExceptionally(e);
        }

        return lookupResult;
    }

    public CompletableFuture<NodeInfo> lookup(BigInteger key) {
        if (keyBelongsToSuccessor(key))
            return lookupFrom(key, getSuccessor());
        else
            return lookupFrom(key, getNextBestNode(key));
    }

    /**
     * Called by a LookupResultOperation, signals the lookup for the key is finished
     *
     * @param key        key that was searched
     * @param targetNode node responsible for the key
     */
    public void onLookupFinished(BigInteger key, NodeInfo targetNode) {
        CompletableFuture<NodeInfo> result = ongoingLookups.remove(key);
        nformAboutExistence(targetNode);
        result.complete(targetNode);
    }

    public void onLookupFailed(BigInteger key) {
        CompletableFuture<NodeInfo> result = ongoingLookups.remove(key);
        result.completeExceptionally(new KeyNotFoundException());
    }
}
