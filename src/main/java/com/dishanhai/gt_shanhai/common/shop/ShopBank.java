package com.dishanhai.gt_shanhai.common.shop;

import java.math.BigInteger;

/**
 * 星火存款 / 贷款利息（山海署名，纯函数）。线性单利：利息只按本金计，不加回本金。
 *
 * <p>没有独立的 tick 调度器。存、取、借、还、查询都先调用 {@link #settle}，把已经走完的整点小时
 * 折进利息桶，再执行本次操作。计息起点只向前推进这些整点小时，不满 1 小时的零头留到下次。
 * 整点小时算出来的利息若因整数除法变成 0，起点也不推进，避免小额本金的零头被一次次丢掉。</p>
 */
public final class ShopBank {
    private ShopBank() {}

    public static final long MS_PER_HOUR = 3_600_000L;

    /** 一笔存款或一笔贷款：本金、尚未入账到本金的利息、上次计息起点。 */
    public static final class Book {
        public final BigInteger principal;
        public final BigInteger interest;
        public final long lastMs;

        public Book(BigInteger principal, BigInteger interest, long lastMs) {
            this.principal = nonNeg(principal);
            this.interest = nonNeg(interest);
            this.lastMs = Math.max(0L, lastMs);
        }

        public BigInteger total() {
            return principal.add(interest);
        }

        public boolean isEmpty() {
            return principal.signum() <= 0 && interest.signum() <= 0;
        }
    }

    /** 一次资金移动的结果。{@code amount} 为实际存入、取出、借到或还掉的数量。 */
    public static final class Move {
        public final Book book;
        public final BigInteger amount;

        public Move(Book book, BigInteger amount) {
            this.book = book == null ? new Book(BigInteger.ZERO, BigInteger.ZERO, 0L) : book;
            this.amount = nonNeg(amount);
        }
    }

    /**
     * 按经过的毫秒数 + 每小时计息基点，算出这段时间应计的利息。
     * 不足 1 小时的零头不在这里结算。
     * @return 本金为 0、费率非正或不足 1 小时时返回 0
     */
    public static BigInteger accrue(BigInteger principal, long rateBpPerHour, long elapsedMs) {
        if (principal == null || principal.signum() <= 0 || rateBpPerHour <= 0L || elapsedMs <= 0L) {
            return BigInteger.ZERO;
        }
        long elapsedHours = elapsedMs / MS_PER_HOUR;
        if (elapsedHours <= 0L) return BigInteger.ZERO;
        return principal.multiply(BigInteger.valueOf(rateBpPerHour))
                .multiply(BigInteger.valueOf(elapsedHours))
                .divide(BigInteger.valueOf(10_000L));
    }

    /**
     * 把本金在整点小时上的单利加进利息桶。本金不变。
     * 账本为空时计息起点归 0。
     */
    public static Book settle(Book book, long now, long rateBpPerHour) {
        Book src = book == null ? new Book(BigInteger.ZERO, BigInteger.ZERO, 0L) : book;
        if (src.isEmpty()) return new Book(BigInteger.ZERO, BigInteger.ZERO, 0L);
        if (src.lastMs <= 0L || now <= src.lastMs || rateBpPerHour <= 0L || src.principal.signum() <= 0) {
            return src;
        }
        long elapsed = now - src.lastMs;
        long hours = elapsed / MS_PER_HOUR;
        if (hours <= 0L) return src;
        BigInteger gained = accrue(src.principal, rateBpPerHour, hours * MS_PER_HOUR);
        if (gained.signum() <= 0) return src;
        return new Book(src.principal, src.interest.add(gained), src.lastMs + hours * MS_PER_HOUR);
    }

    /** 结息后把星火转入本金。实际存入量不超过钱包余额。新账本的计息起点为 0 时设成 {@code now}。 */
    public static Move deposit(Book book, BigInteger wallet, BigInteger request, long now, long rateBpPerHour) {
        Book settled = settle(book, now, rateBpPerHour);
        BigInteger take = nonNeg(wallet).min(nonNeg(request));
        if (take.signum() <= 0) return new Move(settled, BigInteger.ZERO);
        long ms = settled.lastMs > 0L ? settled.lastMs : Math.max(0L, now);
        return new Move(new Book(settled.principal.add(take), settled.interest, ms), take);
    }

    /** 结息后先取利息，再取本金。取空后计息起点归 0。 */
    public static Move withdraw(Book book, BigInteger request, long now, long rateBpPerHour) {
        Book settled = settle(book, now, rateBpPerHour);
        if (nonNeg(request).signum() <= 0) return new Move(settled, BigInteger.ZERO);
        return reduce(settled, request);
    }

    /**
     * 结息后按剩余额度借款，只加本金。额度是本金加利息相对 {@code cap} 的差额。
     */
    public static Move borrow(Book book, BigInteger request, BigInteger cap, long now, long rateBpPerHour) {
        Book settled = settle(book, now, rateBpPerHour);
        BigInteger take = room(settled, cap).min(nonNeg(request));
        if (take.signum() <= 0) return new Move(settled, BigInteger.ZERO);
        long ms = settled.lastMs > 0L ? settled.lastMs : Math.max(0L, now);
        return new Move(new Book(settled.principal.add(take), settled.interest, ms), take);
    }

    /** 结息后用钱包余额还款，先冲利息再冲本金。实际还款量不超过欠款合计。 */
    public static Move repay(Book book, BigInteger wallet, BigInteger request, long now, long rateBpPerHour) {
        Book settled = settle(book, now, rateBpPerHour);
        BigInteger pay = nonNeg(wallet).min(nonNeg(request));
        if (pay.signum() <= 0) return new Move(settled, BigInteger.ZERO);
        return reduce(settled, pay);
    }

    /** 还可再借的星火。{@code cap} 小于等于已欠合计时返回 0。 */
    public static BigInteger room(Book book, BigInteger cap) {
        BigInteger owed = book == null ? BigInteger.ZERO : book.total();
        BigInteger left = nonNeg(cap).subtract(owed);
        return left.signum() > 0 ? left : BigInteger.ZERO;
    }

    private static Move reduce(Book settled, BigInteger request) {
        BigInteger take = settled.total().min(nonNeg(request));
        if (take.signum() <= 0) return new Move(settled, BigInteger.ZERO);
        BigInteger fromInterest = settled.interest.min(take);
        BigInteger newInterest = settled.interest.subtract(fromInterest);
        BigInteger newPrincipal = settled.principal.subtract(take.subtract(fromInterest));
        long ms = newPrincipal.signum() <= 0 && newInterest.signum() <= 0 ? 0L : settled.lastMs;
        return new Move(new Book(newPrincipal, newInterest, ms), take);
    }

    private static BigInteger nonNeg(BigInteger value) {
        if (value == null || value.signum() <= 0) return BigInteger.ZERO;
        return value;
    }
}
