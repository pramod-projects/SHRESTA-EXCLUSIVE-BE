package com.shrestaexclusive.platform.admin.changes;

public class UnsupportedAdminChangeRequestException extends RuntimeException {

    public UnsupportedAdminChangeRequestException(String requestKey, String requestType, String action) {
        super("Unsupported admin change request " + requestKey + " with type " + requestType + " and action " + action);
    }
}
