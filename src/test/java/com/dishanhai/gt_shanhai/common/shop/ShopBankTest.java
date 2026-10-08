package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopBankTest {

    private static final long HOUR = ShopBank.MS_PER_HOUR;
    private static final long T0 = 1_700_000_000_000L;
    private static final BigInteger PRINCIPAL = BigInteger.valueOf(1_000_000L);

    @Test
    void partialHourDoesNotAccrueAndNinetyMinutesKeepsRemainder() {
        ShopBank.Book start = new ShopBank.Book(PRINCIPAL, BigInteger.ZERO, T0);

        ShopBank.Book under = ShopBank.settle(start, T0 + 59 * 60_000L, 15L);
        assertEquals(PRINCIPAL, under.principal);
        assertEquals(BigInteger.ZERO, under.interest);
        assertEquals(T0, under.lastMs);

        ShopBank.Book ninety = ShopBank.settle(start, T0 + 90 * 60_000L, 15L);
        assertEquals(PRINCIPAL, ninety.principal);
        assertEquals(BigInteger.valueOf(1500L), ninety.interest);
        assertEquals(T0 + HOUR, ninety.lastMs);

        ShopBank.Book still = ShopBank.settle(ninety, T0 + 90 * 60_000L, 15L);
        assertEquals(BigInteger.valueOf(1500L), still.interest);
        assertEquals(T0 + HOUR, still.lastMs);

        ShopBank.Book next = ShopBank.settle(ninety, T0 + 2 * HOUR, 15L);
        assertEquals(PRINCIPAL, next.principal);
        assertEquals(BigInteger.valueOf(3000L), next.interest);
        assertEquals(T0 + 2 * HOUR, next.lastMs);
    }

    @Test
    void secondHourDoesNotCompoundOntoInterest() {
        ShopBank.Book once = ShopBank.settle(new ShopBank.Book(PRINCIPAL, BigInteger.ZERO, T0), T0 + HOUR, 15L);
        ShopBank.Book twice = ShopBank.settle(once, T0 + 2 * HOUR, 15L);
        assertEquals(PRINCIPAL, twice.principal);
        assertEquals(BigInteger.valueOf(3000L), twice.interest);
    }

    @Test
    void zeroInterestHourStaysAnchoredUntilDustBecomesOne() {
        BigInteger small = BigInteger.valueOf(100L);
        ShopBank.Book start = new ShopBank.Book(small, BigInteger.ZERO, T0);
        ShopBank.Book oneHour = ShopBank.settle(start, T0 + HOUR, 5L);
        assertEquals(BigInteger.ZERO, oneHour.interest);
        assertEquals(T0, oneHour.lastMs);

        ShopBank.Book twenty = ShopBank.settle(start, T0 + 20 * HOUR, 5L);
        assertEquals(small, twenty.principal);
        assertEquals(BigInteger.ONE, twenty.interest);
        assertEquals(T0 + 20 * HOUR, twenty.lastMs);
    }

    @Test
    void repayAndWithdrawTakeInterestBeforePrincipal() {
        ShopBank.Book debt = new ShopBank.Book(BigInteger.valueOf(100L), BigInteger.valueOf(10L), T0);
        ShopBank.Move interestOnly = ShopBank.repay(debt, BigInteger.valueOf(1000L), BigInteger.valueOf(4L), T0, 15L);
        assertEquals(BigInteger.valueOf(4L), interestOnly.amount);
        assertEquals(BigInteger.valueOf(100L), interestOnly.book.principal);
        assertEquals(BigInteger.valueOf(6L), interestOnly.book.interest);

        ShopBank.Move intoPrincipal = ShopBank.repay(debt, BigInteger.valueOf(1000L), BigInteger.valueOf(15L), T0, 15L);
        assertEquals(BigInteger.valueOf(15L), intoPrincipal.amount);
        assertEquals(BigInteger.valueOf(95L), intoPrincipal.book.principal);
        assertEquals(BigInteger.ZERO, intoPrincipal.book.interest);

        ShopBank.Book deposit = new ShopBank.Book(BigInteger.valueOf(100L), BigInteger.valueOf(40L), T0);
        ShopBank.Move withdrawn = ShopBank.withdraw(deposit, BigInteger.valueOf(50L), T0, 5L);
        assertEquals(BigInteger.valueOf(50L), withdrawn.amount);
        assertEquals(BigInteger.valueOf(90L), withdrawn.book.principal);
        assertEquals(BigInteger.ZERO, withdrawn.book.interest);
    }

    @Test
    void borrowRoomIncludesInterestAndZeroRequestChangesNothing() {
        ShopBank.Book debt = new ShopBank.Book(BigInteger.valueOf(80L), BigInteger.valueOf(15L), T0);
        ShopBank.Move borrowed = ShopBank.borrow(debt, BigInteger.valueOf(50L), BigInteger.valueOf(100L), T0, 15L);
        assertEquals(BigInteger.valueOf(5L), borrowed.amount);
        assertEquals(BigInteger.valueOf(85L), borrowed.book.principal);
        assertEquals(BigInteger.valueOf(15L), borrowed.book.interest);

        ShopBank.Move blocked = ShopBank.borrow(debt, BigInteger.valueOf(10L), BigInteger.valueOf(95L), T0, 15L);
        assertEquals(BigInteger.ZERO, blocked.amount);
        assertEquals(debt.principal, blocked.book.principal);
        assertEquals(debt.interest, blocked.book.interest);

        ShopBank.Move none = ShopBank.repay(debt, BigInteger.ZERO, BigInteger.valueOf(10L), T0, 15L);
        assertEquals(BigInteger.ZERO, none.amount);
        assertEquals(debt.principal, none.book.principal);
    }

    @Test
    void depositKeepsExistingAnchor() {
        ShopBank.Book book = new ShopBank.Book(BigInteger.valueOf(1000L), BigInteger.ZERO, T0);
        ShopBank.Move added = ShopBank.deposit(book, BigInteger.valueOf(5000L), BigInteger.valueOf(500L), T0 + 30 * 60_000L, 5L);
        assertEquals(BigInteger.valueOf(500L), added.amount);
        assertEquals(BigInteger.valueOf(1500L), added.book.principal);
        assertEquals(T0, added.book.lastMs);
    }

    @Test
    void oldDebtTagWithoutInterestLoadsPrincipalOnly() {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("bankDebt", BigInteger.valueOf(12345L).toByteArray());
        tag.putLong("bankDebtMs", 50L);
        tag.putByteArray("bankDeposit", BigInteger.valueOf(80L).toByteArray());
        tag.putLong("bankDepositMs", 60L);

        WalletAccount loaded = WalletAccount.load(tag);
        assertEquals(BigInteger.valueOf(12345L), loaded.getBankDebt());
        assertEquals(BigInteger.ZERO, loaded.getBankDebtInterest());
        assertEquals(50L, loaded.getBankDebtLastMs());
        assertEquals(BigInteger.valueOf(80L), loaded.getBankDeposit());
        assertEquals(BigInteger.ZERO, loaded.getBankDepositInterest());

        loaded.setBankDebtInterest(BigInteger.valueOf(7L));
        loaded.setBankDepositInterest(BigInteger.valueOf(3L));
        WalletAccount roundTrip = WalletAccount.load(loaded.save());
        assertEquals(BigInteger.valueOf(7L), roundTrip.getBankDebtInterest());
        assertEquals(BigInteger.valueOf(3L), roundTrip.getBankDepositInterest());
        assertEquals(BigInteger.valueOf(12345L), roundTrip.getBankDebt());
        assertEquals(BigInteger.valueOf(80L), roundTrip.getBankDeposit());
    }
}
