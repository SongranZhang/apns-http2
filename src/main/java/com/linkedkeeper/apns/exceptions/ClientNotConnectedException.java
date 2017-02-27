package com.linkedkeeper.apns.exceptions;

/**
 * @author frank@linkedkeeper.com on 2016/12/29.
 */
public class ClientNotConnectedException extends IllegalStateException {

//    private static final long serialVersionUID = 1L;

    public ClientNotConnectedException() {
        super();
    }

    public ClientNotConnectedException(final String message) {
        super(message);
    }
}
