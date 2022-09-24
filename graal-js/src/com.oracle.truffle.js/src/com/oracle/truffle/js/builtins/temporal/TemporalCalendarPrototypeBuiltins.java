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
import static com.oracle.truffle.js.runtime.util.TemporalConstants.DAY;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.HOUR;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.ISO8601;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.MICROSECOND;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.MILLISECOND;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.MINUTE;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.MONTH;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.MONTH_CODE;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.NANOSECOND;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.SECOND;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.YEAR;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.js.builtins.JSBuiltinsContainer;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarDateAddNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarDateFromFieldsNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarDateUntilNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarDayNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarDayOfWeekNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarDayOfYearNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarDaysInMonthNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarDaysInWeekNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarDaysInYearNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarFieldsNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarGetterNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarInLeapYearNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarMergeFieldsNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarMonthCodeNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarMonthDayFromFieldsNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarMonthNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarMonthsInYearNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarToStringNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarWeekOfYearNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarYearMonthFromFieldsNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalCalendarPrototypeBuiltinsFactory.JSTemporalCalendarYearNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalPlainDatePrototypeBuiltins.JSTemporalBuiltinOperation;
import com.oracle.truffle.js.nodes.access.EnumerableOwnPropertyNamesNode;
import com.oracle.truffle.js.nodes.access.GetIteratorBaseNode;
import com.oracle.truffle.js.nodes.access.IteratorCloseNode;
import com.oracle.truffle.js.nodes.access.IteratorStepNode;
import com.oracle.truffle.js.nodes.access.IteratorValueNode;
import com.oracle.truffle.js.nodes.binary.JSIdenticalNode;
import com.oracle.truffle.js.nodes.cast.JSToIntegerOrInfinityNode;
import com.oracle.truffle.js.nodes.cast.JSToObjectNode;
import com.oracle.truffle.js.nodes.cast.JSToStringNode;
import com.oracle.truffle.js.nodes.function.JSBuiltin;
import com.oracle.truffle.js.nodes.function.JSBuiltinNode;
import com.oracle.truffle.js.nodes.temporal.TemporalGetOptionNode;
import com.oracle.truffle.js.nodes.temporal.ToTemporalDateNode;
import com.oracle.truffle.js.nodes.temporal.ToTemporalDurationNode;
import com.oracle.truffle.js.runtime.Boundaries;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.Strings;
import com.oracle.truffle.js.runtime.builtins.BuiltinEnum;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalCalendar;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalCalendarObject;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalDateTimeRecord;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalDuration;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalDurationObject;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalDurationRecord;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalPlainDate;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalPlainDateObject;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalPlainDateTime;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalPlainMonthDay;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalPlainMonthDayObject;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalPlainYearMonth;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalPlainYearMonthObject;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalYearMonthDayRecord;
import com.oracle.truffle.js.runtime.builtins.temporal.TemporalMonth;
import com.oracle.truffle.js.runtime.builtins.temporal.TemporalYear;
import com.oracle.truffle.js.runtime.objects.IteratorRecord;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.util.TemporalErrors;
import com.oracle.truffle.js.runtime.util.TemporalUtil;
import com.oracle.truffle.js.runtime.util.TemporalUtil.Overflow;
import com.oracle.truffle.js.runtime.util.TemporalUtil.Unit;

public class TemporalCalendarPrototypeBuiltins extends JSBuiltinsContainer.SwitchEnum<TemporalCalendarPrototypeBuiltins.TemporalCalendarPrototype> {

    public static final TemporalCalendarPrototypeBuiltins BUILTINS = new TemporalCalendarPrototypeBuiltins();

    protected TemporalCalendarPrototypeBuiltins() {
        super(JSTemporalCalendar.PROTOTYPE_NAME, TemporalCalendarPrototype.class);
    }

    public enum TemporalCalendarPrototype implements BuiltinEnum<TemporalCalendarPrototype> {
        // getters
        id(0),

        // methods
        mergeFields(2),
        fields(1),
        dateFromFields(1),
        yearMonthFromFields(1),
        monthDayFromFields(1),
        dateAdd(2),
        dateUntil(2),
        year(1),
        month(1),
        monthCode(1),
        day(1),
        dayOfWeek(1),
        dayOfYear(1),
        weekOfYear(1),
        daysInWeek(1),
        daysInMonth(1),
        daysInYear(1),
        monthsInYear(1),
        inLeapYear(1),
        toString(0),
        toJSON(0);

        private final int length;

        TemporalCalendarPrototype(int length) {
            this.length = length;
        }

        @Override
        public int getLength() {
            return length;
        }

        @Override
        public boolean isGetter() {
            return EnumSet.of(id).contains(this);
        }
    }

    @Override
    protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, TemporalCalendarPrototype builtinEnum) {
        switch (builtinEnum) {
            case id:
                return JSTemporalCalendarGetterNodeGen.create(context, builtin, builtinEnum, args().withThis().createArgumentNodes(context));

            case mergeFields:
                return JSTemporalCalendarMergeFieldsNodeGen.create(context, builtin, args().withThis().fixedArgs(2).createArgumentNodes(context));
            case fields:
                return JSTemporalCalendarFieldsNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case dateFromFields:
                return JSTemporalCalendarDateFromFieldsNodeGen.create(context, builtin, args().withThis().fixedArgs(2).createArgumentNodes(context));
            case yearMonthFromFields:
                return JSTemporalCalendarYearMonthFromFieldsNodeGen.create(context, builtin, args().withThis().fixedArgs(3).createArgumentNodes(context));
            case monthDayFromFields:
                return JSTemporalCalendarMonthDayFromFieldsNodeGen.create(context, builtin, args().withThis().fixedArgs(3).createArgumentNodes(context));
            case dateAdd:
                return JSTemporalCalendarDateAddNodeGen.create(context, builtin, args().withThis().fixedArgs(4).createArgumentNodes(context));
            case dateUntil:
                return JSTemporalCalendarDateUntilNodeGen.create(context, builtin, args().withThis().fixedArgs(3).createArgumentNodes(context));
            case year:
                return JSTemporalCalendarYearNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case month:
                return JSTemporalCalendarMonthNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case monthCode:
                return JSTemporalCalendarMonthCodeNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case day:
                return JSTemporalCalendarDayNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case dayOfWeek:
                return JSTemporalCalendarDayOfWeekNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case dayOfYear:
                return JSTemporalCalendarDayOfYearNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case daysInWeek:
                return JSTemporalCalendarDaysInWeekNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case weekOfYear:
                return JSTemporalCalendarWeekOfYearNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case daysInMonth:
                return JSTemporalCalendarDaysInMonthNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case daysInYear:
                return JSTemporalCalendarDaysInYearNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case monthsInYear:
                return JSTemporalCalendarMonthsInYearNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case inLeapYear:
                return JSTemporalCalendarInLeapYearNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case toString:
            case toJSON:
                return JSTemporalCalendarToStringNodeGen.create(context, builtin, args().withThis().createArgumentNodes(context));
        }
        return null;
    }

    public abstract static class JSTemporalCalendarGetterNode extends JSBuiltinNode {

        public final TemporalCalendarPrototype property;

        public JSTemporalCalendarGetterNode(JSContext context, JSBuiltin builtin, TemporalCalendarPrototype property) {
            super(context, builtin);
            this.property = property;
        }

        @Specialization(guards = "isJSTemporalCalendar(thisObj)")
        protected Object durationGetter(Object thisObj,
                        @Cached JSToStringNode toStringNode) {
            switch (property) {
                case id:
                    return toStringNode.executeString(thisObj);
            }
            CompilerDirectives.transferToInterpreter();
            throw Errors.shouldNotReachHere();
        }

        @Specialization(guards = "!isJSTemporalCalendar(thisObj)")
        protected static int error(@SuppressWarnings("unused") Object thisObj) {
            throw TemporalErrors.createTypeErrorTemporalCalendarExpected();
        }
    }

    public abstract static class JSTemporalCalendarOperation extends JSTemporalBuiltinOperation {

        @Child private ToTemporalDateNode toTemporalDate;

        public JSTemporalCalendarOperation(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        protected JSDynamicObject toTemporalDate(Object item, JSDynamicObject options) {
            if (toTemporalDate == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toTemporalDate = insert(ToTemporalDateNode.create(getContext()));
            }
            return toTemporalDate.executeDynamicObject(item, options);
        }
    }

    public abstract static class JSTemporalCalendarMergeFields extends JSTemporalBuiltinOperation {

        protected JSTemporalCalendarMergeFields(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public JSDynamicObject mergeFields(Object thisObj, Object fieldsParam, Object additionalFieldsParam,
                        @Cached("createToObject(getContext())") JSToObjectNode toObject,
                        @Cached("createKeys(getContext())") EnumerableOwnPropertyNamesNode namesNode) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            JSDynamicObject fields = (JSDynamicObject) toObject.execute(fieldsParam);
            JSDynamicObject additionalFields = (JSDynamicObject) toObject.execute(additionalFieldsParam);
            return TemporalUtil.defaultMergeFields(getContext(), namesNode, fields, additionalFields);
        }
    }

    public abstract static class JSTemporalCalendarFields extends JSTemporalBuiltinOperation {
        @Child private IteratorCloseNode iteratorCloseNode;
        @Child private GetIteratorBaseNode getIteratorNode;
        @Child private IteratorValueNode getIteratorValueNode;
        @Child private IteratorStepNode iteratorStepNode;

        protected void iteratorCloseAbrupt(JSDynamicObject iterator) {
            if (iteratorCloseNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                iteratorCloseNode = insert(IteratorCloseNode.create(getContext()));
            }
            iteratorCloseNode.executeAbrupt(iterator);
        }

        protected IteratorRecord getIterator(Object iterator) {
            if (getIteratorNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getIteratorNode = insert(GetIteratorBaseNode.create());
            }
            return getIteratorNode.execute(iterator);
        }

        protected Object getIteratorValue(JSDynamicObject iteratorResult) {
            if (getIteratorValueNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getIteratorValueNode = insert(IteratorValueNode.create());
            }
            return getIteratorValueNode.execute(iteratorResult);
        }

        protected Object iteratorStep(IteratorRecord iterator) {
            if (iteratorStepNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                iteratorStepNode = insert(IteratorStepNode.create());
            }
            return iteratorStepNode.execute(iterator);
        }

        protected JSTemporalCalendarFields(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public JSDynamicObject fields(Object thisObj, Object fieldsParam) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);

            IteratorRecord iter = getIterator(fieldsParam /* , sync */);
            List<TruffleString> fieldNames = new ArrayList<>();
            Object next = Boolean.TRUE;
            while (next != Boolean.FALSE) {
                next = iteratorStep(iter);
                if (next != Boolean.FALSE) {
                    Object nextValue = getIteratorValue((JSDynamicObject) next);
                    if (!Strings.isTString(nextValue)) {
                        iteratorCloseAbrupt(iter.getIterator());
                        throw Errors.createTypeError("string expected");
                    }
                    TruffleString str = JSRuntime.toString(nextValue);
                    if (str != null && Boundaries.listContains(fieldNames, str)) {
                        iteratorCloseAbrupt(iter.getIterator());
                        throw Errors.createRangeError("");
                    }
                    if (!(YEAR.equals(str) || MONTH.equals(str) || MONTH_CODE.equals(str) || DAY.equals(str) || HOUR.equals(str) ||
                                    MINUTE.equals(str) || SECOND.equals(str) || MILLISECOND.equals(str) || MICROSECOND.equals(str) || NANOSECOND.equals(str))) {
                        iteratorCloseAbrupt(iter.getIterator());
                        throw Errors.createRangeError("");
                    }
                    fieldNames.add(str);
                }
            }
            return JSRuntime.createArrayFromList(getContext(), getRealm(), fieldNames);
        }
    }

    // 12.4.4
    public abstract static class JSTemporalCalendarDateFromFields extends JSTemporalBuiltinOperation {

        protected JSTemporalCalendarDateFromFields(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public Object dateFromFields(Object thisObj, Object fields, Object optionsParam,
                        @Cached("createSameValue()") JSIdenticalNode identicalNode,
                        @Cached("create()") TemporalGetOptionNode getOptionNode,
                        @Cached("create()") JSToIntegerOrInfinityNode toIntOrInfinityNode) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            if (!isObject(fields)) {
                errorBranch.enter();
                throw TemporalErrors.createTypeErrorFieldsNotAnObject();
            }
            JSDynamicObject options = getOptionsObject(optionsParam);
            JSTemporalDateTimeRecord result = TemporalUtil.isoDateFromFields((JSDynamicObject) fields, options, getContext(),
                            isObjectNode, getOptionNode, toIntOrInfinityNode, identicalNode);

            return JSTemporalPlainDate.create(getContext(), result.getYear(), result.getMonth(), result.getDay(), calendar, errorBranch);
        }
    }

    // 12.4.5
    public abstract static class JSTemporalCalendarYearMonthFromFields extends JSTemporalBuiltinOperation {

        protected JSTemporalCalendarYearMonthFromFields(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public Object yearMonthFromFields(Object thisObj, Object fields, Object optionsParam,
                        @Cached("createSameValue()") JSIdenticalNode identicalNode,
                        @Cached("create()") TemporalGetOptionNode getOptionNode,
                        @Cached("create()") JSToIntegerOrInfinityNode toIntOrInfinityNode) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            if (!isObject(fields)) {
                errorBranch.enter();
                throw TemporalErrors.createTypeErrorFieldsNotAnObject();
            }
            JSDynamicObject options = getOptionsObject(optionsParam);
            JSTemporalYearMonthDayRecord result = TemporalUtil.isoYearMonthFromFields((JSDynamicObject) fields, options, getContext(),
                            isObjectNode, getOptionNode, toIntOrInfinityNode, identicalNode);
            return JSTemporalPlainYearMonth.create(getContext(), result.getYear(), result.getMonth(), calendar, result.getDay(), errorBranch);
        }
    }

    // 12.4.6
    public abstract static class JSTemporalCalendarMonthDayFromFields extends JSTemporalBuiltinOperation {

        protected JSTemporalCalendarMonthDayFromFields(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public Object monthDayFromFields(Object thisObj, Object fields, Object optionsParam,
                        @Cached("createSameValue()") JSIdenticalNode identicalNode,
                        @Cached("create()") TemporalGetOptionNode getOptionNode,
                        @Cached("create()") JSToIntegerOrInfinityNode toIntOrInfinityNode) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            if (!isObject(fields)) {
                errorBranch.enter();
                throw TemporalErrors.createTypeErrorFieldsNotAnObject();
            }
            JSDynamicObject options = getOptionsObject(optionsParam);
            JSTemporalYearMonthDayRecord result = TemporalUtil.isoMonthDayFromFields((JSDynamicObject) fields, options, getContext(),
                            isObjectNode, getOptionNode, toIntOrInfinityNode, identicalNode);
            return JSTemporalPlainMonthDay.create(getContext(), result.getMonth(), result.getDay(), calendar, result.getYear(), errorBranch);
        }
    }

    // 12.4.7
    public abstract static class JSTemporalCalendarDateAdd extends JSTemporalCalendarOperation {

        protected final ConditionProfile needConstrain = ConditionProfile.createBinaryProfile();

        protected JSTemporalCalendarDateAdd(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public Object dateAdd(Object thisObj, Object dateObj, Object durationObj, Object optionsParam,
                        @Cached("create(getContext())") ToTemporalDurationNode toTemporalDurationNode,
                        @Cached("createKeys(getContext())") EnumerableOwnPropertyNamesNode namesNode) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            JSTemporalPlainDateObject date = (JSTemporalPlainDateObject) toTemporalDate(dateObj, Undefined.instance);
            JSTemporalDurationObject duration = (JSTemporalDurationObject) toTemporalDurationNode.executeDynamicObject(durationObj);
            JSDynamicObject options = getOptionsObject(optionsParam);
            Overflow overflow = TemporalUtil.toTemporalOverflow(options, getOptionNode());
            JSTemporalDurationRecord balanceResult = TemporalUtil.balanceDuration(getContext(), namesNode, duration.getDays(), duration.getHours(), duration.getMinutes(), duration.getSeconds(),
                            duration.getMilliseconds(), duration.getMicroseconds(), duration.getNanoseconds(), Unit.DAY);
            JSTemporalDateTimeRecord result = TemporalUtil.addISODate(date.getYear(), date.getMonth(), date.getDay(),
                            dtoiConstrain(duration.getYears()), dtoiConstrain(duration.getMonths()), dtoiConstrain(duration.getWeeks()), dtoiConstrain(balanceResult.getDays()), overflow);
            return JSTemporalPlainDate.create(getContext(), result.getYear(), result.getMonth(), result.getDay(), calendar, errorBranch);
        }

        // in contrast to `dtoi`, set to Integer.MAX_VALUE/MIN_VALUE if outside range.
        // later operations either CONSTRAIN or REJECT anyway!
        protected int dtoiConstrain(double d) {
            if (needConstrain.profile(JSRuntime.doubleIsRepresentableAsInt(d))) {
                return (int) d;
            } else {
                return d > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
            }
        }
    }

    // 12.4.8
    public abstract static class JSTemporalCalendarDateUntil extends JSTemporalCalendarOperation {

        protected JSTemporalCalendarDateUntil(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public Object dateUntil(Object thisObj, Object oneObj, Object twoObj, Object optionsParam,
                        @Cached TruffleString.EqualNode equalNode) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            JSTemporalPlainDateObject one = (JSTemporalPlainDateObject) toTemporalDate(oneObj, Undefined.instance);
            JSTemporalPlainDateObject two = (JSTemporalPlainDateObject) toTemporalDate(twoObj, Undefined.instance);
            JSDynamicObject options = getOptionsObject(optionsParam);
            Unit largestUnit = toLargestTemporalUnit(options, TemporalUtil.listTime, AUTO, Unit.DAY, equalNode);
            JSTemporalDurationRecord result = JSTemporalPlainDate.differenceISODate(
                            one.getYear(), one.getMonth(), one.getDay(), two.getYear(), two.getMonth(), two.getDay(),
                            largestUnit);
            return JSTemporalDuration.createTemporalDuration(getContext(),
                            result.getYears(), result.getMonths(), result.getWeeks(), result.getDays(), 0, 0, 0, 0, 0, 0, errorBranch);
        }
    }

    // 12.4.9
    public abstract static class JSTemporalCalendarYear extends JSTemporalCalendarOperation {

        protected JSTemporalCalendarYear(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public long year(Object thisObj, Object temporalDateLike) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            if (JSTemporalPlainDate.isJSTemporalPlainDate(temporalDateLike)) {
                return ((JSTemporalPlainDateObject) temporalDateLike).getYear();
            } else if (JSTemporalPlainYearMonth.isJSTemporalPlainYearMonth(temporalDateLike)) {
                return ((JSTemporalPlainYearMonthObject) temporalDateLike).getYear();
            } else {
                JSTemporalPlainDateObject td = (JSTemporalPlainDateObject) toTemporalDate(temporalDateLike, Undefined.instance);
                return td.getYear();
            }
        }
    }

    // 12.4.10
    public abstract static class JSTemporalCalendarMonth extends JSTemporalCalendarOperation {

        protected JSTemporalCalendarMonth(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public long month(Object thisObj, Object temporalDateLike) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            Object dateLike = temporalDateLike;

            if (JSTemporalPlainMonthDay.isJSTemporalPlainMonthDay(dateLike)) {
                throw Errors.createTypeError("PlainMonthDay not expected");
            }

            if (!isObject(dateLike) ||
                            (!JSTemporalPlainDate.isJSTemporalPlainDate(dateLike) && !JSTemporalPlainDateTime.isJSTemporalPlainDateTime(dateLike) &&
                                            !JSTemporalPlainYearMonth.isJSTemporalPlainYearMonth(dateLike))) {
                dateLike = toTemporalDate(dateLike, Undefined.instance);
            }
            assert dateLike instanceof TemporalMonth;
            return ((TemporalMonth) dateLike).getMonth();
        }
    }

    // 12.4.11
    public abstract static class JSTemporalCalendarMonthCode extends JSTemporalCalendarOperation {

        protected JSTemporalCalendarMonthCode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public TruffleString monthCode(Object thisObj, Object temporalDateLike) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            Object dateLike = temporalDateLike;
            if (!isObject(dateLike) || (!JSTemporalPlainDate.isJSTemporalPlainDate(dateLike) && !JSTemporalPlainDateTime.isJSTemporalPlainDateTime(temporalDateLike) &&
                            !(dateLike instanceof JSTemporalPlainMonthDayObject) && !JSTemporalPlainYearMonth.isJSTemporalPlainYearMonth(dateLike))) {
                dateLike = toTemporalDate(dateLike, Undefined.instance);
            }
            return TemporalUtil.isoMonthCode((TemporalMonth) dateLike);
        }

    }

    // 12.4.12
    public abstract static class JSTemporalCalendarDay extends JSTemporalBuiltinOperation {

        protected JSTemporalCalendarDay(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public long day(Object thisObj, Object temporalDateLike,
                        @Cached("create(getContext())") ToTemporalDateNode toTemporalDate) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            JSDynamicObject tdl = Undefined.instance;
            if (!JSTemporalPlainDate.isJSTemporalPlainDate(temporalDateLike) && !JSTemporalPlainDateTime.isJSTemporalPlainDateTime(temporalDateLike) &&
                            !JSTemporalPlainMonthDay.isJSTemporalPlainMonthDay(temporalDateLike)) {
                tdl = toTemporalDate.executeDynamicObject(temporalDateLike, Undefined.instance);
            } else {
                tdl = (JSDynamicObject) temporalDateLike;
            }
            return TemporalUtil.isoDay(tdl);
        }
    }

    // 12.4.13
    public abstract static class JSTemporalCalendarDayOfWeek extends JSTemporalCalendarOperation {

        protected JSTemporalCalendarDayOfWeek(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public long dayOfWeek(Object thisObj, Object temporalDateLike) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            JSTemporalPlainDateObject date = (JSTemporalPlainDateObject) toTemporalDate(temporalDateLike, Undefined.instance);
            return TemporalUtil.toISODayOfWeek(date.getYear(), date.getMonth(), date.getDay());
        }
    }

    // 12.4.14
    public abstract static class JSTemporalCalendarDayOfYear extends JSTemporalCalendarOperation {

        protected JSTemporalCalendarDayOfYear(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public long dayOfYear(Object thisObj, Object temporalDateLike) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            JSTemporalPlainDateObject date = (JSTemporalPlainDateObject) toTemporalDate(temporalDateLike, Undefined.instance);
            return TemporalUtil.toISODayOfYear(date.getYear(), date.getMonth(), date.getDay());
        }
    }

    // 12.4.15
    public abstract static class JSTemporalCalendarWeekOfYear extends JSTemporalCalendarOperation {

        protected JSTemporalCalendarWeekOfYear(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public long weekOfYear(Object thisObj, Object temporalDateLike) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            JSTemporalPlainDateObject date = (JSTemporalPlainDateObject) toTemporalDate(temporalDateLike, Undefined.instance);
            return TemporalUtil.toISOWeekOfYear(date.getYear(), date.getMonth(), date.getDay());
        }
    }

    // 12.4.16
    public abstract static class JSTemporalCalendarDaysInWeek extends JSTemporalCalendarOperation {

        protected JSTemporalCalendarDaysInWeek(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public long daysInWeek(Object thisObj, Object temporalDateLike) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            toTemporalDate(temporalDateLike, Undefined.instance);
            return 7;
        }
    }

    // 12.4.17
    public abstract static class JSTemporalCalendarDaysInMonth extends JSTemporalCalendarOperation {

        protected JSTemporalCalendarDaysInMonth(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public long daysInMonth(Object thisObj, Object temporalDateLike) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            Object dateLike = temporalDateLike;
            if (!isObject(dateLike) || (!JSTemporalPlainDate.isJSTemporalPlainDate(dateLike) && !JSTemporalPlainYearMonth.isJSTemporalPlainYearMonth(temporalDateLike))) {
                dateLike = toTemporalDate(dateLike, Undefined.instance);
            }
            return TemporalUtil.isoDaysInMonth(
                            ((TemporalYear) dateLike).getYear(),
                            ((TemporalMonth) dateLike).getMonth());
        }
    }

    // 12.4.18
    public abstract static class JSTemporalCalendarDaysInYear extends JSTemporalCalendarOperation {

        protected JSTemporalCalendarDaysInYear(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public int daysInYear(Object thisObj, Object temporalDateLike) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            int year = 0;
            if (JSTemporalPlainDate.isJSTemporalPlainDate(temporalDateLike)) {
                year = ((JSTemporalPlainDateObject) temporalDateLike).getYear();
            } else if (JSTemporalPlainYearMonth.isJSTemporalPlainYearMonth(temporalDateLike)) {
                year = ((JSTemporalPlainYearMonthObject) temporalDateLike).getYear();
            } else {
                JSTemporalPlainDateObject dateLike = (JSTemporalPlainDateObject) toTemporalDate(temporalDateLike, Undefined.instance);
                year = dateLike.getYear();
            }
            return TemporalUtil.isoDaysInYear(year);
        }
    }

    // 12.4.19
    public abstract static class JSTemporalCalendarMonthsInYear extends JSTemporalCalendarOperation {

        protected JSTemporalCalendarMonthsInYear(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public long monthsInYear(Object thisObj, Object temporalDateLike) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            if (!JSTemporalPlainDate.isJSTemporalPlainDate(temporalDateLike) && !JSTemporalPlainYearMonth.isJSTemporalPlainYearMonth(temporalDateLike)) {
                toTemporalDate(temporalDateLike, Undefined.instance); // discard result
            }
            return 12;
        }
    }

    // 12.4.20
    public abstract static class JSTemporalCalendarInLeapYear extends JSTemporalCalendarOperation {

        protected JSTemporalCalendarInLeapYear(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public boolean inLeapYear(Object thisObj, Object temporalDateLike) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            assert calendar.getId().equals(ISO8601);
            int year = 0;
            if (JSTemporalPlainDate.isJSTemporalPlainDate(temporalDateLike)) {
                year = ((JSTemporalPlainDateObject) temporalDateLike).getYear();
            } else if (JSTemporalPlainYearMonth.isJSTemporalPlainYearMonth(temporalDateLike)) {
                year = ((JSTemporalPlainYearMonthObject) temporalDateLike).getYear();
            } else {
                JSTemporalPlainDateObject dateLike = (JSTemporalPlainDateObject) toTemporalDate(temporalDateLike, Undefined.instance);
                year = dateLike.getYear();
            }
            return TemporalUtil.isISOLeapYear(year);
        }
    }

    // 12.4.23
    public abstract static class JSTemporalCalendarToString extends JSTemporalBuiltinOperation {

        protected JSTemporalCalendarToString(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        public TruffleString toString(Object thisObj) {
            JSTemporalCalendarObject calendar = requireTemporalCalendar(thisObj);
            return calendar.getId();
        }
    }
}
