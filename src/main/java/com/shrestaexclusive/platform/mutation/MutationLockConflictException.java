package com.shrestaexclusive.platform.mutation;

public class MutationLockConflictException extends RuntimeException {

    public MutationLockConflictException(String lockKey) {
        super("Another mutation is already in progress for lock key: " + lockKey);
    }
}
