package com.greenaddress.greenbits.ui;

import android.util.Log;

import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.greenaddress.greenapi.ISigningWallet;
import com.greenaddress.greenapi.PreparedTransaction;
import com.satoshilabs.trezor.Trezor;
import com.subgraph.orchid.encoders.Hex;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.crypto.ChildNumber;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

public class TrezorHWWallet implements ISigningWallet {

    private static final ListeningExecutorService es = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1));
    private final Trezor trezor;
    private List<Integer> addrn = new LinkedList<>();

    public TrezorHWWallet(Trezor t) {
        trezor = t;
    }

    @Override
    public ISigningWallet deriveChildKey(ChildNumber childNumber) {
        TrezorHWWallet child = new TrezorHWWallet(trezor);
        child.addrn = new LinkedList<>(addrn);
        child.addrn.add(childNumber.getI() + (childNumber.isHardened() ? 0x80000000 : 0));
        return child;
    }

    @Override
    public ListenableFuture<byte[]> getIdentifier() {
        return Futures.transform(getPubKey(), new Function<ECKey, byte[]>() {
            @Nullable
            @Override
            public byte[] apply(@Nullable ECKey input) {
                return input.toAddress(NetworkParameters.fromID(NetworkParameters.ID_MAINNET)).getHash160();
            }
        });
    }

    @Override
    public boolean canSignHashes() {
        return false;
    }

    @Override
    public ListenableFuture<ECKey.ECDSASignature> signHash(Sha256Hash hash) {
        return Futures.immediateFuture(null);
    }

    @Override
    public ListenableFuture<ECKey.ECDSASignature> signMessage(final String message) {
        return es.submit(new Callable<ECKey.ECDSASignature>() {
            @Override
            public ECKey.ECDSASignature call() throws Exception {
                final Integer[] intArray = new Integer[addrn.size()];
                return trezor.MessageSignMessage(addrn.toArray(intArray), message);
            }
        });
    }

    @Override
    public ListenableFuture<ECKey> getPubKey() {
        return es.submit(new Callable<ECKey>() {
            @Override
            public ECKey call() throws Exception {
                final Integer[] intArray = new Integer[addrn.size()];
                final String[] xpub = trezor.MessageGetPublicKey(addrn.toArray(intArray)).split("%", -1);
                final String pkHex = xpub[xpub.length-2];
                Log.d("TrezorHWWallet pkHex", pkHex);
                return ECKey.fromPublicOnly(Hex.decode(pkHex));
            }
        });
    }

    @Override
    public ListenableFuture<List<ECKey.ECDSASignature>> signTransaction(final PreparedTransaction tx, final String coinName, final byte[] gait_path) {
        return es.submit(new Callable<List<ECKey.ECDSASignature>>() {
            @Override
            public List<ECKey.ECDSASignature> call() throws Exception {
                return trezor.MessageSignTx(tx, coinName, gait_path);
            }
        });
    }
}
