package org.aion.precompiled.contracts.TRS;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.aion.base.db.IRepositoryCache;
import org.aion.base.type.Address;
import org.aion.base.util.ByteUtil;
import org.aion.base.vm.IDataWord;
import org.aion.mcf.core.AccountState;
import org.aion.mcf.db.IBlockStoreBase;
import org.aion.mcf.vm.types.DataWord;
import org.aion.mcf.vm.types.DoubleDataWord;
import org.aion.precompiled.ContractExecutionResult;
import org.aion.precompiled.type.StatefulPrecompiledContract;

/**
 * The purpose of this abstract class is mostly as a place to store important constants and methods
 * that may be useful to multiple concrete subclasses.
 */
public abstract class AbstractTRS extends StatefulPrecompiledContract {
    // TODO: grab AION from CfgAion later and preferrably aion prefix too.
    static final Address AION = Address.wrap("0xa0eeaeabdbc92953b072afbd21f3e3fd8a4a4f5e6a6e22200db746ab75e9a99a");
    static final byte AION_PREFIX = (byte) 0xA0;
    static final byte TRS_PREFIX = (byte) 0xC0;
    final Address caller;

    /*
     * The database keys each have unique prefixes denoting the function of that key. Some keys have
     * mutable bytes following this prefix. In these cases oly the prefixes are provided. Otherwise
     * for unchanging keys we store them as an IDataWord object directly.
     */
    private static final IDataWord OWNER_KEY, SPECS_KEY, LIST_HEAD_KEY, FUNDS_SPECS_KEY, NULL32, INVALID;
    private static final byte BALANCE_PREFIX = (byte) 0xB0;
    private static final byte LIST_PREV_PREFIX = (byte) 0x60;
    private static final byte FUNDS_PREFIX = (byte) 0x90;

    private static final int DOUBLE_WORD_SIZE = DoubleDataWord.BYTES;
    private static final int SINGLE_WORD_SIZE = DataWord.BYTES;
    private static final int MAX_DEPOSIT_ROWS = 16;
    private static final byte NULL_BIT = (byte) 0x80;
    private static final byte VALID_BIT = (byte) 0x40;

    static {
        byte[] singleKey = new byte[SINGLE_WORD_SIZE];
        singleKey[0] = (byte) 0xF0;
        OWNER_KEY = toIDataWord(singleKey);

        singleKey[0] = (byte) 0xE0;
        SPECS_KEY = toIDataWord(singleKey);

        singleKey[0] = (byte) 0x91;
        FUNDS_SPECS_KEY = toIDataWord(singleKey);

        singleKey[0] = (byte) 0x70;
        LIST_HEAD_KEY = toIDataWord(singleKey);

        byte[] value = new byte[DOUBLE_WORD_SIZE];
        value[0] = NULL_BIT;
        NULL32 = toIDataWord(value);

        value[0] = (byte) 0x0;
        INVALID = toIDataWord(value);
    }

    // Constructor.
    AbstractTRS(IRepositoryCache<AccountState, IDataWord, IBlockStoreBase<?, ?>> track, Address caller) {
        super(track);
        if (caller == null) { throw new NullPointerException("Construct TRS with null caller."); }
        this.caller = caller;
    }

    // The execute method for subclasses to implement.
    abstract public ContractExecutionResult execute(byte[] input, long nrgLimit);


    // <-------------------------------------HELPER METHODS---------------------------------------->

    private static final int TEST_OFFSET = 9;
    private static final int DIR_DEPO_OFFSET = 10;
    private static final int PRECISION_OFFSET = 11;
    private static final int PERIODS_OFFSET = 12;
    private static final int LOCK_OFFSET = 14;
    private static final int LIVE_OFFSET = 15;

    /**
     * Returns the contract specifications for the TRS contract whose address is contract if this is
     * a valid contract address.
     *
     * Returns null if contract is not a valid TRS contract address and thus there are no specs.
     *
     * @param contract The TRS contract address to query.
     * @return the contract specifications or null if not a TRS contract.
     */
    public byte[] getContractSpecs(Address contract) {
        if (contract.toBytes()[0] != TRS_PREFIX) { return null; }
        IDataWord spec = track.getStorageValue(contract, SPECS_KEY);
        return (spec == null) ? null : Arrays.copyOf(spec.getData(), spec.getData().length);
    }

    /**
     * Sets the contract specifications for the TRS contract whose address is contract to record the
     * parameters listed in this method's signature.
     *
     * If percent requires more than 9 bytes to represent it then it will be truncated down to 9
     * bytes.
     *
     * This method only succeeds if the specifications entry for contract is empty. Thus this method
     * can be called successfully at most once per contract.
     *
     * This method does not flush.
     *
     * @param contract The TRS contract to update.
     * @param isTest true if this is a test contract.
     * @param isDirectDeposit true if users can deposit to the contract directly.
     * @param periods the number of withdrawal periods for this contract.
     * @param percent the percentage of the total balance withdrawable in the special one-off event.
     * @param precision the number of decimal places to left-shift percent.
     */
    void setContractSpecs(Address contract, boolean isTest, boolean isDirectDeposit, int periods,
        BigInteger percent, int precision) {

        if (track.getStorageValue(contract, SPECS_KEY) != null) { return; }
        byte[] specs = new byte[SINGLE_WORD_SIZE];
        byte[] percentBytes = percent.toByteArray();
        int len = (percentBytes.length > TEST_OFFSET) ? TEST_OFFSET : percentBytes.length;

        System.arraycopy(percentBytes, percentBytes.length - len, specs, TEST_OFFSET - len, len);
        specs[TEST_OFFSET] = (isTest) ? (byte) 0x1 : (byte) 0x0;
        specs[DIR_DEPO_OFFSET] = (isDirectDeposit) ? (byte) 0x1 : (byte) 0x0;
        specs[PRECISION_OFFSET] = (byte) (precision & 0xFF);
        specs[PERIODS_OFFSET] = (byte) ((periods >> Byte.SIZE) & 0xFF);
        specs[PERIODS_OFFSET + 1] = (byte) (periods & 0xFF);
        specs[LOCK_OFFSET] = (byte) 0x0; // sanity
        specs[LIVE_OFFSET] = (byte) 0x0; // sanity

        track.addStorageRow(contract, SPECS_KEY, toIDataWord(specs));
    }

    /**
     * Returns the owner of the TRS contract whose address is contract or null if contract has no
     * owner.
     *
     * @param contract The TRS contract address to query.
     * @return the owner of the contract or null if not a TRS contract.
     */
    public Address getContractOwner(Address contract) {
        IDataWord owner = track.getStorageValue(contract, OWNER_KEY);
        return  (owner == null) ? null : new Address(owner.getData());
    }

    /**
     * Sets the current caller to be the owner of the TRS contract given by contract.
     *
     * This method only succeeds if the owner entry for contract is empty. Thus this method can be
     * called successfully at most once per contract.
     *
     * This method does not flush.
     *
     * @param contract The TRS contract to update.
     */
    void setContractOwner(Address contract) {
        if (track.getStorageValue(contract, OWNER_KEY) != null) { return; }
        track.addStorageRow(contract, OWNER_KEY, toIDataWord(caller.toBytes()));
    }

    /**
     * Returns the byte array representing the head of the linked list for the TRS contract given by
     * contract or null if the head of the list is null.
     *
     * The returned array will be length 32 and the bytes in the index range [1, 31] will be the
     * last 31 bytes of a valid Aion account address without the Aion prefix.
     *
     * This method throws an exception if there is no head entry for contract. This should never
     * happen and is here only for debugging.
     *
     * @param contract The TRS contract to query.
     * @return a byte array if there is a non-null head or null otherwise.
     * @throws NullPointerException if contract has no linked list.
     */
    public byte[] getListHead(Address contract) {
        IDataWord head = track.getStorageValue(contract, LIST_HEAD_KEY);
        if (head == null) { throw new NullPointerException("Contract has no list: " + contract); }
        byte[] headData = head.getData();
        return ((headData[0] & NULL_BIT) == NULL_BIT) ? null : Arrays.copyOf(headData, headData.length);
    }

    /**
     * Sets the head of the linked list to head, where head is assumed to be correctly formatted so
     * that the first byte of head is 0x80 iff the head is null, and so that the following 31 bytes
     * of head are the 31 bytes of a valid Aion account address without the Aion prefix.
     *
     * If head is null this method will set the head of the linked list to null.
     * If head is not null this method will set the head to head and will ensure that the null bit
     * is not set.
     *
     * This method does nothing if head is not 32 bytes.
     *
     * This method does not flush.
     *
     * @param contract The TRS contract to update.
     * @param head The head entry data to add.
     */
    void setListHead(Address contract, byte[] head) {
        if (head == null) {
            track.addStorageRow(contract, LIST_HEAD_KEY, NULL32);
        } else if (head.length == DOUBLE_WORD_SIZE) {
            head[0] = 0x0;
            track.addStorageRow(contract, LIST_HEAD_KEY, toIDataWord(head));
        }
    }

    /**
     * Returns the byte array representing account's previous entry in the linked list for the TRS
     * contract given by contract or null if the previous entry is null.
     *
     * The returned array will be length 32 and the bytes in the index range [1, 31] will be the
     * last 31 bytes of a valid Aion account address without the Aion prefix.
     *
     * This method throws an exception if there is no previous entry for account. This should never
     * happen and is here only for debugging.
     *
     * @param contract The TRS contract to query.
     * @return a byte array if there is a non-null head or null otherwise.
     * @throws NullPointerException if contract has no linked list.
     */
    public byte[] getListPrev(Address contract, Address account) {
        byte[] prevKey = new byte[DOUBLE_WORD_SIZE];
        prevKey[0] = LIST_PREV_PREFIX;
        System.arraycopy(account.toBytes(), 1, prevKey, 1, DOUBLE_WORD_SIZE - 1);

        IDataWord prev = track.getStorageValue(contract, toIDataWord(prevKey));
        if (prev == null) { throw new NullPointerException("Account has no prev: " + account); }
        byte[] prevData = prev.getData();
        return ((prevData[0] & NULL_BIT) == NULL_BIT) ? null : Arrays.copyOf(prevData, prevData.length);
    }

    /**
     * Sets account's previous entry in the linked list to prev, where prev is assumed to be
     * correctly formatted so that the first byte of prev is 0x80 iff the previous entry is null,
     * and so that the following 31 bytes of prev are the 31 bytes of a valid Aion account address
     * without the Aion prefix.
     *
     * If prev is null this method will set the previous entry for account to null.
     * If prev is not null this method will set account's previous entry to prev and ensure the null
     * bit is not set.
     *
     * This method does nothing if prev is not 32 bytes.
     *
     * This method does not flush.
     *
     * @param contract The TRS contract to update.
     * @param account The account in contract whose previous entry is being updated.
     * @param prev The previous entry.
     */
    void setListPrevious(Address contract, Address account, byte[] prev) {
        setListPrevious(contract, account.toBytes(), prev);
    }

    /**
     * Sets account's previous entry in the linked list to prev, where prev is assumed to be
     * correctly formatted so that the first byte of prev is 0x80 iff the previous entry is null,
     * and so that the following 31 bytes of prev are the 31 bytes of a valid Aion account address
     * without the Aion prefix.
     *
     * If prev is null this method will set the previous entry for account to null.
     * If prev is not null this method will set account's previous entry to prev and ensure the null
     * bit is not set.
     *
     * This method does nothing if prev is not 32 bytes.
     *
     * This method does not flush.
     *
     * @param contract The TRS contract to update.
     * @param account The account in contract whose previous entry is being updated.
     * @param prev The previous entry.
     */
    void setListPrevious(Address contract, byte[] account, byte[] prev) {
        byte[] prevKey = new byte[DOUBLE_WORD_SIZE];
        prevKey[0] = LIST_PREV_PREFIX;
        System.arraycopy(account, 1, prevKey, 1, DOUBLE_WORD_SIZE - 1);

        if (prev == null) {
            track.addStorageRow(contract, toIDataWord(prevKey), NULL32);
        } else if (prev.length == DOUBLE_WORD_SIZE) {
            prev[0] = 0x0;
            track.addStorageRow(contract, toIDataWord(prevKey), toIDataWord(prev));
        }
    }

    /**
     * Returns account's next entry in the linked list for the TRS contract contract or null if next
     * is null.
     *
     * The returned array will be length 32 and the bytes in the index range [1, 31] will be the
     * last 31 bytes of a valid Aion account address without the Aion prefix.
     *
     * This method throws an exception if there is no next entry for account. This should never
     * happen and is here only for debugging.
     *
     * @param contract The TRS contract to query.
     * @param account The account in contract whose next entry is being updated.
     * @return account's next entry.
     * @throws NullPointerException if account has no next entry.
     */
    public byte[] getListNext(Address contract, Address account) {
        return getListNext(contract, account.toBytes());
    }

    /**
     * Returns account's next entry in the linked list for the TRS contract contract or null if next
     * is null.
     *
     * The returned array will be length 32 and the bytes in the index range [1, 31] will be the
     * last 31 bytes of a valid Aion account address without the Aion prefix.
     *
     * This method throws an exception if there is no next entry for account. This should never
     * happen and is here only for debugging.
     *
     * @param contract The TRS contract to query.
     * @param account The account in contract whose next entry is being updated.
     * @return account's next entry.
     * @throws NullPointerException if account has no next entry.
     */
    byte[] getListNext(Address contract, byte[] account) {
        IDataWord next = track.getStorageValue(contract, toIDataWord(account));
        if (next == null) {
            throw new NullPointerException("Account has no next: " + ByteUtil.toHexString(account));
        }
        byte[] nextData = next.getData();
        return ((nextData[0] & NULL_BIT) == NULL_BIT) ? null : Arrays.copyOf(nextData, nextData.length);
    }

    /**
     * Returns account's next entry in the linked list for the TRS contract contract as the full
     * byte array. This method does not return null if the next entry's null bit is set! This is a
     * way of getting the exact byte array back, which is useful since this array also has a valid
     * bit, which the null return value of getListNext may hide.
     *
     * The returned array will be length 32 and the bytes in the index range [1, 31] will be the
     * last 31 bytes of a valid Aion account address without the Aion prefix.
     *
     * This method throws an exception if there is no previous entry for account. This should never
     * happen and is here only for debugging.
     *
     * @param contract The TRS contract to query.
     * @param account The account in contract whose next entry is being updated.
     * @return account's next entry.
     * @throws NullPointerException if account has no next entry.
     */
    public byte[] getListNextBytes(Address contract, Address account) {
        IDataWord next = track.getStorageValue(contract, toIDataWord(account.toBytes()));
        if (next == null) { throw new NullPointerException("Account has no next: " + account); }
        return Arrays.copyOf(next.getData(), next.getData().length);
    }

    /**
     * Sets account's next entry in the linked list to next, where next is assumed to be correctly
     * formatted so that the first byte of head has its most significant bit set if next is null and
     * its second most significant bit set if it is invalid, and so that the following 31 bytes of
     * head are the 31 bytes of a valid Aion account address without the Aion prefix.
     *
     * If isValid is false then the next entry is set to invalid, indicating that this account has
     * been deleted.
     *
     * If isValid is true and next is null this method will set the next entry for account to null.
     * If isValid is true next is not null then this method will set account's next entry to next
     * and ensure the null bit is not set and that the valid bit is set.
     *
     * The oldMeta parameter is the current (soon to be "old") meta-data about the account's next
     * entry. This is the first byte returned by the getListNextBytes or getListNext methods. This
     * byte contains data about validity and the row counts for the account's balance. This method
     * will persist this meta-data except in the case that we are setting the account to be invalid.
     *
     * This method does nothing if next is not 32 bytes.
     *
     * This method does not flush.
     *
     * @param contract The TRS contract to update.
     * @param account The account in contract whose next entry is being updated.
     * @param next The next entry.
     * @param isValid True only if the account is to be marked as invalid or deleted.
     */
    void setListNext(Address contract, Address account, byte oldMeta, byte[] next, boolean isValid) {
        setListNext(contract, account.toBytes(), oldMeta, next, isValid);
    }

    /**
     * Sets account's next entry in the linked list to next, where next is assumed to be correctly
     * formatted so that the first byte of head has its most significant bit set if next is null and
     * its second most significant bit set if it is invalid, and so that the following 31 bytes of
     * head are the 31 bytes of a valid Aion account address without the Aion prefix.
     *
     * If isValid is false then the next entry is set to invalid, indicating that this account has
     * been deleted.
     *
     * If isValid is true and next is null this method will set the next entry for account to null.
     * If isValid is true next is not null then this method will set account's next entry to next
     * and ensure the null bit is not set and that the valid bit is set.
     *
     * The oldMeta parameter is the current (soon to be "old") meta-data about the account's next
     * entry. This is the first byte returned by the getListNextBytes or getListNext methods. This
     * byte contains data about validity and the row counts for the account's balance. This method
     * will persist this meta-data except in the case that we are setting the account to be invalid.
     *
     * This method does nothing if next is not 32 bytes.
     *
     * This method does not flush.
     *
     * @param contract The TRS contract to update.
     * @param account The account in contract whose next entry is being updated.
     * @param next The next entry.
     * @param isValid True only if the account is to be marked as invalid or deleted.
     */
    void setListNext(Address contract, byte[] account, byte oldMeta, byte[] next, boolean isValid) {
        if (!isValid) {
            track.addStorageRow(contract, toIDataWord(account), INVALID);
        } else if (next == null) {
            byte[] nullNext = Arrays.copyOf(NULL32.getData(), NULL32.getData().length);
            nullNext[0] |= VALID_BIT;
            nullNext[0] |= oldMeta;
            track.addStorageRow(contract, toIDataWord(account), toIDataWord(nullNext));
        } else if (next.length == DOUBLE_WORD_SIZE) {
            next[0] = VALID_BIT;
            next[0] |= oldMeta;
            next[0] &= ~NULL_BIT;
            track.addStorageRow(contract, toIDataWord(account), toIDataWord(next));
        }
    }

    /**
     * Returns true only if the is-valid bit is set in the byte array specs, where it is assumed that
     * spec is the byte array returned by the getListNext method since the account's valid bit is
     * located in that byte array along with the account's next entry.
     *
     * An account marked invalid means that it will deleted from the storage.
     *
     * @param spec The byte array result of getListNext for some account.
     * @return true only if the is-valid bit is set.
     */
    public static boolean accountIsValid(byte[] spec) {
        return ((spec != null) && ((spec[0] & VALID_BIT) == VALID_BIT));
    }

    /**
     * Returns the total deposit balance for the TRS contract given by the address contract.
     *
     * @param contract The TRS contract to query.
     * @return the total balance of the contract.
     */
    public BigInteger getTotalBalance(Address contract) {
        IDataWord ttlSpec = track.getStorageValue(contract, FUNDS_SPECS_KEY);
        int numRows = ByteBuffer.wrap(Arrays.copyOfRange(
            ttlSpec.getData(), SINGLE_WORD_SIZE - Integer.BYTES, SINGLE_WORD_SIZE)).getInt();
        if (numRows == 0) { return BigInteger.ZERO; }

        byte[] balance = new byte[(numRows * DOUBLE_WORD_SIZE) + 1];
        for (int i = 0; i < numRows; i++) {
            byte[] ttlKey = makeTotalBalanceKey(i);
            byte[] ttlVal = track.getStorageValue(contract, toIDataWord(ttlKey)).getData();
            System.arraycopy(ttlVal, 0, balance, (i * DOUBLE_WORD_SIZE) + 1, DOUBLE_WORD_SIZE);
        }
        return new BigInteger(balance);
    }

    /**
     * Sets the total balance of the TRS contract whose address is contract.
     *
     * If the contract does not have a total balance entry this method will create the entries
     * sufficient to hold it and update the total balance specifications corresponding to it.
     *
     * This method assumes that balance is non-negative.
     *
     * This method does not flush.
     *
     * If balance is negative this method throws an exception. This should never happen and is only
     * here for debugging.
     *
     * @param contract The TRS contract to update.
     * @param balance The total balance to set.
     * @throws IllegalArgumentException if balance is negative.
     */
    void setTotalBalance(Address contract, BigInteger balance) {
        if (balance.compareTo(BigInteger.ZERO) < 0) {
            throw new IllegalArgumentException("setTotalBalance to negative balance!");
        }
        byte[] bal = toDoubleWordAlignedArray(balance);
        int numRows = bal.length / DOUBLE_WORD_SIZE;
        for (int i = 0; i < numRows; i++) {
            byte[] ttlKey = makeTotalBalanceKey(i);
            byte[] ttlVal = new byte[DOUBLE_WORD_SIZE];
            System.arraycopy(bal, i * DOUBLE_WORD_SIZE, ttlVal, 0, DOUBLE_WORD_SIZE);
            track.addStorageRow(contract, toIDataWord(ttlKey), toIDataWord(ttlVal));
        }

        // Update total balance specs.
        byte[] ttlSpec = new byte[SINGLE_WORD_SIZE];
        for (int i = 0; i < Integer.BYTES; i++) {
            ttlSpec[SINGLE_WORD_SIZE - i - 1] = (byte) ((numRows >> (i * Byte.SIZE)) & 0xFF);
        }
        track.addStorageRow(contract, FUNDS_SPECS_KEY, toIDataWord(ttlSpec));
    }

    /**
     * Returns the deposit balance for account in the TRS contract given by the address contract.
     *
     * If account does not have a valid entry in this TRS contract, either because it does not exist
     * or its valid bit is unset, then zero is returned.
     *
     * @param contract The TRS contract to query.
     * @param account The account to look up.
     * @return the account's deposit balance for this TRS contract.
     */
    public BigInteger getDepositBalance(Address contract, Address account) {
        IDataWord accountData = track.getStorageValue(contract, toIDataWord(account.toBytes()));
        if (accountData == null) { return BigInteger.ZERO; }
        if ((accountData.getData()[0] & VALID_BIT) == 0x00) { return BigInteger.ZERO; }

        int numRows = (accountData.getData()[0] & 0x0F);
        byte[] balance = new byte[(numRows * DOUBLE_WORD_SIZE) + 1];
        for (int i = 0; i < numRows; i++) {
            byte[] balKey = makeBalanceKey(account, i);
            byte[] balVal = track.getStorageValue(contract, toIDataWord(balKey)).getData();
            System.arraycopy(balVal, 0, balance, (i * DOUBLE_WORD_SIZE) + 1,
                DOUBLE_WORD_SIZE);
        }
        return new BigInteger(balance);
    }

    /**
     * Sets the deposit balance for the account account in the TRS contract given by the address
     * contract to the amount specified by balance and updates the account's 'next' specs to have
     * the number of rows needed to represent this balance.
     *
     * If this is the first deposit for account and account doesn't yet exist then the next entry
     * will have the null bit set, the valid bit unset and the number of rows. Otherwise, for an
     * existing account, the previous setting of the null bit will be persisted and the valid bit
     * will be set and the number of rows will be there along with the address of the next entry.
     *
     * If balance requires more than MAX_DEPOSIT_ROWS storage rows to store, then no update to the
     * account in question will be made and the method will return false.
     *
     * Returns true if the deposit balance was successfully set.
     *
     * This method does not flush.
     *
     * @param contract The TRS contract.
     * @param account The account to update.
     * @param balance The deposit balance to set.
     */
    boolean setDepositBalance(Address contract, Address account, BigInteger balance) {
        if (balance.compareTo(BigInteger.ONE) < 0) { return true; }
        byte[] bal = toDoubleWordAlignedArray(balance);
        int numRows = bal.length / DOUBLE_WORD_SIZE;
        if (numRows > MAX_DEPOSIT_ROWS) { return false; }
        for (int i = 0; i < numRows; i++) {
            byte[] balKey = makeBalanceKey(account, i);
            byte[] balVal = new byte[DOUBLE_WORD_SIZE];
            System.arraycopy(bal, i * DOUBLE_WORD_SIZE, balVal, 0, DOUBLE_WORD_SIZE);
            track.addStorageRow(contract, toIDataWord(balKey), toIDataWord(balVal));
        }

        // Update account meta data.
        IDataWord acctData = track.getStorageValue(contract, toIDataWord(account.toBytes()));
        byte[] acctVal;
        if (acctData == null) {
            // Set null bit and row count but do not set valid bit.
            acctVal = new byte[DOUBLE_WORD_SIZE];
            acctVal[0] = (byte) (NULL_BIT | numRows);
        } else {
            // Set valid bit, row count and preserve the previous null bit setting.
            acctVal = acctData.getData();
            acctVal[0] = (byte) ((acctVal[0] & NULL_BIT) | VALID_BIT | numRows);
        }
        track.addStorageRow(contract, toIDataWord(account.toBytes()), toIDataWord(acctVal));
        return true;
    }

    /**
     * Sets the specifications associated with the TRS contract whose address is contract so that
     * the is-locked bit will be set. If the bit is already set this method effectively does nothing.
     *
     * Assumption: contract IS a valid TRS contract address.
     *
     * @param contract The address of the TRS contract.
     */
    void setLock(Address contract) {
        byte[] spec = getContractSpecs(contract);
        spec[LOCK_OFFSET] = (byte) 0x1;
        track.addStorageRow(contract, SPECS_KEY, toIDataWord(spec));
    }

    /**
     * Sets the specifications associated with the TRS contract whose address is contract so that
     * the is-live bit will be set. If the bit is already set this method effectively does nothing.
     *
     * Assumption: contract IS a valid TRS contract address.
     *
     * @param contract The address of the TRS contract.
     */
    void setLive(Address contract) {
        byte[] spec = getContractSpecs(contract);
        spec[LIVE_OFFSET] = (byte) 0x1;
        track.addStorageRow(contract, SPECS_KEY, toIDataWord(spec));
    }

    /**
     * Returns the percentage of the total funds in a TRS contract that are available for withdrawal
     * during the one-off special withdrawal event.
     *
     * Assumption: specs is a byte array returned from the getContractSpecs method.
     *
     * @param specs The specifications of some TRS contract.
     * @return the withdrawal percetage for the one-off event.
     */
    public static BigDecimal getPercentage(byte[] specs) {
        if (specs == null) { return BigDecimal.ZERO; }
        BigInteger raw = new BigInteger(Arrays.copyOfRange(specs, 0, TEST_OFFSET));
        return new BigDecimal(raw).movePointLeft((int) specs[PRECISION_OFFSET]);
    }

    /**
     * Returns the number of periods for the TRS contract whose specifications are given by specs.
     *
     * Assumption: specs is a byte array returned from the getContractSpecs method.
     *
     * @param specs The specifications of some TRS contract.
     * @return the number of periods for the contract.
     */
    public static int getPeriods(byte[] specs) {
        int periods = specs[PERIODS_OFFSET];
        periods <<= Byte.SIZE;
        periods |= (specs[PERIODS_OFFSET + 1] & 0xFF);
        return periods;
    }

    /**
     * Returns true only if the is-locked bit in specs is set.
     *
     * Assumption: specs is a byte array returned from the getContractSpecs method.
     *
     * @param specs The specifications of some TRS contract.
     * @return true if the specs indicate the contract is locked.
     */
    public static boolean isContractLocked(byte[] specs) {
        return ((specs != null) && (specs[LOCK_OFFSET] == (byte) 0x1));
    }

    /**
     * Returns true only if the is-live bit in specs is set.
     *
     * Assumption: specs is a byte array returned from the getContractSpecs method.
     *
     * @param specs The specifications of some TRS contract.
     * @return true if the specs indicate the contract is live.
     */
    public static boolean isContractLive(byte[] specs) {
        return ((specs != null) && (specs[LIVE_OFFSET] == (byte) 0x1));
    }

    /**
     * Returns true only if the bit in specs is set that represents direct deposits being enabled.
     *
     * Assumption: specs is a byte array returned from the getContractSpecs method.
     *
     * @param specs The specifications of some TRS contract.
     * @return true only if direct deposits are enabled.
     */
    public static boolean isDirDepositsEnabled(byte[] specs) {
        return ((specs != null) && (specs[DIR_DEPO_OFFSET] == (byte) 0x1));
    }

    /**
     * Returns true only if the is-test bit in specs is set.
     *
     * Assumption: specs is a byte array returned from the getContractSpecs method.
     *
     * @param specs The specifications of some TRS contract.
     * @return true if the specs indicate the contract is for testing.
     */
    public static boolean isTestContract(byte[] specs) {
        return ((specs != null) && (specs[TEST_OFFSET] == (byte) 0x1));
    }

    /**
     * Returns a key for the database to query the total balance entry at row number row of some
     * TRS contract.
     *
     * @param row The total balance row to query.
     * @return the key to access the specified total balance row for some contract.
     */
    private byte[] makeTotalBalanceKey(int row) {
        byte[] fundsKey = new byte[SINGLE_WORD_SIZE];
        fundsKey[0] = FUNDS_PREFIX;
        for (int i = 0; i < Integer.BYTES; i++) {
            fundsKey[SINGLE_WORD_SIZE - i - 1] = (byte) ((row >> (i * Byte.SIZE)) & 0xFF);
        }
        return fundsKey;
    }

    /**
     * Returns a key for the database to query the balance entry at row number row for the account
     * account. All valid row numbers are in the range [0, 15] and we assume row is valid here.
     *
     * @param account The account to look up.
     * @param row The balance row to query.
     * @return the key to access the specified balance row for the account in contract.
     */
    private byte[] makeBalanceKey(Address account, int row) {
        byte[] balKey = new byte[DOUBLE_WORD_SIZE];
        balKey[0] = (byte) (BALANCE_PREFIX | row);
        System.arraycopy(account.toBytes(), 1, balKey, 1, DOUBLE_WORD_SIZE - 1);
        return balKey;
    }

    /**
     * Returns a byte array representing balance such that the returned array is 32-byte word
     * aligned.
     *
     * None of the 32-byte consecutive sections of the array will consist only of zero bytes. At
     * least 1 byte per such section will be non-zero.
     *
     * @param balance The balance to convert.
     * @return the 32-byte word-aligned byte array representation of balance.
     */
    private byte[] toDoubleWordAlignedArray(BigInteger balance) {
        if (balance.equals(BigInteger.ZERO)) { return new byte[DOUBLE_WORD_SIZE]; }
        byte[] temp = balance.toByteArray();
        boolean chopFirstByte = ((temp.length - 1) % DOUBLE_WORD_SIZE == 0) && (temp[0] == 0x0);

        byte[] bal;
        if (chopFirstByte) {
            int numRows = (temp.length - 1) / DOUBLE_WORD_SIZE; // guaranteed a divisor by above.
            bal = new byte[numRows * DOUBLE_WORD_SIZE];
            System.arraycopy(temp, 1, bal, bal.length - temp.length + 1, temp.length - 1);
        } else {
            int numRows = (int) Math.ceil(((double) temp.length) / DOUBLE_WORD_SIZE);
            bal = new byte[numRows * DOUBLE_WORD_SIZE];
            System.arraycopy(temp, 0, bal, bal.length - temp.length, temp.length);
        }
        return bal;
    }

    /**
     * Returns an IDataWord object that wraps word with the correctly sized IDataWord implementation.
     *
     * @param word The word to wrap.
     * @return the word as an IDataWord.
     */
    private static IDataWord toIDataWord(byte[] word) {
        if (word.length == SINGLE_WORD_SIZE) {
            return new DataWord(word);
        } else if (word.length == DOUBLE_WORD_SIZE) {
            return new DoubleDataWord(word);
        } else {
            throw new IllegalArgumentException("Incorrect word size: " + word.length);
        }
    }

}
