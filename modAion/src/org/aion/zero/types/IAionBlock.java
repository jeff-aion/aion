package org.aion.zero.types;

import java.math.BigInteger;
import java.util.List;
import org.aion.types.AionAddress;
import org.aion.interfaces.block.Block;

/** aion block interface. */
public interface IAionBlock extends Block<AionTransaction, A0BlockHeader> {

    AionAddress getCoinbase();

    long getTimestamp();

    byte[] getDifficulty();

    byte[] getStateRoot();

    void setStateRoot(byte[] stateRoot);

    BigInteger getCumulativeDifficulty();

    byte[] getReceiptsRoot();

    byte[] getTxTrieRoot();

    byte[] getLogBloom();

    void setNonce(byte[] nonce);

    List<AionTransaction> getTransactionsList();

    long getNrgConsumed();

    long getNrgLimit();
}
