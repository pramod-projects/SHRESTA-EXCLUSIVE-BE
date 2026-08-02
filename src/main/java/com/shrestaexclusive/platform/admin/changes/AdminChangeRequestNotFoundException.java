package com.shrestaexclusive.platform.admin.changes;

public class AdminChangeRequestNotFoundException extends RuntimeException {

    public AdminChangeRequestNotFoundException(String requestKey) {
        super("Admin change request not found: " + requestKey);
    }
}
