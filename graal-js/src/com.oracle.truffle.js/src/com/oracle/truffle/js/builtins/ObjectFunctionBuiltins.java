/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.js.builtins;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectAssignNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectCreateNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectDefinePropertiesNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectDefinePropertyNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectFromEntriesNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectGetOwnPropertyDescriptorNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectGetOwnPropertyDescriptorsNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectGetOwnPropertyNamesOrSymbolsNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectGetPrototypeOfNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectHasOwnNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectIsExtensibleNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectIsNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectKeysNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectPreventExtensionsNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectSetIntegrityLevelNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectSetPrototypeOfNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectTestIntegrityLevelNodeGen;
import com.oracle.truffle.js.builtins.ObjectFunctionBuiltinsFactory.ObjectValuesOrEntriesNodeGen;
import com.oracle.truffle.js.builtins.ObjectPrototypeBuiltins.ObjectOperation;
import com.oracle.truffle.js.builtins.helper.ListGetNode;
import com.oracle.truffle.js.builtins.helper.ListSizeNode;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.CreateObjectNode;
import com.oracle.truffle.js.nodes.access.EnumerableOwnPropertyNamesNode;
import com.oracle.truffle.js.nodes.access.FromPropertyDescriptorNode;
import com.oracle.truffle.js.nodes.access.GetIteratorBaseNode;
import com.oracle.truffle.js.nodes.access.GetPrototypeNode;
import com.oracle.truffle.js.nodes.access.IsExtensibleNode;
import com.oracle.truffle.js.nodes.access.IsObjectNode;
import com.oracle.truffle.js.nodes.access.IteratorCloseNode;
import com.oracle.truffle.js.nodes.access.IteratorStepNode;
import com.oracle.truffle.js.nodes.access.IteratorValueNode;
import com.oracle.truffle.js.nodes.access.JSGetOwnPropertyNode;
import com.oracle.truffle.js.nodes.access.JSHasPropertyNode;
import com.oracle.truffle.js.nodes.access.ReadElementNode;
import com.oracle.truffle.js.nodes.access.RequireObjectCoercibleNode;
import com.oracle.truffle.js.nodes.access.ToPropertyDescriptorNode;
import com.oracle.truffle.js.nodes.access.WriteElementNode;
import com.oracle.truffle.js.nodes.binary.JSIdenticalNode;
import com.oracle.truffle.js.nodes.cast.JSToObjectNode;
import com.oracle.truffle.js.nodes.cast.JSToPropertyKeyNode;
import com.oracle.truffle.js.nodes.function.JSBuiltin;
import com.oracle.truffle.js.nodes.function.JSBuiltinNode;
import com.oracle.truffle.js.nodes.interop.ForeignObjectPrototypeNode;
import com.oracle.truffle.js.nodes.interop.ImportValueNode;
import com.oracle.truffle.js.runtime.BigInt;
import com.oracle.truffle.js.runtime.Boundaries;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSConfig;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.Properties;
import com.oracle.truffle.js.runtime.SafeInteger;
import com.oracle.truffle.js.runtime.Strings;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.builtins.BuiltinEnum;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSClass;
import com.oracle.truffle.js.runtime.builtins.JSOrdinary;
import com.oracle.truffle.js.runtime.interop.JSInteropUtil;
import com.oracle.truffle.js.runtime.objects.IteratorRecord;
import com.oracle.truffle.js.runtime.objects.JSAttributes;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.Null;
import com.oracle.truffle.js.runtime.objects.PropertyDescriptor;
import com.oracle.truffle.js.runtime.objects.PropertyProxy;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.util.JSClassProfile;
import com.oracle.truffle.js.runtime.util.Pair;
import com.oracle.truffle.js.runtime.util.UnmodifiableArrayList;

/**
 * Contains builtins for {@linkplain JSDynamicObject} function (constructor).
 */
public final class ObjectFunctionBuiltins extends JSBuiltinsContainer.SwitchEnum<ObjectFunctionBuiltins.ObjectFunction> {
    public static final JSBuiltinsContainer BUILTINS = new ObjectFunctionBuiltins();
    public static final JSBuiltinsContainer BUILTINS_NASHORN_COMPAT = new ObjectFunctionNashornCompatBuiltins();

    protected ObjectFunctionBuiltins() {
        super(JSOrdinary.CLASS_NAME, ObjectFunction.class);
    }

    public enum ObjectFunction implements BuiltinEnum<ObjectFunction> {
        create(2),
        defineProperties(2),
        defineProperty(3),
        freeze(1),
        getOwnPropertyDescriptor(2),
        getOwnPropertyNames(1),
        getPrototypeOf(1),
        isExtensible(1),
        isFrozen(1),
        isSealed(1),
        keys(1),
        preventExtensions(1),
        seal(1),
        setPrototypeOf(2),

        // ES6
        is(2),
        getOwnPropertySymbols(1),
        assign(2),

        // ES8
        getOwnPropertyDescriptors(1),
        values(1),
        entries(1),

        // ES2019
        fromEntries(1),

        // ES2022
        hasOwn(2);

        private final int length;

        ObjectFunction(int length) {
            this.length = length;
        }

        @Override
        public int getLength() {
            return length;
        }

        @Override
        public int getECMAScriptVersion() {
            if (EnumSet.of(is, getOwnPropertySymbols, assign).contains(this)) {
                return 6;
            } else if (EnumSet.of(getOwnPropertyDescriptors, values, entries).contains(this)) {
                return JSConfig.ECMAScript2017;
            } else if (this == fromEntries) {
                return JSConfig.ECMAScript2019;
            } else if (this == hasOwn) {
                return JSConfig.ECMAScript2022;
            }
            return BuiltinEnum.super.getECMAScriptVersion();
        }
    }

    @Override
    protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, ObjectFunction builtinEnum) {
        switch (builtinEnum) {
            case create:
                return ObjectCreateNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case defineProperties:
                return ObjectDefinePropertiesNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case defineProperty:
                return ObjectDefinePropertyNodeGen.create(context, builtin, args().fixedArgs(3).createArgumentNodes(context));
            case freeze:
                return ObjectSetIntegrityLevelNodeGen.create(context, builtin, true, args().fixedArgs(1).createArgumentNodes(context));
            case getOwnPropertyDescriptor:
                return ObjectGetOwnPropertyDescriptorNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case getOwnPropertyDescriptors:
                return ObjectGetOwnPropertyDescriptorsNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case getOwnPropertyNames:
                return ObjectGetOwnPropertyNamesOrSymbolsNodeGen.create(context, builtin, false, args().fixedArgs(1).createArgumentNodes(context));
            case getPrototypeOf:
                return ObjectGetPrototypeOfNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case isExtensible:
                return ObjectIsExtensibleNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case isFrozen:
                return ObjectTestIntegrityLevelNodeGen.create(context, builtin, true, args().fixedArgs(1).createArgumentNodes(context));
            case isSealed:
                return ObjectTestIntegrityLevelNodeGen.create(context, builtin, false, args().fixedArgs(1).createArgumentNodes(context));
            case keys:
                return ObjectKeysNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case preventExtensions:
                return ObjectPreventExtensionsNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case seal:
                return ObjectSetIntegrityLevelNodeGen.create(context, builtin, false, args().fixedArgs(1).createArgumentNodes(context));
            case setPrototypeOf:
                return ObjectSetPrototypeOfNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));

            case is:
                return ObjectIsNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case getOwnPropertySymbols:
                return ObjectGetOwnPropertyNamesOrSymbolsNodeGen.create(context, builtin, true, args().fixedArgs(1).createArgumentNodes(context));
            case assign:
                return ObjectAssignNodeGen.create(context, builtin, args().fixedArgs(1).varArgs().createArgumentNodes(context));
            case values:
                return ObjectValuesOrEntriesNodeGen.create(context, builtin, false, args().fixedArgs(1).createArgumentNodes(context));
            case entries:
                return ObjectValuesOrEntriesNodeGen.create(context, builtin, true, args().fixedArgs(1).createArgumentNodes(context));
            case fromEntries:
                return ObjectFromEntriesNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case hasOwn:
                return ObjectHasOwnNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
        }
        return null;
    }

    public static final class ObjectFunctionNashornCompatBuiltins extends JSBuiltinsContainer.SwitchEnum<ObjectFunctionNashornCompatBuiltins.ObjectNashornCompat> {
        protected ObjectFunctionNashornCompatBuiltins() {
            super(ObjectNashornCompat.class);
        }

        public enum ObjectNashornCompat implements BuiltinEnum<ObjectNashornCompat> {
            bindProperties(2);

            private final int length;

            ObjectNashornCompat(int length) {
                this.length = length;
            }

            @Override
            public int getLength() {
                return length;
            }
        }

        @Override
        protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, ObjectNashornCompat builtinEnum) {
            switch (builtinEnum) {
                case bindProperties:
                    return ObjectFunctionBuiltinsFactory.ObjectBindPropertiesNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            }
            return null;
        }
    }

    public abstract static class ObjectGetPrototypeOfNode extends ObjectOperation {
        @Child private ForeignObjectPrototypeNode foreignObjectPrototypeNode;

        public ObjectGetPrototypeOfNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization(guards = "!isJSObject(object)")
        protected JSDynamicObject getPrototypeOfNonObject(Object object,
                        @Cached("createBinaryProfile()") ConditionProfile isForeignProfile) {
            if (getContext().getEcmaScriptVersion() < 6) {
                if (JSRuntime.isJSPrimitive(object)) {
                    throw Errors.createTypeErrorNotAnObject(object);
                } else {
                    return Null.instance;
                }
            } else {
                if (isForeignProfile.profile(JSRuntime.isForeignObject(object))) {
                    if (InteropLibrary.getUncached(object).isNull(object)) {
                        throw Errors.createTypeErrorNotAnObject(object);
                    }
                    if (getContext().getContextOptions().hasForeignObjectPrototype()) {
                        return getForeignObjectPrototype(object);
                    } else {
                        return Null.instance;
                    }
                } else {
                    assert JSRuntime.isJSPrimitive(object);
                    Object tobject = toObject(object);
                    return JSObject.getPrototype((JSDynamicObject) tobject);
                }
            }
        }

        private JSDynamicObject getForeignObjectPrototype(Object truffleObject) {
            assert JSRuntime.isForeignObject(truffleObject);
            if (foreignObjectPrototypeNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                foreignObjectPrototypeNode = insert(ForeignObjectPrototypeNode.create());
            }
            return foreignObjectPrototypeNode.execute(truffleObject);
        }

        @Specialization(guards = "isJSObject(object)")
        protected JSDynamicObject getPrototypeOfJSObject(JSDynamicObject object,
                        @Cached("create()") GetPrototypeNode getPrototypeNode) {
            return getPrototypeNode.execute(object);
        }
    }

    @ImportStatic({JSConfig.class})
    public abstract static class ObjectGetOwnPropertyDescriptorNode extends ObjectOperation {
        public ObjectGetOwnPropertyDescriptorNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Child private JSToPropertyKeyNode toPropertyKeyNode = JSToPropertyKeyNode.create();
        @Child private JSGetOwnPropertyNode getOwnPropertyNode = JSGetOwnPropertyNode.create();
        @Child private FromPropertyDescriptorNode fromPropertyDescriptorNode = FromPropertyDescriptorNode.create();

        @Specialization(guards = {"isJSObject(thisObj)"})
        protected JSDynamicObject getJSObject(JSDynamicObject thisObj, Object property) {
            Object propertyKey = toPropertyKeyNode.execute(property);
            PropertyDescriptor desc = getOwnPropertyNode.execute(thisObj, propertyKey);
            return fromPropertyDescriptorNode.execute(desc, getContext());
        }

        @Specialization(guards = {"isForeignObject(thisObj)"}, limit = "InteropLibraryLimit")
        protected JSDynamicObject getForeignObject(Object thisObj, Object property,
                        @CachedLibrary("thisObj") InteropLibrary interop,
                        @Cached("create()") ImportValueNode toJSType,
                        @Cached TruffleString.ReadCharUTF16Node charAtNode) {
            Object propertyKey = toPropertyKeyNode.execute(property);
            if (Strings.isTString(propertyKey)) {
                PropertyDescriptor desc = JSInteropUtil.getOwnProperty(thisObj, (TruffleString) propertyKey, interop, toJSType, charAtNode);
                if (desc != null) {
                    return fromPropertyDescriptorNode.execute(desc, getContext());
                }
            }
            return Undefined.instance;
        }

        @Specialization(guards = {"!isJSObject(thisObj)", "!isForeignObject(thisObj)"})
        protected JSDynamicObject getDefault(Object thisObj, Object property) {
            Object object = toObject(thisObj);
            assert JSDynamicObject.isJSDynamicObject(object);
            return getJSObject((JSDynamicObject) object, property);
        }

    }

    @ImportStatic({JSConfig.class})
    public abstract static class ObjectGetOwnPropertyDescriptorsNode extends ObjectOperation {

        @Child private FromPropertyDescriptorNode fromPropertyDescriptorNode = FromPropertyDescriptorNode.create();
        @Child private DynamicObjectLibrary putPropDescNode = DynamicObjectLibrary.getFactory().createDispatched(JSConfig.PropertyCacheLimit);

        public ObjectGetOwnPropertyDescriptorsNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        protected abstract JSDynamicObject executeEvaluated(Object obj);

        @Specialization(guards = {"isJSObject(thisObj)"})
        protected JSDynamicObject getJSObject(JSDynamicObject thisObj,
                        @Cached JSGetOwnPropertyNode getOwnPropertyNode,
                        @Cached ListSizeNode listSize,
                        @Cached ListGetNode listGet,
                        @Cached JSClassProfile classProfile) {
            JSDynamicObject retObj = JSOrdinary.create(getContext(), getRealm());

            List<Object> ownPropertyKeys = JSObject.ownPropertyKeys(thisObj, classProfile);
            int size = listSize.execute(ownPropertyKeys);
            for (int i = 0; i < size; i++) {
                Object key = listGet.execute(ownPropertyKeys, i);
                assert JSRuntime.isPropertyKey(key);
                PropertyDescriptor desc = getOwnPropertyNode.execute(thisObj, key);
                if (desc != null) {
                    JSDynamicObject propDesc = fromPropertyDescriptorNode.execute(desc, getContext());
                    Properties.putWithFlags(putPropDescNode, retObj, key, propDesc, JSAttributes.configurableEnumerableWritable());
                }
            }
            return retObj;
        }

        @Specialization(guards = {"isForeignObject(thisObj)"}, limit = "InteropLibraryLimit")
        protected JSDynamicObject getForeignObject(Object thisObj,
                        @CachedLibrary("thisObj") InteropLibrary interop,
                        @CachedLibrary(limit = "InteropLibraryLimit") InteropLibrary members,
                        @Cached("create()") ImportValueNode toJSType,
                        @Cached BranchProfile errorBranch) {
            JSDynamicObject result = JSOrdinary.create(getContext(), getRealm());
            try {
                if (interop.hasMembers(thisObj)) {
                    Object keysObj = interop.getMembers(thisObj);
                    long size = members.getArraySize(keysObj);
                    if (size < 0 || size >= Integer.MAX_VALUE) {
                        errorBranch.enter();
                        throw Errors.createRangeErrorInvalidArrayLength();
                    }
                    for (int i = 0; i < size; i++) {
                        String member = (String) members.readArrayElement(keysObj, i);
                        PropertyDescriptor desc = JSInteropUtil.getExistingMemberProperty(thisObj, member, interop, toJSType);
                        if (desc != null) {
                            JSDynamicObject propDesc = fromPropertyDescriptorNode.execute(desc, getContext());
                            Properties.putWithFlags(putPropDescNode, result, Strings.fromJavaString(member), propDesc, JSAttributes.configurableEnumerableWritable());
                        }
                    }
                }
                if (interop.hasArrayElements(thisObj)) {
                    long size = interop.getArraySize(thisObj);
                    if (size < 0 || size >= Integer.MAX_VALUE) {
                        errorBranch.enter();
                        throw Errors.createRangeErrorInvalidArrayLength();
                    }
                    for (long i = 0; i < size; i++) {
                        PropertyDescriptor desc = JSInteropUtil.getArrayElementProperty(thisObj, i, interop, toJSType);
                        if (desc != null) {
                            JSDynamicObject propDesc = fromPropertyDescriptorNode.execute(desc, getContext());
                            Properties.putWithFlags(putPropDescNode, result, Strings.fromLong(i), propDesc, JSAttributes.configurableEnumerableWritable());
                        }
                    }
                }
            } catch (InteropException iex) {
            }

            return result;
        }

        @Specialization(guards = {"!isJSObject(thisObj)", "!isForeignObject(thisObj)"})
        protected JSDynamicObject getDefault(Object thisObj,
                        @Cached("createRecursive()") ObjectGetOwnPropertyDescriptorsNode recursive) {
            Object object = toObject(thisObj);
            return recursive.executeEvaluated(object);
        }

        ObjectGetOwnPropertyDescriptorsNode createRecursive() {
            return ObjectGetOwnPropertyDescriptorsNodeGen.create(getContext(), getBuiltin(), new JavaScriptNode[0]);
        }
    }

    @ImportStatic(JSConfig.class)
    public abstract static class ObjectGetOwnPropertyNamesOrSymbolsNode extends ObjectOperation {
        protected final boolean symbols;

        public ObjectGetOwnPropertyNamesOrSymbolsNode(JSContext context, JSBuiltin builtin, boolean symbols) {
            super(context, builtin);
            this.symbols = symbols;
        }

        @Specialization(guards = "isJSObject(thisObj)")
        protected JSDynamicObject getJSObject(JSDynamicObject thisObj,
                        @Cached @Shared("jsclassProfile") JSClassProfile jsclassProfile,
                        @Cached @Shared("listSize") ListSizeNode listSize) {
            List<Object> ownPropertyKeys = jsclassProfile.getJSClass(thisObj).getOwnPropertyKeys(thisObj, !symbols, symbols);
            return JSArray.createLazyArray(getContext(), getRealm(), ownPropertyKeys, listSize.execute(ownPropertyKeys));
        }

        @Specialization(guards = {"!isJSObject(thisObj)", "!isForeignObject(thisObj)"})
        protected JSDynamicObject getDefault(Object thisObj,
                        @Cached @Shared("jsclassProfile") JSClassProfile jsclassProfile,
                        @Cached @Shared("listSize") ListSizeNode listSize) {
            JSDynamicObject object = toOrAsJSObject(thisObj);
            return getJSObject(object, jsclassProfile, listSize);
        }

        @Specialization(guards = {"isForeignObject(thisObj)", "symbols"})
        protected JSDynamicObject getForeignObjectSymbols(@SuppressWarnings("unused") Object thisObj) {
            // TruffleObjects can never have symbols.
            return JSArray.createConstantEmptyArray(getContext(), getRealm());
        }

        @Specialization(guards = {"isForeignObject(thisObj)", "!symbols"})
        protected JSDynamicObject getForeignObjectNames(Object thisObj,
                        @Cached("createKeys(getContext())") EnumerableOwnPropertyNamesNode enumerableOwnPropertyNamesNode,
                        @Cached ConditionProfile hasElements) {
            UnmodifiableArrayList<? extends Object> keyList = enumerableOwnPropertyNamesNode.execute(thisObj);
            int len = keyList.size();
            JSRealm realm = getRealm();
            if (hasElements.profile(len > 0)) {
                assert keyList.stream().allMatch(Strings::isTString);
                return JSArray.createConstant(getContext(), realm, keyList.toArray());
            }
            return JSArray.createEmptyChecked(getContext(), realm, 0);
        }
    }

    protected abstract static class ObjectDefineOperation extends ObjectOperation {

        @Child private ToPropertyDescriptorNode toPropertyDescriptorNode;

        public ObjectDefineOperation(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        protected PropertyDescriptor toPropertyDescriptor(Object target) {
            if (toPropertyDescriptorNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toPropertyDescriptorNode = insert(ToPropertyDescriptorNode.create(getContext()));
            }
            return (PropertyDescriptor) toPropertyDescriptorNode.execute(target);
        }

        @TruffleBoundary
        protected JSDynamicObject intlDefineProperties(JSDynamicObject obj, JSDynamicObject descs) {
            List<Pair<Object, PropertyDescriptor>> descriptors = new ArrayList<>();
            JSClass descsClass = JSObject.getJSClass(descs);
            for (Object key : descsClass.ownPropertyKeys(descs)) {
                PropertyDescriptor keyDesc = descsClass.getOwnProperty(descs, key);
                if (keyDesc != null && keyDesc.getEnumerable()) {
                    PropertyDescriptor desc = toPropertyDescriptor(descsClass.get(descs, key));
                    Boundaries.listAdd(descriptors, new Pair<>(key, desc));
                }
            }
            for (Pair<Object, PropertyDescriptor> descPair : descriptors) {
                JSRuntime.definePropertyOrThrow(obj, descPair.getFirst(), descPair.getSecond());
            }
            return obj;
        }
    }

    @ImportStatic(JSConfig.class)
    public abstract static class ObjectCreateNode extends ObjectDefineOperation {
        public ObjectCreateNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Child private CreateObjectNode.CreateObjectWithPrototypeNode objectCreateNode;
        private final BranchProfile needDefineProperties = BranchProfile.create();

        @SuppressWarnings("unused")
        @Specialization(guards = "isJSNull(prototype)")
        protected JSDynamicObject createPrototypeNull(Object prototype, Object properties) {
            JSDynamicObject ret = JSOrdinary.createWithNullPrototype(getContext());
            return objectDefineProperties(ret, properties);
        }

        @Specialization(guards = {"!isJSNull(prototype)", "!isJSObject(prototype)"}, limit = "InteropLibraryLimit")
        protected JSDynamicObject createForeignNullOrInvalidPrototype(Object prototype, Object properties,
                        @CachedLibrary("prototype") InteropLibrary interop,
                        @Cached("createBinaryProfile()") ConditionProfile isNull) {
            assert prototype != null;
            if (isNull.profile(prototype != Undefined.instance && interop.isNull(prototype))) {
                return createPrototypeNull(Null.instance, properties);
            } else {
                throw Errors.createTypeErrorInvalidPrototype(prototype);
            }
        }

        @Specialization(guards = {"isJSObject(prototype)", "isJSObject(properties)"})
        protected JSDynamicObject createObjectObject(JSDynamicObject prototype, JSDynamicObject properties) {
            JSDynamicObject ret = createObjectWithPrototype(prototype);
            intlDefineProperties(ret, properties);
            return ret;
        }

        @Specialization(guards = {"isJSObject(prototype)", "!isJSNull(properties)"})
        protected JSDynamicObject createObjectNotNull(JSDynamicObject prototype, Object properties) {
            JSDynamicObject ret = createObjectWithPrototype(prototype);
            return objectDefineProperties(ret, properties);
        }

        @SuppressWarnings("unused")
        @Specialization(guards = {"isJSObject(prototype)", "isJSNull(properties)"})
        protected JSDynamicObject createObjectNull(JSDynamicObject prototype, Object properties) {
            throw Errors.createTypeErrorNotObjectCoercible(properties, null, getContext());
        }

        private JSDynamicObject objectDefineProperties(JSDynamicObject ret, Object properties) {
            if (properties != Undefined.instance) {
                needDefineProperties.enter();
                intlDefineProperties(ret, toJSObject(properties));
            }
            return ret;
        }

        private JSDynamicObject createObjectWithPrototype(JSDynamicObject prototype) {
            if (objectCreateNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                objectCreateNode = insert(CreateObjectNode.createOrdinaryWithPrototype(getContext()));
            }
            return objectCreateNode.execute(prototype);
        }
    }

    public abstract static class ObjectDefinePropertyNode extends ObjectDefineOperation {
        public ObjectDefinePropertyNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Child private JSToPropertyKeyNode toPropertyKeyNode = JSToPropertyKeyNode.create();

        @Specialization(guards = "isJSObject(thisObj)")
        protected JSDynamicObject definePropertyJSObjectTString(JSDynamicObject thisObj, TruffleString property, Object attributes) {
            PropertyDescriptor desc = toPropertyDescriptor(attributes);
            JSRuntime.definePropertyOrThrow(thisObj, property, desc);
            return thisObj;
        }

        @Specialization(replaces = "definePropertyJSObjectTString")
        protected JSDynamicObject definePropertyGeneric(Object thisObj, Object property, Object attributes) {
            JSDynamicObject object = asJSObject(thisObj);
            PropertyDescriptor desc = toPropertyDescriptor(attributes);
            Object propertyKey = toPropertyKeyNode.execute(property);
            JSRuntime.definePropertyOrThrow(object, propertyKey, desc);
            return object;
        }

        @Override
        protected JavaScriptNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return ObjectDefinePropertyNodeGen.create(getContext(), getBuiltin(), cloneUninitialized(getArguments(), materializedTags));
        }
    }

    public abstract static class ObjectDefinePropertiesNode extends ObjectDefineOperation {

        public ObjectDefinePropertiesNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization(guards = {"isJSObject(thisObj)", "isJSObject(properties)"})
        protected JSDynamicObject definePropertiesObjectObject(JSDynamicObject thisObj, JSDynamicObject properties) {
            return intlDefineProperties(thisObj, properties);
        }

        @Specialization(replaces = "definePropertiesObjectObject")
        protected JSDynamicObject definePropertiesGeneric(Object thisObj, Object properties) {
            JSDynamicObject object = asJSObject(thisObj);
            return intlDefineProperties(object, toJSObject(properties));
        }
    }

    public abstract static class ObjectIsExtensibleNode extends ObjectOperation {
        public ObjectIsExtensibleNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization(guards = "isJSObject(thisObj)")
        protected boolean isExtensibleObject(JSDynamicObject thisObj,
                        @Cached IsExtensibleNode isExtensibleNode) {
            return isExtensibleNode.executeBoolean(thisObj);
        }

        @Specialization(guards = "!isJSObject(thisObj)")
        protected boolean isExtensibleNonObject(Object thisObj) {
            if (getContext().getEcmaScriptVersion() < 6) {
                throw createTypeErrorCalledOnNonObject(thisObj);
            }
            return false;
        }
    }

    public abstract static class ObjectPreventExtensionsNode extends ObjectOperation {
        public ObjectPreventExtensionsNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization(guards = "isJSObject(thisObj)")
        protected JSDynamicObject preventExtensionsObject(JSDynamicObject thisObj) {
            JSObject.preventExtensions(thisObj, true);
            return thisObj;
        }

        @Specialization(guards = "!isJSObject(thisObj)")
        protected Object preventExtensionsNonObject(Object thisObj) {
            if (getContext().getEcmaScriptVersion() < 6) {
                throw createTypeErrorCalledOnNonObject(thisObj);
            }
            return thisObj;
        }
    }

    /**
     * Implementing isFrozen, isSealed via testIntegrityLevel().
     *
     */
    public abstract static class ObjectTestIntegrityLevelNode extends ObjectOperation {
        private final boolean frozen;
        private final ConditionProfile isObject = ConditionProfile.createBinaryProfile();

        public ObjectTestIntegrityLevelNode(JSContext context, JSBuiltin builtin, boolean frozen) {
            super(context, builtin);
            this.frozen = frozen;
        }

        @Specialization
        protected boolean testIntegrityLevel(Object thisObj) {
            if (isObject.profile(JSRuntime.isObject(thisObj))) {
                return JSObject.testIntegrityLevel((JSDynamicObject) thisObj, frozen);
            } else {
                if (getContext().getEcmaScriptVersion() < 6) {
                    throw createTypeErrorCalledOnNonObject(thisObj);
                }
                return true;
            }
        }
    }

    /**
     * SetIntegrityLevel, implements freeze() and seal().
     *
     */
    public abstract static class ObjectSetIntegrityLevelNode extends ObjectOperation {
        private final boolean freeze;
        private final ConditionProfile isObject = ConditionProfile.createBinaryProfile();

        public ObjectSetIntegrityLevelNode(JSContext context, JSBuiltin builtin, boolean freeze) {
            super(context, builtin);
            this.freeze = freeze;
        }

        @Specialization
        protected Object setIntegrityLevel(Object thisObj) {
            if (isObject.profile(JSRuntime.isObject(thisObj))) {
                JSObject.setIntegrityLevel((JSDynamicObject) thisObj, freeze, true);
            } else {
                if (getContext().getEcmaScriptVersion() < 6) {
                    throw createTypeErrorCalledOnNonObject(thisObj);
                }
            }
            return thisObj;
        }
    }

    @ImportStatic(JSConfig.class)
    public abstract static class ObjectKeysNode extends ObjectOperation {
        @Child private EnumerableOwnPropertyNamesNode enumerableOwnPropertyNamesNode;
        private final ConditionProfile hasElements = ConditionProfile.createBinaryProfile();

        public ObjectKeysNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        private JSDynamicObject keys(Object obj) {
            assert JSObject.isJSObject(obj) || JSRuntime.isForeignObject(obj);
            UnmodifiableArrayList<? extends Object> keyList = enumerableOwnPropertyNames(obj);
            int len = keyList.size();
            JSRealm realm = getRealm();
            if (hasElements.profile(len > 0)) {
                assert keyList.stream().allMatch(Strings::isTString);
                return JSArray.createConstant(getContext(), realm, keyList.toArray());
            }
            return JSArray.createEmptyChecked(getContext(), realm, 0);
        }

        @Specialization(guards = "isJSDynamicObject(thisObj)")
        protected JSDynamicObject keysDynamicObject(JSDynamicObject thisObj) {
            return keys(toOrAsJSObject(thisObj));
        }

        @Specialization
        protected JSDynamicObject keysSymbol(Symbol symbol) {
            return keys(toOrAsJSObject(symbol));
        }

        @Specialization
        protected JSDynamicObject keysString(TruffleString string) {
            return keys(toOrAsJSObject(string));
        }

        @Specialization
        protected JSDynamicObject keysSafeInt(SafeInteger largeInteger) {
            return keys(toOrAsJSObject(largeInteger));
        }

        @Specialization
        protected JSDynamicObject keysBigInt(BigInt bigInt) {
            return keys(toOrAsJSObject(bigInt));
        }

        @Specialization(guards = "!isTruffleObject(thisObj)")
        protected JSDynamicObject keysOther(Object thisObj) {
            return keys(toOrAsJSObject(thisObj));
        }

        @Specialization(guards = "isForeignObject(obj)")
        protected JSDynamicObject keysForeign(Object obj) {
            return keys(obj);
        }

        private UnmodifiableArrayList<? extends Object> enumerableOwnPropertyNames(Object obj) {
            if (enumerableOwnPropertyNamesNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                enumerableOwnPropertyNamesNode = insert(EnumerableOwnPropertyNamesNode.createKeys(getContext()));
            }
            return enumerableOwnPropertyNamesNode.execute(obj);
        }

    }

    public abstract static class ObjectSetPrototypeOfNode extends ObjectOperation {
        private final BranchProfile errorBranch = BranchProfile.create();
        private final JSClassProfile classProfile = JSClassProfile.create();

        public ObjectSetPrototypeOfNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization(guards = {"isValidPrototype(newProto)"})
        final Object setPrototypeOfJSObject(JSObject object, JSDynamicObject newProto) {
            if (!JSObject.setPrototype(object, newProto, classProfile)) {
                errorBranch.enter();
                throw Errors.createTypeError("setPrototype failed");
            }
            return object;
        }

        @Specialization(guards = {"!isValidPrototype(newProto)"})
        static Object setPrototypeOfJSObjectToInvalidNewProto(@SuppressWarnings("unused") JSObject object, Object newProto) {
            throw Errors.createTypeErrorInvalidPrototype(newProto);
        }

        @Specialization(guards = {"isNullOrUndefined(object)"})
        final Object setPrototypeOfNonObjectCoercible(Object object, @SuppressWarnings("unused") Object newProto) {
            // ? RequireObjectCoercible(O).
            throw createTypeErrorCalledOnNonObject(object);
        }

        @Specialization(guards = {"!isJSObject(object)", "!isNullOrUndefined(object)", "!isForeignObject(object)"})
        static Object setPrototypeOfValue(Object object, @SuppressWarnings("unused") Object newProto) {
            // If Type(O) is not Object, return O.
            return object;
        }

        @Specialization(guards = {"isForeignObject(object)"})
        final Object setPrototypeOfForeignObject(Object object, @SuppressWarnings("unused") Object newProto) {
            throw createTypeErrorCalledOnNonObject(object);
        }
    }

    public abstract static class ObjectIsNode extends ObjectOperation {
        public ObjectIsNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected boolean isInt(int a, int b) {
            return a == b;
        }

        @Specialization
        protected boolean isDouble(double a, double b) {
            if (a == 0 && b == 0) {
                return JSRuntime.isNegativeZero(a) == JSRuntime.isNegativeZero(b);
            }
            if (Double.isNaN(a)) {
                return Double.isNaN(b);
            }
            return a == b;
        }

        @Specialization(guards = "isNumberNumber(a,b)") // GR-7577
        protected boolean isNumberNumber(Number a, Number b,
                        @Cached("createSameValue()") JSIdenticalNode doIdenticalNode) {
            return doIdenticalNode.executeBoolean(JSRuntime.doubleValue(a), JSRuntime.doubleValue(b));
        }

        @Specialization(guards = "!isNumberNumber(a, b)")
        protected boolean isObject(Object a, Object b,
                        @Cached("createSameValue()") JSIdenticalNode doIdenticalNode) {
            return doIdenticalNode.executeBoolean(a, b);
        }

        protected boolean isNumberNumber(Object a, Object b) {
            return a instanceof Number && b instanceof Number;
        }
    }

    public abstract static class ObjectAssignNode extends JSBuiltinNode {

        protected static final boolean STRICT = true;

        public ObjectAssignNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object assign(Object target, Object[] sources,
                        @Cached("createToObject(getContext())") JSToObjectNode toObjectNode,
                        @Cached("create(getContext(), STRICT)") WriteElementNode write,
                        @Cached("create(getContext())") AssignPropertiesNode assignProperties) {
            Object to = toObjectNode.execute(target);
            if (sources.length == 0) {
                return to;
            }
            for (Object o : sources) {
                if (!JSRuntime.isNullOrUndefined(o)) {
                    Object from = toObjectNode.execute(o);
                    assignProperties.executeVoid(to, from, write);
                }
            }
            return to;
        }
    }

    @ImportStatic(JSConfig.class)
    abstract static class AssignPropertiesNode extends JavaScriptBaseNode {
        protected final JSContext context;

        protected AssignPropertiesNode(JSContext context) {
            this.context = context;
        }

        abstract void executeVoid(Object to, Object from, WriteElementNode write);

        @Specialization(guards = {"isJSObject(from)"})
        protected static void copyPropertiesFromJSObject(Object to, JSDynamicObject from, WriteElementNode write,
                        @Cached("create(context)") ReadElementNode read,
                        @Cached("create(false)") JSGetOwnPropertyNode getOwnProperty,
                        @Cached ListSizeNode listSize,
                        @Cached ListGetNode listGet,
                        @Cached JSClassProfile classProfile) {
            List<Object> ownPropertyKeys = JSObject.ownPropertyKeys(from, classProfile);
            int size = listSize.execute(ownPropertyKeys);
            for (int i = 0; i < size; i++) {
                Object nextKey = listGet.execute(ownPropertyKeys, i);
                assert JSRuntime.isPropertyKey(nextKey);
                PropertyDescriptor desc = getOwnProperty.execute(from, nextKey);
                if (desc != null && desc.getEnumerable()) {
                    Object propValue = read.executeWithTargetAndIndex(from, nextKey);
                    write.executeWithTargetAndIndexAndValue(to, nextKey, propValue);
                }
            }
        }

        @Specialization(guards = {"!isJSObject(from)"}, limit = "InteropLibraryLimit")
        protected final void doObject(Object to, Object from, WriteElementNode write,
                        @CachedLibrary("from") InteropLibrary fromInterop,
                        @CachedLibrary(limit = "InteropLibraryLimit") InteropLibrary keysInterop,
                        @CachedLibrary(limit = "InteropLibraryLimit") InteropLibrary stringInterop) {
            if (fromInterop.isNull(from)) {
                return;
            }
            try {
                Object members = fromInterop.getMembers(from);
                long length = JSInteropUtil.getArraySize(members, keysInterop, this);
                for (long i = 0; i < length; i++) {
                    Object key = keysInterop.readArrayElement(members, i);
                    String stringKey = Strings.interopAsString(stringInterop, key);
                    Object value = fromInterop.readMember(from, stringKey);
                    write.executeWithTargetAndIndexAndValue(to, Strings.fromJavaString(stringKey), value);
                }
            } catch (UnsupportedMessageException | InvalidArrayIndexException | UnknownIdentifierException e) {
                throw Errors.createTypeErrorInteropException(from, e, "CopyDataProperties", this);
            }
        }
    }

    @ImportStatic(JSConfig.class)
    public abstract static class ObjectValuesOrEntriesNode extends ObjectOperation {
        protected final boolean entries;

        @Child private EnumerableOwnPropertyNamesNode enumerableOwnPropertyNamesNode;
        private final ConditionProfile hasElements = ConditionProfile.createBinaryProfile();

        public ObjectValuesOrEntriesNode(JSContext context, JSBuiltin builtin, boolean entries) {
            super(context, builtin);
            this.entries = entries;
        }

        protected abstract JSDynamicObject executeEvaluated(Object obj);

        @Specialization(guards = "isJSObject(obj)")
        protected JSDynamicObject valuesOrEntriesJSObject(JSDynamicObject obj) {
            return valuesOrEntries(obj);
        }

        private JSDynamicObject valuesOrEntries(Object obj) {
            assert JSObject.isJSObject(obj) || JSRuntime.isForeignObject(obj);
            UnmodifiableArrayList<? extends Object> list = enumerableOwnPropertyNames(obj);
            int len = list.size();
            JSRealm realm = getRealm();
            if (hasElements.profile(len > 0)) {
                return JSArray.createConstant(getContext(), realm, list.toArray());
            }
            return JSArray.createEmptyChecked(getContext(), realm, 0);
        }

        protected UnmodifiableArrayList<? extends Object> enumerableOwnPropertyNames(Object obj) {
            if (enumerableOwnPropertyNamesNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                enumerableOwnPropertyNamesNode = insert(entries ? EnumerableOwnPropertyNamesNode.createKeysValues(getContext()) : EnumerableOwnPropertyNamesNode.createValues(getContext()));
            }
            return enumerableOwnPropertyNamesNode.execute(obj);
        }

        @Specialization(guards = {"isForeignObject(thisObj)"})
        protected JSDynamicObject valuesOrEntriesForeign(Object thisObj) {
            return valuesOrEntries(thisObj);
        }

        @Specialization(guards = {"!isJSObject(obj)", "!isForeignObject(obj)"})
        protected JSDynamicObject valuesOrEntriesGeneric(Object obj,
                        @Cached("createRecursive()") ObjectValuesOrEntriesNode recursive) {
            Object thisObj = toObject(obj);
            return recursive.executeEvaluated(thisObj);
        }

        ObjectValuesOrEntriesNode createRecursive() {
            return ObjectValuesOrEntriesNodeGen.create(getContext(), getBuiltin(), entries, new JavaScriptNode[0]);
        }
    }

    public abstract static class ObjectFromEntriesNode extends ObjectOperation {
        @Child private RequireObjectCoercibleNode requireObjectCoercibleNode = RequireObjectCoercibleNode.create();
        @Child private GetIteratorBaseNode getIteratorNode;
        @Child private IteratorStepNode iteratorStepNode;
        @Child private IteratorValueNode iteratorValueNode;
        @Child private IsObjectNode isObjectNode = IsObjectNode.create();
        @Child private IteratorCloseNode iteratorCloseNode;
        @Child private JSToPropertyKeyNode toPropertyKeyNode = JSToPropertyKeyNode.create();
        @Child private ReadElementNode readElementNode;
        private final BranchProfile errorBranch = BranchProfile.create();

        public ObjectFromEntriesNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
            this.getIteratorNode = GetIteratorBaseNode.create();
            this.iteratorStepNode = IteratorStepNode.create();
            this.iteratorValueNode = IteratorValueNode.create();
            this.readElementNode = ReadElementNode.create(context);
        }

        @Specialization
        protected JSDynamicObject entries(Object iterable) {
            requireObjectCoercibleNode.executeVoid(iterable);
            JSObject obj = JSOrdinary.create(getContext(), getRealm());
            return addEntriesFromIterable(obj, iterable);
        }

        private JSDynamicObject addEntriesFromIterable(JSDynamicObject target, Object iterable) {
            assert !JSRuntime.isNullOrUndefined(target);
            IteratorRecord iteratorRecord = getIteratorNode.execute(iterable);
            try {
                while (true) {
                    Object next = iteratorStepNode.execute(iteratorRecord);
                    if (next == Boolean.FALSE) {
                        return target;
                    }
                    Object nextItem = iteratorValueNode.execute(next);
                    if (!isObjectNode.executeBoolean(nextItem)) {
                        errorBranch.enter();
                        throw Errors.createTypeErrorIteratorResultNotObject(nextItem, this);
                    }
                    Object k = readElementNode.executeWithTargetAndIndex(nextItem, 0);
                    Object v = readElementNode.executeWithTargetAndIndex(nextItem, 1);
                    createDataPropertyOnObject(target, k, v);
                }
            } catch (AbstractTruffleException ex) {
                errorBranch.enter();
                iteratorCloseAbrupt(iteratorRecord.getIterator());
                throw ex;
            }
        }

        private void createDataPropertyOnObject(JSDynamicObject thisObject, Object key, Object value) {
            assert JSRuntime.isObject(thisObject);
            Object propertyKey = toPropertyKeyNode.execute(key);
            JSRuntime.createDataPropertyOrThrow(thisObject, propertyKey, value);
        }

        private void iteratorCloseAbrupt(JSDynamicObject iterator) {
            if (iteratorCloseNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                iteratorCloseNode = insert(IteratorCloseNode.create(getContext()));
            }
            iteratorCloseNode.executeAbrupt(iterator);
        }
    }

    @ImportStatic({JSConfig.class})
    public abstract static class ObjectBindPropertiesNode extends ObjectOperation {
        @Child private EnumerableOwnPropertyNamesNode enumerableOwnPropertyNamesNode;
        private final JSClassProfile sourceProfile = JSClassProfile.create();
        private final JSClassProfile targetProfile = JSClassProfile.create();

        public ObjectBindPropertiesNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization(guards = "!isJSObject(target)")
        protected JSDynamicObject bindPropertiesInvalidTarget(Object target, @SuppressWarnings("unused") Object source) {
            throw Errors.createTypeErrorNotAnObject(target, this);
        }

        @Specialization(guards = {"isJSObject(target)", "isJSDynamicObject(source)"})
        protected JSDynamicObject bindPropertiesDynamicObject(JSDynamicObject target, JSDynamicObject source) {
            JSDynamicObject sourceObject = toJSObject(source);
            boolean extensible = JSObject.isExtensible(target, targetProfile);
            JSClass sourceClass = sourceProfile.getJSClass(sourceObject);
            UnmodifiableArrayList<? extends Object> keys = enumerableOwnPropertyNames(sourceObject);
            int length = keys.size();
            for (int i = 0; i < length; i++) {
                Object key = keys.get(i);
                if (!JSObject.hasOwnProperty(target, key, targetProfile)) {
                    if (!extensible) {
                        throw Errors.createTypeErrorNotExtensible(target, key);
                    }
                    PropertyDescriptor desc = JSObject.getOwnProperty(sourceObject, key, sourceProfile);
                    if (desc.isAccessorDescriptor()) {
                        JSObject.defineOwnProperty(target, key, desc);
                    } else {
                        JSObjectUtil.defineProxyProperty(target, key, new BoundProperty(source, key, sourceClass), desc.getFlags());
                    }
                }
            }
            return target;
        }

        @Specialization(guards = "isJSObject(target)")
        protected JSDynamicObject bindProperties(JSDynamicObject target, Symbol source) {
            return bindPropertiesDynamicObject(target, toJSObject(source));
        }

        @Specialization(guards = "isJSObject(target)")
        protected JSDynamicObject bindProperties(JSDynamicObject target, TruffleString source) {
            return bindPropertiesDynamicObject(target, toJSObject(source));
        }

        @Specialization(guards = "isJSObject(target)")
        protected JSDynamicObject bindProperties(JSDynamicObject target, SafeInteger source) {
            return bindPropertiesDynamicObject(target, toJSObject(source));
        }

        @Specialization(guards = "isJSObject(target)")
        protected JSDynamicObject bindProperties(JSDynamicObject target, BigInt source) {
            return bindPropertiesDynamicObject(target, toJSObject(source));
        }

        @Specialization(guards = {"isJSObject(target)", "!isTruffleObject(source)"})
        protected JSDynamicObject bindProperties(JSDynamicObject target, Object source) {
            return bindPropertiesDynamicObject(target, toJSObject(source));
        }

        @Specialization(guards = {"isJSObject(target)", "isForeignObject(source)"}, limit = "InteropLibraryLimit")
        protected JSDynamicObject bindProperties(JSDynamicObject target, Object source,
                        @CachedLibrary("source") InteropLibrary interop,
                        @CachedLibrary(limit = "InteropLibraryLimit") InteropLibrary members) {
            if (interop.hasMembers(source)) {
                try {
                    boolean extensible = JSObject.isExtensible(target, targetProfile);
                    boolean hostObject = getRealm().getEnv().isHostObject(source);
                    Object keysObj = interop.getMembers(source);
                    long size = members.getArraySize(keysObj);
                    for (int i = 0; i < size; i++) {
                        Object key = members.readArrayElement(keysObj, i);
                        String stringKey = Strings.interopAsString(key);
                        TruffleString tStringKey = Strings.interopAsTruffleString(key);
                        if (!JSObject.hasOwnProperty(target, tStringKey, targetProfile)) {
                            if (!extensible) {
                                throw Errors.createTypeErrorNotExtensible(target, key);
                            }
                            JSObjectUtil.defineProxyProperty(target, tStringKey, new ForeignBoundProperty(source, stringKey), JSAttributes.getDefault());
                        }
                        if (hostObject) {
                            // Special handling of bean properties: when there is "getProp",
                            // "setProp" or "isProp" but not "prop" in the source then define also
                            // "prop" in the target (unless the target has "prop" already).
                            String beanProperty;
                            if (stringKey.length() > 3 && (stringKey.charAt(0) == 's' || stringKey.charAt(0) == 'g') && stringKey.charAt(1) == 'e' && stringKey.charAt(2) == 't' &&
                                            Boundaries.characterIsUpperCase(stringKey.charAt(3))) {
                                beanProperty = beanProperty(stringKey, 3);
                            } else if (stringKey.length() > 2 && stringKey.charAt(0) == 'i' && stringKey.charAt(1) == 's' && Boundaries.characterIsUpperCase(stringKey.charAt(2))) {
                                beanProperty = beanProperty(stringKey, 2);
                            } else {
                                continue;
                            }
                            TruffleString tStringBeanProperty = Strings.fromJavaString(beanProperty);
                            if (!JSObject.hasOwnProperty(target, tStringBeanProperty, targetProfile) && !interop.isMemberExisting(source, beanProperty)) {
                                String getKey = beanAccessor("get", beanProperty);
                                String getter;
                                if (interop.isMemberExisting(source, getKey)) {
                                    getter = getKey;
                                } else {
                                    String isKey = beanAccessor("is", beanProperty);
                                    if (interop.isMemberExisting(source, isKey)) {
                                        getter = isKey;
                                    } else {
                                        getter = null;
                                    }
                                }
                                String setKey = beanAccessor("set", beanProperty);
                                String setter = interop.isMemberExisting(source, setKey) ? setKey : null;
                                JSObjectUtil.defineProxyProperty(target, tStringBeanProperty, new ForeignBoundBeanProperty(source, getter, setter), JSAttributes.getDefault());
                            }
                        }
                    }
                } catch (UnsupportedMessageException | InvalidArrayIndexException ex) {
                }
            } else {
                throw Errors.createTypeErrorNotAnObject(target, this);
            }
            return target;
        }

        private UnmodifiableArrayList<? extends Object> enumerableOwnPropertyNames(JSDynamicObject obj) {
            if (enumerableOwnPropertyNamesNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                enumerableOwnPropertyNamesNode = insert(EnumerableOwnPropertyNamesNode.createKeys(getContext()));
            }
            return enumerableOwnPropertyNamesNode.execute(obj);
        }

        @TruffleBoundary
        private static String beanProperty(String accessor, int prefixLength) {
            char c = accessor.charAt(prefixLength);
            return Character.toLowerCase(c) + accessor.substring(prefixLength + 1);
        }

        @TruffleBoundary
        private static String beanAccessor(String prefix, String beanProperty) {
            return prefix + Character.toUpperCase(beanProperty.charAt(0)) + beanProperty.substring(1);
        }

        static final class BoundProperty extends PropertyProxy {
            private final JSDynamicObject source;
            private final Object key;
            private final JSClass sourceClass;

            BoundProperty(JSDynamicObject source, Object key, JSClass sourceClass) {
                this.source = source;
                this.key = key;
                this.sourceClass = sourceClass;
            }

            @Override
            public Object get(JSDynamicObject store) {
                return sourceClass.get(source, key);
            }

            @Override
            public boolean set(JSDynamicObject store, Object value) {
                return sourceClass.set(source, key, value, source, false, null);
            }

        }

        static final class ForeignBoundProperty extends PropertyProxy {
            private final Object source;
            private final String key;

            ForeignBoundProperty(Object source, String key) {
                this.source = source;
                this.key = key;
            }

            @Override
            public Object get(JSDynamicObject store) {
                InteropLibrary library = InteropLibrary.getFactory().getUncached(source);
                if (library.isMemberReadable(source, key)) {
                    try {
                        return JSRuntime.importValue(library.readMember(source, key));
                    } catch (UnsupportedMessageException | UnknownIdentifierException ex) {
                    }
                }
                return Undefined.instance;
            }

            @Override
            public boolean set(JSDynamicObject store, Object value) {
                InteropLibrary library = InteropLibrary.getFactory().getUncached(source);
                if (library.isMemberWritable(source, key)) {
                    try {
                        library.writeMember(source, key, JSRuntime.exportValue(value));
                        return true;
                    } catch (UnsupportedMessageException | UnknownIdentifierException | UnsupportedTypeException ex) {
                    }
                }
                return false;
            }
        }

        static final class ForeignBoundBeanProperty extends PropertyProxy {
            private final Object source;
            private final String getKey;
            private final String setKey;

            ForeignBoundBeanProperty(Object source, String getKey, String setKey) {
                assert getKey != null || setKey != null;
                this.source = source;
                this.getKey = getKey;
                this.setKey = setKey;
            }

            @Override
            public Object get(JSDynamicObject store) {
                if (getKey != null) {
                    InteropLibrary library = InteropLibrary.getFactory().getUncached(source);
                    if (library.isMemberInvocable(source, getKey)) {
                        try {
                            return JSRuntime.importValue(library.invokeMember(source, getKey));
                        } catch (UnsupportedMessageException | UnknownIdentifierException | UnsupportedTypeException | ArityException ex) {
                        }
                    }
                }
                return Undefined.instance;
            }

            @Override
            public boolean set(JSDynamicObject store, Object value) {
                if (setKey != null) {
                    InteropLibrary library = InteropLibrary.getFactory().getUncached(source);
                    if (library.isMemberInvocable(source, setKey)) {
                        try {
                            library.invokeMember(source, setKey, JSRuntime.exportValue(value));
                            return true;
                        } catch (UnsupportedMessageException | UnknownIdentifierException | UnsupportedTypeException | ArityException ex) {
                        }
                    }
                }
                return false;
            }
        }

    }

    public abstract static class ObjectHasOwnNode extends ObjectOperation {
        @Child JSToPropertyKeyNode toPropertyKeyNode = JSToPropertyKeyNode.create();
        @Child JSHasPropertyNode hasOwnPropertyNode = JSHasPropertyNode.create(true);

        public ObjectHasOwnNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected boolean hasOwn(Object o, Object p) {
            Object obj = toObject(o);
            Object key = toPropertyKeyNode.execute(p);
            return hasOwnPropertyNode.executeBoolean(obj, key);
        }

    }

}
