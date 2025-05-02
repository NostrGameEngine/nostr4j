/**
 * BSD 3-Clause License
 * 
 * Copyright (c) 2025, Riccardo Balbo
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.ngengine.nostr4j.platform.jvm;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.ngengine.nostr4j.utils.NostrUtils;

final class Point {

    private static final BigInteger p = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    private static final BigInteger n = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    public static final Point G = new Point(
        new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
        false
    );

    private static final BigInteger BI_TWO = BigInteger.valueOf(2);
    private static final BigInteger BI_THREE = BigInteger.valueOf(3L);
    private static final BigInteger BI_SEVEN = BigInteger.valueOf(7L);
    private static final BigInteger P_PLUS_ONE_DIV_FOUR = p.add(BigInteger.ONE).divide(BigInteger.valueOf(4L));
    private static final BigInteger P_MINUS_ONE_DIV_TWO = p.subtract(BigInteger.ONE).divide(BI_TWO);
    private static final byte[] ZEROES = new byte[32];

    private final BigInteger[] coords;
    private byte[] cachedBytes;
    private static final Point INFINITY = new Point(null, null, false);

    public Point(BigInteger x, BigInteger y) {
        this(x, y, true);
    }

    private Point(BigInteger x, BigInteger y, boolean verify) {
        this.coords = new BigInteger[] { x, y };

        // Don't validate infinity point or special pre-computed points
        if (verify) {
            validateOnCurve(x, y);
        }
    }

    // Validate that the point lies on the curve y² = x³ + 7 (mod p)
    private void validateOnCurve(BigInteger x, BigInteger y) {
        // Check x and y are in the valid range [0, p-1]
        if (
            x.compareTo(BigInteger.ZERO) < 0 || x.compareTo(p) >= 0 || y.compareTo(BigInteger.ZERO) < 0 || y.compareTo(p) >= 0
        ) {
            throw new IllegalArgumentException("Point coordinates outside valid range");
        }

        // Calculate left side: y²
        BigInteger left = y.multiply(y).mod(p);

        // Calculate right side: x³ + 7
        BigInteger right = x.modPow(BI_THREE, p).add(BI_SEVEN).mod(p);

        // Verify the equation
        if (!left.equals(right)) {
            throw new IllegalArgumentException("Point is not on the curve");
        }
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

    // A point is infinite if either coordinate is null.
    public boolean isInfinite() {
        return getX() == null || getY() == null;
    }

    public static boolean isInfinite(Point P) {
        return P == null || P.isInfinite();
    }

    public Point add(Point P) {
        return add(this, P);
    }

    public static Point add(Point P1, Point P2) {
        if ((P1 == null || P1.isInfinite()) && (P2 == null || P2.isInfinite())) return INFINITY;
        if (P1 == null || P1.isInfinite()) return P2;
        if (P2 == null || P2.isInfinite()) return P1;
        JacobianPoint J1 = P1.toJacobian();
        JacobianPoint J2 = P2.toJacobian();
        return JacobianPoint.add(J1, J2).toAffine();
    }

    public static Point mul(Point P, BigInteger k) {
        if (P == null || P.isInfinite()) return INFINITY;
        JacobianPoint J = P.toJacobian();
        return JacobianPoint.wNAFScalarMul(J, k).toAffine();
    }

    public boolean hasEvenY() {
        return hasEvenY(this);
    }

    public static boolean hasEvenY(Point P) {
        return P.getY().mod(BI_TWO).equals(BigInteger.ZERO);
    }

    public static boolean isSquare(BigInteger x) {
        return x.modPow(P_MINUS_ONE_DIV_TWO, p).equals(BigInteger.ONE);
    }

    public boolean hasSquareY() {
        return hasSquareY(this);
    }

    public static boolean hasSquareY(Point P) {
        if (isInfinite(P)) {
            throw new IllegalArgumentException("Cannot test square property of infinity point");
        }
        return isSquare(P.getY());
    }

    public static byte[] taggedHash(String tag, byte[] msg) {
        byte[] tagHash = NostrUtils.getPlatform().sha256(tag.getBytes(StandardCharsets.UTF_8));
        int len = (tagHash.length * 2) + msg.length;
        byte[] buf = new byte[len];
        System.arraycopy(tagHash, 0, buf, 0, tagHash.length);
        System.arraycopy(tagHash, 0, buf, tagHash.length, tagHash.length);
        System.arraycopy(msg, 0, buf, tagHash.length * 2, msg.length);
        return NostrUtils.getPlatform().sha256(buf);
    }

    public byte[] toBytes() {
        if (isInfinite()) return ZEROES;
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
        if (x.compareTo(p) >= 0) return null;
        BigInteger y_sq = x.modPow(BI_THREE, p).add(BI_SEVEN).mod(p);
        BigInteger y = y_sq.modPow(P_PLUS_ONE_DIV_FOUR, p);
        if (!y.modPow(BI_TWO, p).equals(y_sq)) return null; else return new Point(x, (y.testBit(0)) ? p.subtract(y) : y);
    }

    public static Point infinityPoint() {
        return INFINITY;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Point other = (Point) obj;
        if (this.isInfinite() && other.isInfinite()) return true;
        if (this.isInfinite() || other.isInfinite()) return false;
        return getX().equals(other.getX()) && getY().equals(other.getY());
    }

    @Override
    public int hashCode() {
        if (isInfinite()) return 0;
        return 31 * getX().hashCode() + getY().hashCode();
    }

    /**
     * Optimized Schnorr verification using Jacobian arithmetic.
     * Computes: R = s * G + (-e mod n) * P.
     */
    public static Point schnorrVerify(BigInteger s, Point P, BigInteger e) {
        BigInteger t = n.subtract(e).mod(n);
        JacobianPoint JG = G.toJacobian();
        JacobianPoint JP = P.toJacobian();
        JacobianPoint R = JacobianPoint.doubleScalarWNAF(JG, s, JP, t);
        return R.toAffine();
    }

    /**
     * Convert this affine point to Jacobian coordinates.
     * For an affine point (x, y) (non-infinite), the Jacobian representation is (x,
     * y, 1).
     */
    private JacobianPoint toJacobian() {
        return isInfinite()
            ? new JacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO)
            : new JacobianPoint(getX(), getY(), BigInteger.ONE);
    }

    /**
     * Inner class for points in Jacobian coordinates.
     * A point (X, Y, Z) in Jacobian represents the affine point (X/Z^2, Y/Z^3).
     * The representation avoids repeated modular inversions.
     */
    private static class JacobianPoint {

        private final BigInteger X, Y, Z;
        private static final int WINDOW_SIZE = 4;
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
            if (isInfinity()) return infinityPoint();
            BigInteger zInv = Z.modInverse(p);
            BigInteger zInv2 = zInv.multiply(zInv).mod(p);
            BigInteger xAff = X.multiply(zInv2).mod(p);
            BigInteger zInv3 = zInv2.multiply(zInv).mod(p);
            BigInteger yAff = Y.multiply(zInv3).mod(p);
            return new Point(xAff, yAff);
        }

        /**
         * Point doubling in Jacobian coordinates.
         */
        public JacobianPoint doublePoint() {
            if (isInfinity()) return this;
            BigInteger Y2 = Y.multiply(Y).mod(p);
            BigInteger S = X.multiply(Y2.shiftLeft(2)).mod(p);
            BigInteger M = X.multiply(X).multiply(BI_THREE).mod(p);
            BigInteger X3 = M.multiply(M).subtract(S.shiftLeft(1)).mod(p);
            BigInteger Y3 = M.multiply(S.subtract(X3)).subtract(Y2.multiply(Y2).shiftLeft(3)).mod(p);
            BigInteger Z3 = Y.shiftLeft(1).multiply(Z).mod(p);
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
            if (P.isInfinity()) return Q;
            if (Q.isInfinity()) return P;
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
                return S1.equals(S2) ? P.doublePoint() : new JacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO);
            }
            BigInteger H = U2.subtract(U1).mod(p);
            BigInteger R = S2.subtract(S1).mod(p);
            BigInteger H2 = H.multiply(H).mod(p);
            BigInteger H3 = H2.multiply(H).mod(p);
            BigInteger U1H2 = U1.multiply(H2).mod(p);
            BigInteger X3 = R.multiply(R).subtract(H3).subtract(U1H2.shiftLeft(1)).mod(p);
            BigInteger Y3 = R.multiply(U1H2.subtract(X3)).subtract(S1.multiply(H3)).mod(p);
            BigInteger Z3 = P.Z.multiply(Q.Z).multiply(H).mod(p);
            return new JacobianPoint(X3, Y3, Z3);
        }

        /**
         * Compute the wNAF (windowed non-adjacent form) representation of a scalar.
         */
        private static int[] computeWNAF(BigInteger k, int width) {
            int maxSize = k.bitLength() + 1;
            int[] buffer = new int[maxSize];
            int pos = 0;
            BigInteger tmp = k;
            BigInteger twoPowW = BigInteger.ONE.shiftLeft(width);
            BigInteger twoPowWMinus1 = BigInteger.ONE.shiftLeft(width - 1);
            while (!tmp.equals(BigInteger.ZERO)) {
                if (tmp.testBit(0)) {
                    BigInteger mod = tmp.mod(twoPowW);
                    int digit = mod.intValue();
                    if (BigInteger.valueOf(digit).compareTo(twoPowWMinus1) >= 0) {
                        digit -= (1 << width);
                    }
                    buffer[pos++] = digit;
                    tmp = tmp.subtract(BigInteger.valueOf(digit));
                } else {
                    buffer[pos++] = 0;
                }
                tmp = tmp.shiftRight(1);
            }
            int[] result = new int[pos];
            System.arraycopy(buffer, 0, result, 0, pos);
            return result;
        }

        /**
         * Single-scalar multiplication using the wNAF method.
         * If the point equals the fixed base point G, use a precomputed table.
         */
        public static JacobianPoint wNAFScalarMul(JacobianPoint P, BigInteger k) {
            if (P.equals(G.toJacobian())) return wNAFScalarMulPrecomputed(k);
            int[] wnaf = computeWNAF(k, WINDOW_SIZE);
            int tableSize = 1 << (WINDOW_SIZE - 1);
            JacobianPoint[] table = new JacobianPoint[tableSize];
            table[0] = P;
            JacobianPoint twoP = P.doublePoint();
            for (int i = 1; i < tableSize; i++) {
                table[i] = add(table[i - 1], twoP);
            }
            JacobianPoint R = new JacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO);
            for (int i = wnaf.length - 1; i >= 0; i--) {
                R = R.doublePoint();
                int digit = wnaf[i];
                if (digit != 0) {
                    int index = Math.abs(digit) / 2;
                    R = digit > 0 ? add(R, table[index]) : add(R, table[index].negate());
                }
            }
            return R;
        }

        /**
         * Single-scalar multiplication using a precomputed table for G.
         */
        private static JacobianPoint wNAFScalarMulPrecomputed(BigInteger k) {
            int[] wnaf = computeWNAF(k, WINDOW_SIZE);
            JacobianPoint R = new JacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO);
            for (int i = wnaf.length - 1; i >= 0; i--) {
                R = R.doublePoint();
                int digit = wnaf[i];
                if (digit != 0) {
                    int index = Math.abs(digit) / 2;
                    R = digit > 0 ? add(R, precomputedG[index]) : add(R, precomputedG[index].negate());
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
            int[] padS = new int[len];
            int[] padT = new int[len];
            for (int i = 0; i < len; i++) {
                padS[i] = i < wnafS.length ? wnafS[i] : 0;
                padT[i] = i < wnafT.length ? wnafT[i] : 0;
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
            JacobianPoint R = new JacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO);
            for (int i = len - 1; i >= 0; i--) {
                R = R.doublePoint();
                int dS = padS[i];
                int dQ = padT[i];
                if (dS != 0) {
                    int idx = Math.abs(dS) / 2;
                    R = dS > 0 ? add(R, tableP[idx]) : add(R, tableP[idx].negate());
                }
                if (dQ != 0) {
                    int idx = Math.abs(dQ) / 2;
                    R = dQ > 0 ? add(R, tableQ[idx]) : add(R, tableQ[idx].negate());
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
            table[0] = G.toJacobian();
            JacobianPoint twoG = table[0].doublePoint();
            for (int i = 1; i < tableSize; i++) {
                table[i] = add(table[i - 1], twoG);
            }
            return table;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            JacobianPoint other = (JacobianPoint) obj;
            if (this.isInfinity() && other.isInfinity()) return true;
            if (this.isInfinity() || other.isInfinity()) return false;
            return (this.X.equals(other.X) && this.Y.equals(other.Y) && this.Z.equals(other.Z));
        }

        @Override
        public int hashCode() {
            if (isInfinity()) return 0;
            int result = X.hashCode();
            result = 31 * result + Y.hashCode();
            result = 31 * result + Z.hashCode();
            return result;
        }
    }
}
