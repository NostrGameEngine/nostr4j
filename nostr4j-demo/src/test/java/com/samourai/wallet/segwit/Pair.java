/**
 * Copyright https://github.com/SamouraiDev/bech32
 */
package com.samourai.wallet.segwit;

public class Pair<L, R> {

    private L l;
    private R r;

    private Pair() {}

    public static <L, R> Pair<L, R> of(L l, R r) {
        Pair pair = new Pair();
        pair.l = l;
        pair.r = r;
        return pair;
    }

    public R getRight() {
        return r;
    }

    public L getLeft() {
        return l;
    }
}
