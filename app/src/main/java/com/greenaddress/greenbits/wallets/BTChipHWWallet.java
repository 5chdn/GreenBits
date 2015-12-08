package com.greenaddress.greenbits.wallets;

import android.support.annotation.NonNull;
import android.util.Log;

import com.btchip.BTChipDongle;
import com.btchip.BTChipException;
import com.btchip.BitcoinTransaction;
import com.btchip.comm.BTChipTransport;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.greenaddress.greenapi.ISigningWallet;
import com.greenaddress.greenapi.Network;
import com.greenaddress.greenapi.Output;
import com.greenaddress.greenapi.PreparedTransaction;
import com.greenaddress.greenbits.ui.RequestLoginActivity;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.UnsafeByteArrayOutputStream;
import org.bitcoinj.core.VarInt;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.spongycastle.util.encoders.Hex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;


public class BTChipHWWallet implements ISigningWallet {
    private static final ListeningExecutorService es = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1));
    private final BTChipDongle dongle;
    private RequestLoginActivity loginActivity;
    private final String pin;
    @android.support.annotation.Nullable
    private DeterministicKey cachedPubkey;
    private List<Integer> addrn = new LinkedList<>();

    @NonNull private static final String TAG = BTChipHWWallet.class.getSimpleName();

    private BTChipHWWallet(final BTChipDongle dongle, final RequestLoginActivity loginActivity, final String pin, final List<Integer> addrn) {
        this.dongle = dongle;
        this.loginActivity = loginActivity;
        this.pin = pin;
        this.addrn = addrn;
    }

    public BTChipHWWallet(final BTChipDongle dongle) {
        this.dongle = dongle;
        this.pin = "0000";
    }    

    public BTChipHWWallet(final BTChipTransport transport, final RequestLoginActivity loginActivity, final String pin, @NonNull final SettableFuture<Integer> remainingAttemptsFuture) {
        this.dongle = new BTChipDongle(transport);
        this.loginActivity = loginActivity;
        this.pin = pin;
        es.submit(new Callable<Object>() {
            @android.support.annotation.Nullable
            @Override
            public Object call() {
                try {
                    dongle.verifyPin(BTChipHWWallet.this.pin.getBytes());
                    remainingAttemptsFuture.set(-1);  // -1 means success
                } catch (@NonNull final BTChipException e) {
                    if (e.toString().contains("63c")) {
                        remainingAttemptsFuture.set(
                                Integer.valueOf(String.valueOf(e.toString().charAt(e.toString().indexOf("63c") + 3))));
                    } else if (e.toString().contains("6985")) {
                        // dongle is not set up
                        remainingAttemptsFuture.set(0);
                    } else {
                        remainingAttemptsFuture.setException(e);
                    }
                    e.printStackTrace();
                }
                return null;
            }
        });
    }

    @NonNull
    private String outToPath(@NonNull final Output out) {
        if (out.subaccount != null && out.subaccount != 0) {
            return "3'/" + out.subaccount + "'/1/" + out.pointer;
        } else {
            return "1/" + out.pointer;
        }
    }

    @NonNull
    @Override
    public ListenableFuture<List<ECKey.ECDSASignature>> signTransaction(@NonNull final PreparedTransaction tx, final byte[] gait_path) {
        return es.submit(new Callable<List<ECKey.ECDSASignature>>() {
            @NonNull
            @Override
            public List<ECKey.ECDSASignature> call() throws Exception {
                final List<ECKey.ECDSASignature> sigs = new LinkedList<>();

                final BTChipDongle.BTChipInput inputs[] = new BTChipDongle.BTChipInput[tx.decoded.getInputs().size()];
                if (!dongle.hasScreenSupport()) {                
                    for (int i = 0; i < tx.decoded.getInputs().size(); ++i) {
                        final byte[] inputHash = tx.decoded.getInputs().get(i).getOutpoint().getHash().getBytes();
                        for (int j = 0; j < inputHash.length / 2; ++j) {
                            final byte temp = inputHash[j];
                            inputHash[j] = inputHash[inputHash.length - j - 1];
                            inputHash[inputHash.length - j - 1] = temp;
                        }
                        final byte[] input = Arrays.copyOf(inputHash, inputHash.length + 4);
                        long index = tx.decoded.getInputs().get(i).getOutpoint().getIndex();
                        input[input.length - 4] = (byte) (index % 256);
                        index /= 256;
                        input[input.length - 3] = (byte) (index % 256);
                        index /= 256;
                        input[input.length - 2] = (byte) (index % 256);
                        index /= 256;
                        input[input.length - 1] = (byte) (index % 256);
                        inputs[i] = dongle.createInput(input, false);
                    }
                }
                else {
                 for (int i = 0; i < tx.decoded.getInputs().size(); ++i) {
                   final TransactionOutPoint outpoint = tx.decoded.getInputs().get(i).getOutpoint();
                   final long index = outpoint.getIndex();
                   final ByteArrayInputStream in = new ByteArrayInputStream(tx.prevoutRawTxs.get(outpoint.getHash().toString()).unsafeBitcoinSerialize());
                   final BitcoinTransaction encodedTx = new BitcoinTransaction(in);
                   inputs[i] = dongle.getTrustedInput(encodedTx, index);
                 }                    
                }
                for (int i = 0; i < tx.decoded.getInputs().size(); ++i) {
                    dongle.startUntrustedTransction(i == 0, i, inputs, Hex.decode(tx.prev_outputs.get(i).script));
                    final ByteArrayOutputStream stream = new UnsafeByteArrayOutputStream(tx.decoded.getMessageSize() < 32 ? 32 : tx.decoded.getMessageSize() + 32);
                    stream.write(new VarInt(tx.decoded.getOutputs().size()).encode());
                    for (final TransactionOutput out : tx.decoded.getOutputs())
                        out.bitcoinSerialize(stream);
                    dongle.finalizeInputFull(stream.toByteArray());
                    sigs.add(ECKey.ECDSASignature.decodeFromDER(
                            dongle.untrustedHashSign(outToPath(tx.prev_outputs.get(i)),
                                    "0", tx.decoded.getLockTime(), (byte) 1 /* = SIGHASH_ALL */)));
                }

                return sigs;
            }
        });
    }

    @Override
    public boolean requiresPrevoutRawTxs() {
        return true;
    }


    @NonNull
    @Override
    public ListenableFuture<DeterministicKey> getPubKey() {
        return es.submit(new Callable<DeterministicKey>() {
            @android.support.annotation.Nullable
            @Override
            public DeterministicKey call() throws Exception {
                return internalGetPubKey();
            }
        });
    }

    @android.support.annotation.Nullable
    private DeterministicKey internalGetPubKey() throws BTChipException {
        if (cachedPubkey != null) {
                return cachedPubkey;
        }
        final BTChipDongle.BTChipPublicKey pubKey = dongle.getWalletPublicKey(getPath());
        final ECKey uncompressed = ECKey.fromPublicOnly(pubKey.getPublicKey());
        final DeterministicKey retVal = new DeterministicKey(
                new ImmutableList.Builder<ChildNumber>().build(),
                pubKey.getChainCode(),
                uncompressed.getPubKeyPoint(),
                null, null
        );
        cachedPubkey = retVal;
        return retVal;
    }

    @Override
    public boolean canSignHashes() {
        return false;
    }

    @NonNull
    @Override
    public ListenableFuture<ECKey.ECDSASignature> signMessage(@NonNull final String message) {
        return es.submit(new Callable<ECKey.ECDSASignature>() {
            @Override
            public ECKey.ECDSASignature call() throws Exception {
                dongle.signMessagePrepare(getPath(), message.getBytes());
                final BTChipDongle.BTChipSignature sig = dongle.signMessageSign(new byte[]{0});
                return ECKey.ECDSASignature.decodeFromDER(sig.getSignature());
            }
        });
    }

    @Override
    public ListenableFuture<byte[]> signSchnorrHash(Sha256Hash hash) {
        return Futures.immediateFuture(new byte[0]);
    }

    @android.support.annotation.Nullable
    @Override
    public ListenableFuture<ECKey.ECDSASignature> signHash(final Sha256Hash hash) {
        return null;
    }

    @NonNull
    @Override
    public ListenableFuture<byte[]> getIdentifier() {
        return Futures.transform(getPubKey(), new Function<ECKey, byte[]>() {
            @Nullable
            @Override
            public byte[] apply(final @Nullable ECKey input) {
                return input.toAddress(Network.NETWORK).getHash160();
            }
        });
    }

    private String getPath() {
        final List<String> pathStr = new LinkedList<>();
        for (final Integer i : addrn) {
            String s = String.valueOf(i & ~0x80000000);
            if ((i & 0x80000000) != 0) {
                s = s + "'";
            }
            pathStr.add(s);
        }
        return Joiner.on("/").join(pathStr);
    }

    @NonNull
    @Override
    public ISigningWallet deriveChildKey(@NonNull final ChildNumber childNumber) {
        final LinkedList<Integer> addrn_child = new LinkedList<>(addrn);
        addrn_child.add(childNumber.getI());
        return new BTChipHWWallet(dongle, loginActivity, pin, addrn_child);
    }

    public BTChipDongle getDongle() {
        return dongle;
    }

    public boolean checkConnected() {
        try {
                Log.d(TAG, "Connection check");
                dongle.getFirmwareVersion();
                Log.d(TAG, "Connection ok");
                return true;
        }
        catch(@NonNull final Exception e) {
                Log.d(TAG, "Connection not connected");
                try {
                        dongle.getTransport().close();
                        Log.d(TAG, "Connection closed");
                }
                catch(@NonNull final Exception e1) {
                }
                return false;
        }
    }

    public void setTransport(final BTChipTransport transport) {
        dongle.setTransport(transport);
        try {
                dongle.verifyPin(BTChipHWWallet.this.pin.getBytes());
        }
        catch(@NonNull final Exception e) {
        }
    }    
}
