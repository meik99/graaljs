/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.nodes.temporal;

import static com.oracle.truffle.js.runtime.util.TemporalConstants.LARGEST_UNIT;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.YEAR;
import static com.oracle.truffle.js.runtime.util.TemporalUtil.dtol;

import java.math.BigDecimal;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.profiles.ValueProfile;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.access.EnumerableOwnPropertyNamesNode;
import com.oracle.truffle.js.runtime.BigInt;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.builtins.JSOrdinary;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalDuration;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalDurationObject;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalDurationRecord;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalNanosecondsDaysRecord;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalPlainDateObject;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalRelativeDateRecord;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.util.TemporalConstants;
import com.oracle.truffle.js.runtime.util.TemporalErrors;
import com.oracle.truffle.js.runtime.util.TemporalUtil;

/**
 * Implementation of the roundDuration operation.
 */
public abstract class TemporalRoundDurationNode extends JavaScriptBaseNode {

    protected final JSContext ctx;
    private final BranchProfile errorBranch = BranchProfile.create();
    private final ConditionProfile hasRelativeTo = ConditionProfile.createBinaryProfile();
    private final ConditionProfile unitYMWD = ConditionProfile.createBinaryProfile();
    private final ValueProfile unitValueProfile = ValueProfile.createIdentityProfile();
    @Child private TemporalMoveRelativeDateNode moveRelativeDateNode;
    @Child EnumerableOwnPropertyNamesNode namesNode;
    @Child ToTemporalDateNode toTemporalDateNode;

    protected TemporalRoundDurationNode(JSContext ctx) {
        this.ctx = ctx;
        this.namesNode = EnumerableOwnPropertyNamesNode.createKeys(ctx);
        this.toTemporalDateNode = ToTemporalDateNode.create(ctx);
    }

    public static TemporalRoundDurationNode create(JSContext ctx) {
        return TemporalRoundDurationNodeGen.create(ctx);
    }

    public abstract JSTemporalDurationRecord execute(double y, double m, double w, double d, double h, double min,
                    double sec, double milsec, double micsec, double nsec, double increment,
                    TemporalUtil.Unit unit, TemporalUtil.RoundingMode roundingMode, JSDynamicObject relTo);

    // @Cached parameters create unused variable in generated code, see GR-37931
    @Specialization
    protected JSTemporalDurationRecord add(double years, double months, double weeks, double d, double h, double min,
                    double sec, double milsec, double micsec, double nsec, double increment,
                    TemporalUtil.Unit unit, TemporalUtil.RoundingMode roundingMode, JSDynamicObject relTo) {
        double days = d;
        double hours = h;
        double minutes = min;
        double seconds = sec;
        double microseconds = micsec;
        double milliseconds = milsec;
        double nanoseconds = nsec;

        JSDynamicObject relativeTo = relTo;
        if ((unit == TemporalUtil.Unit.YEAR || unit == TemporalUtil.Unit.MONTH || unit == TemporalUtil.Unit.WEEK) && relativeTo == Undefined.instance) {
            errorBranch.enter();
            throw TemporalErrors.createRangeErrorRelativeToNotUndefined(unit);
        }
        JSDynamicObject zonedRelativeTo = Undefined.instance;
        JSDynamicObject calendar = Undefined.instance;
        BigDecimal fractionalSeconds = BigDecimal.ZERO;

        if (hasRelativeTo.profile(relativeTo != Undefined.instance)) {
            if (TemporalUtil.isTemporalZonedDateTime(relativeTo)) {
                zonedRelativeTo = relativeTo;
                relativeTo = toTemporalDateNode.executeDynamicObject(relativeTo, Undefined.instance);
            } else {
                TemporalUtil.requireTemporalDate(relativeTo, errorBranch);
            }
            calendar = ((JSTemporalPlainDateObject) relativeTo).getCalendar();
        }
        if (unitYMWD.profile(unit == TemporalUtil.Unit.YEAR || unit == TemporalUtil.Unit.MONTH || unit == TemporalUtil.Unit.WEEK || unit == TemporalUtil.Unit.DAY)) {
            nanoseconds = TemporalUtil.totalDurationNanoseconds(0, hours, minutes, seconds, milliseconds, microseconds, nanoseconds, 0);
            JSDynamicObject intermediate = Undefined.instance;
            if (zonedRelativeTo != Undefined.instance) {
                intermediate = TemporalUtil.moveRelativeZonedDateTime(ctx, zonedRelativeTo, dtol(years), dtol(months), dtol(weeks), dtol(days));
            }
            JSTemporalNanosecondsDaysRecord result = TemporalUtil.nanosecondsToDays(ctx, namesNode, BigInt.valueOf(dtol(nanoseconds)), intermediate);
            days = calculateDays(days, result);
            hours = 0;
            minutes = 0;
            seconds = 0;
            milliseconds = 0;
            microseconds = 0;
            nanoseconds = 0;
        } else {
            fractionalSeconds = TemporalUtil.roundDurationCalculateFractionalSeconds(seconds, microseconds, milliseconds, nanoseconds);
        }
        switch (unitValueProfile.profile(unit)) {
            case YEAR:
                return getUnitYear(increment, roundingMode, years, months, weeks, days, hours, minutes, seconds, microseconds, milliseconds, nanoseconds, relativeTo, calendar);
            case MONTH:
                return getUnitMonth(increment, roundingMode, years, months, weeks, days, hours, minutes, seconds, microseconds, milliseconds, nanoseconds, relativeTo, calendar);
            case WEEK:
                return getUnitWeek(increment, roundingMode, years, months, weeks, days, hours, minutes, seconds, microseconds, milliseconds, nanoseconds, relativeTo, calendar);
            case DAY:
                return getUnitDay(increment, roundingMode, years, months, weeks, days, hours, minutes, seconds, microseconds, milliseconds, nanoseconds);
            case HOUR:
                return getUnitHour(increment, roundingMode, years, months, weeks, days, hours, minutes, fractionalSeconds);
            case MINUTE:
                return getUnitMinute(increment, roundingMode, years, months, weeks, days, hours, minutes, fractionalSeconds);
            case SECOND:
                return getUnitSecond(increment, roundingMode, years, months, weeks, days, hours, minutes, fractionalSeconds);
            case MILLISECOND:
                return getUnitMillisecond(increment, roundingMode, years, months, weeks, days, hours, minutes, seconds, microseconds, milliseconds, nanoseconds);
            case MICROSECOND:
                return getUnitMicrosecond(increment, roundingMode, years, months, weeks, days, hours, minutes, seconds, microseconds, milliseconds, nanoseconds);
            case NANOSECOND:
                return getUnitNanosecond(increment, roundingMode, years, months, weeks, days, hours, minutes, seconds, microseconds, milliseconds, nanoseconds);
        }
        CompilerDirectives.transferToInterpreter();
        throw Errors.shouldNotReachHere();
    }

    private static JSTemporalDurationRecord getUnitNanosecond(double increment, TemporalUtil.RoundingMode roundingMode, final double years, final double months, final double weeks, final double days,
                    final double hours, final double minutes, final double seconds, final double microseconds, final double milliseconds, final double nanosecondsP) {
        double nanoseconds = nanosecondsP;
        double remainder = nanoseconds;
        nanoseconds = TemporalUtil.roundNumberToIncrement(nanoseconds, increment, roundingMode);
        remainder = remainder - nanoseconds;
        return JSTemporalDurationRecord.createWeeksRemainder(years, months, weeks, days, hours, minutes, seconds, milliseconds, microseconds, nanoseconds, remainder);
    }

    private static JSTemporalDurationRecord getUnitMicrosecond(double increment, TemporalUtil.RoundingMode roundingMode, final double years, final double months, final double weeks, final double days,
                    final double hours, final double minutes, final double seconds, final double microsecondsP, final double milliseconds, final double nanoseconds) {
        double microseconds = microsecondsP;
        double fractionalMicroseconds = (nanoseconds * 0.001) + microseconds;
        microseconds = TemporalUtil.roundNumberToIncrement(fractionalMicroseconds, increment, roundingMode);
        double remainder = fractionalMicroseconds - microseconds;
        return JSTemporalDurationRecord.createWeeksRemainder(years, months, weeks, days, hours, minutes, seconds, milliseconds, microseconds, 0, remainder);
    }

    private static JSTemporalDurationRecord getUnitMillisecond(double increment, TemporalUtil.RoundingMode roundingMode, final double years, final double months, final double weeks, final double days,
                    final double hours, final double minutes, final double seconds, final double microseconds, final double millisecondsP, final double nanoseconds) {
        double milliseconds = millisecondsP;
        double fractionalMilliseconds = (nanoseconds * 0.000_001) + (microseconds * 0.001) + milliseconds;
        milliseconds = TemporalUtil.roundNumberToIncrement(fractionalMilliseconds, increment, roundingMode);
        double remainder = fractionalMilliseconds - milliseconds;
        return JSTemporalDurationRecord.createWeeksRemainder(years, months, weeks, days, hours, minutes, seconds, milliseconds, 0, 0, remainder);
    }

    private static JSTemporalDurationRecord getUnitMinute(double increment, TemporalUtil.RoundingMode roundingMode, final double years, final double months, final double weeks, final double days,
                    final double hours, final double minutesP, BigDecimal fractionalSeconds) {
        double minutes = minutesP;
        double secondsPart = TemporalUtil.roundDurationFractionalDecondsDiv60(fractionalSeconds);
        double fractionalMinutes = secondsPart + minutes;
        minutes = TemporalUtil.roundNumberToIncrement(fractionalMinutes, increment, roundingMode);
        double remainder = fractionalMinutes - minutes;
        return JSTemporalDurationRecord.createWeeksRemainder(years, months, weeks, days, hours, minutes, 0, 0, 0, 0, remainder);
    }

    private static JSTemporalDurationRecord getUnitHour(double increment, TemporalUtil.RoundingMode roundingMode, final double years, final double months, final double weeks, final double days,
                    final double hoursP, final double minutes, BigDecimal fractionalSeconds) {
        double hours = hoursP;
        double secondsPart = TemporalUtil.roundDurationFractionalDecondsDiv60(fractionalSeconds);
        double fractionalHours = ((secondsPart + minutes) / 60.0) + hours;
        hours = TemporalUtil.roundNumberToIncrement(fractionalHours, increment, roundingMode);
        double remainder = fractionalHours - hours;
        return JSTemporalDurationRecord.createWeeksRemainder(years, months, weeks, days, hours, 0, 0, 0, 0, 0, remainder);
    }

    private static JSTemporalDurationRecord getUnitDay(double increment, TemporalUtil.RoundingMode roundingMode, final double years, final double months, final double weeks, final double daysP,
                    final double hours, final double minutes, final double seconds, final double microseconds, final double milliseconds, final double nanoseconds) {
        double fractionalDays = daysP;
        double days = TemporalUtil.roundNumberToIncrement(daysP, increment, roundingMode);
        double remainder = fractionalDays - days;
        return JSTemporalDurationRecord.createWeeksRemainder(years, months, weeks, days, hours, minutes, seconds, milliseconds, microseconds, nanoseconds, remainder);
    }

    private JSTemporalDurationRecord getUnitWeek(double increment, TemporalUtil.RoundingMode roundingMode, final double years, final double months, final double weeksP, final double daysP,
                    final double hours, final double minutes, final double seconds, final double microseconds, final double milliseconds, final double nanoseconds, JSDynamicObject relativeToP,
                    JSDynamicObject calendar) {
        double weeks = weeksP;
        double days = daysP;
        JSDynamicObject relativeTo = relativeToP;

        double sign = (days >= 0) ? 1 : -1;
        JSDynamicObject oneWeek = JSTemporalDuration.createTemporalDuration(ctx, 0, 0, sign, 0, 0, 0, 0, 0, 0, 0);
        JSTemporalRelativeDateRecord moveResult = moveRelativeDate(calendar, relativeTo, oneWeek);
        relativeTo = moveResult.getRelativeTo();
        double oneWeekDays = moveResult.getDays();
        while (Math.abs(days) >= Math.abs(oneWeekDays)) {
            weeks = weeks - sign;
            days = days - oneWeekDays;
            moveResult = moveRelativeDate(calendar, relativeTo, oneWeek);
            relativeTo = moveResult.getRelativeTo();
            oneWeekDays = moveResult.getDays();
        }
        double fractionalWeeks = weeks + (days / Math.abs(oneWeekDays));
        weeks = TemporalUtil.roundNumberToIncrement(fractionalWeeks, increment, roundingMode);
        double remainder = fractionalWeeks - weeks;
        return JSTemporalDurationRecord.createWeeksRemainder(years, months, weeks, 0, hours, minutes, seconds, milliseconds, microseconds, nanoseconds, remainder);
    }

    private JSTemporalDurationRecord getUnitMonth(double increment, TemporalUtil.RoundingMode roundingMode, final double years, final double monthsP, final double weeks, final double daysP,
                    final double hours, final double minutes, final double seconds, final double microseconds, final double milliseconds, final double nanoseconds, JSDynamicObject relativeToP,
                    JSDynamicObject calendar) {
        double months = monthsP;
        double days = daysP;
        JSDynamicObject relativeTo = relativeToP;

        JSDynamicObject yearsMonths = JSTemporalDuration.createTemporalDuration(ctx, years, months, 0, 0, 0, 0, 0, 0, 0, 0);
        Object dateAdd = JSObject.getMethod(calendar, TemporalConstants.DATE_ADD);
        JSTemporalPlainDateObject yearsMonthsLater = TemporalUtil.calendarDateAdd(calendar, relativeTo, yearsMonths, Undefined.instance, dateAdd);
        JSDynamicObject yearsMonthsWeeks = JSTemporalDuration.createTemporalDuration(ctx, years, months, weeks, 0, 0, 0, 0, 0, 0, 0);
        JSTemporalPlainDateObject yearsMonthsWeeksLater = TemporalUtil.calendarDateAdd(calendar, relativeTo, yearsMonthsWeeks, Undefined.instance, dateAdd);
        double weeksInDays = TemporalUtil.daysUntil(yearsMonthsLater, yearsMonthsWeeksLater);
        relativeTo = yearsMonthsLater;
        days = days + weeksInDays;
        double sign = (days >= 0) ? 1 : -1;
        JSDynamicObject oneMonth = JSTemporalDuration.createTemporalDuration(ctx, 0, sign, 0, 0, 0, 0, 0, 0, 0, 0);
        JSTemporalRelativeDateRecord moveResult = moveRelativeDate(calendar, relativeTo, oneMonth);
        relativeTo = moveResult.getRelativeTo();
        double oneMonthDays = moveResult.getDays();
        while (Math.abs(days) >= Math.abs(oneMonthDays)) {
            months = months + sign;
            days = days - oneMonthDays;
            moveResult = moveRelativeDate(calendar, relativeTo, oneMonth);
            relativeTo = moveResult.getRelativeTo();
            oneMonthDays = moveResult.getDays();
        }
        double fractionalMonths = months + (days / Math.abs(oneMonthDays));
        months = TemporalUtil.roundNumberToIncrement(fractionalMonths, increment, roundingMode);
        double remainder = fractionalMonths - months;
        return JSTemporalDurationRecord.createWeeksRemainder(years, months, 0, 0, hours, minutes, seconds, milliseconds, microseconds, nanoseconds, remainder);
    }

    @TruffleBoundary
    private static JSTemporalDurationRecord getUnitSecond(double increment, TemporalUtil.RoundingMode roundingMode, double years, double months, double weeks, double days, double hours,
                    double minutes,
                    BigDecimal fractionalSeconds) {
        double seconds = TemporalUtil.bitod(TemporalUtil.roundNumberToIncrement(fractionalSeconds, new BigDecimal(increment), roundingMode));
        double remainder = TemporalUtil.roundDurationFractionalSecondsSubtract(seconds, fractionalSeconds);
        return JSTemporalDurationRecord.createWeeksRemainder(years, months, weeks, days, hours, minutes, seconds, 0, 0, 0, remainder);
    }

    private JSTemporalDurationRecord getUnitYear(final double increment, TemporalUtil.RoundingMode roundingMode, final double yearsP, final double months, final double weeks, final double daysP,
                    final double hours, final double minutes, final double seconds, final double microseconds, final double milliseconds, final double nanoseconds, JSDynamicObject relativeToP,
                    JSDynamicObject calendar) {
        double years = yearsP;
        double days = daysP;
        JSDynamicObject relativeTo = relativeToP;

        JSDynamicObject yearsDuration = JSTemporalDuration.createTemporalDuration(ctx, years, 0, 0, 0, 0, 0, 0, 0, 0, 0, errorBranch);
        Object dateAdd = JSObject.getMethod(calendar, TemporalConstants.DATE_ADD);
        JSDynamicObject yearsLater = TemporalUtil.calendarDateAdd(calendar, relativeTo, yearsDuration, Undefined.instance, dateAdd);
        JSDynamicObject yearsMonthsWeeks = JSTemporalDuration.createTemporalDuration(ctx, years, months, weeks, 0, 0, 0, 0, 0, 0, 0, errorBranch);

        JSDynamicObject yearsMonthsWeeksLater = TemporalUtil.calendarDateAdd(calendar, relativeTo, yearsMonthsWeeks, Undefined.instance, dateAdd);
        double monthsWeeksInDays = TemporalUtil.daysUntil(yearsLater, yearsMonthsWeeksLater);
        relativeTo = yearsLater;
        days = days + monthsWeeksInDays;
        JSDynamicObject daysDuration = JSTemporalDuration.createTemporalDuration(ctx, 0, 0, 0, days, 0, 0, 0, 0, 0, 0, errorBranch);
        JSDynamicObject daysLater = TemporalUtil.calendarDateAdd(calendar, relativeTo, daysDuration, Undefined.instance, dateAdd);
        JSDynamicObject untilOptions = JSOrdinary.createWithNullPrototype(ctx);
        TemporalUtil.createDataPropertyOrThrow(ctx, untilOptions, LARGEST_UNIT, YEAR);
        JSTemporalDurationObject timePassed = TemporalUtil.calendarDateUntil(calendar, relativeTo, daysLater, untilOptions);
        double yearsPassed = dtol(timePassed.getYears());
        years = years + yearsPassed;
        JSDynamicObject oldRelativeTo = relativeTo;

        yearsDuration = JSTemporalDuration.createTemporalDuration(ctx, yearsPassed, 0, 0, 0, 0, 0, 0, 0, 0, 0, errorBranch);
        relativeTo = TemporalUtil.calendarDateAdd(calendar, relativeTo, yearsDuration, Undefined.instance, dateAdd);
        double daysPassed = TemporalUtil.daysUntil(oldRelativeTo, relativeTo);
        days = days - daysPassed;

        double sign = (days >= 0) ? 1 : -1;
        JSDynamicObject oneYear = JSTemporalDuration.createTemporalDuration(ctx, sign, 0, 0, 0, 0, 0, 0, 0, 0, 0, errorBranch);
        JSTemporalRelativeDateRecord moveResult = moveRelativeDate(calendar, relativeTo, oneYear);

        double oneYearDays = moveResult.getDays();
        double fractionalYears = years + (days / Math.abs(oneYearDays));
        years = TemporalUtil.roundNumberToIncrement(fractionalYears, increment, roundingMode);
        double remainder = fractionalYears - years;
        return JSTemporalDurationRecord.createWeeksRemainder(years, 0, 0, 0, hours, minutes, seconds, milliseconds, microseconds, nanoseconds, remainder);
    }

    @TruffleBoundary
    private static double calculateDays(double days, JSTemporalNanosecondsDaysRecord result) {
        return days + TemporalUtil.bitod(result.getDays().add(result.getNanoseconds().divide(result.getDayLength().abs())));
    }

    private JSTemporalRelativeDateRecord moveRelativeDate(JSDynamicObject calendar, JSDynamicObject relativeTo, JSDynamicObject oneMonth) {
        if (moveRelativeDateNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            moveRelativeDateNode = insert(TemporalMoveRelativeDateNode.create(ctx));
        }
        return moveRelativeDateNode.execute(calendar, relativeTo, oneMonth);
    }
}
