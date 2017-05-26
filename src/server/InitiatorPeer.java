package server;

import common.IInitiatorPeer;
import server.chord.DistributedHashTable;
import server.exceptions.DecryptionFailedException;
import server.utils.Utils;

import javax.crypto.BadPaddingException;
import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.math.BigInteger;
import java.rmi.server.UnicastRemoteObject;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class InitiatorPeer extends UnicastRemoteObject implements IInitiatorPeer {
    private final DistributedHashTable dht;
    private final FileManager fileManager;

    InitiatorPeer(DistributedHashTable dht) throws IOException, NoSuchAlgorithmException {
        super();
        this.dht = dht;
        fileManager = dht.getFileManager();
    }

    @Override
    public String backup(String pathName) throws IOException {
        byte[] file;
        try {
            file = fileManager.loadFileFromDisk(pathName);
        } catch (IOException e) {
            return "Could not open file. Aborting backup...";
        } catch (Exception e) {
            return "Could not encrypt file. Aborting backup...";
        }

        BigInteger key;
        try {
            key = new BigInteger(Utils.hash(file));
        } catch (NoSuchAlgorithmException e) {
            return "Could not create key for file backup. Aborting...";
        }

        try {
            if (dht.insert(key, file))
                return "File " + pathName + " stored with key " + DatatypeConverter.printHexBinary(key.toByteArray());
            else
                return "File " + pathName + " could not be inserted in the system.";

        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            return "Backup of file " + pathName + " timed out.";
        }
    }


    @Override
    public boolean restore(String hexKey, String filename) throws IOException {
        BigInteger key = new BigInteger(DatatypeConverter.parseHexBinary(hexKey));
        byte[] content = dht.get(key);

        if (content != null) {
            try {
                fileManager.saveRestoredFile(filename, content);
            } catch (DecryptionFailedException | BadPaddingException e) {
                System.err.println("Attempted decryption with wrong key. Restore failed...");
                return false;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        } else {
            System.err.println("File stored with key " + hexKey + " not found.");
            return false;
        }

        System.out.println("File stored with key " + hexKey + " restored successfully.");
        return true;
    }

    @Override
    public boolean delete(String hexKey) {
        BigInteger key = new BigInteger(DatatypeConverter.parseHexBinary(hexKey));
        boolean ret = dht.delete(key);
        System.out.println("File stored with key " + hexKey + " deleted successfully.");
        return ret;
    }

    @Override
    public String state() {
        return dht.getState();
    }
}
