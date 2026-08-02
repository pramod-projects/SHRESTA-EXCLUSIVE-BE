package com.shrestaexclusive.platform.mutation;

public class MutationSafetyUnavailableException extends RuntimeException {

    public MutationSafetyUnavailableException(Throwable cause) {
        super("Mutation safety infrastructure is unavailable", cause);
    }
}
