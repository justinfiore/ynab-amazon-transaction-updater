package com.ynab.amazon.service

/** Compatibility entry point; wire contracts and fingerprints remain unchanged. */
class WalmartRemoteClassifier extends RemoteItemClassifier {
    WalmartRemoteClassifier(Map config, Closure transport = null) { super(config, transport) }
}
