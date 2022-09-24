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
package com.oracle.truffle.js.nodes.access;

import java.util.Arrays;
import java.util.Set;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.js.nodes.JSGuards;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.cast.JSToObjectNode;
import com.oracle.truffle.js.nodes.cast.JSToPropertyKeyNode;
import com.oracle.truffle.js.nodes.function.FunctionNameHolder;
import com.oracle.truffle.js.nodes.function.JSFunctionExpressionNode;
import com.oracle.truffle.js.nodes.function.NamedEvaluationTargetNode;
import com.oracle.truffle.js.nodes.function.SetFunctionNameNode;
import com.oracle.truffle.js.nodes.instrumentation.JSTags;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.LiteralTag;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSConfig;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSErrorType;
import com.oracle.truffle.js.runtime.JSException;
import com.oracle.truffle.js.runtime.JSFrameUtil;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.Strings;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData;
import com.oracle.truffle.js.runtime.builtins.JSFunctionObject;
import com.oracle.truffle.js.runtime.objects.Accessor;
import com.oracle.truffle.js.runtime.objects.JSAttributes;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.JSProperty;
import com.oracle.truffle.js.runtime.objects.PropertyDescriptor;
import com.oracle.truffle.js.runtime.objects.Undefined;

public class ObjectLiteralNode extends JavaScriptNode {

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        if (tag == LiteralTag.class) {
            return true;
        } else {
            return super.hasTag(tag);
        }
    }

    @Override
    public Object getNodeObject() {
        return JSTags.createNodeObjectDescriptor(LiteralTag.TYPE, LiteralTag.Type.ObjectLiteral.name());
    }

    protected static Object executeWithRealm(JavaScriptNode valueNode, VirtualFrame frame, JSRealm realm) {
        if (valueNode instanceof JSFunctionExpressionNode) {
            return ((JSFunctionExpressionNode) valueNode).executeWithRealm(frame, realm);
        } else {
            return valueNode.execute(frame);
        }
    }

    public static boolean isAutoAccessor(ObjectLiteralMemberNode memberNode) {
        return memberNode instanceof AutoAccessorDataMemberNode;
    }

    public static boolean isPrivateMethod(ObjectLiteralMemberNode memberNode) {
        assert isMethod(memberNode);
        return memberNode instanceof PrivateMethodMemberNode;
    }

    public static boolean isMethod(ObjectLiteralMemberNode memberNode) {
        if (memberNode instanceof PrivateMethodMemberNode) {
            return true;
        }
        if (memberNode instanceof AccessorMemberNode || memberNode instanceof AutoAccessorDataMemberNode) {
            return false;
        }
        return !memberNode.isFieldOrStaticBlock();
    }

    public static boolean isAccessor(ObjectLiteralMemberNode memberNode) {
        return memberNode instanceof AccessorMemberNode;
    }

    public static final class MakeMethodNode extends JavaScriptNode implements FunctionNameHolder.Delegate {
        @Child private JavaScriptNode functionNode;
        @Child private PropertySetNode makeMethodNode;

        private MakeMethodNode(JSContext context, JavaScriptNode functionNode) {
            this.functionNode = functionNode;
            this.makeMethodNode = PropertySetNode.createSetHidden(JSFunction.HOME_OBJECT_ID, context);
        }

        private MakeMethodNode(JSContext context, JavaScriptNode functionNode, HiddenKey key) {
            this.functionNode = functionNode;
            this.makeMethodNode = PropertySetNode.createSetHidden(key, context);
        }

        public static JavaScriptNode create(JSContext context, JavaScriptNode functionNode) {
            return new MakeMethodNode(context, functionNode);
        }

        public static JavaScriptNode createWithKey(JSContext context, JavaScriptNode functionNode, HiddenKey key) {
            return new MakeMethodNode(context, functionNode, key);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return functionNode.execute(frame);
        }

        public Object executeWithObject(VirtualFrame frame, JSDynamicObject obj, JSRealm realm) {
            Object function = executeWithRealm(functionNode, frame, realm);
            makeMethodNode.setValue(function, obj);
            return function;
        }

        @Override
        public FunctionNameHolder getFunctionNameHolder() {
            return (FunctionNameHolder) functionNode;
        }

        @Override
        protected JavaScriptNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return create(makeMethodNode.getContext(), cloneUninitialized(functionNode, materializedTags));
        }
    }

    public abstract static class ObjectLiteralMemberNode extends JavaScriptBaseNode {

        public static final ObjectLiteralMemberNode[] EMPTY = {};

        protected final boolean isStatic;
        protected final boolean isPrivate;
        protected final byte attributes;
        protected final boolean isFieldOrStaticBlock;
        protected final boolean isAnonymousFunctionDefinition;

        public ObjectLiteralMemberNode(boolean isStatic, int attributes) {
            this(isStatic, false, attributes, false, false);
        }

        public ObjectLiteralMemberNode(boolean isStatic, boolean isPrivate, int attributes, boolean isFieldOrStaticBlock, boolean isAnonymousFunctionDefinition) {
            assert attributes == (attributes & JSAttributes.ATTRIBUTES_MASK);
            this.isStatic = isStatic;
            this.isPrivate = isPrivate;
            this.attributes = (byte) attributes;
            this.isFieldOrStaticBlock = isFieldOrStaticBlock;
            this.isAnonymousFunctionDefinition = isAnonymousFunctionDefinition;
        }

        public abstract void executeVoid(VirtualFrame frame, JSDynamicObject receiver, JSDynamicObject homeObject, JSRealm realm);

        public final void executeVoid(VirtualFrame frame, JSDynamicObject obj, JSRealm realm) {
            executeVoid(frame, obj, obj, realm);
        }

        public Object evaluateKey(@SuppressWarnings("unused") VirtualFrame frame) {
            throw Errors.shouldNotReachHere(getClass().getName());
        }

        public Object evaluateValue(@SuppressWarnings("unused") VirtualFrame frame, @SuppressWarnings("unused") JSDynamicObject homeObject, @SuppressWarnings("unused") Object key,
                        @SuppressWarnings("unused") JSRealm realm) {
            throw Errors.shouldNotReachHere();
        }

        public void evaluateWithKeyAndValue(@SuppressWarnings("unused") VirtualFrame frame, @SuppressWarnings("unused") JSDynamicObject obj, @SuppressWarnings("unused") Object key,
                        @SuppressWarnings("unused") Object value, @SuppressWarnings("unused") JSRealm realm) {
            throw Errors.shouldNotReachHere(getClass().getName());
        }

        public final boolean isStatic() {
            return isStatic;
        }

        public final boolean isPrivate() {
            return isPrivate;
        }

        public final boolean isFieldOrStaticBlock() {
            return isFieldOrStaticBlock;
        }

        public final boolean isAnonymousFunctionDefinition() {
            return isAnonymousFunctionDefinition;
        }

        static boolean isAnonymousFunctionDefinition(JavaScriptNode expression) {
            return expression instanceof FunctionNameHolder && ((FunctionNameHolder) expression).isAnonymous();
        }

        protected static boolean isMethodNode(JavaScriptNode valueNode) {
            return valueNode instanceof MakeMethodNode;
        }

        protected static Object evaluateWithHomeObject(JavaScriptNode valueNode, VirtualFrame frame, JSDynamicObject obj, JSRealm realm) {
            if (isMethodNode(valueNode)) {
                return ((MakeMethodNode) valueNode).executeWithObject(frame, obj, realm);
            }
            return executeWithRealm(valueNode, frame, realm);
        }

        protected abstract ObjectLiteralMemberNode copyUninitialized(Set<Class<? extends Tag>> materializedTags);

        public static ObjectLiteralMemberNode[] cloneUninitialized(ObjectLiteralMemberNode[] members, Set<Class<? extends Tag>> materializedTags) {
            ObjectLiteralMemberNode[] copy = members.clone();
            for (int i = 0; i < copy.length; i++) {
                copy[i] = copy[i].copyUninitialized(materializedTags);
            }
            return copy;
        }

        public int getAttributes() {
            return attributes;
        }
    }

    private abstract static class CachingObjectLiteralMemberNode extends ObjectLiteralMemberNode {
        protected final Object name;
        @Child private DynamicObjectLibrary dynamicObjectLibrary;

        CachingObjectLiteralMemberNode(Object name, boolean isStatic, int attributes, boolean isFieldOrStaticBlock) {
            super(isStatic, false, attributes, isFieldOrStaticBlock, false);
            assert this instanceof AutoAccessorDataMemberNode || JSRuntime.isPropertyKey(name) || (name == null && isStatic && isFieldOrStaticBlock) : name;
            this.name = name;
        }

        @Override
        public Object evaluateKey(VirtualFrame frame) {
            return name;
        }

        protected final DynamicObjectLibrary dynamicObjectLibrary() {
            DynamicObjectLibrary dynamicObjectLib = dynamicObjectLibrary;
            if (dynamicObjectLib == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                JSContext context = getLanguage().getJSContext();
                dynamicObjectLibrary = dynamicObjectLib = insert(JSObjectUtil.createDispatched(name, context.getPropertyCacheLimit()));
                JSObjectUtil.checkForNoSuchPropertyOrMethod(context, name);
            }
            return dynamicObjectLib;
        }
    }

    public static class ComputedAutoAccessorDataMemberNode extends AutoAccessorDataMemberNode {

        @Child private JavaScriptNode keyNode;
        @Child private JSToPropertyKeyNode toPropertyKeyNode;

        ComputedAutoAccessorDataMemberNode(JavaScriptNode keyNode, boolean isStatic, int attributes, JavaScriptNode valueNode) {
            super(Undefined.instance, isStatic, attributes, valueNode);
            this.keyNode = keyNode;
            this.toPropertyKeyNode = JSToPropertyKeyNode.create();
        }

        @Override
        public Object evaluateKey(VirtualFrame frame) {
            return toPropertyKeyNode.execute(keyNode.execute(frame));
        }

        @Override
        protected ObjectLiteralMemberNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return new ComputedAutoAccessorDataMemberNode(keyNode, isStatic, attributes, valueNode);
        }
    }

    public static class AutoAccessorDataMemberNode extends ObjectLiteralDataMemberNode {

        private static final String ACCESSOR_STORAGE = " accessor storage";
        private static final HiddenKey STORAGE_KEY_MAGIC = new HiddenKey(":storage-key-magic");

        @Child private PropertySetNode backingStorageMagicSetNode;

        private final JSFunctionData getterFunctionData;
        private final JSFunctionData setterFunctionData;

        AutoAccessorDataMemberNode(Object name, boolean isStatic, int attributes, JavaScriptNode valueNode) {
            super(name, isStatic, attributes, valueNode, false);
            this.setterFunctionData = createAutoAccessorSetFunctionData();
            this.getterFunctionData = createAutoAccessorGetFunctionData();
            this.backingStorageMagicSetNode = PropertySetNode.createSetHidden(STORAGE_KEY_MAGIC, getRealm().getContext());
        }

        private static HiddenKey checkAutoaccessorTarget(VirtualFrame frame, PropertyGetNode getMagicNode, DynamicObjectLibrary storageLibrary, Object thiz) {
            Object function = JSFrameUtil.getFunctionObject(frame);
            HiddenKey backingStorageKey = (HiddenKey) getMagicNode.getValue(function);
            if (!(thiz instanceof JSDynamicObject) || !storageLibrary.containsKey((JSDynamicObject) thiz, backingStorageKey)) {
                CompilerDirectives.transferToInterpreter();
                throw JSException.create(JSErrorType.TypeError, "Bad auto-accessor target.");
            }
            return backingStorageKey;
        }

        @TruffleBoundary
        private JSFunctionData createAutoAccessorSetFunctionData() {
            CompilerAsserts.neverPartOfCompilation();
            JSRealm realm = getRealm();
            final JSContext context = realm.getContext();
            CallTarget callTarget = new JavaScriptRootNode(context.getLanguage(), null, null) {
                @Child private PropertyGetNode getMagicNode = PropertyGetNode.createGetHidden(STORAGE_KEY_MAGIC, context);
                @Child private DynamicObjectLibrary storageLibrary = DynamicObjectLibrary.getFactory().createDispatched(5);

                @Override
                public Object execute(VirtualFrame frame) {
                    Object thiz = JSFrameUtil.getThisObj(frame);
                    HiddenKey backingStorageKey = checkAutoaccessorTarget(frame, getMagicNode, storageLibrary, thiz);
                    Object[] args = frame.getArguments();
                    int userArgumentCount = JSArguments.getUserArgumentCount(args);
                    Object value = userArgumentCount > 0 ? JSArguments.getUserArgument(args, 0) : Undefined.instance;
                    storageLibrary.put((DynamicObject) thiz, backingStorageKey, value);
                    return value;
                }
            }.getCallTarget();
            return JSFunctionData.createCallOnly(context, callTarget, 1, Strings.SET);
        }

        @TruffleBoundary
        private JSFunctionData createAutoAccessorGetFunctionData() {
            CompilerAsserts.neverPartOfCompilation();
            JSRealm realm = getRealm();
            final JSContext context = realm.getContext();
            CallTarget callTarget = new JavaScriptRootNode(context.getLanguage(), null, null) {
                @Child private PropertyGetNode getMagicNode = PropertyGetNode.createGetHidden(STORAGE_KEY_MAGIC, context);
                @Child private DynamicObjectLibrary storageLibrary = DynamicObjectLibrary.getFactory().createDispatched(5);

                @Override
                public Object execute(VirtualFrame frame) {
                    Object thiz = JSFrameUtil.getThisObj(frame);
                    HiddenKey backingStorageKey = checkAutoaccessorTarget(frame, getMagicNode, storageLibrary, thiz);
                    return storageLibrary.getOrDefault((DynamicObject) thiz, backingStorageKey, Undefined.instance);
                }
            }.getCallTarget();
            return JSFunctionData.createCallOnly(context, callTarget, 0, Strings.GET);
        }

        public void executeWithGetterSetter(JSDynamicObject obj, Object key, JSDynamicObject getterV, JSDynamicObject setterV) {
            DynamicObjectLibrary dynamicObjectLib = dynamicObjectLibrary();
            Accessor accessor = new Accessor(getterV, setterV);
            dynamicObjectLib.putWithFlags(obj, key, accessor, attributes | JSProperty.ACCESSOR);
        }

        @Override
        protected ObjectLiteralMemberNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return new AutoAccessorDataMemberNode(name, isStatic, attributes, valueNode);
        }

        public JSFunctionObject createAutoAccessorSetter(HiddenKey backingStorageKey) {
            JSFunctionObject functionObject = JSFunction.create(getRealm(), setterFunctionData);
            backingStorageMagicSetNode.setValue(functionObject, backingStorageKey);
            return functionObject;
        }

        public JSFunctionObject createAutoAccessorGetter(HiddenKey backingStorageKey) {
            JSFunctionObject functionObject = JSFunction.create(getRealm(), getterFunctionData);
            backingStorageMagicSetNode.setValue(functionObject, backingStorageKey);
            return functionObject;
        }

        @TruffleBoundary
        public HiddenKey createBackingStorageKey(Object key) {
            return new HiddenKey(JSRuntime.safeToString(key) + ACCESSOR_STORAGE);
        }
    }

    private static class ObjectLiteralDataMemberNode extends CachingObjectLiteralMemberNode {
        @Child protected JavaScriptNode valueNode;

        ObjectLiteralDataMemberNode(Object name, boolean isStatic, int attributes, JavaScriptNode valueNode, boolean isFieldOrStaticBlock) {
            super(name, isStatic, attributes, isFieldOrStaticBlock);
            this.valueNode = valueNode;
        }

        @Override
        public void executeVoid(VirtualFrame frame, JSDynamicObject receiver, JSDynamicObject homeObject, JSRealm realm) {
            Object value = evaluateWithHomeObject(valueNode, frame, homeObject, realm);
            execute(receiver, value, name);
        }

        @Override
        public Object evaluateValue(VirtualFrame frame, JSDynamicObject homeObject, Object key, JSRealm realm) {
            return evaluateWithHomeObject(valueNode, frame, homeObject, realm);
        }

        @Override
        public void evaluateWithKeyAndValue(VirtualFrame frame, JSDynamicObject obj, Object key, Object value, JSRealm realm) {
            // NOP
        }

        private void execute(JSDynamicObject obj, Object value, Object key) {
            if (isFieldOrStaticBlock) {
                return;
            }
            DynamicObjectLibrary dynamicObjectLib = dynamicObjectLibrary();
            dynamicObjectLib.putWithFlags(obj, key, value, attributes);
        }

        @Override
        protected ObjectLiteralMemberNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return new ObjectLiteralDataMemberNode(name, isStatic, attributes, JavaScriptNode.cloneUninitialized(valueNode, materializedTags), isFieldOrStaticBlock);
        }
    }

    public interface AccessorMemberNode {
        boolean hasGetter();

        boolean hasSetter();

        Object evaluateGetter(VirtualFrame frame, JSDynamicObject homeObject, Object key, JSRealm realm);

        Object evaluateSetter(VirtualFrame frame, JSDynamicObject homeObject, Object key, JSRealm realm);
    }

    public static class ObjectLiteralAccessorMemberNode extends CachingObjectLiteralMemberNode implements AccessorMemberNode {
        @Child protected JavaScriptNode getterNode;
        @Child protected JavaScriptNode setterNode;

        ObjectLiteralAccessorMemberNode(Object name, boolean isStatic, int attributes, JavaScriptNode getter, JavaScriptNode setter) {
            super(name, isStatic, attributes, false);
            this.getterNode = getter;
            this.setterNode = setter;
        }

        @Override
        public boolean hasGetter() {
            return getterNode != null;
        }

        @Override
        public boolean hasSetter() {
            return setterNode != null;
        }

        @Override
        public Object evaluateGetter(VirtualFrame frame, JSDynamicObject homeObject, Object key, JSRealm realm) {
            return evaluateWithHomeObject(getterNode, frame, homeObject, realm);
        }

        @Override
        public Object evaluateSetter(VirtualFrame frame, JSDynamicObject homeObject, Object key, JSRealm realm) {
            return evaluateWithHomeObject(setterNode, frame, homeObject, realm);
        }

        @Override
        public final void executeVoid(VirtualFrame frame, JSDynamicObject receiver, JSDynamicObject homeObject, JSRealm realm) {
            Object getterV = null;
            Object setterV = null;
            if (getterNode != null) {
                getterV = evaluateWithHomeObject(getterNode, frame, homeObject, realm);
            }
            if (setterNode != null) {
                setterV = evaluateWithHomeObject(setterNode, frame, homeObject, realm);
            }
            assert getterV != null || setterV != null;
            execute(receiver, getterV, setterV);
        }

        @Override
        public void evaluateWithKeyAndValue(VirtualFrame frame, JSDynamicObject obj, Object key, Object value, JSRealm realm) {
            // NOP
        }

        private void execute(JSDynamicObject obj, Object getterV, Object setterV) {
            DynamicObjectLibrary dynamicObjectLib = dynamicObjectLibrary();

            Object getter = getterV;
            Object setter = setterV;

            if ((getterNode == null || setterNode == null) && JSProperty.isAccessor(dynamicObjectLib.getPropertyFlagsOrDefault(obj, name, 0))) {
                // No full accessor information and there is an accessor property already
                // => merge the new and existing accessor functions
                Accessor existing = (Accessor) dynamicObjectLib.getOrDefault(obj, name, null);
                getter = (getter == null) ? existing.getGetter() : getter;
                setter = (setter == null) ? existing.getSetter() : setter;
            }
            Accessor accessor = new Accessor(getter, setter);

            dynamicObjectLib.putWithFlags(obj, name, accessor, attributes | JSProperty.ACCESSOR);
        }

        @Override
        protected ObjectLiteralMemberNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return new ObjectLiteralAccessorMemberNode(name, isStatic, attributes,
                            JavaScriptNode.cloneUninitialized(getterNode, materializedTags),
                            JavaScriptNode.cloneUninitialized(setterNode, materializedTags));
        }

    }

    public abstract static class ComputedObjectLiteralDataMemberNode extends ObjectLiteralMemberNode {
        @Child private JavaScriptNode propertyKey;
        @Child protected JavaScriptNode valueNode;
        @Child private JSToPropertyKeyNode toPropertyKey;
        @Child protected SetFunctionNameNode setFunctionName;

        ComputedObjectLiteralDataMemberNode(JavaScriptNode key, boolean isStatic, int attributes, JavaScriptNode valueNode, boolean isField, boolean isAnonymousFunctionDefinition) {
            super(isStatic, false, attributes, isField, isAnonymousFunctionDefinition);
            this.propertyKey = key;
            this.valueNode = valueNode;
            this.toPropertyKey = JSToPropertyKeyNode.create();
            this.setFunctionName = isAnonymousFunctionDefinition(valueNode) ? SetFunctionNameNode.create() : null;
        }

        @SuppressWarnings("unused")
        @Specialization(guards = {"!isFieldOrStaticBlock", "!isAnonymousFunctionDefinition", "setFunctionName==null", "!isMethodNode(valueNode)"}, limit = "3")
        public final void doNoFieldNoFunctionDef(VirtualFrame frame, JSDynamicObject receiver, JSDynamicObject homeObject, JSRealm realm,
                        @CachedLibrary("receiver") DynamicObjectLibrary dynamicObject) {
            Object key = evaluateKey(frame);
            Object value = valueNode.execute(frame);
            dynamicObject.putWithFlags(receiver, key, value, attributes);
        }

        @SuppressWarnings("unused")
        @Specialization
        public final void doGeneric(VirtualFrame frame, JSDynamicObject receiver, JSDynamicObject homeObject, JSRealm realm) {
            if (isFieldOrStaticBlock) {
                return;
            }
            Object key = evaluateKey(frame);
            Object value;
            JavaScriptNode unwrappedValueNode;
            if (isAnonymousFunctionDefinition && valueNode instanceof NamedEvaluationTargetNode) {
                value = ((NamedEvaluationTargetNode) valueNode).executeWithName(frame, key);
            } else {
                value = evaluateWithHomeObject(valueNode, frame, homeObject, realm);
                if (setFunctionName != null) {
                    setFunctionName.execute(value, key);
                }
            }

            PropertyDescriptor propDesc = PropertyDescriptor.createData(value, attributes);
            JSRuntime.definePropertyOrThrow(receiver, key, propDesc);
        }

        @Override
        public Object evaluateKey(VirtualFrame frame) {
            Object key = propertyKey.execute(frame);
            return toPropertyKey.execute(key);
        }

        @Override
        public Object evaluateValue(VirtualFrame frame, JSDynamicObject homeObject, Object key, JSRealm realm) {
            if (!isFieldOrStaticBlock && !isAnonymousFunctionDefinition && setFunctionName == null && !isMethodNode(valueNode)) {
                return valueNode.execute(frame);
            } else {
                Object value;
                if (isAnonymousFunctionDefinition && valueNode instanceof NamedEvaluationTargetNode) {
                    value = ((NamedEvaluationTargetNode) valueNode).executeWithName(frame, key);
                } else {
                    value = evaluateWithHomeObject(valueNode, frame, homeObject, realm);
                    if (setFunctionName != null) {
                        setFunctionName.execute(value, key);
                    }
                }
                return value;
            }
        }

        @Override
        public void evaluateWithKeyAndValue(VirtualFrame frame, JSDynamicObject obj, Object key, Object value, JSRealm realm) {
            // NOP
        }

        @Override
        protected ObjectLiteralMemberNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return ObjectLiteralNodeFactory.ComputedObjectLiteralDataMemberNodeGen.create(JavaScriptNode.cloneUninitialized(propertyKey, materializedTags), isStatic, attributes,
                            JavaScriptNode.cloneUninitialized(valueNode, materializedTags), isFieldOrStaticBlock, isAnonymousFunctionDefinition);
        }
    }

    private static class ComputedObjectLiteralAccessorMemberNode extends ObjectLiteralMemberNode implements AccessorMemberNode {
        @Child private JavaScriptNode propertyKey;
        @Child private JavaScriptNode getterNode;
        @Child private JavaScriptNode setterNode;
        @Child private JSToPropertyKeyNode toPropertyKey;
        @Child private SetFunctionNameNode setFunctionName;
        private final boolean isGetterAnonymousFunction;
        private final boolean isSetterAnonymousFunction;

        ComputedObjectLiteralAccessorMemberNode(JavaScriptNode key, boolean isStatic, int attributes, JavaScriptNode getter, JavaScriptNode setter) {
            super(isStatic, attributes);
            this.propertyKey = key;
            this.getterNode = getter;
            this.setterNode = setter;
            this.toPropertyKey = JSToPropertyKeyNode.create();
            this.isGetterAnonymousFunction = isAnonymousFunctionDefinition(getter);
            this.isSetterAnonymousFunction = isAnonymousFunctionDefinition(setter);
            this.setFunctionName = (isGetterAnonymousFunction || isSetterAnonymousFunction) ? SetFunctionNameNode.create() : null;
        }

        @Override
        public void evaluateWithKeyAndValue(VirtualFrame frame, JSDynamicObject obj, Object key, Object value, JSRealm realm) {
            // NOP
        }

        @Override
        public final void executeVoid(VirtualFrame frame, JSDynamicObject receiver, JSDynamicObject homeObject, JSRealm realm) {
            Object key = evaluateKey(frame);
            Object getterV = null;
            Object setterV = null;
            if (getterNode != null) {
                getterV = evaluateWithHomeObject(getterNode, frame, homeObject, realm);
                if (isGetterAnonymousFunction) {
                    setFunctionName.execute(getterV, key, Strings.GET);
                }
            }
            if (setterNode != null) {
                setterV = evaluateWithHomeObject(setterNode, frame, homeObject, realm);
                if (isSetterAnonymousFunction) {
                    setFunctionName.execute(setterV, key, Strings.SET);
                }
            }

            assert getterV != null || setterV != null;
            PropertyDescriptor propDesc = PropertyDescriptor.createAccessor(getterV, setterV, attributes);
            JSRuntime.definePropertyOrThrow(receiver, key, propDesc);
        }

        @Override
        public Object evaluateKey(VirtualFrame frame) {
            Object key = propertyKey.execute(frame);
            return toPropertyKey.execute(key);
        }

        @Override
        protected ObjectLiteralMemberNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return new ComputedObjectLiteralAccessorMemberNode(JavaScriptNode.cloneUninitialized(propertyKey, materializedTags), isStatic, attributes,
                            JavaScriptNode.cloneUninitialized(getterNode, materializedTags), JavaScriptNode.cloneUninitialized(setterNode, materializedTags));
        }

        @Override
        public boolean hasGetter() {
            return getterNode != null;
        }

        @Override
        public boolean hasSetter() {
            return setterNode != null;
        }

        @Override
        public Object evaluateGetter(VirtualFrame frame, JSDynamicObject homeObject, Object key, JSRealm realm) {
            Object getterV = evaluateWithHomeObject(getterNode, frame, homeObject, realm);
            if (isGetterAnonymousFunction) {
                setFunctionName.execute(getterV, key, Strings.GET);
            }
            return getterV;
        }

        @Override
        public Object evaluateSetter(VirtualFrame frame, JSDynamicObject homeObject, Object key, JSRealm realm) {
            Object setterV = evaluateWithHomeObject(setterNode, frame, homeObject, realm);
            if (isSetterAnonymousFunction) {
                setFunctionName.execute(setterV, key, Strings.SET);
            }
            return setterV;
        }
    }

    private static class ObjectLiteralProtoMemberNode extends ObjectLiteralMemberNode {
        @Child protected JavaScriptNode valueNode;

        ObjectLiteralProtoMemberNode(boolean isStatic, JavaScriptNode valueNode) {
            super(isStatic, 0);
            this.valueNode = valueNode;
        }

        @Override
        public final void executeVoid(VirtualFrame frame, JSDynamicObject receiver, JSDynamicObject homeObject, JSRealm realm) {
            Object value = valueNode.execute(frame);
            if (JSDynamicObject.isJSDynamicObject(value)) {
                if (value == Undefined.instance) {
                    return;
                }
                JSObject.setPrototype(receiver, (JSDynamicObject) value);
            }
        }

        @Override
        protected ObjectLiteralMemberNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return new ObjectLiteralProtoMemberNode(isStatic, JavaScriptNode.cloneUninitialized(valueNode, materializedTags));
        }
    }

    private static class ObjectLiteralSpreadMemberNode extends ObjectLiteralMemberNode {
        @Child private JavaScriptNode valueNode;
        @Child private JSToObjectNode toObjectNode;
        @Child private CopyDataPropertiesNode copyDataPropertiesNode;

        ObjectLiteralSpreadMemberNode(boolean isStatic, int attributes, JavaScriptNode valueNode) {
            super(isStatic, attributes);
            this.valueNode = valueNode;
        }

        @Override
        public final void executeVoid(VirtualFrame frame, JSDynamicObject receiver, JSDynamicObject target, JSRealm realm) {
            Object sourceValue = valueNode.execute(frame);
            if (JSGuards.isNullOrUndefined(sourceValue)) {
                return;
            }
            if (toObjectNode == null || copyDataPropertiesNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                JSContext context = getLanguage().getJSContext();
                toObjectNode = insert(JSToObjectNode.createToObjectNoCheck(context));
                copyDataPropertiesNode = insert(CopyDataPropertiesNode.create(context));
            }
            Object from = toObjectNode.execute(sourceValue);
            copyDataPropertiesNode.execute(target, from);
        }

        @Override
        protected ObjectLiteralMemberNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return new ObjectLiteralSpreadMemberNode(isStatic, attributes, JavaScriptNode.cloneUninitialized(valueNode, materializedTags));
        }
    }

    private static class DictionaryObjectDataMemberNode extends ObjectLiteralMemberNode {
        private final Object name;
        @Child private JavaScriptNode valueNode;

        DictionaryObjectDataMemberNode(Object name, boolean isStatic, int attributes, JavaScriptNode valueNode) {
            super(isStatic, attributes);
            assert JSRuntime.isPropertyKey(name);
            this.name = name;
            this.valueNode = valueNode;
        }

        @Override
        public final void executeVoid(VirtualFrame frame, JSDynamicObject receiver, JSDynamicObject homeObject, JSRealm realm) {
            Object value = evaluateWithHomeObject(valueNode, frame, homeObject, realm);
            PropertyDescriptor propDesc = PropertyDescriptor.createData(value, attributes);
            JSObject.defineOwnProperty(receiver, name, propDesc, true);
        }

        @Override
        protected ObjectLiteralMemberNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return new DictionaryObjectDataMemberNode(name, isStatic, attributes, JavaScriptNode.cloneUninitialized(valueNode, materializedTags));
        }
    }

    private static class PrivateFieldMemberNode extends ObjectLiteralMemberNode {
        @Child private JavaScriptNode keyNode;
        @Child private JavaScriptNode valueNode;
        @Child private JSWriteFrameSlotNode writePrivateNode;

        PrivateFieldMemberNode(JavaScriptNode key, boolean isStatic, JavaScriptNode valueNode, JSWriteFrameSlotNode writePrivateNode) {
            super(isStatic, true, JSAttributes.getDefaultNotEnumerable(), true, false);
            this.keyNode = key;
            this.valueNode = valueNode;
            this.writePrivateNode = writePrivateNode;
        }

        @Override
        public final void executeVoid(VirtualFrame frame, JSDynamicObject receiver, JSDynamicObject homeObject, JSRealm realm) {
            writePrivateNode.execute(frame);
        }

        @Override
        public Object evaluateKey(VirtualFrame frame) {
            return keyNode.execute(frame);
        }

        @Override
        public Object evaluateValue(VirtualFrame frame, JSDynamicObject homeObject, Object key, JSRealm realm) {
            return evaluateWithHomeObject(valueNode, frame, homeObject, realm);
        }

        @Override
        protected ObjectLiteralMemberNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return new PrivateFieldMemberNode(JavaScriptNode.cloneUninitialized(keyNode, materializedTags), isStatic, JavaScriptNode.cloneUninitialized(valueNode, materializedTags),
                            JavaScriptNode.cloneUninitialized(writePrivateNode, materializedTags));
        }
    }

    public static class PrivateMethodMemberNode extends ObjectLiteralMemberNode {
        @Child private JavaScriptNode valueNode;
        @Child private JSWriteFrameSlotNode writePrivateNode;

        private final TruffleString privateName;
        private final int privateBrandSlotIndex;

        PrivateMethodMemberNode(TruffleString privateName, boolean isStatic, JavaScriptNode valueNode, JSWriteFrameSlotNode writePrivateNode, int privateBrandSlotIndex) {
            super(isStatic, true, JSAttributes.getDefaultNotEnumerable(), false, false);
            this.privateName = privateName;
            this.valueNode = valueNode;
            this.writePrivateNode = writePrivateNode;
            this.privateBrandSlotIndex = privateBrandSlotIndex;
        }

        public int getPrivateBrandSlotIndex() {
            return privateBrandSlotIndex;
        }

        public JSWriteFrameSlotNode getWritePrivateNode() {
            return writePrivateNode;
        }

        @Override
        public Object evaluateKey(VirtualFrame frame) {
            return privateName;
        }

        @Override
        public Object evaluateValue(VirtualFrame frame, JSDynamicObject homeObject, Object key, JSRealm realm) {
            return evaluateWithHomeObject(valueNode, frame, homeObject, realm);
        }

        @Override
        public void evaluateWithKeyAndValue(VirtualFrame frame, JSDynamicObject obj, Object key, Object value, JSRealm realm) {
            writePrivateNode.executeWrite(frame, value);
        }

        @Override
        public final void executeVoid(VirtualFrame frame, JSDynamicObject receiver, JSDynamicObject homeObject, JSRealm realm) {
            Object value = evaluateWithHomeObject(valueNode, frame, homeObject, realm);
            writePrivateNode.executeWrite(frame, value);
        }

        @Override
        protected ObjectLiteralMemberNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return new PrivateMethodMemberNode(privateName, isStatic, JavaScriptNode.cloneUninitialized(valueNode, materializedTags),
                            JavaScriptNode.cloneUninitialized(writePrivateNode, materializedTags), privateBrandSlotIndex);
        }
    }

    public static class PrivateAccessorMemberNode extends ObjectLiteralMemberNode implements AccessorMemberNode {
        @Child private JavaScriptNode getterNode;
        @Child private JavaScriptNode setterNode;
        @Child private JSWriteFrameSlotNode writePrivateNode;

        private final int privateBrandSlotIndex;

        PrivateAccessorMemberNode(boolean isStatic, JavaScriptNode getterNode, JavaScriptNode setterNode, JSWriteFrameSlotNode writePrivateNode, int privateBrandSlotIndex) {
            super(isStatic, true, JSAttributes.getDefaultNotEnumerable(), false, false);
            this.getterNode = getterNode;
            this.setterNode = setterNode;
            this.writePrivateNode = writePrivateNode;
            this.privateBrandSlotIndex = privateBrandSlotIndex;
        }

        public int getPrivateBrandSlotIndex() {
            return privateBrandSlotIndex;
        }

        public FrameSlotNode getWritePrivateNode() {
            return writePrivateNode;
        }

        @Override
        public Object evaluateKey(VirtualFrame frame) {
            return writePrivateNode.getIdentifier();
        }

        @Override
        public void evaluateWithKeyAndValue(VirtualFrame frame, JSDynamicObject obj, Object key, Object value, JSRealm realm) {
            executeVoid(frame, obj, realm);
        }

        @Override
        public final void executeVoid(VirtualFrame frame, JSDynamicObject receiver, JSDynamicObject homeObject, JSRealm realm) {
            Object getter = null;
            Object setter = null;
            if (getterNode != null) {
                getter = evaluateWithHomeObject(getterNode, frame, homeObject, realm);
            }
            if (setterNode != null) {
                setter = evaluateWithHomeObject(setterNode, frame, homeObject, realm);
            }

            assert getter != null || setter != null;
            Accessor accessor = new Accessor(getter, setter);
            writePrivateNode.executeWrite(frame, accessor);
        }

        @Override
        protected ObjectLiteralMemberNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return new PrivateAccessorMemberNode(isStatic, JavaScriptNode.cloneUninitialized(getterNode, materializedTags), JavaScriptNode.cloneUninitialized(setterNode, materializedTags),
                            JavaScriptNode.cloneUninitialized(writePrivateNode, materializedTags), privateBrandSlotIndex);
        }

        @Override
        public boolean hasGetter() {
            return getterNode != null;
        }

        @Override
        public boolean hasSetter() {
            return setterNode != null;
        }

        @Override
        public Object evaluateGetter(VirtualFrame frame, JSDynamicObject homeObject, Object key, JSRealm realm) {
            return evaluateWithHomeObject(getterNode, frame, homeObject, realm);
        }

        @Override
        public Object evaluateSetter(VirtualFrame frame, JSDynamicObject homeObject, Object key, JSRealm realm) {
            return evaluateWithHomeObject(setterNode, frame, homeObject, realm);
        }
    }

    public static ObjectLiteralMemberNode newDataMember(TruffleString name, boolean isStatic, boolean enumerable, JavaScriptNode valueNode, boolean isField) {
        return new ObjectLiteralDataMemberNode(name, isStatic, enumerable ? JSAttributes.getDefault() : JSAttributes.getDefaultNotEnumerable(), valueNode, isField);
    }

    public static ObjectLiteralMemberNode newAutoAccessor(TruffleString name, boolean isStatic, boolean enumerable, JavaScriptNode valueNode) {
        return new AutoAccessorDataMemberNode(name, isStatic, enumerable ? JSAttributes.getDefault() : JSAttributes.getDefaultNotEnumerable(), valueNode);
    }

    public static ObjectLiteralMemberNode newComputedAutoAccessor(JavaScriptNode keyNode, boolean isStatic, boolean enumerable, JavaScriptNode valueNode) {
        return new ComputedAutoAccessorDataMemberNode(keyNode, isStatic, enumerable ? JSAttributes.getDefault() : JSAttributes.getDefaultNotEnumerable(), valueNode);
    }

    public static ObjectLiteralMemberNode newAccessorMember(TruffleString name, boolean isStatic, boolean enumerable, JavaScriptNode getterNode, JavaScriptNode setterNode) {
        return new ObjectLiteralAccessorMemberNode(name, isStatic, JSAttributes.fromConfigurableEnumerable(true, enumerable), getterNode, setterNode);
    }

    public static ObjectLiteralMemberNode newComputedDataMember(JavaScriptNode name, boolean isStatic, boolean enumerable, JavaScriptNode valueNode, boolean isField,
                    boolean isAnonymousFunctionDefinition) {
        int attributes = enumerable ? JSAttributes.getDefault() : JSAttributes.getDefaultNotEnumerable();
        return ObjectLiteralNodeFactory.ComputedObjectLiteralDataMemberNodeGen.create(name, isStatic, attributes, valueNode, isField, isAnonymousFunctionDefinition);
    }

    public static ObjectLiteralMemberNode newComputedAccessorMember(JavaScriptNode name, boolean isStatic, boolean enumerable, JavaScriptNode getter, JavaScriptNode setter) {
        return new ComputedObjectLiteralAccessorMemberNode(name, isStatic, JSAttributes.fromConfigurableEnumerable(true, enumerable), getter, setter);
    }

    public static ObjectLiteralMemberNode newDataMember(Object name, boolean isStatic, int attributes, JavaScriptNode valueNode) {
        return new ObjectLiteralDataMemberNode(name, isStatic, attributes, valueNode, false);
    }

    public static ObjectLiteralMemberNode newAccessorMember(Object name, boolean isStatic, int attributes, JavaScriptNode getterNode, JavaScriptNode setterNode) {
        return new ObjectLiteralAccessorMemberNode(name, isStatic, attributes, getterNode, setterNode);
    }

    public static ObjectLiteralMemberNode newComputedDataMember(JavaScriptNode name, boolean isStatic, int attributes, JavaScriptNode valueNode) {
        return ObjectLiteralNodeFactory.ComputedObjectLiteralDataMemberNodeGen.create(name, isStatic, attributes, valueNode, false, false);
    }

    public static ObjectLiteralMemberNode newPrivateFieldMember(JavaScriptNode name, boolean isStatic, JavaScriptNode valueNode, JSWriteFrameSlotNode writePrivateNode) {
        return new PrivateFieldMemberNode(name, isStatic, valueNode, writePrivateNode);
    }

    public static ObjectLiteralMemberNode newPrivateMethodMember(TruffleString privateName, boolean isStatic, JavaScriptNode valueNode, JSWriteFrameSlotNode writePrivateNode,
                    int privateBrandSlotIndex) {
        return new PrivateMethodMemberNode(privateName, isStatic, valueNode, writePrivateNode, privateBrandSlotIndex);
    }

    public static ObjectLiteralMemberNode newPrivateAccessorMember(boolean isStatic, JavaScriptNode getterNode, JavaScriptNode setterNode, JSWriteFrameSlotNode writePrivateNode,
                    int privateBrandSlotIndex) {
        return new PrivateAccessorMemberNode(isStatic, getterNode, setterNode, writePrivateNode, privateBrandSlotIndex);
    }

    public static ObjectLiteralMemberNode newProtoMember(TruffleString name, boolean isStatic, JavaScriptNode valueNode) {
        assert Strings.equals(JSObject.PROTO, name);
        return new ObjectLiteralProtoMemberNode(isStatic, valueNode);
    }

    public static ObjectLiteralMemberNode newSpreadObjectMember(boolean isStatic, JavaScriptNode valueNode) {
        return new ObjectLiteralSpreadMemberNode(isStatic, JSAttributes.getDefault(), valueNode);
    }

    public static ObjectLiteralMemberNode newStaticBlockMember(JavaScriptNode valueNode) {
        return new ObjectLiteralDataMemberNode(null, true, JSAttributes.getDefaultNotEnumerable(), valueNode, true);
    }

    @Children private final ObjectLiteralMemberNode[] members;
    @Child private CreateObjectNode objectCreateNode;

    public ObjectLiteralNode(ObjectLiteralMemberNode[] members, CreateObjectNode objectCreateNode) {
        this.members = members;
        this.objectCreateNode = objectCreateNode;
    }

    public static ObjectLiteralNode create(JSContext context, ObjectLiteralMemberNode[] members) {
        if (members.length > 0 && members[0] instanceof ObjectLiteralProtoMemberNode) {
            return new ObjectLiteralNode(Arrays.copyOfRange(members, 1, members.length),
                            CreateObjectNode.createOrdinaryWithPrototype(context, ((ObjectLiteralProtoMemberNode) members[0]).valueNode));
        } else if (JSConfig.DictionaryObject && members.length > JSConfig.DictionaryObjectThreshold && onlyDataMembers(members)) {
            return createDictionaryObject(context, members);
        } else {
            return new ObjectLiteralNode(members, CreateObjectNode.create(context));
        }
    }

    private static boolean onlyDataMembers(ObjectLiteralMemberNode[] members) {
        for (ObjectLiteralMemberNode member : members) {
            if (!(member instanceof ObjectLiteralDataMemberNode)) {
                return false;
            }
        }
        return true;
    }

    private static ObjectLiteralNode createDictionaryObject(JSContext context, ObjectLiteralMemberNode[] members) {
        ObjectLiteralMemberNode[] newMembers = new ObjectLiteralMemberNode[members.length];
        for (int i = 0; i < members.length; i++) {
            ObjectLiteralDataMemberNode member = (ObjectLiteralDataMemberNode) members[i];
            newMembers[i] = new DictionaryObjectDataMemberNode(member.name, member.isStatic, member.attributes, member.valueNode);
        }
        return new ObjectLiteralNode(newMembers, CreateObjectNode.createDictionary(context));
    }

    @Override
    public JSDynamicObject execute(VirtualFrame frame) {
        JSRealm realm = getRealm();
        JSDynamicObject ret = objectCreateNode.executeWithRealm(frame, realm);
        return executeWithObject(frame, ret, realm);
    }

    @ExplodeLoop
    protected JSDynamicObject executeWithObject(VirtualFrame frame, JSDynamicObject ret, JSRealm realm) {
        for (int i = 0; i < members.length; i++) {
            members[i].executeVoid(frame, ret, realm);
        }
        return ret;
    }

    @Override
    public boolean isResultAlwaysOfType(Class<?> clazz) {
        return clazz == JSDynamicObject.class;
    }

    @Override
    protected JavaScriptNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
        return new ObjectLiteralNode(ObjectLiteralMemberNode.cloneUninitialized(members, materializedTags), objectCreateNode.copyUninitialized(materializedTags));
    }
}
