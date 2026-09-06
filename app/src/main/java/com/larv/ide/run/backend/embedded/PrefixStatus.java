package com.larv.ide.run.backend.embedded;

/** Install state of the embedded Termux bootstrap in private storage. */
public enum PrefixStatus {
    NOT_INSTALLED,
    INSTALLING,
    READY,
    ERROR
}
