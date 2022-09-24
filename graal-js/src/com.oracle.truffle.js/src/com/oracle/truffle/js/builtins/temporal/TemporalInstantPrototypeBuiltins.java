/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.js.builtins.temporal;

import static com.oracle.truffle.js.runtime.util.TemporalConstants.AUTO;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.HALF_EXPAND;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.NANOSECOND;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.TIME_ZONE;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.TRUNC;

import java.math.BigInteger;
import java.util.EnumSet;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.js.builtins.JSBuiltinsContainer;
import com.oracle.truffle.js.builtins.temporal.TemporalInstantPrototypeBuiltinsFactory.JSTemporalInstantAddNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalInstantPrototypeBuiltinsFactory.JSTemporalInstantEqualsNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalInstantPrototypeBuiltinsFactory.JSTemporalInstantGetterNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalInstantPrototypeBuiltinsFactory.JSTemporalInstantRoundNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalInstantPrototypeBuiltinsFactory.JSTemporalInstantSubtractNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalInstantPrototypeBuiltinsFactory.JSTemporalInstantToLocaleStringNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalInstantPrototypeBuiltinsFactory.JSTemporalInstantToStringNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalInstantPrototypeBuiltinsFactory.JSTemporalInstantToZonedDateTimeISONodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalInstantPrototypeBuiltinsFactory.JSTemporalInstantToZonedDateTimeNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalInstantPrototypeBuiltinsFactory.JSTemporalInstantUntilSinceNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalInstantPrototypeBuiltinsFactory.JSTemporalInstantValueOfNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalPlainDatePrototypeBuiltins.JSTemporalBuiltinOperation;
import com.oracle.truffle.js.nodes.access.EnumerableOwnPropertyNamesNode;
import com.oracle.truffle.js.nodes.cast.JSToNumberNode;
import com.oracle.truffle.js.nodes.cast.JSToStringNode;
import com.oracle.truffle.js.nodes.function.JSBuiltin;
import com.oracle.truffle.js.nodes.function.JSBuiltinNode;
import com.oracle.truffle.js.nodes.temporal.ToLimitedTemporalDurationNode;
import com.oracle.truffle.js.nodes.temporal.ToTemporalCalendarNode;
import com.oracle.truffle.js.nodes.temporal.ToTemporalInstantNode;
import com.oracle.truffle.js.nodes.temporal.ToTemporalTimeZoneNode;
import com.oracle.truffle.js.runtime.BigInt;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.Strings;
import com.oracle.truffle.js.runtime.builtins.BuiltinEnum;
import com.oracle.truffle.js.runtime.builtins.JSOrdinary;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalDuration;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalDurationRecord;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalInstant;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalInstantObject;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalPrecisionRecord;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalZonedDateTime;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.util.TemporalConstants;
import com.oracle.truffle.js.runtime.util.TemporalErrors;
import com.oracle.truffle.js.runtime.util.TemporalUtil;
import com.oracle.truffle.js.runtime.util.TemporalUtil.RoundingMode;
import com.oracle.truffle.js.runtime.util.TemporalUtil.Unit;

public class TemporalInstantPrototypeBuiltins extends JSBuiltinsContainer.SwitchEnum<TemporalInstantPrototypeBuiltins.TemporalInstantPrototype> {

    public static final JSBuiltinsContainer BUILTINS = new TemporalInstantPrototypeBuiltins();

    protected TemporalInstantPrototypeBuiltins() {
        super(JSTemporalInstant.PROTOTYPE_NAME, TemporalInstantPrototype.class);
    }

    public enum TemporalInstantPrototype implements BuiltinEnum<TemporalInstantPrototype> {
        // getters
        epochSeconds(0),
        epochMilliseconds(0),
        epochMicroseconds(0),
        epochNanoseconds(0),

        // methods
        add(1),
        subtract(1),
        until(1),
        since(1),
        round(1),
        equals(1),
        toString(0),
        toLocaleString(0),
        toJSON(0),
        valueOf(0),
        toZonedDateTime(1),
        toZonedDateTimeISO(1);

        private final int length;

        TemporalInstantPrototype(int length) {
            this.length = length;
        }

        @Override
        public int getLength() {
            return length;
        }

        @Override
        public boolean isGetter() {
            return EnumSet.of(epochSeconds, epochMilliseconds, epochMicroseconds, epochNanoseconds).contains(this);
        }
    }

    @Override
    protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, TemporalInstantPrototype builtinEnum) {
        switch (builtinEnum) {
            case epochSeconds:
            case epochMilliseconds:
            case epochMicroseconds:
            case epochNanoseconds:
                return JSTemporalInstantGetterNodeGen.create(context, builtin, builtinEnum, args().withThis().createArgumentNodes(context));

            case add:
                return JSTemporalInstantAddNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case subtract:
                return JSTemporalInstantSubtractNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case until:
                return JSTemporalInstantUntilSinceNodeGen.create(context, builtin, true, args().withThis().fixedArgs(2).createArgumentNodes(context));
            case since:
                return JSTemporalInstantUntilSinceNodeGen.create(context, builtin, false, args().withThis().fixedArgs(2).createArgumentNodes(context));
            case round:
                return JSTemporalInstantRoundNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case equals:
                return JSTemporalInstantEqualsNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case toString:
                return JSTemporalInstantToStringNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case toLocaleString:
            case toJSON:
                return JSTemporalInstantToLocaleStringNodeGen.create(context, builtin, args().withThis().createArgumentNodes(context));
            case valueOf:
                return JSTemporalInstantValueOfNodeGen.create(context, builtin, args().withThis().createArgumentNodes(context));
            case toZonedDateTime:
                return JSTemporalInstantToZonedDateTimeNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case toZonedDateTimeISO:
                return JSTemporalInstantToZonedDateTimeISONodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));

        }
        return null;
    }

    public abstract static class JSTemporalInstantGetterNode extends JSBuiltinNode {

        public final TemporalInstantPrototype property;

        public JSTemporalInstantGetterNode(JSContext context, JSBuiltin builtin, TemporalInstantPrototype property) {
            super(context, builtin);
            this.property = property;
        }

        @TruffleBoundary
        @Specialization(guards = "isJSTemporalInstant(thisObj)")
        protected Object instantGetter(Object thisObj) {
            JSTemporalInstantObject instant = (JSTemporalInstantObject) thisObj;
            BigInteger ns = instant.getNanoseconds().bigIntegerValue();
            switch (property) {
                // roundTowardsZero is a no-op for BigIntegers
                case epochSeconds:
                    return ns.divide(TemporalUtil.BI_10_POW_9).doubleValue();
                case epochMilliseconds:
                    return ns.divide(TemporalUtil.BI_10_POW_6).doubleValue();
                case epochMicroseconds:
                    return new BigInt(ns.divide(TemporalUtil.BI_1000));
                case epochNanoseconds:
                    return instant.getNanoseconds();
            }
            CompilerDirectives.transferToInterpreter();
            throw Errors.shouldNotReachHere();
        }

        @Specialization(guards = "!isJSTemporalInstant(thisObj)")
        protected static int error(@SuppressWarnings("unused") Object thisObj) {
            throw TemporalErrors.createTypeErrorTemporalInstantExpected();
        }
    }

    public abstract static class InstantOperation extends JSTemporalBuiltinOperation {
        public InstantOperation(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        protected JSTemporalInstantObject addDurationToOrSubtractDurationFromInstant(int sign, JSTemporalInstantObject instant, Object temporalDurationLike,
                        ToLimitedTemporalDurationNode toLimitedTemporalDurationNode) {
            JSTemporalDurationRecord duration = toLimitedTemporalDurationNode.executeDynamicObject(temporalDurationLike, TemporalUtil.listPluralYMWD);
            BigInt ns = TemporalUtil.addInstant(instant.getNanoseconds(), sign * duration.getHours(), sign * duration.getMinutes(), sign * duration.getSeconds(),
                            sign * duration.getMilliseconds(), sign * duration.getMicroseconds(), sign * duration.getNanoseconds());
            return JSTemporalInstant.create(getContext(), getRealm(), ns);
        }
    }

    public abstract static class JSTemporalInstantAdd extends InstantOperation {

        protected JSTemporalInstantAdd(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public JSDynamicObject add(Object thisObj, Object temporalDurationLike,
                        @Cached("create()") ToLimitedTemporalDurationNode toLimitedTemporalDurationNode) {
            JSTemporalInstantObject instant = requireTemporalInstant(thisObj);
            return addDurationToOrSubtractDurationFromInstant(TemporalUtil.ADD, instant, temporalDurationLike, toLimitedTemporalDurationNode);
        }
    }

    public abstract static class JSTemporalInstantSubtract extends InstantOperation {

        protected JSTemporalInstantSubtract(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public JSDynamicObject subtract(Object thisObj, Object temporalDurationLike,
                        @Cached("create()") ToLimitedTemporalDurationNode toLimitedTemporalDurationNode) {
            JSTemporalInstantObject instant = requireTemporalInstant(thisObj);
            return addDurationToOrSubtractDurationFromInstant(TemporalUtil.SUBTRACT, instant, temporalDurationLike, toLimitedTemporalDurationNode);
        }
    }

    public abstract static class JSTemporalInstantUntilSinceNode extends JSTemporalBuiltinOperation {

        private final boolean isUntil;

        protected JSTemporalInstantUntilSinceNode(JSContext context, JSBuiltin builtin, boolean isUntil) {
            super(context, builtin);
            this.isUntil = isUntil;
        }

        @Specialization
        public JSDynamicObject untilOrSince(Object thisObj, Object otherObj, Object optionsParam,
                        @Cached("create()") JSToNumberNode toNumber,
                        @Cached("createKeys(getContext())") EnumerableOwnPropertyNamesNode namesNode,
                        @Cached TruffleString.EqualNode equalNode,
                        @Cached("create(getContext())") ToTemporalInstantNode toTemporalInstantNode) {
            JSTemporalInstantObject instant = requireTemporalInstant(thisObj);
            JSTemporalInstantObject other = toTemporalInstantNode.execute(otherObj);
            JSDynamicObject options = getOptionsObject(optionsParam);
            Unit smallestUnit = toSmallestTemporalUnit(options, TemporalUtil.listYMWD, NANOSECOND, equalNode);
            Unit defaultLargestUnit = TemporalUtil.largerOfTwoTemporalUnits(Unit.SECOND, smallestUnit);
            Unit largestUnit = toLargestTemporalUnit(options, TemporalUtil.listYMWD, AUTO, defaultLargestUnit, equalNode);
            TemporalUtil.validateTemporalUnitRange(largestUnit, smallestUnit);
            RoundingMode roundingMode = toTemporalRoundingMode(options, TRUNC, equalNode);
            Double maximum = TemporalUtil.maximumTemporalDurationRoundingIncrement(smallestUnit);
            double roundingIncrement = TemporalUtil.toTemporalRoundingIncrement(options, maximum, false, isObjectNode, toNumber);

            BigInt one = isUntil ? instant.getNanoseconds() : other.getNanoseconds();
            BigInt two = isUntil ? other.getNanoseconds() : instant.getNanoseconds();

            BigInteger roundedNs = TemporalUtil.differenceInstant(one, two, roundingIncrement, smallestUnit, roundingMode);
            JSTemporalDurationRecord result = TemporalUtil.balanceDuration(getContext(), namesNode, 0, 0, 0, 0, 0, 0, roundedNs, largestUnit, Undefined.instance);
            return JSTemporalDuration.createTemporalDuration(getContext(), 0, 0, 0, 0, result.getHours(), result.getMinutes(), result.getSeconds(),
                            result.getMilliseconds(), result.getMicroseconds(), result.getNanoseconds(), errorBranch);
        }
    }

    public abstract static class JSTemporalInstantRound extends JSTemporalBuiltinOperation {

        protected JSTemporalInstantRound(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public JSDynamicObject round(Object thisObj, Object roundToParam,
                        @Cached("create()") JSToNumberNode toNumber,
                        @Cached TruffleString.EqualNode equalNode) {
            JSTemporalInstantObject instant = requireTemporalInstant(thisObj);
            if (roundToParam == Undefined.instance) {
                throw TemporalErrors.createTypeErrorOptionsUndefined();
            }
            JSDynamicObject roundTo;
            if (Strings.isTString(roundToParam)) {
                roundTo = JSOrdinary.createWithNullPrototype(getContext());
                JSRuntime.createDataPropertyOrThrow(roundTo, TemporalConstants.SMALLEST_UNIT, JSRuntime.toStringIsString(roundToParam));
            } else {
                roundTo = getOptionsObject(roundToParam);
            }
            Unit smallestUnit = toSmallestTemporalUnit(roundTo, TemporalUtil.listYMWD, null, equalNode);
            if (smallestUnit == Unit.EMPTY) {
                errorBranch.enter();
                throw TemporalErrors.createRangeErrorSmallestUnitExpected();
            }
            RoundingMode roundingMode = toTemporalRoundingMode(roundTo, HALF_EXPAND, equalNode);
            double maximum;
            if (Unit.HOUR == smallestUnit) {
                maximum = TemporalUtil.HOURS_PER_DAY;
            } else if (Unit.MINUTE == smallestUnit) {
                maximum = TemporalUtil.MINUTES_PER_HOUR * TemporalUtil.HOURS_PER_DAY;
            } else if (Unit.SECOND == smallestUnit) {
                maximum = TemporalUtil.SECONDS_PER_MINUTE * TemporalUtil.MINUTES_PER_HOUR * TemporalUtil.HOURS_PER_DAY;
            } else if (Unit.MILLISECOND == smallestUnit) {
                maximum = TemporalUtil.MS_PER_DAY;
            } else if (Unit.MICROSECOND == smallestUnit) {
                maximum = TemporalUtil.MS_PER_DAY * 1000;
            } else {
                assert Unit.NANOSECOND == smallestUnit;
                maximum = TemporalUtil.NS_PER_DAY;
            }
            double roundingIncrement = TemporalUtil.toTemporalRoundingIncrement(roundTo, maximum, true, isObjectNode, toNumber);
            BigInteger roundedNs = TemporalUtil.roundTemporalInstant(instant.getNanoseconds(), (long) roundingIncrement, smallestUnit, roundingMode);
            return JSTemporalInstant.create(getContext(), getRealm(), new BigInt(roundedNs));
        }
    }

    public abstract static class JSTemporalInstantEquals extends JSTemporalBuiltinOperation {

        protected JSTemporalInstantEquals(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public boolean equals(Object thisObj, Object otherObj,
                        @Cached("create(getContext())") ToTemporalInstantNode toTemporalInstantNode) {
            JSTemporalInstantObject instant = requireTemporalInstant(thisObj);
            JSTemporalInstantObject other = toTemporalInstantNode.execute(otherObj);
            return instant.getNanoseconds().compareTo(other.getNanoseconds()) == 0;
        }
    }

    public abstract static class JSTemporalInstantToString extends JSTemporalBuiltinOperation {

        protected JSTemporalInstantToString(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected TruffleString toString(Object thisObj, Object optionsParam,
                        @Cached("create(getContext())") ToTemporalTimeZoneNode toTemporalTimeZone,
                        @Cached JSToStringNode toStringNode,
                        @Cached TruffleString.EqualNode equalNode) {
            JSTemporalInstantObject instant = requireTemporalInstant(thisObj);
            JSDynamicObject options = getOptionsObject(optionsParam);
            Object timeZoneRaw = JSObject.get(options, TIME_ZONE);
            JSDynamicObject timeZone = Undefined.instance;
            if (timeZoneRaw != Undefined.instance) {
                timeZone = toTemporalTimeZone.executeDynamicObject(timeZoneRaw);
            }
            JSTemporalPrecisionRecord precision = TemporalUtil.toSecondsStringPrecision(options, toStringNode, getOptionNode(), equalNode);
            RoundingMode roundingMode = toTemporalRoundingMode(options, TRUNC, equalNode);
            BigInt ns = instant.getNanoseconds();
            BigInteger roundedNs = TemporalUtil.roundTemporalInstant(ns, (long) precision.getIncrement(), precision.getUnit(), roundingMode);
            JSRealm realm = getRealm();
            JSDynamicObject roundedInstant = JSTemporalInstant.create(getContext(), realm, new BigInt(roundedNs));
            return TemporalUtil.temporalInstantToString(getContext(), realm, roundedInstant, timeZone, precision.getPrecision());
        }
    }

    public abstract static class JSTemporalInstantToLocaleString extends JSTemporalBuiltinOperation {

        protected JSTemporalInstantToLocaleString(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @SuppressWarnings("unused")
        @Specialization
        public TruffleString toLocaleString(Object thisObj) {
            JSTemporalInstantObject instant = requireTemporalInstant(thisObj);
            return TemporalUtil.temporalInstantToString(getContext(), getRealm(), instant, Undefined.instance, AUTO);
        }
    }

    public abstract static class JSTemporalInstantValueOf extends JSTemporalBuiltinOperation {

        protected JSTemporalInstantValueOf(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object valueOf(@SuppressWarnings("unused") Object thisObj) {
            throw Errors.createTypeError("Not supported.");
        }
    }

    public abstract static class JSTemporalInstantToZonedDateTimeNode extends JSTemporalBuiltinOperation {

        protected JSTemporalInstantToZonedDateTimeNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected JSDynamicObject toZonedDateTime(Object thisObj, Object item,
                        @Cached("create(getContext())") ToTemporalCalendarNode toTemporalCalendar,
                        @Cached("create(getContext())") ToTemporalTimeZoneNode toTemporalTimeZone) {
            JSTemporalInstantObject instant = requireTemporalInstant(thisObj);
            if (!isObject(item)) {
                errorBranch.enter();
                throw Errors.createTypeError("object expected");
            }
            JSDynamicObject itemObj = TemporalUtil.toJSDynamicObject(item, errorBranch);
            Object calendarLike = JSObject.get(itemObj, TemporalConstants.CALENDAR);
            if (calendarLike == Undefined.instance) {
                errorBranch.enter();
                throw TemporalErrors.createTypeErrorTemporalCalendarExpected();
            }
            JSDynamicObject calendar = toTemporalCalendar.executeDynamicObject(calendarLike);
            Object timeZoneLike = JSObject.get(itemObj, TemporalConstants.TIME_ZONE);
            if (timeZoneLike == Undefined.instance) {
                errorBranch.enter();
                throw TemporalErrors.createTypeErrorTemporalTimeZoneExpected();
            }
            JSDynamicObject timeZone = toTemporalTimeZone.executeDynamicObject(timeZoneLike);
            return JSTemporalZonedDateTime.create(getContext(), getRealm(), instant.getNanoseconds(), timeZone, calendar);
        }
    }

    public abstract static class JSTemporalInstantToZonedDateTimeISONode extends JSTemporalBuiltinOperation {

        protected JSTemporalInstantToZonedDateTimeISONode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object toZonedDateTimeISO(Object thisObj, Object itemParam,
                        @Cached("create(getContext())") ToTemporalTimeZoneNode toTemporalTimeZone) {
            JSTemporalInstantObject instant = requireTemporalInstant(thisObj);
            Object item = itemParam;
            if (isObject(item)) {
                JSDynamicObject itemObj = TemporalUtil.toJSDynamicObject(item, errorBranch);
                Object timeZoneProperty = JSObject.get(itemObj, TIME_ZONE);
                if (timeZoneProperty != Undefined.instance) {
                    item = timeZoneProperty;
                }
            }
            JSDynamicObject timeZone = toTemporalTimeZone.executeDynamicObject(item);
            JSRealm realm = getRealm();
            JSDynamicObject calendar = TemporalUtil.getISO8601Calendar(getContext(), realm, errorBranch);
            return JSTemporalZonedDateTime.create(getContext(), realm, instant.getNanoseconds(), timeZone, calendar);
        }
    }

}
