package com.greenaddress.greenapi;

import com.blockstream.libwally.Wally;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Utils;
import org.bitcoinj.crypto.DeterministicKey;

public abstract class HWWallet extends ISigningWallet {

    @Override
    public byte[] getIdentifier() {
        return getPubKey().toAddress(Network.NETWORK).getHash160();
    }
    @Override
    public boolean canSignHashes() { return false; }
    @Override
    public boolean requiresPrevoutRawTxs() { return true; }

    @Override
    public DeterministicKey getMyPublicKey(final int subAccount, final Integer pointer) {
        DeterministicKey k = getMyKey(subAccount).getPubKey();
        // Currently only regular transactions are supported
        k = HDKey.deriveChildKey(k, HDKey.BRANCH_REGULAR);
        return HDKey.deriveChildKey(k, pointer);
    }

    @Override
    public String[] signChallenge(final String challengeString, final String[] challengePath) {

        // Generate a path for the challenge.
        // We use "GA" + 0xB11E as the child path as this allows btchip to skip HID auth.
        final HWWallet child = (HWWallet) this.derive(0x4741b11e); // 0x4741 = Ascii G << 8 + A

        // Generate a message to sign from the challenge
        final String challenge = "greenaddress.it      login " + challengeString;
        final Sha256Hash hash = Sha256Hash.wrap(Wally.sha256d(Utils.formatMessageForSigning(challenge)));

        // Return the path to the caller for them to pass in the server RPC call
        challengePath[0] = "GA";

        // Compute and return the challenge signatures
        final ECKey.ECDSASignature signature = child.signMessage(challenge);
        int recId;
        for (recId = 0; recId < 4; ++recId) {
            final ECKey recovered = ECKey.recoverFromSignature(recId, signature, hash, true);
            if (recovered != null && recovered.equals(child.getPubKey()))
                break;
        }
        return new String[]{signature.r.toString(), signature.s.toString(), String.valueOf(recId)};
    }

    private HWWallet getMyKey(final int subAccount) {
        HWWallet parent = this;
        if (subAccount != 0)
            parent = parent.derive(ISigningWallet.HARDENED | 3)
                           .derive(ISigningWallet.HARDENED | subAccount);
        return parent;
    }

    protected abstract DeterministicKey getPubKey();
    protected abstract HWWallet derive(Integer childNumber);
    protected abstract ECKey.ECDSASignature signMessage(String message);
}
