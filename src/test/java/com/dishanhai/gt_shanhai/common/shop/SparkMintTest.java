package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SparkMintTest {

    @Test
    void earlyCoinsKeepFaceValue() {
        assertEquals(0L, SparkMint.perCoin(0L));
        assertEquals(0L, SparkMint.perCoin(-1L));
        assertEquals(1L, SparkMint.perCoin(1L));
        assertEquals(4L, SparkMint.perCoin(4L));
        assertEquals(16L, SparkMint.perCoin(16L));
        assertEquals(64L, SparkMint.perCoin(64L));
        assertEquals(256L, SparkMint.perCoin(256L));
    }

    @Test
    void higherTiersCompoundTheDiscountWithAFiftyPercentFloor() {
        assertEquals(257L, SparkMint.perCoin(257L));
        assertEquals(921L, SparkMint.perCoin(1024L));
        assertEquals(3317L, SparkMint.perCoin(4096L));
        assertEquals(11796L, SparkMint.perCoin(16384L));
        assertEquals(534_773L, SparkMint.perCoin(1_048_576L));
        assertEquals(2_199_023_255_552L, SparkMint.perCoin(4_398_046_511_104L));
    }

    @Test
    void totalScalesByCoinCount() {
        assertEquals(BigInteger.valueOf(2763L), SparkMint.total(1024L, BigInteger.valueOf(3)));
        assertEquals(BigInteger.ZERO, SparkMint.total(1024L, BigInteger.ZERO));
        assertEquals(BigInteger.ZERO, SparkMint.total(0L, BigInteger.TEN));
    }
}
