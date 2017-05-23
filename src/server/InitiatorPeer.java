package server;

import common.IInitiatorPeer;
import server.chord.DistributedHashTable;
import server.utils.Utils;

import javax.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigInteger;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.security.NoSuchAlgorithmException;

import encryptUtils.KeyEncryption;

import java.security.PrivateKey;
import java.security.PublicKey;

public class InitiatorPeer extends UnicastRemoteObject implements IInitiatorPeer {
    private final DistributedHashTable dht;
	


    InitiatorPeer(DistributedHashTable dht) throws IOException, NoSuchAlgorithmException {
        super();
        this.dht = dht;

    }

    @Override
    public boolean backup(String pathName) throws IOException, ClassNotFoundException {
        byte[] file = FileManager.loadFile(pathName);

        BigInteger key;
        try {
            key = new BigInteger(Utils.hash(file));
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return false;
        }

        boolean ret = dht.put(key, file);
        System.out.println("Filename " + pathName + " stored with key " + DatatypeConverter.printHexBinary(key.toByteArray()));
        return ret;
        }




    @Override
    public boolean restore(String hexKey, String filename) throws IOException {
        BigInteger key = new BigInteger(DatatypeConverter.parseHexBinary(hexKey));
        byte[] content = dht.get(key);

        if (content != null)
            FileManager.saveFile(filename, content);

        System.out.println("File stored with key " + hexKey + " restored successfully.");
        return content != null;
    }

    @Override
    public boolean delete(String hexKey) {
        BigInteger key = new BigInteger(DatatypeConverter.parseHexBinary(hexKey));
        boolean ret = dht.remove(key);
        System.out.println("File stored with key " + hexKey + " deleted successfully.");
        return ret;
    }

    @Override
    public String state() {
        return dht.getState();
    }
}
