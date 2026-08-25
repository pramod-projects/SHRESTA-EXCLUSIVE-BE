package com.shrestaexclusive.platform.asset;

class MediaObjectNotFoundException extends RuntimeException {

    MediaObjectNotFoundException(Throwable cause) {
        super("Uploaded media object was not found", cause);
    }
}