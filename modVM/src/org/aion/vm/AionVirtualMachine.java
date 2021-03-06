package org.aion.vm;

import java.util.concurrent.locks.ReentrantLock;
import org.aion.avm.core.AvmConfiguration;
import org.aion.avm.core.AvmImpl;
import org.aion.avm.core.CommonAvmFactory;
import org.aion.interfaces.tx.Transaction;
import org.aion.vm.api.interfaces.KernelInterface;
import org.aion.vm.api.interfaces.SimpleFuture;
import org.aion.vm.api.interfaces.TransactionResult;

/**
 * A thread-safe access-point to the Aion Virtual Machine.
 */
public final class AionVirtualMachine {
    private final ReentrantLock avmLock = new ReentrantLock();
    private final AvmImpl avm;

    private AionVirtualMachine(AvmImpl avm) {
        if (avm == null) {
            throw new IllegalStateException("Cannot initialize AionVirtualMachine with a null avm!");
        }
        this.avm = avm;
    }

    /**
     * Constructs a new Avm instance and starts it up.
     *
     * @return A new AVM.
     */
    public static AionVirtualMachine createAndInitializeNewAvm() {
        return new AionVirtualMachine(CommonAvmFactory.buildAvmInstanceForConfiguration(new AionCapabilities(), new AvmConfiguration()));
    }

    /**
     * Executes the given transactions.
     *
     * This method can only be invoked by the owner of the avm lock! That is, the caller must first
     * have called {@code acquireAvmLock()} first.
     *
     * @param kernelInterface The interface into the kernel.
     * @param transactions The transactions to execute.
     * @return The future results.
     */
    public SimpleFuture<TransactionResult>[] run(KernelInterface kernelInterface, Transaction[] transactions) {
        if (this.avmLock.isHeldByCurrentThread()) {
            return this.avm.run(kernelInterface, transactions);
        } else {
            throw new IllegalMonitorStateException("The current thread does not own the avm lock!");
        }
    }

    /**
     * Shuts down the AVM. This object can no longer be used once this method is called.
     *
     * This method can only be invoked by the owner of the avm lock! That is, the caller must first
     * have called {@code acquireAvmLock()} first.
     */
    public void shutdown() {
        if (this.avmLock.isHeldByCurrentThread()) {
            this.avm.shutdown();
        } else {
            throw new IllegalMonitorStateException("The current thread does not own the avm lock!");
        }
    }

    /**
     * Acquires the lock.
     *
     * If another thread currently holds the lock, then this method blocks until the current thread
     * is the owner of the lock and then returns.
     *
     * It is the responsibility of the caller to release the lock once they are done with it.
     */
    public void acquireAvmLock() {
        this.avmLock.lock();
    }

    /**
     * Releases the lock.
     *
     * If the caller does not currently own the lock, this method returns immediately without doing
     * anything.
     */
    public void releaseAvmLock() {
        if (this.avmLock.isHeldByCurrentThread()) {
            this.avmLock.unlock();
        }
    }
}
