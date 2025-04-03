package org.ngengine.nostr4j.platform.jvm;

import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Optimized point addition.
     * Converts points to Jacobian coordinates, adds them, and converts back.
     */
    public Point add(Point P) {
        return add(this, P);
    }


    public static Point add(Point P1, Point P2) {
        if (P1 != null && P2 != null && P1.isInfinite() && P2.isInfinite()) {
            return infinityPoint();
        }
        if (P1 == null || P1.isInfinite()) {
            return P2;
        }
        if (P2 == null || P2.isInfinite()) {
            return P1;
        }
        // Use Jacobian addition for improved performance.
        JacobianPoint J1 = P1.toJacobian();
        JacobianPoint J2 = P2.toJacobian();
        JacobianPoint J3 = JacobianPoint.add(J1, J2);
        return J3.toAffine();
    }

    /**
     * Optimized scalar multiplication.
     * Converts the point to Jacobian coordinates, uses wNAF-based multiplication,
     * and then converts the result back to affine.
     */
    public static Point mul(Point P, BigInteger k) {
        if (P == null || P.isInfinite()) {
            return infinityPoint();
        }
        JacobianPoint J = P.toJacobian();
        JacobianPoint R = JacobianPoint.wNAFScalarMul(J, k);
        return R.toAffine();
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
        if (isInfinite())
            return ZEROES;
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
        if (isInfinite())
            return 0;
        return 31 * getX().hashCode() + getY().hashCode();
    }

    /**
     * Optimized Schnorr verification using Jacobian arithmetic.
     * Computes: R = s * G + (-e mod n) * P.
     */
    public static Point schnorrVerify(BigInteger s, Point P, BigInteger e) {
        // Compute -e mod n.
        BigInteger t = n.subtract(e).mod(n);
        JacobianPoint JG = G.toJacobian();
        JacobianPoint JP = P.toJacobian();
        // Use the simultaneous wNAF double-scalar multiplication.
        JacobianPoint R = JacobianPoint.doubleScalarWNAF(JG, s, JP, t);
        return R.toAffine();
    }

    // --------------------- Internal Jacobian Arithmetic ---------------------
    // These methods and inner class implement the optimized arithmetic in Jacobian
    // coordinates.

    /**
     * Convert this affine point to Jacobian coordinates.
     * For an affine point (x, y) (non-infinite), the Jacobian representation is (x,
     * y, 1).
     */
    private JacobianPoint toJacobian() {
        if (isInfinite()) {
            return new JacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO);
        }
        return new JacobianPoint(getX(), getY(), BigInteger.ONE);
    }

    /**
     * Inner class for points in Jacobian coordinates.
     * A point (X, Y, Z) in Jacobian represents the affine point (X/Z^2, Y/Z^3).
     * The representation avoids repeated modular inversions.
     */
    private static class JacobianPoint {
        private final BigInteger X;
        private final BigInteger Y;
        private final BigInteger Z;
        // Window size for wNAF multiplication.
        private final static int WINDOW_SIZE = 4;
        // Toggle debug output if needed.
        private final static boolean DEBUG = false;
        // Precomputed table for the fixed base point G.
        private static final JacobianPoint[] precomputedG = precomputeG();

        public JacobianPoint(BigInteger X, BigInteger Y, BigInteger Z) {
            this.X = X;
            this.Y = Y;
            this.Z = Z;
        }

        public boolean isInfinity() {
            return Z.equals(BigInteger.ZERO);
        }

        /**
         * Convert this Jacobian point to an affine Point.
         * This requires one modular inversion.
         */
        public Point toAffine() {
            if (isInfinity()) {
                return infinityPoint();
            }
            long start = System.nanoTime();
            BigInteger zInv = Z.modInverse(p);
            BigInteger zInv2 = zInv.multiply(zInv).mod(p);
            BigInteger xAff = X.multiply(zInv2).mod(p);
            BigInteger zInv3 = zInv2.multiply(zInv).mod(p);
            BigInteger yAff = Y.multiply(zInv3).mod(p);
            if (DEBUG) {
                System.out.println("Conversion to affine took " + (System.nanoTime() - start) + " ns");
            }
            return new Point(xAff, yAff);
        }

        /**
         * Point doubling in Jacobian coordinates.
         */
        public JacobianPoint doublePoint() {
            long start = System.nanoTime();
            if (isInfinity())
                return this;
            BigInteger Y2 = Y.multiply(Y).mod(p); // Y^2
            BigInteger S = X.multiply(Y2.shiftLeft(2)).mod(p); // S = 4*X*Y^2
            BigInteger M = X.multiply(X).multiply(BI_THREE).mod(p); // M = 3*X^2
            BigInteger X3 = M.multiply(M).subtract(S.shiftLeft(1)).mod(p); // X3 = M^2 - 2*S
            BigInteger Y4 = Y2.multiply(Y2).mod(p); // Y^4
            BigInteger Y3 = M.multiply(S.subtract(X3)).subtract(Y4.shiftLeft(3)).mod(p); // Y3 = M*(S - X3) - 8*Y^4
            BigInteger Z3 = Y.shiftLeft(1).multiply(Z).mod(p); // Z3 = 2*Y*Z
            if (DEBUG) {
                System.out.println("Jacobian doublePoint took " + (System.nanoTime() - start) + " ns");
            }
            return new JacobianPoint(X3, Y3, Z3);
        }

        /**
         * Return the negation of this point.
         * In Jacobian, -P = (X, -Y mod p, Z).
         */
        public JacobianPoint negate() {
            return new JacobianPoint(X, Y.negate().mod(p), Z);
        }

        /**
         * Point addition in Jacobian coordinates.
         */
        public static JacobianPoint add(JacobianPoint P, JacobianPoint Q) {
            long start = System.nanoTime();
            if (P.isInfinity())
                return Q;
            if (Q.isInfinity())
                return P;
            // U1 = X1 * Z2^2, U2 = X2 * Z1^2
            BigInteger Z1Sq = P.Z.multiply(P.Z).mod(p);
            BigInteger Z2Sq = Q.Z.multiply(Q.Z).mod(p);
            BigInteger U1 = P.X.multiply(Z2Sq).mod(p);
            BigInteger U2 = Q.X.multiply(Z1Sq).mod(p);
            // S1 = Y1 * Z2^3, S2 = Y2 * Z1^3
            BigInteger Z1Cu = Z1Sq.multiply(P.Z).mod(p);
            BigInteger Z2Cu = Z2Sq.multiply(Q.Z).mod(p);
            BigInteger S1 = P.Y.multiply(Z2Cu).mod(p);
            BigInteger S2 = Q.Y.multiply(Z1Cu).mod(p);
            if (U1.equals(U2)) {
                if (S1.equals(S2)) {
                    if (DEBUG)
                        System.out.println("Jacobian add detected doubling");
                    return P.doublePoint();
                } else {
                    // Result is the point at infinity.
                    return new JacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO);
                }
            }
            BigInteger H = U2.subtract(U1).mod(p);
            BigInteger R = S2.subtract(S1).mod(p);
            BigInteger H2 = H.multiply(H).mod(p);
            BigInteger H3 = H2.multiply(H).mod(p);
            BigInteger U1H2 = U1.multiply(H2).mod(p);
            BigInteger X3 = R.multiply(R).subtract(H3).subtract(U1H2.shiftLeft(1)).mod(p);
            BigInteger Y3 = R.multiply(U1H2.subtract(X3)).subtract(S1.multiply(H3)).mod(p);
            BigInteger Z3 = P.Z.multiply(Q.Z).multiply(H).mod(p);
            if (DEBUG) {
                System.out.println("Jacobian add took " + (System.nanoTime() - start) + " ns");
            }
            return new JacobianPoint(X3, Y3, Z3);
        }

        /**
         * Compute the wNAF (windowed non-adjacent form) representation of a scalar.
         */
        private static int[] computeWNAF(BigInteger k, int width) {
            List<Integer> wnaf = new ArrayList<>();
            BigInteger _k = k;
            BigInteger twoPowW = BigInteger.valueOf(1 << width);
            BigInteger twoPowWMinus1 = BigInteger.valueOf(1 << (width - 1));
            while (_k.compareTo(BigInteger.ZERO) > 0) {
                if (_k.testBit(0)) {
                    BigInteger mod = _k.mod(twoPowW);
                    int digit = mod.intValue();
                    if (digit >= twoPowWMinus1.intValue()) {
                        digit = digit - (1 << width);
                    }
                    wnaf.add(digit);
                    _k = _k.subtract(BigInteger.valueOf(digit));
                } else {
                    wnaf.add(0);
                }
                _k = _k.shiftRight(1);
            }
            int[] result = new int[wnaf.size()];
            for (int i = 0; i < wnaf.size(); i++) {
                result[i] = wnaf.get(i);
            }
            return result;
        }

        /**
         * Single-scalar multiplication using the wNAF method.
         * If the point equals the fixed base point G, use a precomputed table.
         */
        public static JacobianPoint wNAFScalarMul(JacobianPoint P, BigInteger k) {
            // If P equals G, use the precomputed table.
            if (P.equals(G.toJacobian())) {
                return wNAFScalarMulPrecomputed(k);
            }
            int[] wnaf = computeWNAF(k, WINDOW_SIZE);
            int tableSize = 1 << (WINDOW_SIZE - 1);
            JacobianPoint[] table = new JacobianPoint[tableSize];
            table[0] = P;
            JacobianPoint twoP = P.doublePoint();
            for (int i = 1; i < tableSize; i++) {
                table[i] = add(table[i - 1], twoP);
            }
            JacobianPoint R = new JacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO); // infinity
            for (int i = wnaf.length - 1; i >= 0; i--) {
                R = R.doublePoint();
                int digit = wnaf[i];
                if (digit != 0) {
                    int index = Math.abs(digit) / 2;
                    if (digit > 0) {
                        R = add(R, table[index]);
                    } else {
                        R = add(R, table[index].negate());
                    }
                }
            }
            return R;
        }

        /**
         * Single-scalar multiplication using a precomputed table for G.
         */
        private static JacobianPoint wNAFScalarMulPrecomputed(BigInteger k) {
            int[] wnaf = computeWNAF(k, WINDOW_SIZE);
            JacobianPoint R = new JacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO); // infinity
            for (int i = wnaf.length - 1; i >= 0; i--) {
                R = R.doublePoint();
                int digit = wnaf[i];
                if (digit != 0) {
                    int index = Math.abs(digit) / 2;
                    if (digit > 0) {
                        R = add(R, precomputedG[index]);
                    } else {
                        R = add(R, precomputedG[index].negate());
                    }
                }
            }
            return R;
        }

        /**
         * Double-scalar multiplication using the wNAF method.
         * Computes R = s * P + t * Q.
         */
        public static JacobianPoint doubleScalarWNAF(JacobianPoint P, BigInteger s, JacobianPoint Q, BigInteger t) {
            int[] wnafS = computeWNAF(s, WINDOW_SIZE);
            int[] wnafT = computeWNAF(t, WINDOW_SIZE);
            int len = Math.max(wnafS.length, wnafT.length);
            int[] wnafS_pad = new int[len];
            int[] wnafT_pad = new int[len];
            for (int i = 0; i < len; i++) {
                wnafS_pad[i] = (i < wnafS.length) ? wnafS[i] : 0;
                wnafT_pad[i] = (i < wnafT.length) ? wnafT[i] : 0;
            }
            int tableSize = 1 << (WINDOW_SIZE - 1);
            JacobianPoint[] tableP = new JacobianPoint[tableSize];
            JacobianPoint[] tableQ = new JacobianPoint[tableSize];
            tableP[0] = P;
            tableQ[0] = Q;
            JacobianPoint twoP = P.doublePoint();
            JacobianPoint twoQ = Q.doublePoint();
            for (int i = 1; i < tableSize; i++) {
                tableP[i] = add(tableP[i - 1], twoP);
                tableQ[i] = add(tableQ[i - 1], twoQ);
            }
            JacobianPoint R = new JacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO); // infinity
            for (int i = len - 1; i >= 0; i--) {
                R = R.doublePoint();
                int digitS = wnafS_pad[i];
                int digitT = wnafT_pad[i];
                if (digitS != 0) {
                    int index = Math.abs(digitS) / 2;
                    if (digitS > 0) {
                        R = add(R, tableP[index]);
                    } else {
                        R = add(R, tableP[index].negate());
                    }
                }
                if (digitT != 0) {
                    int index = Math.abs(digitT) / 2;
                    if (digitT > 0) {
                        R = add(R, tableQ[index]);
                    } else {
                        R = add(R, tableQ[index].negate());
                    }
                }
            }
            return R;
        }

        /**
         * Precompute the table for the fixed base point G.
         */
        private static JacobianPoint[] precomputeG() {
            int tableSize = 1 << (WINDOW_SIZE - 1);
            JacobianPoint[] table = new JacobianPoint[tableSize];
            // Use the Jacobian representation of G.
            table[0] = G.toJacobian();
            JacobianPoint twoG = table[0].doublePoint();
            for (int i = 1; i < tableSize; i++) {
                table[i] = add(table[i - 1], twoG);
            }
            return table;
        }
    }
}
