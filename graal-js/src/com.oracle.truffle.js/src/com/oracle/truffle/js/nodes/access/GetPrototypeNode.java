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

import java.util.Set;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.object.Location;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.js.nodes.JSGuards;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.RepeatableNode;
import com.oracle.truffle.js.runtime.builtins.JSProxy;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.JSShape;
import com.oracle.truffle.js.runtime.objects.Null;
import com.oracle.truffle.js.runtime.util.JSClassProfile;

@GenerateUncached
public abstract class GetPrototypeNode extends JavaScriptBaseNode {
    static final int MAX_SHAPE_COUNT = 2;

    GetPrototypeNode() {
    }

    public abstract JSDynamicObject execute(JSDynamicObject obj);

    public abstract JSDynamicObject execute(Object obj);

    public static GetPrototypeNode create() {
        return GetPrototypeNodeGen.create();
    }

    public static JavaScriptNode create(JavaScriptNode object) {
        assert object instanceof RepeatableNode;
        class GetPrototypeOfNode extends JavaScriptNode implements RepeatableNode {
            @Child private JavaScriptNode objectNode = object;
            @Child private GetPrototypeNode getPrototypeNode = GetPrototypeNode.create();

            @Override
            public Object execute(VirtualFrame frame) {
                return getPrototypeNode.execute(objectNode.execute(frame));
            }

            @Override
            protected JavaScriptNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
                return new GetPrototypeOfNode();
            }
        }
        return new GetPrototypeOfNode();
    }

    static Location getPrototypeLocation(Shape shape) {
        if (JSShape.getJSClass(shape) == JSProxy.INSTANCE) {
            return null;
        }
        Property prototypeProperty = JSShape.getPrototypeProperty(shape);
        if (prototypeProperty != null) {
            return prototypeProperty.getLocation();
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    @Specialization(guards = {"obj.getShape() == shape", "prototypeLocation != null"}, limit = "MAX_SHAPE_COUNT")
    static JSDynamicObject doCachedShape(JSDynamicObject obj,
                    @Cached("obj.getShape()") Shape shape,
                    @Cached("getPrototypeLocation(shape)") Location prototypeLocation) {
        assert !JSGuards.isJSProxy(obj);
        return (JSDynamicObject) prototypeLocation.get(obj, shape);
    }

    @Specialization(guards = "!isJSProxy(obj)", replaces = "doCachedShape")
    static JSDynamicObject doGeneric(JSDynamicObject obj) {
        return JSObjectUtil.getPrototype(obj);
    }

    @Specialization(guards = "isJSProxy(obj)")
    static JSDynamicObject doProxy(JSDynamicObject obj,
                    @Cached("create()") JSClassProfile jsclassProfile) {
        return JSObject.getPrototype(obj, jsclassProfile);
    }

    @Specialization(guards = "!isJSDynamicObject(obj)")
    static JSDynamicObject doNotObject(@SuppressWarnings("unused") Object obj) {
        return Null.instance;
    }
}
