package org.ngengine.nostr4j.platform.jvm;

import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.ngengine.nostr4j.utils.NostrUtils;

// based on https://github.com/tcheeric/nostr-java/blob/main/nostr-java-crypto/src/main/java/nostr/crypto/schnorr/Schnorr.java#L19
// non thread-safe
class Point {
    // Curve parameters
    private final static BigInteger p = new BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    private final static BigInteger n = new BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    // Base point (generator)
    public final static Point G = new Point(
            new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
            new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16));

    private static final BigInteger BI_TWO = BigInteger.valueOf(2);
    private static final BigInteger BI_THREE = BigInteger.valueOf(3L);
    private static final BigInteger BI_SEVEN = BigInteger.valueOf(7L);
    private static final BigInteger P_MINUS_BI_TWO = p.subtract(BI_TWO);
    private static final BigInteger P_PLUS_ONE_DIV_FOUR = p.add(BigInteger.ONE).divide(BigInteger.valueOf(4L));
    private static final BigInteger P_MINUS_ONE_DIV_TWO = p.subtract(BigInteger.ONE).divide(BI_TWO);
    private static final byte[] ZEROES = new byte[32];

    // Point coordinates: [0] = x, [1] = y
    private final BigInteger[] coords;

    private byte[] cachedBytes;

    public Point(BigInteger x, BigInteger y) {
        coords = new BigInteger[] { x, y };
    }
 
    public static BigInteger getp() {
        return p;
    }

    public static BigInteger getn() {
        return n;
    }

    public static Point getG() {
        return G;
    }

    public BigInteger getX() {
        return coords[0];
    }

    public BigInteger getY() {
        return coords[1];
    }

  
    public boolean isInfinite() {
        return coords == null || coords[0] == null || coords[1] == null;
    }

    public static boolean isInfinite(Point P) {
        return P.isInfinite();
    }

    public Point add(Point P) {
        return add(this, P);
    }

    public static Point add(Point P1, Point P2) {
        if ((P1 != null && P2 != null && P1.isInfinite() && P2.isInfinite())) {
            return infinityPoint();
        }
        if (P1 == null || P1.isInfinite()) {
            return P2;
        }
        if (P2 == null || P2.isInfinite()) {
            return P1;
        }
        if (P1.getX().equals(P2.getX()) && !P1.getY().equals(P2.getY())) {
            return infinityPoint();
        }

        BigInteger lam;
        if (P1.getX().equals(P2.getX()) && P1.getY().equals(P2.getY())) {
            BigInteger base = P2.getY().multiply(BI_TWO);
            lam = (BI_THREE.multiply(P1.getX()).multiply(P1.getX())
                    .multiply(base.modPow(P_MINUS_BI_TWO, p))).mod(p);
        } else {
            BigInteger base = P2.getX().subtract(P1.getX());
            lam = ((P2.getY().subtract(P1.getY())).multiply(base.modPow(P_MINUS_BI_TWO, p))).mod(p);
        }

        BigInteger x3 = (lam.multiply(lam).subtract(P1.getX()).subtract(P2.getX())).mod(p);
        return new Point(x3, lam.multiply(P1.getX().subtract(x3)).subtract(P1.getY()).mod(p));
    }

    public static Point mul(Point P, BigInteger n) {
        Point R = null;

        for (int i = 0; i < 256; i++) {
            if (n.shiftRight(i).and(BigInteger.ONE).compareTo(BigInteger.ZERO) > 0) {
                R = add(R, P);
            }
            P = add(P, P);
        }

        return R;
    }

    public boolean hasEvenY() {
        return hasEvenY(this);
    }

    public static boolean hasEvenY(Point P) {
        return P.getY().mod(BI_TWO).compareTo(BigInteger.ZERO) == 0;
    }

    public static boolean isSquare(BigInteger x) {
        return x.modPow(P_MINUS_ONE_DIV_TWO, p).equals(BigInteger.ONE);
    }

    public boolean hasSquareY() {
        return hasSquareY(this);
    }

    public static boolean hasSquareY(Point P) {
        assert !isInfinite(P);
        return isSquare(P.getY());
    }

    public static byte[] taggedHash(String tag, byte[] msg) throws NoSuchAlgorithmException {
        byte[] tagHash = NostrUtils.getPlatform().sha256(tag.getBytes(StandardCharsets.UTF_8));
        int len = (tagHash.length * 2) + msg.length;
        byte[] buf = new byte[len];
        System.arraycopy(tagHash, 0, buf, 0, tagHash.length);
        System.arraycopy(tagHash, 0, buf, tagHash.length, tagHash.length);
        System.arraycopy(msg, 0, buf, tagHash.length * 2, msg.length);
        return NostrUtils.getPlatform().sha256(buf);
    }

    public byte[] toBytes() {
        if (isInfinite())  return ZEROES;
        if (cachedBytes == null) {
            cachedBytes = bytesFromPoint(this);
        }
        return cachedBytes;
    }

    public static byte[] bytesFromPoint(Point P) {
        return Util.bytesFromBigInteger(P.getX());
    }

    public static Point liftX(byte[] b) {
        BigInteger x = Util.bigIntFromBytes(b);
        if (x.compareTo(p) >= 0) {
            return null;
        }

        BigInteger y_sq = x.modPow(BigInteger.valueOf(3L), p).add(BI_SEVEN).mod(p);
        BigInteger y = y_sq.modPow(P_PLUS_ONE_DIV_FOUR, p);

        if (y.modPow(BI_TWO, p).compareTo(y_sq) != 0) {
            return null;
        } else {
            return new Point(x, y.and(BigInteger.ONE).compareTo(BigInteger.ZERO) == 0 ? y : p.subtract(y));
        }
    }

    public static Point infinityPoint() {
        return new Point(null, (BigInteger) null);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;

        Point other = (Point) obj;

        if (isInfinite() && other.isInfinite())
            return true;
        if (isInfinite() || other.isInfinite())
            return false;

        return getX().equals(other.getX()) && getY().equals(other.getY());
    }

    @Override
    public int hashCode() {
        if (isInfinite()) return 0;
        return 31 * getX().hashCode() + getY().hashCode();
    }
}