/*
 * Copyright (c) 2017-2018 Aion foundation.
 *
 *     This file is part of the aion network project.
 *
 *     The aion network project is free software: you can redistribute it
 *     and/or modify it under the terms of the GNU General Public License
 *     as published by the Free Software Foundation, either version 3 of
 *     the License, or any later version.
 *
 *     The aion network project is distributed in the hope that it will
 *     be useful, but WITHOUT ANY WARRANTY; without even the implied
 *     warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *     See the GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with the aion network project source files.
 *     If not, see <https://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     Aion foundation.
 */
package org.aion.precompiled.contracts.TRS;

import java.math.BigInteger;
import java.util.Arrays;
import org.aion.base.db.IRepositoryCache;
import org.aion.base.type.Address;
import org.aion.base.vm.IDataWord;
import org.aion.mcf.core.AccountState;
import org.aion.mcf.db.IBlockStoreBase;
import org.aion.precompiled.ContractExecutionResult;
import org.aion.precompiled.ContractExecutionResult.ResultCode;

/**
 * The TRSuseContract is 1 of 3 inter-dependent but separate contracts that together make up the
 * public-facing TRS contract. A public-facing TRS contract can be owned by any user. In addition to
 * a regular user being able to own a public-facing TRS contract, there is also a special instance
 * of the public-facing TRS contract that is owned by The Aion Foundation itself, which differs from
 * the private TRS contract.
 *
 * The public-facing TRS contract was split into 3 contracts mostly for user-friendliness, since the
 * TRS contract supports many operations, rather than have a single execute method and one very
 * large document specifying its use, the contract was split into 3 logical components instead.
 *
 * The TRSuseContract is the component of the public-facing TRS contract that users of the contract
 * (as well as the owner) interact with in order to perform some state-changing operation on the
 * contract itself. Some of the supported operations are privileged and can only be called by the
 * contract owner.
 *
 * The following operations are supported:
 *      deposit -- deposits funds into a public-facing TRS contract.
 *      withdraw -- withdraws funds from a public-facing TRS contract.
 *      bulkDeposit -- bulk fund depositing on the behalf of depositors. Owner-only.
 *      bulkWithdraw -- bulk fund withdrawal to all depositors. Owner-only.
 *      depositBonus -- deposits bonus funds into a public-facing TRS contract.
 *      refund -- refunds funds to a depositor. Can only be called prior to locking. Owner-only.
 *      updateTotal -- updates the total balance, in case of subsequent sales. Owner-only.
 */
public final class TRSuseContract extends AbstractTRS {
    private static final long COST = 21000L;    // temporary.

    /**
     * Constructs a new TRSuseContract that will use repo as the database cache to update its
     * state with and is called by caller.
     *
     * @param repo The database cache.
     * @param caller The calling address.
     */
    public TRSuseContract(
        IRepositoryCache<AccountState, IDataWord, IBlockStoreBase<?, ?>> repo, Address caller) {

        super(repo, caller);
    }

    /**
     * The input byte array provided to this method must have the following format:
     *
     * [<1b - operation> | <arguments>]
     *
     * where arguments is defined differently for different operations. The supported operations
     * along with their expected arguments are outlined as follows:
     *
     *   <b>operation 0x0</b> - deposits funds into a public-facing TRS contract.
     *     [<32b - contractAddress> | <128b - amount>]
     *     total = 161 bytes
     *   where:
     *     contractAddress is the address of the public-facing TRS contract to deposit funds into.
     *     amount is the amount of funds to deposit. The contract interprets these 128 bytes as an
     *       unsigned and positive amount.
     *
     *     conditions: the calling account must have enough balance to deposit amount otherwise this
     *       method effectively does nothing. The deposit operation is enabled only when the contract
     *       is unlocked; once locked depositing is disabled.
     *
     *     returns: void.
     *
     * @param input The input arguments for the contract.
     * @param nrgLimit The energy limit.
     * @return the result of calling execute on the specified input.
     */
    @Override
    public ContractExecutionResult execute(byte[] input, long nrgLimit) {
        if (input == null) {
            return new ContractExecutionResult(ResultCode.INTERNAL_ERROR, 0);
        }
        if (input.length == 0) {
            return new ContractExecutionResult(ResultCode.INTERNAL_ERROR, 0);
        }
        if (nrgLimit < COST) {
            return new ContractExecutionResult(ResultCode.OUT_OF_NRG, 0);
        }
        if (!isValidTxNrg(nrgLimit)) {
            return new ContractExecutionResult(ResultCode.INVALID_NRG_LIMIT, 0);
        }

        int operation = input[0];
        switch (operation) {
            case 0: return deposit(input, nrgLimit);
            default: return new ContractExecutionResult(ResultCode.INTERNAL_ERROR, 0);
        }
    }

    /**
     * Logic to deposit funds to an existing public-facing TRS contract.
     *
     * The input byte array format is defined as follows:
     *   [<32b - contractAddress> | <128b - amount>]
     *   total = 161 bytes
     * where:
     *   contractAddress is the address of the public-facing TRS contract to deposit funds into.
     *   amount is the amount of funds to deposit. The contract interprets these 128 bytes as an
     *     unsigned and positive amount.
     *
     *   conditions: the calling account must have enough balance to deposit amount otherwise this
     *     method effectively does nothing. The deposit operation is enabled only when the contract
     *     is unlocked; once locked depositing is disabled. Note that if zero is deposited, the call
     *     will succeed but the depositor will not be saved into the database.
     *
     *   returns: void.
     *
     * @param input The input to deposit to a public-facing TRS contract logic.
     * @param nrgLimit The energy limit.
     * @return the result of executing this logic on the specified input.
     */
    private ContractExecutionResult deposit(byte[] input, long nrgLimit) {
        // Some "constants".
        final int indexAddress = 1;
        final int indexAmount = 33;
        final int len = 161;

        if (input.length != len) {
            return new ContractExecutionResult(ResultCode.INTERNAL_ERROR, 0);
        }

        Address contract = Address.wrap(Arrays.copyOfRange(input, indexAddress, indexAmount));
        IDataWord specs = getContractSpecs(contract);
        if (specs == null) {
            return new ContractExecutionResult(ResultCode.INTERNAL_ERROR, 0);
        }

        // A deposit operation can only execute if direct depositing is enabled or caller is owner.
        Address owner = getContractOwner(contract);
        byte[] specBytes = specs.getData();
        if (!caller.equals(owner) && !fetchIsDirDepositsEnabled(specBytes)) {
            return new ContractExecutionResult(ResultCode.INTERNAL_ERROR, 0);
        }

        // A deposit operation can only execute if the current state of the TRS contract is:
        // contract is unlocked (and obviously not live -- check this for sanity).
        if (isContractLocked(specBytes) || isContractLive(specBytes)) {
            return new ContractExecutionResult(ResultCode.INTERNAL_ERROR, 0);
        }

        // Put amount in a byte array one byte larger with an empty initial byte so it is unsigned.
        byte[] amountBytes = new byte[len - indexAmount + 1];
        System.arraycopy(input, indexAmount, amountBytes, 1, len - indexAmount);
        BigInteger amount = new BigInteger(amountBytes);

        // The caller must have adequate funds to make the proposed deposit.
        BigInteger fundsAvailable = track.getBalance(caller);
        if (fundsAvailable.compareTo(amount) < 0) {
            return new ContractExecutionResult(ResultCode.INSUFFICIENT_BALANCE, 0);
        }

        // If deposit amount is larger than zero, update the depositor's current deposit balance and
        // then update the deposit meta-data (linked list, count, etc.)
        if (amount.compareTo(BigInteger.ZERO) > 0) {
            BigInteger currAmount = fetchDepositBalance(contract, caller);
            if (!setDepositBalance(contract, caller, currAmount.add(amount))) {
                return new ContractExecutionResult(ResultCode.INTERNAL_ERROR, 0);
            }
            if (!updateLinkedList(contract)) {
                return new ContractExecutionResult(ResultCode.INTERNAL_ERROR, 0);
            }

            setTotalBalance(contract, fetchTotalBalance(contract).add(amount));
            track.addBalance(caller, amount.negate());
            track.flush();
        }

        return new ContractExecutionResult(ResultCode.SUCCESS, nrgLimit - COST);
    }

    /**
     * Updates the linked list data structure in the storage by proposing to add caller to the list.
     *
     * If caller has a valid storage entry we know it already exists in the list and so no changes
     * are made. However, if caller has no valid storage entry, we make one for it and we add caller
     * to the head of the linked list.
     *
     * Returns true if the list was successfully updated, otherwise false.
     *
     * @param contract The TRS contract.
     */
    private boolean updateLinkedList(Address contract) {
        byte[] acctData = getAccountData(contract, caller);
        if ((acctData[0] & 0x80) == 0x80) { return true; }  // no update needed.
        acctData[0] |= 0x80; // set valid bit.

        // Set caller's 'next' to the current 'head'.
        byte[] headData = getHeadData(contract);
        if (headData == null) { return false; } // should never happen.
        if ((headData[0] & 0x80) == 0x80) {
            acctData[0] |= 0x40;     // set 'next' to null since head is null.
        } else {
            acctData[0] &= ~0x40;    // unset the null bit for account's 'next' entry.
            System.arraycopy(headData, 1, acctData, 1, DOUBLE_WORD_SIZE - 1);

            // set the current head's 'prev' to the caller.
            byte[] currHead = Arrays.copyOfRange(headData, 0, Address.ADDRESS_LEN);
            currHead[0] = AION_PREFIX;
            byte[] currHeadPrev = new byte[DOUBLE_WORD_SIZE];
            currHeadPrev[0] = 0x00; //sanity.
            System.arraycopy(caller.toBytes(), 1, currHeadPrev, 1, DOUBLE_WORD_SIZE - 1);
            addPreviousData(contract, Address.wrap(currHead) ,currHeadPrev);
        }

        // Set the current 'head' to caller.
        headData[0] &= ~0x80;   // unset the null bit for linked list head.
        System.arraycopy(caller.toBytes(), 1, headData, 1, DOUBLE_WORD_SIZE - 1);

        // Set caller's 'prev' to null.
        byte[] prevData = getPreviousData(contract, caller);
        prevData[0] = (byte) 0x80;  // set null bit for previous entry.

        addAccountData(contract, caller, acctData);
        setListHead(contract, headData);
        addPreviousData(contract, caller, prevData);
        return true;
    }

    /**
     * Returns the deposit balance for account in the TRS contract given by the address contract.
     *
     * If account does not have a valid entry in this TRS contract (that is, an existent storage
     * row with the valid bit set) then zero is returned.
     *
     * @param contract The TRS contract to query.
     * @param account The account to look up.
     * @return the account's deposit balance for this TRS contract.
     */
    private BigInteger fetchDepositBalance(Address contract, Address account) {
        IDataWord accountData = track
            .getStorageValue(contract, toIDataWord(account.toBytes()));
        if (accountData == null) {
            return BigInteger.ZERO;
        }    // no entry.
        if ((accountData.getData()[0] & 0x80) == 0x00) {
            return BigInteger.ZERO;
        }  // valid bit unset.

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
     * contract to the amount specified by balance.
     *
     * If balance is not a strictly positive number, or if balance requires more than 16 storage
     * rows to store, then no update to the account in question will be made and the method will
     * return false.
     *
     * Returns true if the deposit balance was successfully set.
     *
     * This method also sets the account's deposit row count so that the newly set balance can be
     * properly retrieved. If the account does not yet have a valid entry then an entry is created
     * for it but marked as invalid. Entry must be added to list to make it valid.
     *
     * This method does not flush.
     *
     * @param contract The TRS contract.
     * @param account The account to update.
     * @param balance The deposit balance to set.
     */
    private boolean setDepositBalance(Address contract, Address account, BigInteger balance) {
        if (balance.compareTo(BigInteger.ONE) < 0) { return false; }
        byte[] bal = toDoubleWordAlignedArray(balance);
        int numRows = bal.length / DOUBLE_WORD_SIZE;
        if (numRows > 16) { return false; }
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
            acctVal[0] = (byte) (0x40 | numRows);
        } else {
            // Set valid bit, row count and preserve the previous null bit setting.
            acctVal = acctData.getData();
            acctVal[0] = (byte) ((acctVal[0] & 0x40) | 0x80 | numRows);
        }
        track.addStorageRow(contract, toIDataWord(account.toBytes()), toIDataWord(acctVal));
        return true;
    }

    /**
     * Returns the account data associated with account in the TRS contract given by the address
     * contract.
     *
     * If account has no account data then a byte array consisting only of zero bytes is returned.
     *
     * @param contract The TRS contract to query.
     * @return the account data of the caller.
     */
    private byte[] getAccountData(Address contract, Address account) {
        IDataWord acctData = track.getStorageValue(contract, toIDataWord(account.toBytes()));
        return (acctData == null) ? new byte[DOUBLE_WORD_SIZE] : acctData.getData();
    }

    /**
     * Adds the data data as the account data corresponding to account in the TRS contract given by
     * the address contract.
     *
     * This method does not flush.
     *
     * @param contract The TRS contract.
     * @param account The account to update.
     * @param data The account data to add.
     */
    private void addAccountData(Address contract, Address account, byte[] data) {
        track.addStorageRow(contract, toIDataWord(account.toBytes()), toIDataWord(data));
    }

    /**
     * Returns the previous entry data for account in the TRS contract given by the address contract.
     *
     * This data represents the account that is previous to account in the linked list.
     *
     * If account does not yet have a previous data entry then this method returns a byte array
     * consisting only of zero bytes.
     *
     * @param contract The TRS contract.
     * @param account The account to look up.
     * @return the previous entry in the linked list.
     */
    private byte[] getPreviousData(Address contract, Address account) {
        byte[] prevKey = new byte[DOUBLE_WORD_SIZE];
        prevKey[0] = LIST_PREV_PREFIX;
        System.arraycopy(account.toBytes(), 1, prevKey, 1, DOUBLE_WORD_SIZE - 1);
        IDataWord val = track.getStorageValue(contract, toIDataWord(prevKey));
        return (val == null) ? new byte[DOUBLE_WORD_SIZE] : val.getData();
    }

    /**
     * Adds the data data as previous entry data (for the linked list) corresponding to account in
     * the TRS contract given by the address contract.
     *
     * This method does not flush.
     *
     * @param contract The TRS contract.
     * @param account The account to update.
     * @param data The previous entry data to add.
     */
    private void addPreviousData(Address contract, Address account, byte[] data) {
        byte[] prevKey = new byte[DOUBLE_WORD_SIZE];
        prevKey[0] = LIST_PREV_PREFIX;
        System.arraycopy(account.toBytes(), 1, prevKey, 1, DOUBLE_WORD_SIZE - 1);
        track.addStorageRow(contract, toIDataWord(prevKey), toIDataWord(data));
    }

    /**
     * Returns the linked list head data for the TRS contract given by the address contract or null
     * if there is no head data for the specified contract (this should never happen).
     *
     * This data represents the head of the linked list in the given contract.
     *
     * @param contract The TRS contract.
     * @return the head of the linked list.
     */
    private byte[] getHeadData(Address contract) {
        IDataWord listVal = track.getStorageValue(contract, LIST_HEAD_KEY);
        if (listVal == null) { return null; }
        return listVal.getData();
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

}