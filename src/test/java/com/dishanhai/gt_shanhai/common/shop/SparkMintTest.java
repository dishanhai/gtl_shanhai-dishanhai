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
    void higherTiersDoubleSparkWhenFaceQuadruples() {
        assertEquals(256L, SparkMint.perCoin(257L));
        assertEquals(512L, SparkMint.perCoin(1024L));
        assertEquals(1024L, SparkMint.perCoin(4096L));
        assertEquals(2048L, SparkMint.perCoin(16384L));
        assertEquals(16384L, SparkMint.perCoin(1_048_576L));
        assertEquals(33_554_432L, SparkMint.perCoin(4_398_046_511_104L));
    }

    @Test
    void totalScalesByCoinCount() {
        assertEquals(BigInteger.valueOf(1536L), SparkMint.total(1024L, BigInteger.valueOf(3)));
        assertEquals(BigInteger.ZERO, SparkMint.total(1024L, BigInteger.ZERO));
        assertEquals(BigInteger.ZERO, SparkMint.total(0L, BigInteger.TEN));
    }
}
