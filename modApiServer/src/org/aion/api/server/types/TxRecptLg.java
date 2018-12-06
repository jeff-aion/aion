package org.aion.api.server.types;

import org.aion.base.type.IBlock;
import org.aion.base.type.ITransaction;
import org.aion.base.util.TypeConverter;
import org.aion.mcf.vm.types.Log;

public class TxRecptLg {

    public String address;

    public String blockHash;

    public String blockNumber;

    public String data;

    public String logIndex;

    public String[] topics;

    public String transactionHash;

    public String transactionIndex;

    // true when the log was removed, due to a chain reorganization. false if its a valid log.
    public boolean removed;

    public <TX extends ITransaction> TxRecptLg(
            Log logInfo, IBlock b, Integer txIndex, TX tx, int logIdx, boolean isMainchain) {
        this.logIndex = TypeConverter.toJsonHex(logIdx);
        this.blockNumber = b == null ? null : TypeConverter.toJsonHex(b.getNumber());
        this.blockHash = b == null ? null : TypeConverter.toJsonHex(b.getHash());
        this.transactionIndex =
                (b == null || txIndex == null) ? null : TypeConverter.toJsonHex(txIndex);
        this.transactionHash = TypeConverter.toJsonHex(tx.getHash());
        this.address = TypeConverter.toJsonHex(logInfo.getLogSourceAddress().toString());
        this.data = TypeConverter.toJsonHex(logInfo.getLogData());
        this.removed = !isMainchain;

        this.topics = new String[logInfo.getLogTopics().size()];
        for (int i = 0, m = this.topics.length; i < m; i++) {
            this.topics[i] = TypeConverter.toJsonHex(logInfo.getLogTopics().get(i));
        }
    }
}
