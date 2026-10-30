package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopBankTest {

    private static final long HOUR = ShopBank.MS_PER_HOUR;
    private static final long T0 = 1_700_000_000_000L;
    private static final BigInteger PRINCIPAL = BigInteger.valueOf(1_000_000L);

    @Test
    void loansRequireAnActualVoltageStageSubmission() {
        assertEquals(0L, ShopBank.loanCap(100_000_000L, "杂货/正常", Set.of(), Set.of()));
        assertEquals(0L, ShopBank.loanCap(100_000_000L, "杂货/正常",
                Set.of("stage:其他/hv", "entry:杂货/正常/max", "stage:杂货/正常"), Set.of()));
    }

    @Test
    void highestSubmittedVoltageRaisesCreditByFourPerTier() {
        String[] tiers = {"ulv", "lv", "mv", "hv", "ev", "iv", "luv", "zpm", "uv", "uhv",
                "uev", "uiv", "uxv", "opv", "max"};
        long expected = 1000L;
        for (String tier : tiers) {
            assertEquals(expected, ShopBank.loanCap(Long.MAX_VALUE, "自定义/电压",
                    Set.of("stage:自定义/电压/" + tier), Set.of()));
            expected *= 4L;
        }
        assertEquals(64_000L, ShopBank.loanCap(100_000_000L, "杂货/正常",
                Set.of("stage:杂货/正常/ulv", "stage:杂货/正常/hv"), Set.of()));
    }

    @Test
    void submittedChildDoesNotBypassConfiguredParentRequirements() {
        Set<String> requirements = Set.of("杂货", "杂货/正常", "杂货/正常/hv", "其他/max");
        assertEquals(0L, ShopBank.loanCap(100_000_000L, "杂货/正常",
                Set.of("stage:杂货/正常/hv"), requirements));
        assertEquals(1000L, ShopBank.loanCap(100_000_000L, "杂货/正常",
                Set.of("stage:杂货", "stage:杂货/正常", "stage:杂货/正常/ulv"), requirements));
        assertEquals(64_000L, ShopBank.loanCap(100_000_000L, "杂货/正常",
                Set.of("stage:杂货", "stage:杂货/正常", "stage:杂货/正常/hv"), requirements));
    }

    @Test
    void stageCreditAlsoHonorsTheServerCeiling() {
        Set<String> unlocked = Set.of("stage:杂货/正常/max");
        assertEquals(100_000_000L, ShopBank.loanCap(100_000_000L, "杂货/正常", unlocked, Set.of()));
        assertEquals(500L, ShopBank.loanCap(500L, "杂货/正常", unlocked, Set.of()));
        assertEquals(0L, ShopBank.loanCap(0L, "杂货/正常", unlocked, Set.of()));
        assertEquals(0L, ShopBank.loanCap(-1L, "杂货/正常", unlocked, Set.of()));
    }

    @Test
    void repeatedBorrowingCannotExceedStageCreditAndExistingDebtIsPreserved() {
        BigInteger cap = BigInteger.valueOf(ShopBank.loanCap(100_000_000L, "杂货/正常",
                Set.of("stage:杂货/正常/ulv"), Set.of()));
        ShopBank.Move first = ShopBank.borrow(null, BigInteger.valueOf(100_000_000L), cap, T0, 15L);
        assertEquals(BigInteger.valueOf(1000L), first.amount);
        ShopBank.Move repeated = ShopBank.borrow(first.book, BigInteger.valueOf(100_000_000L), cap, T0, 15L);
        assertEquals(BigInteger.ZERO, repeated.amount);

        ShopBank.Book oldDebt = new ShopBank.Book(PRINCIPAL, BigInteger.valueOf(15L), T0);
        assertEquals(BigInteger.ZERO, ShopBank.room(oldDebt, cap));
        ShopBank.Move blocked = ShopBank.borrow(oldDebt, BigInteger.ONE, cap, T0, 15L);
        assertEquals(BigInteger.ZERO, blocked.amount);
        assertEquals(oldDebt.total(), blocked.book.total());
        assertEquals(oldDebt.total(), ShopBank.repay(oldDebt, oldDebt.total(), oldDebt.total(), T0, 15L).amount);
    }

    @Test
    void stageUpgradeAddsOnlyTheRemainingRoomAfterPrincipalAndInterest() {
        ShopBank.Book debt = new ShopBank.Book(BigInteger.valueOf(1000L), BigInteger.ZERO, T0);
        BigInteger cap = BigInteger.valueOf(ShopBank.loanCap(100_000_000L, "杂货/正常",
                Set.of("stage:杂货/正常/ulv", "stage:杂货/正常/lv"), Set.of()));
        ShopBank.Move upgraded = ShopBank.borrow(debt, BigInteger.valueOf(100_000_000L), cap, T0 + HOUR, 15L);
        assertEquals(BigInteger.valueOf(2999L), upgraded.amount);
        assertEquals(BigInteger.ONE, upgraded.book.interest);
        assertEquals(BigInteger.valueOf(4000L), upgraded.book.total());
    }

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

    @Test
    void overdueBlocksOnlyWhileDebtRemainsPastDue() {
        BigInteger owed = BigInteger.valueOf(10L);
        assertEquals(false, ShopBank.overdue(owed, T0 + HOUR, T0));
        assertEquals(true, ShopBank.overdue(owed, T0, T0));
        assertEquals(true, ShopBank.overdue(owed, T0, T0 + 1L));
        assertEquals(false, ShopBank.overdue(BigInteger.ZERO, T0, T0 + HOUR));
        assertEquals(T0 + 24 * HOUR, ShopBank.freshDue(T0, 24 * HOUR));
    }

    @Test
    void missingDueTagLoadsAsZeroAndRoundTripsWhenSet() {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("bankDebt", BigInteger.valueOf(9L).toByteArray());
        tag.putLong("bankDebtMs", 70L);
        WalletAccount loaded = WalletAccount.load(tag);
        assertEquals(0L, loaded.getBankDebtDueMs());

        loaded.setBankDebtDueMs(T0 + HOUR);
        WalletAccount roundTrip = WalletAccount.load(loaded.save());
        assertEquals(T0 + HOUR, roundTrip.getBankDebtDueMs());
        assertEquals(BigInteger.valueOf(9L), roundTrip.getBankDebt());
    }
}
