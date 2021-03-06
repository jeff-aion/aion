package org.aion.precompiled.contracts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.aion.types.AionAddress;
import org.aion.db.impl.DBVendor;
import org.aion.db.impl.DatabaseFactory;
import org.aion.interfaces.db.ContractDetails;
import org.aion.interfaces.db.PruneConfig;
import org.aion.interfaces.db.RepositoryCache;
import org.aion.interfaces.db.RepositoryConfig;
import org.aion.mcf.config.CfgPrune;
import org.aion.mcf.vm.types.DataWordImpl;
import org.aion.vm.api.types.ByteArrayWrapper;
import org.aion.crypto.ECKeyFac;
import org.aion.crypto.ISignature;
import org.aion.crypto.ed25519.ECKeyEd25519;
import org.aion.precompiled.PrecompiledResultCode;
import org.aion.precompiled.PrecompiledTransactionResult;
import org.aion.precompiled.type.StatefulPrecompiledContract;

import org.aion.util.bytes.ByteUtil;
import org.aion.zero.impl.db.AionRepositoryCache;
import org.aion.zero.impl.db.AionRepositoryImpl;
import org.aion.zero.impl.db.ContractDetailsAion;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/** Tests the MultiSignatureContract API. */
@Ignore
public class MultiSignatureContractTest {
    private static final long COST = 21000L; // must be equal to valid used in contract.
    private static final int SIG_SIZE = 96; // must be equal to valid used in contract.
    private static final int AMT_SIZE = 128; // must be equal to valid used in contract.
    private static final BigInteger DEFAULT_BALANCE = new BigInteger("100000");
    private static final BigInteger AMOUNT = BigInteger.TEN;
    private static final long NRG_LIMIT = 100000L;
    private static final long NRG_PRICE = 10000000000L;
    private AionAddress to;
    private RepositoryCache repo;
    private List<AionAddress> addrsToClean;

    @Before
    public void setup() {
        RepositoryConfig repoConfig =
            new RepositoryConfig() {
                @Override
                public String getDbPath() {
                    return "";
                }

                @Override
                public PruneConfig getPruneConfig() {
                    return new CfgPrune(false);
                }

                @Override
                public ContractDetails contractDetailsImpl() {
                    return ContractDetailsAion.createForTesting(0, 1000000).getDetails();
                }

                @Override
                public Properties getDatabaseConfig(String db_name) {
                    Properties props = new Properties();
                    props.setProperty(DatabaseFactory.Props.DB_TYPE, DBVendor.MOCKDB.toValue());
                    props.setProperty(DatabaseFactory.Props.ENABLE_HEAP_CACHE, "false");
                    return props;
                }
            };
        repo = new AionRepositoryCache(AionRepositoryImpl.createForTesting(repoConfig));
        addrsToClean = new ArrayList<>();
        to = getExistentAddress(BigInteger.ZERO);
    }

    @After
    public void tearDown() {
        for (AionAddress addr : addrsToClean) {
            repo.deleteAccount(addr);
        }
        repo = null;
        addrsToClean = null;
        to = null;
    }

    // <--------------------------------------HELPER METHODS--------------------------------------->

    // Executes a MSC with input and NRG_LIMIT args, calls it with address caller, and expects
    // code and nrg as results of the execution. Returns the result.
    private PrecompiledTransactionResult execute(
            AionAddress caller, byte[] input, long nrgLimit, PrecompiledResultCode code, long nrg) {

        MultiSignatureContract msc = new MultiSignatureContract(repo, caller);
        PrecompiledTransactionResult res = msc.execute(input, nrgLimit);
        assertEquals(code, res.getResultCode());
        assertEquals(nrg, res.getEnergyRemaining());
        return res;
    }

    // Creates a new account with initial balance balance that will be deleted at test end.
    private AionAddress getExistentAddress(BigInteger balance) {
        AionAddress addr = new AionAddress(ECKeyFac.inst().create().getAddress());
        repo.createAccount(addr);
        repo.addBalance(addr, balance);
        addrsToClean.add(addr);
        return addr;
    }

    // Returns a list of existent accounts of size numOwners, each of which has initial balance.
    private List<AionAddress> getExistentAddresses(long numOwners, BigInteger balance) {
        List<AionAddress> accounts = new ArrayList<>();
        for (int i = 0; i < numOwners; i++) {
            accounts.add(getExistentAddress(balance));
        }
        return accounts;
    }

    // Returns a list of existent accounts of size umOtherOwners + 1 that contains owner and then
    // numOtherOwners other owners each with initial balance balance.
    private List<AionAddress> getExistentAddresses(
            long numOtherOwners, AionAddress owner, BigInteger balance) {
        List<AionAddress> accounts = new ArrayList<>();
        for (int i = 0; i < numOtherOwners; i++) {
            accounts.add(getExistentAddress(balance));
        }
        accounts.add(owner);
        return accounts;
    }

    // This is the constructMsg method provided by MSC class but here you can specify your nonce.
    private static byte[] customMsg(
            RepositoryCache repo,
            BigInteger nonce,
            AionAddress walletId,
            AionAddress to,
            BigInteger amount,
            long nrgPrice,
            long nrgLimit) {

        byte[] nonceBytes = nonce.toByteArray();
        byte[] toBytes = to.toByteArray();
        byte[] amountBytes = amount.toByteArray();
        int len = nonceBytes.length + toBytes.length + amountBytes.length + (Long.BYTES * 2);

        byte[] msg = new byte[len];
        ByteBuffer buffer = ByteBuffer.allocate(len);
        buffer.put(nonceBytes);
        buffer.put(toBytes);
        buffer.put(amountBytes);
        buffer.putLong(nrgLimit);
        buffer.putLong(nrgPrice);
        buffer.flip();
        buffer.get(msg);
        return msg;
    }

    // Returns a properly formatted byte array for these input params for send-tx logic. We want to
    // allow null values so we can simulate missing elements in the input array.
    private byte[] toValidSendInput(
            AionAddress wallet, List<ISignature> signatures, BigInteger amount, long nrg, AionAddress to) {

        int walletLen = (wallet == null) ? 0 : AionAddress.LENGTH;
        int sigsLen = (signatures == null) ? 0 : (signatures.size() * SIG_SIZE);
        int amtLen = (amount == null) ? 0 : AMT_SIZE;
        int toLen = (to == null) ? 0 : AionAddress.LENGTH;

        int len = 1 + walletLen + sigsLen + amtLen + Long.BYTES + toLen;
        byte[] input = new byte[len];

        int index = 0;
        input[index] = (byte) 0x1;
        index++;
        if (wallet != null) {
            System.arraycopy(wallet.toByteArray(), 0, input, index, AionAddress.LENGTH);
            index += AionAddress.LENGTH;
        }

        if (signatures != null) {
            for (ISignature sig : signatures) {
                byte[] sigBytes = sig.toBytes();
                System.arraycopy(sigBytes, 0, input, index, sigBytes.length);
                index += sigBytes.length;
            }
        }

        if (amount != null) {
            byte[] amt = new byte[AMT_SIZE];
            byte[] amtBytes = amount.toByteArray();
            System.arraycopy(amtBytes, 0, amt, AMT_SIZE - amtBytes.length, amtBytes.length);
            System.arraycopy(amt, 0, input, index, AMT_SIZE);
            index += AMT_SIZE;
        }

        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(nrg);
        buffer.flip();
        byte[] nrgBytes = new byte[Long.BYTES];
        buffer.get(nrgBytes);
        System.arraycopy(nrgBytes, 0, input, index, Long.BYTES);
        index += Long.BYTES;

        if (to != null) {
            System.arraycopy(to.toByteArray(), 0, input, index, AionAddress.LENGTH);
        }
        return input;
    }

    // Returns a list of size 2 containing the threshold & number of owners of this multi-sig
    // wallet.
    // If walletId is not a multi-sig wallet this method fails.
    private List<Long> getWalletThresholdAndNumOwners(AionAddress walletId) {
        List<Long> values = new ArrayList<>();
        byte[] metaKey = new byte[DataWordImpl.BYTES];
        metaKey[0] = (byte) 0x80;
        ByteArrayWrapper metaData =
                repo.getStorageValue(walletId, new DataWordImpl(metaKey).toWrapper());
        if (metaData == null) {
            fail();
        }
        byte[] rawData = metaData.getData();
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.put(Arrays.copyOfRange(rawData, 0, Long.BYTES));
        buffer.flip();
        values.add(buffer.getLong());
        buffer.flip();
        buffer.put(Arrays.copyOfRange(rawData, Long.BYTES, DataWordImpl.BYTES));
        buffer.flip();
        values.add(buffer.getLong());
        return values;
    }

    // Returns a set of numOwners owners of the wallet. If the wallet has duplicate owners this
    // method fails.
    private Set<AionAddress> getWalletOwners(AionAddress walletId, long numOwners) {
        Set<AionAddress> owners = new HashSet<>();
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        ByteArrayWrapper portion;

        for (long i = 0; i < numOwners; i++) {
            byte[] account = new byte[AionAddress.LENGTH];
            buffer.putLong(i);
            buffer.flip();
            byte[] request = new byte[DataWordImpl.BYTES];
            buffer.get(request, DataWordImpl.BYTES - Long.BYTES, Long.BYTES);
            portion = repo.getStorageValue(walletId, new DataWordImpl(request).toWrapper());
            if (portion == null) {
                fail();
            }
            System.arraycopy(portion.getData(), 0, account, 0, DataWordImpl.BYTES);

            request[0] = (byte) 0x40;
            portion = repo.getStorageValue(walletId, new DataWordImpl(request).toWrapper());
            if (portion == null) {
                fail();
            }
            System.arraycopy(portion.getData(), 0, account, DataWordImpl.BYTES, DataWordImpl.BYTES);

            AionAddress address = new AionAddress(account);
            if (owners.contains(address)) {
                fail();
            }
            owners.add(address);
            buffer.flip();
            buffer.clear();
        }
        return owners;
    }

    // Returns a list of numKeys keys.
    private List<ECKeyEd25519> produceKeys(long numKeys) {
        List<ECKeyEd25519> keys = new ArrayList<>();
        for (long i = 0; i < numKeys; i++) {
            keys.add(new ECKeyEd25519());
        }
        return keys;
    }

    // Returns the address of the new multi-sig wallet that uses the addresses in the keys of owners
    // as the owners, requires at least threshold signatures and has initial balance balance.
    private AionAddress createMultiSigWallet(
            List<ECKeyEd25519> owners, long threshold, BigInteger balance) {
        if (owners.isEmpty()) {
            fail();
        }
        List<AionAddress> ownerAddrs = new ArrayList<>();
        AionAddress addr;
        for (ECKeyEd25519 key : owners) {
            addr = new AionAddress(key.getAddress());
            repo.createAccount(addr);
            addrsToClean.add(addr);
            ownerAddrs.add(addr);
        }

        byte[] input = MultiSignatureContract.constructCreateWalletInput(threshold, ownerAddrs);
        MultiSignatureContract msc = new MultiSignatureContract(repo, ownerAddrs.get(0));
        PrecompiledTransactionResult res = msc.execute(input, COST);
        assertEquals(PrecompiledResultCode.SUCCESS, res.getResultCode());
        AionAddress wallet = new AionAddress(res.getReturnData());
        repo.addBalance(wallet, balance);
        addrsToClean.add(wallet);
        repo.flush();
        return wallet;
    }

    // Shifts all bytes at index and to the right of index left by 1 position, losing a single byte.
    private byte[] shiftLeftAtIndex(byte[] original, int index) {
        byte[] shifted = new byte[original.length - 1];
        System.arraycopy(original, 0, shifted, 0, index - 1);
        System.arraycopy(original, index, shifted, index - 1, original.length - index);
        return shifted;
    }

    // Produces a list of numSigs signatures from the first numSigs owners; each signs msg.
    private List<ISignature> produceSignatures(List<ECKeyEd25519> owners, int numSigs, byte[] msg) {
        List<ISignature> signatures = new ArrayList<>();
        for (int i = 0; i < numSigs; i++) {
            signatures.add(owners.get(i).sign(msg));
        }
        return signatures;
    }

    // Verifies that the result of a create-wallet operation, res, saves a wallet with threshold
    // threshold and consists of all the owners in owners and no more.
    private void checkCreateResult(
            PrecompiledTransactionResult res, long threshold, List<AionAddress> owners) {
        AionAddress walletId = new AionAddress(res.getReturnData());
        addrsToClean.add(walletId);
        assertEquals(BigInteger.ZERO, repo.getBalance(walletId));
        assertEquals(BigInteger.ZERO, repo.getNonce(walletId));

        List<Long> threshAndOwners = getWalletThresholdAndNumOwners(walletId);
        assertEquals(threshold, threshAndOwners.get(0).longValue());
        assertEquals(owners.size(), threshAndOwners.get(1).longValue());

        Set<AionAddress> walletOwners = getWalletOwners(walletId, threshAndOwners.get(1));
        assertEquals(owners.size(), walletOwners.size());
        for (AionAddress own : owners) {
            assertTrue(walletOwners.contains(own));
        }
    }

    // Verifies that account has a nonce equal to nonce and a balance equal to balance.
    private void checkAccountState(AionAddress account, BigInteger nonce, BigInteger balance) {
        assertEquals(nonce, repo.getNonce(account));
        assertEquals(balance, repo.getBalance(account));
    }

    // <------------------------------------MISCELLANEOUS TESTS------------------------------------>

    @Test(expected = IllegalArgumentException.class)
    public void testConstructWithNullTrack() {
        new MultiSignatureContract(null, new AionAddress(ECKeyFac.inst().create().getAddress()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructWithNullCaller() {
        new MultiSignatureContract(repo, null);
    }

    @Test
    public void testNrgBelowLegalLimit() {
        // First test create-wallet logic.
        // Test with min illegal cost.
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        List<AionAddress> owners = getExistentAddresses(3, BigInteger.ZERO);
        byte[] input =
                MultiSignatureContract.constructCreateWalletInput(
                        MultiSignatureContract.MIN_THRESH, owners);

        execute(caller, input, Long.MIN_VALUE, PrecompiledResultCode.OUT_OF_NRG, 0);

        // Test with max illegal cost.
        execute(caller, input, COST - 1, PrecompiledResultCode.OUT_OF_NRG, 0);

        // Second test send-tx logic.
        // Test with min illegal cost.
        List<ECKeyEd25519> sendOwners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress sendCaller = new AionAddress(sendOwners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(
                        sendOwners, MultiSignatureContract.MIN_OWNERS - 1, DEFAULT_BALANCE);
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(sendOwners, sendOwners.size(), txMsg);

        input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, COST - 1, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(sendCaller, input, Long.MIN_VALUE, PrecompiledResultCode.OUT_OF_NRG, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);

        // Test with max illegal cost.
        input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, COST - 1, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(sendCaller, input, COST - 1, PrecompiledResultCode.OUT_OF_NRG, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testNrgAboveThanCost() {
        long nrgLimit = StatefulPrecompiledContract.TX_NRG_MAX + 1;

        // First test create-wallet logic.
        // Test with min illegal cost.
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        List<AionAddress> owners = getExistentAddresses(3, BigInteger.ZERO);
        byte[] input =
                MultiSignatureContract.constructCreateWalletInput(
                        MultiSignatureContract.MIN_THRESH, owners);

        execute(caller, input, nrgLimit, PrecompiledResultCode.INVALID_NRG_LIMIT, nrgLimit);

        // Test with max illegal cost.
        execute(
                caller,
                input,
                Long.MAX_VALUE,
                PrecompiledResultCode.INVALID_NRG_LIMIT,
                Long.MAX_VALUE);

        // Second test send-tx logic.
        // Test with min illegal cost.
        List<ECKeyEd25519> sendOwners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress sendCaller = new AionAddress(sendOwners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(
                        sendOwners, MultiSignatureContract.MIN_OWNERS - 1, DEFAULT_BALANCE);
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(sendOwners, sendOwners.size(), txMsg);

        input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, COST - 1, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(sendCaller, input, nrgLimit, PrecompiledResultCode.INVALID_NRG_LIMIT, nrgLimit);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);

        // Test with max illegal cost.
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(
                sendCaller,
                input,
                Long.MAX_VALUE,
                PrecompiledResultCode.INVALID_NRG_LIMIT,
                Long.MAX_VALUE);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testNullInput() {
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        execute(caller, null, COST, PrecompiledResultCode.FAILURE, 0);
    }

    @Test
    public void testEmptyInput() {
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        execute(caller, ByteUtil.EMPTY_BYTE_ARRAY, COST, PrecompiledResultCode.FAILURE, 0);
    }

    @Test
    public void testInputWithOperationOnly() {
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        execute(caller, new byte[] {(byte) 0x0}, COST, PrecompiledResultCode.FAILURE, 0);
        execute(caller, new byte[] {(byte) 0x1}, COST, PrecompiledResultCode.FAILURE, 0);
    }

    @Test
    public void testInputWithUnsupportedOperation() {
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        List<AionAddress> owners = getExistentAddresses(3, BigInteger.ZERO);
        byte[] input =
                MultiSignatureContract.constructCreateWalletInput(
                        MultiSignatureContract.MIN_THRESH, owners);

        // Test all possible invalid operations.
        for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
            if ((i != 0) && (i != 1)) {
                input[0] = (byte) i;
                execute(caller, input, COST, PrecompiledResultCode.FAILURE, 0);
            }
        }
    }

    // <------------------------------------CREATE WALLET TESTS------------------------------------>

    @Test
    public void testCreateWalletThresholdBelowLegalLimit() {
        // Test with min illegal value.
        List<AionAddress> owners = getExistentAddresses(3, BigInteger.ZERO);
        AionAddress caller = owners.get(0);
        byte[] input = MultiSignatureContract.constructCreateWalletInput(Long.MIN_VALUE, owners);

        execute(caller, input, COST, PrecompiledResultCode.FAILURE, 0);

        // Test with max illegal value.
        input =
                MultiSignatureContract.constructCreateWalletInput(
                        MultiSignatureContract.MIN_THRESH - 1, owners);
        execute(caller, input, COST, PrecompiledResultCode.FAILURE, 0);
    }

    @Test
    public void testCreateWalletWithThresholdLargerThanNumOwners() {
        // Test with max illegal value.
        List<AionAddress> owners = getExistentAddresses(3, BigInteger.ZERO);
        AionAddress caller = owners.get(0);
        byte[] input = MultiSignatureContract.constructCreateWalletInput(Long.MAX_VALUE, owners);

        execute(caller, input, COST, PrecompiledResultCode.FAILURE, 0);

        // Test with smallest illegal value.
        input = MultiSignatureContract.constructCreateWalletInput(owners.size() + 1, owners);
        execute(caller, input, COST, PrecompiledResultCode.FAILURE, 0);
    }

    @Test
    public void testCreateWalletZeroOwners() {
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        List<AionAddress> owners = new ArrayList<>();
        byte[] input =
                MultiSignatureContract.constructCreateWalletInput(
                        MultiSignatureContract.MIN_THRESH, owners);

        execute(caller, input, COST, PrecompiledResultCode.FAILURE, 0);
    }

    @Test
    public void testCreateWalletOneOwner() {
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        List<AionAddress> owners = getExistentAddresses(1, BigInteger.ZERO);
        byte[] input = MultiSignatureContract.constructCreateWalletInput(Long.MAX_VALUE, owners);

        execute(caller, input, COST, PrecompiledResultCode.FAILURE, 0);
    }

    @Test
    public void testCreateWalletWithMoreOwnersThanLegalLimit() {
        List<AionAddress> owners =
                getExistentAddresses(MultiSignatureContract.MAX_OWNERS + 1, BigInteger.ZERO);
        AionAddress caller = owners.get(0);
        byte[] input = MultiSignatureContract.constructCreateWalletInput(Long.MAX_VALUE, owners);

        execute(caller, input, COST, PrecompiledResultCode.FAILURE, 0);
    }

    @Test
    public void testCreateWalletWithTwoDuplicateOwners() {
        // Test with max amount of owners
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        List<AionAddress> owners =
                getExistentAddresses(MultiSignatureContract.MAX_OWNERS - 1, BigInteger.ZERO);
        owners.add(owners.get(0));
        byte[] input =
                MultiSignatureContract.constructCreateWalletInput(
                        MultiSignatureContract.MIN_THRESH, owners);

        execute(caller, input, COST, PrecompiledResultCode.FAILURE, 0);

        // Test with min amount of owners
        owners = getExistentAddresses(MultiSignatureContract.MIN_OWNERS - 1, BigInteger.ZERO);
        owners.add(owners.get(0));
        input =
                MultiSignatureContract.constructCreateWalletInput(
                        MultiSignatureContract.MIN_THRESH, owners);

        execute(caller, input, COST, PrecompiledResultCode.FAILURE, 0);

        // Test all owners same
        owners.clear();
        for (int i = 0; i < MultiSignatureContract.MIN_OWNERS; i++) {
            owners.add(caller);
        }
        input =
                MultiSignatureContract.constructCreateWalletInput(
                        MultiSignatureContract.MIN_THRESH, owners);

        execute(caller, input, COST, PrecompiledResultCode.FAILURE, 0);
    }

    @Test
    public void testCreateWalletButCallerIsNotAnOwner() {
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        List<AionAddress> owners =
                getExistentAddresses(MultiSignatureContract.MIN_OWNERS, BigInteger.ZERO);
        byte[] input =
                MultiSignatureContract.constructCreateWalletInput(
                        MultiSignatureContract.MIN_THRESH, owners);

        execute(caller, input, COST, PrecompiledResultCode.FAILURE, 0);
    }

    @Test
    public void testCreateWalletWithPartiallyCompleteAddress() {
        // Test on nearly min legal number of owners.
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        List<AionAddress> owners =
                getExistentAddresses(MultiSignatureContract.MIN_OWNERS - 1, BigInteger.ZERO);
        owners.add(caller);
        byte[] in =
                MultiSignatureContract.constructCreateWalletInput(
                        MultiSignatureContract.MIN_THRESH, owners);

        byte[] partialAddr = new byte[AionAddress.LENGTH - 1];
        ThreadLocalRandom.current().nextBytes(partialAddr);

        byte[] input = new byte[in.length + partialAddr.length];
        System.arraycopy(in, 0, input, 0, in.length);
        System.arraycopy(partialAddr, 0, input, in.length, partialAddr.length);

        execute(caller, input, COST, PrecompiledResultCode.FAILURE, 0);

        // Test on max legal number of owners.
        owners = getExistentAddresses(MultiSignatureContract.MAX_OWNERS - 2, BigInteger.ZERO);
        in =
                MultiSignatureContract.constructCreateWalletInput(
                        MultiSignatureContract.MIN_THRESH, owners);

        input = new byte[in.length + partialAddr.length];
        System.arraycopy(in, 0, input, 0, in.length);
        System.arraycopy(partialAddr, 0, input, in.length, partialAddr.length);

        execute(caller, input, COST, PrecompiledResultCode.FAILURE, 0);
    }

    @Test
    public void testCreateWalletWithMultiSigWalletCaller() {
        // First create a multi-sig wallet.
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        List<AionAddress> owners =
                getExistentAddresses(
                        MultiSignatureContract.MIN_OWNERS - 1, caller, BigInteger.ZERO);
        long threshold = owners.size();
        byte[] input = MultiSignatureContract.constructCreateWalletInput(threshold, owners);

        PrecompiledTransactionResult res =
                execute(caller, input, NRG_LIMIT, PrecompiledResultCode.SUCCESS, NRG_LIMIT - COST);

        AionAddress walletCaller = new AionAddress(res.getReturnData());
        addrsToClean.add(walletCaller);
        checkAccountState(walletCaller, BigInteger.ZERO, BigInteger.ZERO);

        // Now try to create a wallet using this wallet as the caller and an owner.
        owners =
                getExistentAddresses(
                        MultiSignatureContract.MAX_OWNERS - 1, walletCaller, BigInteger.ZERO);
        input =
                MultiSignatureContract.constructCreateWalletInput(
                        MultiSignatureContract.MIN_THRESH, owners);

        execute(walletCaller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
    }

    @Test
    public void testCreateWalletWithOwnerAsAMultiSigWallet() {
        // First create a multi-sig wallet.
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        List<AionAddress> owners =
                getExistentAddresses(
                        MultiSignatureContract.MIN_OWNERS - 1, caller, BigInteger.ZERO);
        long threshold = owners.size();
        byte[] input = MultiSignatureContract.constructCreateWalletInput(threshold, owners);

        PrecompiledTransactionResult res =
                execute(caller, input, NRG_LIMIT, PrecompiledResultCode.SUCCESS, NRG_LIMIT - COST);

        AionAddress wallet = new AionAddress(res.getReturnData());
        addrsToClean.add(wallet);
        checkAccountState(wallet, BigInteger.ZERO, BigInteger.ZERO);

        // Now try to create a wallet using this wallet as one of the owners.
        AionAddress newCaller = getExistentAddress(BigInteger.ZERO);
        owners =
                getExistentAddresses(
                        MultiSignatureContract.MAX_OWNERS - 2, wallet, BigInteger.ZERO);
        owners.add(newCaller);
        input =
                MultiSignatureContract.constructCreateWalletInput(
                        MultiSignatureContract.MIN_THRESH, owners);

        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
    }

    @Test
    public void testCreateWalletWithThresholdEqualToLegalNumOwners() {
        // Test using min legal number of owners.
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        List<AionAddress> owners =
                getExistentAddresses(
                        MultiSignatureContract.MIN_OWNERS - 1, caller, BigInteger.ZERO);
        long threshold = owners.size();
        byte[] input = MultiSignatureContract.constructCreateWalletInput(threshold, owners);

        PrecompiledTransactionResult res =
                execute(caller, input, NRG_LIMIT, PrecompiledResultCode.SUCCESS, NRG_LIMIT - COST);
        checkCreateResult(res, threshold, owners);
        checkAccountState(new AionAddress(res.getReturnData()), BigInteger.ZERO, BigInteger.ZERO);

        // Test using max legal number of owners.
        owners =
                getExistentAddresses(
                        MultiSignatureContract.MAX_OWNERS - 1, caller, BigInteger.ZERO);
        threshold = owners.size();
        input = MultiSignatureContract.constructCreateWalletInput(threshold, owners);

        res = execute(caller, input, NRG_LIMIT, PrecompiledResultCode.SUCCESS, NRG_LIMIT - COST);
        checkCreateResult(res, threshold, owners);
        checkAccountState(new AionAddress(res.getReturnData()), BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testCreateWalletAddressIsDeterministic() {
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        List<AionAddress> owners =
                getExistentAddresses(
                        MultiSignatureContract.MIN_OWNERS - 1, caller, BigInteger.ZERO);
        long threshold = owners.size();
        byte[] input = MultiSignatureContract.constructCreateWalletInput(threshold, owners);

        PrecompiledTransactionResult res =
                execute(caller, input, NRG_LIMIT, PrecompiledResultCode.SUCCESS, NRG_LIMIT - COST);
        AionAddress wallet1 = new AionAddress(res.getReturnData());

        res = execute(caller, input, NRG_LIMIT, PrecompiledResultCode.SUCCESS, NRG_LIMIT - COST);
        AionAddress wallet2 = new AionAddress(res.getReturnData());

        assertEquals(wallet1, wallet2);
    }

    @Test
    public void testWalletAddressStartsWithAionPrefix() {
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        List<AionAddress> owners =
                getExistentAddresses(
                        MultiSignatureContract.MIN_OWNERS - 1, caller, BigInteger.ZERO);
        long threshold = owners.size();
        byte[] input = MultiSignatureContract.constructCreateWalletInput(threshold, owners);

        PrecompiledTransactionResult res =
                execute(caller, input, NRG_LIMIT, PrecompiledResultCode.SUCCESS, NRG_LIMIT - COST);
        AionAddress wallet = new AionAddress(res.getReturnData());
        assertTrue(wallet.toString().startsWith("a0"));
    }

    @Test
    public void testCreateWalletWithMinimumLegalThreshold() {
        // Test using min legal number of owners.
        AionAddress caller = getExistentAddress(BigInteger.ZERO);
        List<AionAddress> owners =
                getExistentAddresses(
                        MultiSignatureContract.MIN_OWNERS - 1, caller, BigInteger.ZERO);
        long threshold = MultiSignatureContract.MIN_THRESH;
        byte[] input = MultiSignatureContract.constructCreateWalletInput(threshold, owners);

        PrecompiledTransactionResult res =
                execute(caller, input, NRG_LIMIT, PrecompiledResultCode.SUCCESS, NRG_LIMIT - COST);
        checkCreateResult(res, threshold, owners);
        checkAccountState(new AionAddress(res.getReturnData()), BigInteger.ZERO, BigInteger.ZERO);

        // Test using max legal number of owners.
        owners =
                getExistentAddresses(
                        MultiSignatureContract.MAX_OWNERS - 1, caller, BigInteger.ZERO);
        threshold = owners.size();
        input = MultiSignatureContract.constructCreateWalletInput(threshold, owners);

        res = execute(caller, input, NRG_LIMIT, PrecompiledResultCode.SUCCESS, NRG_LIMIT - COST);
        checkCreateResult(res, threshold, owners);
        checkAccountState(new AionAddress(res.getReturnData()), BigInteger.ZERO, BigInteger.ZERO);
    }

    // <----------------------------------SEND TRANSACTION TESTS----------------------------------->

    @Test
    public void testSendTxWithZeroSignatures() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_THRESH, DEFAULT_BALANCE);

        // No signatures.
        List<ISignature> signatures = new ArrayList<>();

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxWithMoreThanMaxOwnersSignatures() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MAX_OWNERS + 1);
        ECKeyEd25519 extra = owners.remove(0);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MAX_OWNERS, DEFAULT_BALANCE);

        // Have all owners plus one extra sign the tx.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);
        signatures.add(extra.sign(txMsg));

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxValidSignaturesMeetsThresholdPlusPhonySig() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS + 1);
        ECKeyEd25519 phony = owners.remove(0);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        // Have all owners sign, meet threshold requirement, and attach a phony signaure.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);
        signatures.add(phony.sign(txMsg));

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxNegativeAmountWithZeroBalance() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_THRESH, BigInteger.ZERO);
        BigInteger amt = AMOUNT.negate();

        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        byte[] input =
                MultiSignatureContract.constructSendTxInput(wallet, signatures, amt, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, BigInteger.ZERO);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, BigInteger.ZERO);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxNegativeAmountWithActualBalance() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_THRESH, DEFAULT_BALANCE);
        BigInteger amt = AMOUNT.negate();

        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, amt, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        byte[] input =
                MultiSignatureContract.constructSendTxInput(wallet, signatures, amt, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxFromRegularAddress() {
        // Our wallet is not a wallet...
        List<ECKeyEd25519> phonies = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress phonyWallet = new AionAddress(phonies.get(0).getAddress());
        repo.addBalance(phonyWallet, DEFAULT_BALANCE);
        BigInteger amt = BigInteger.ONE;

        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        phonyWallet, repo.getNonce(phonyWallet), to, amt, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(phonies, phonies.size(), txMsg);

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        phonyWallet, signatures, amt, NRG_PRICE, to);

        checkAccountState(phonyWallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(phonyWallet, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(phonyWallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxNoSenderInInput() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        // input does not contain the sender info.
        byte[] input = toValidSendInput(null, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxNoRecipient() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        // input does not contain the recipient info.
        byte[] input = toValidSendInput(wallet, signatures, AMOUNT, NRG_PRICE, null);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxNoAmount() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        // input does not contain the amount info.
        byte[] input = toValidSendInput(wallet, signatures, null, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxNoNrgPrice() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        // input does not contain the amount info.
        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);
        byte[] noNrgInput = new byte[input.length - Long.BYTES];
        System.arraycopy(input, 0, noNrgInput, 0, input.length - AionAddress.LENGTH - Long.BYTES - 1);
        System.arraycopy(
                input,
                input.length - AionAddress.LENGTH,
                noNrgInput,
                input.length - AionAddress.LENGTH - Long.BYTES,
                AionAddress.LENGTH);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, noNrgInput, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxWithSignatureUsingPreviousNonce() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        // We sign a tx msg that uses the previous nonce.
        BigInteger nonce = repo.getNonce(wallet);
        byte[] txMsg =
                customMsg(
                        repo,
                        nonce.subtract(BigInteger.ONE),
                        wallet,
                        to,
                        AMOUNT,
                        NRG_PRICE,
                        NRG_LIMIT);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxWhereSignedMessagesDifferInNonce() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        // One signee signs tx with a different nonce than the others. The others sign correct tx.
        BigInteger nonce = repo.getNonce(wallet);
        byte[] correctTx =
                MultiSignatureContract.constructMsg(wallet, nonce, to, AMOUNT, NRG_PRICE);
        byte[] badNonceTx =
                customMsg(
                        repo,
                        nonce.subtract(BigInteger.ONE),
                        wallet,
                        to,
                        AMOUNT,
                        NRG_PRICE,
                        NRG_LIMIT);
        List<ISignature> signatures =
                produceSignatures(owners, MultiSignatureContract.MIN_OWNERS - 1, correctTx);
        signatures.add(owners.get(MultiSignatureContract.MIN_OWNERS - 1).sign(badNonceTx));

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxWhereSignedMessagesDifferInRecipient() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        // One signee signs tx with a different recipient than the others. The others sign correct
        // tx.
        byte[] correctTx =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        byte[] badToTx =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), caller, AMOUNT, NRG_PRICE);
        List<ISignature> signatures =
                produceSignatures(owners, MultiSignatureContract.MIN_OWNERS - 1, correctTx);
        signatures.add(owners.get(MultiSignatureContract.MIN_OWNERS - 1).sign(badToTx));

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxWhereSignedMessagesDifferInAmount() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        // One signee signs tx with a different amount than the others. The others sign correct tx.
        byte[] correctTx =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        byte[] badAmtTx =
                MultiSignatureContract.constructMsg(
                        wallet,
                        repo.getNonce(wallet),
                        to,
                        AMOUNT.subtract(BigInteger.ONE),
                        NRG_PRICE);
        List<ISignature> signatures =
                produceSignatures(owners, MultiSignatureContract.MIN_OWNERS - 1, correctTx);
        signatures.add(owners.get(MultiSignatureContract.MIN_OWNERS - 1).sign(badAmtTx));

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxWhereSignedMessagesDifferInNrgPrice() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        // One signee signs tx with a different nrg price than the others. The others sign correct
        // tx.
        byte[] correctTx =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        byte[] badNrgTx =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE - 1);
        List<ISignature> signatures =
                produceSignatures(owners, MultiSignatureContract.MIN_OWNERS - 1, correctTx);
        signatures.add(owners.get(MultiSignatureContract.MIN_OWNERS - 1).sign(badNrgTx));

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxAllSignWrongRecipient() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);
        AionAddress stranger = getExistentAddress(BigInteger.ZERO);

        // Everyone signs a valid recipient and whole tx is fine but the recipient stated in input
        // differs.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        // The recipient in input differs from all the signatures.
        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, stranger);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxAllSignWrongAmount() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        // Everyone signs a valid amount and whole tx is fine but the amount stated in input
        // differs.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        // The recipient in input differs from all the signatures.
        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT.subtract(BigInteger.ONE), NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxAllSignWrongNonce() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);
        AionAddress stranger = getExistentAddress(BigInteger.ZERO);

        // Everyone signs a different nonce than the wallet's current one.
        BigInteger nonce = repo.getNonce(wallet);
        byte[] txMsg =
                customMsg(
                        repo, nonce.add(BigInteger.ONE), wallet, to, AMOUNT, NRG_PRICE, NRG_LIMIT);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        // The recipient in input differs from all the signatures.
        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, stranger);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxAllSignWrongNrgPrice() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        // Everyone signs a valid NRG_PRICE and whole tx is fine but the NRG_PRICE stated in input
        // differs.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE + 1);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        // The recipient in input differs from all the signatures.
        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxInsufficientBalance() {
        // Create account with zero balance.
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_THRESH, BigInteger.ZERO);

        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, BigInteger.ZERO);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.INSUFFICIENT_BALANCE, 0);
        checkAccountState(wallet, BigInteger.ZERO, BigInteger.ZERO);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testWalletAbleToSendTxToDiffWallet() {
        List<ECKeyEd25519> owners1 = produceKeys(MultiSignatureContract.MIN_OWNERS);
        List<ECKeyEd25519> owners2 = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners1.get(0).getAddress());
        AionAddress wallet1 =
                createMultiSigWallet(owners1, MultiSignatureContract.MIN_THRESH, DEFAULT_BALANCE);
        AionAddress wallet2 =
                createMultiSigWallet(owners2, MultiSignatureContract.MIN_THRESH, DEFAULT_BALANCE);

        // Sign tx to send from wallet1 to wallet2.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet1, repo.getNonce(wallet1), wallet2, AMOUNT, NRG_PRICE);
        List<ISignature> signatures =
                produceSignatures(owners1, MultiSignatureContract.MIN_THRESH, txMsg);

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet1, signatures, AMOUNT, NRG_PRICE, wallet2);

        checkAccountState(wallet1, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(wallet2, BigInteger.ZERO, DEFAULT_BALANCE);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.SUCCESS, NRG_LIMIT - COST);
        checkAccountState(wallet1, BigInteger.ONE, DEFAULT_BALANCE.subtract(AMOUNT));
        checkAccountState(wallet2, BigInteger.ZERO, DEFAULT_BALANCE.add(AMOUNT));
    }

    @Test
    public void testSendTxLessSignaturesThanThresholdMinOwners() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        // Have 1 less owner than required sign the tx msg.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures =
                produceSignatures(owners, MultiSignatureContract.MIN_OWNERS - 1, txMsg);

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxLessSignaturesThanThresholdMaxOwners() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MAX_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MAX_OWNERS, DEFAULT_BALANCE);

        // Have 1 less owner than required sign the tx msg.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures =
                produceSignatures(owners, MultiSignatureContract.MAX_OWNERS - 1, txMsg);

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxSameSignaturesAsThresholdMinOwners() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_THRESH, DEFAULT_BALANCE);

        // Have each owner sign the tx msg.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.SUCCESS, NRG_LIMIT - COST);
        checkAccountState(wallet, BigInteger.ONE, DEFAULT_BALANCE.subtract(AMOUNT));
        checkAccountState(to, BigInteger.ZERO, AMOUNT);
    }

    @Test
    public void testSendTxSameSignaturesAsThresholdMaxOwners() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MAX_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MAX_OWNERS, DEFAULT_BALANCE);

        // Have each owner sign the tx msg.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.SUCCESS, NRG_LIMIT - COST);
        checkAccountState(wallet, BigInteger.ONE, DEFAULT_BALANCE.subtract(AMOUNT));
        checkAccountState(to, BigInteger.ZERO, AMOUNT);
    }

    @Test
    public void testSendTxMoreSignaturesThanThresholdMinOwners() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(
                        owners, MultiSignatureContract.MIN_OWNERS - 1, DEFAULT_BALANCE);

        // Have all the owners sign.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.SUCCESS, NRG_LIMIT - COST);
        checkAccountState(wallet, BigInteger.ONE, DEFAULT_BALANCE.subtract(AMOUNT));
        checkAccountState(to, BigInteger.ZERO, AMOUNT);
    }

    @Test
    public void testSendTxMoreSignaturesThanThresholdMaxOwners() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MAX_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_THRESH, DEFAULT_BALANCE);

        // Have all the owners sign.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures = produceSignatures(owners, owners.size(), txMsg);

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.SUCCESS, NRG_LIMIT - COST);
        checkAccountState(wallet, BigInteger.ONE, DEFAULT_BALANCE.subtract(AMOUNT));
        checkAccountState(to, BigInteger.ZERO, AMOUNT);
    }

    @Test
    public void testSendTxDuplicateSignee() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MAX_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MAX_OWNERS, DEFAULT_BALANCE);

        // All owners but 1 sign, and 1 signs twice to meet threshold req.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures =
                produceSignatures(owners, MultiSignatureContract.MAX_OWNERS - 1, txMsg);
        signatures.add(owners.get(MultiSignatureContract.MAX_OWNERS - 2).sign(txMsg));

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testSendTxSignatureOneSigneeIsNonOwner() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MAX_OWNERS);
        ECKeyEd25519 phony = produceKeys(1).get(0);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MAX_OWNERS, DEFAULT_BALANCE);

        // All owners but 1 sign, and then the phony signs.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures =
                produceSignatures(owners, MultiSignatureContract.MAX_OWNERS, txMsg);
        signatures.add(phony.sign(txMsg));

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    // This is ok: caller is owner and so their absent sig does not matter if all sigs are ok.
    @Test
    public void testSendTxSignedProperlyButNotSignedByOwnerCaller() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MAX_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_THRESH, DEFAULT_BALANCE);

        // Adequate number of signees but we skip signee 0 since they are caller.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures =
                produceSignatures(owners, MultiSignatureContract.MIN_THRESH, txMsg);

        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, input, NRG_LIMIT, PrecompiledResultCode.SUCCESS, NRG_LIMIT - COST);
        checkAccountState(wallet, BigInteger.ONE, DEFAULT_BALANCE.subtract(AMOUNT));
        checkAccountState(to, BigInteger.ZERO, AMOUNT);
    }

    // This is bad: only want transactions triggered by a calling owner, even if caller doesn't
    // sign.
    @Test
    public void testSendTxSignedProperlyButCallerIsNotOwner() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        ECKeyEd25519 phony = produceKeys(1).get(0);
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        // Signed adequately.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures =
                produceSignatures(owners, MultiSignatureContract.MIN_THRESH, txMsg);

        // The phony is the one who calls the contract though.
        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(
                new AionAddress(phony.getAddress()),
                input,
                NRG_LIMIT,
                PrecompiledResultCode.FAILURE,
                0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testPartialSignature() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        // Signed adequately.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures =
                produceSignatures(owners, MultiSignatureContract.MIN_THRESH, txMsg);

        // Input gets shifted to lose 1 byte of the last signature.
        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);
        int amtStart = input.length - AionAddress.LENGTH - Long.BYTES - AMT_SIZE;
        byte[] shiftedInput = shiftLeftAtIndex(input, amtStart);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, shiftedInput, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testPartialWalletAddress() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        // Signed adequately.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures =
                produceSignatures(owners, MultiSignatureContract.MIN_THRESH, txMsg);

        // Input gets shifted to lose 1 byte of the wallet address.
        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);
        int sigsStart = 1 + AionAddress.LENGTH;
        byte[] shiftedInput = shiftLeftAtIndex(input, sigsStart);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, shiftedInput, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testPartialRecipientAddress() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        // Signed adequately.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures =
                produceSignatures(owners, MultiSignatureContract.MIN_THRESH, txMsg);

        // Input gets shifted to lose 1 byte of the recipient address.
        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);
        int end = input.length;
        byte[] shiftedInput = shiftLeftAtIndex(input, end);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, shiftedInput, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testPartialAmount() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        // Signed adequately.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures =
                produceSignatures(owners, MultiSignatureContract.MIN_THRESH, txMsg);

        // Input gets shifted to lose 1 byte of the amount.
        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);
        int nrgStart = input.length - AionAddress.LENGTH - Long.BYTES;
        byte[] shiftedInput = shiftLeftAtIndex(input, nrgStart);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, shiftedInput, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test
    public void testPartialNrgPrice() {
        List<ECKeyEd25519> owners = produceKeys(MultiSignatureContract.MIN_OWNERS);
        AionAddress caller = new AionAddress(owners.get(0).getAddress());
        AionAddress wallet =
                createMultiSigWallet(owners, MultiSignatureContract.MIN_OWNERS, DEFAULT_BALANCE);

        // Signed adequately.
        byte[] txMsg =
                MultiSignatureContract.constructMsg(
                        wallet, repo.getNonce(wallet), to, AMOUNT, NRG_PRICE);
        List<ISignature> signatures =
                produceSignatures(owners, MultiSignatureContract.MIN_THRESH, txMsg);

        // Input gets shifted to lose 1 byte of the energy price.
        byte[] input =
                MultiSignatureContract.constructSendTxInput(
                        wallet, signatures, AMOUNT, NRG_PRICE, to);
        int toStart = input.length - AionAddress.LENGTH;
        byte[] shiftedInput = shiftLeftAtIndex(input, toStart);

        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
        execute(caller, shiftedInput, NRG_LIMIT, PrecompiledResultCode.FAILURE, 0);
        checkAccountState(wallet, BigInteger.ZERO, DEFAULT_BALANCE);
        checkAccountState(to, BigInteger.ZERO, BigInteger.ZERO);
    }
}
